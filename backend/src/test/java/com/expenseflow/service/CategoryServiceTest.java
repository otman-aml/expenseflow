package com.expenseflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.expenseflow.dto.CategoryRequest;
import com.expenseflow.dto.CategoryResponse;
import com.expenseflow.entity.Category;
import com.expenseflow.exception.CategoryInUseException;
import com.expenseflow.exception.DuplicateResourceException;
import com.expenseflow.exception.ResourceNotFoundException;
import com.expenseflow.repository.BudgetLimitRepository;
import com.expenseflow.repository.CategoryRepository;
import com.expenseflow.repository.ExpenseLineRepository;

/**
 * Tests unitaires de {@link CategoryService}.
 *
 * <p>
 * L'attention porte en particulier sur {@code requireNomLibre(nom, excludeId)},
 * dont le second parametre evite un piege lors d'une renomination : sans lui,
 * un {@code PUT} qui renvoie une categorie sous son propre nom echouerait a tort
 * avec une erreur de doublon.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CategoryService")
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private BudgetLimitRepository budgetLimitRepository;

    @Mock
    private ExpenseLineRepository expenseLineRepository;

    @InjectMocks
    private CategoryService service;

    @Captor
    private ArgumentCaptor<Category> categoryCaptor;

    private static Category category(Long id, String nom) {
        return Category.builder().id(id).nom(nom).build();
    }

    /**
     * Simule le {@code save} d'une categorie en lui attribuant un identifiant.
     *
     * <p>
     * L'identifiant n'est pose que s'il est absent : c'est le comportement
     * reel de JPA, qui n'affecte que les entites nouvelles. Ecraser
     * systematiquement l'id masquerait un defaut de persistance dans le cas d'un
     * {@code update}, ou l'entite est deja identifiee.
     * </p>
     */
    private void givenSaveAssignsId() {
        given(categoryRepository.save(any(Category.class))).willAnswer(i -> {
            Category entity = i.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(77L);
            }
            return entity;
        });
    }

    // =====================================================================
    // Lectures
    // =====================================================================

    @Test
    @DisplayName("findAll() renvoie toutes les categories")
    void findAll_shouldReturnAllCategories() {
        // Given
        given(categoryRepository.findAll()).willReturn(List.of(category(1L, "Transport"), category(2L, "Restauration")));

        // When
        List<CategoryResponse> result = service.findAll();

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).extracting(CategoryResponse::nom)
                .containsExactly("Transport", "Restauration");
    }

    @Test
    @DisplayName("findById() renvoie la categorie demandee")
    void findById_withExistingId_shouldReturnCategory() {
        // Given
        given(categoryRepository.findById(1L)).willReturn(Optional.of(category(1L, "Transport")));

        // When
        CategoryResponse result = service.findById(1L);

        // Then
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.nom()).isEqualTo("Transport");
    }

    @Test
    @DisplayName("findById() renvoie 404 si la categorie n'existe pas")
    void findById_withUnknownId_shouldThrowResourceNotFound() {
        // Given
        given(categoryRepository.findById(404L)).willReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.findById(404L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("404");
    }

    // =====================================================================
    // create
    // =====================================================================

    @Test
    @DisplayName("create() accepte un nom unique")
    void create_withUniqueName_shouldSucceed() {
        // Given
        given(categoryRepository.findByNom("Transport")).willReturn(Optional.empty());
        givenSaveAssignsId();

        // When
        CategoryResponse result = service.create(new CategoryRequest("Transport"));

        // Then
        assertThat(result.nom()).isEqualTo("Transport");
        verify(categoryRepository).save(any(Category.class));
    }

    @Test
    @DisplayName("create() refuse un nom deja utilise")
    void create_withDuplicateName_shouldThrowDuplicateResource() {
        // Given
        given(categoryRepository.findByNom("Transport")).willReturn(Optional.of(category(1L, "Transport")));

        // When / Then
        assertThatThrownBy(() -> service.create(new CategoryRequest("Transport")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Transport");

        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("create() applique trim() sur le nom avant de verifier l'unicite")
    void create_shouldTrimName() {
        // Given : le nom est stocke avec des espaces, la verification doit porter
        // sur la version normalisee
        given(categoryRepository.findByNom("Transport")).willReturn(Optional.empty());
        givenSaveAssignsId();

        // When
        CategoryResponse result = service.create(new CategoryRequest("  Transport  "));

        // Then
        assertThat(result.nom()).isEqualTo("Transport");
        verify(categoryRepository).findByNom("Transport");
        verify(categoryRepository).save(categoryCaptor.capture());
        assertThat(categoryCaptor.getValue().getNom()).isEqualTo("Transport");
    }

    // =====================================================================
    // update - le piege du excludeId
    // =====================================================================

    @Test
    @DisplayName("update() accepte de renvoyer une categorie sous son propre nom")
    void update_withSameName_shouldSucceed() {
        // Given : findByNom renvoie la MEME categorie que l'on modifie
        Category existing = category(1L, "Transport");
        given(categoryRepository.findById(1L)).willReturn(Optional.of(existing));
        given(categoryRepository.findByNom("Transport")).willReturn(Optional.of(existing));
        givenSaveAssignsId();

        // When : aucun conflit ne doit etre leve
        CategoryResponse result = service.update(1L, new CategoryRequest("Transport"));

        // Then : l'entite mise a jour est bien celle du repository
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.nom()).isEqualTo("Transport");
        verify(categoryRepository).save(categoryCaptor.capture());
        assertThat(categoryCaptor.getValue().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("update() accepte un nouveau nom unique")
    void update_withNewUniqueName_shouldSucceed() {
        // Given
        Category existing = category(1L, "Transport");
        given(categoryRepository.findById(1L)).willReturn(Optional.of(existing));
        given(categoryRepository.findByNom("Mobilite")).willReturn(Optional.empty());
        givenSaveAssignsId();

        // When
        CategoryResponse result = service.update(1L, new CategoryRequest("Mobilite"));

        // Then
        assertThat(result.nom()).isEqualTo("Mobilite");
    }

    @Test
    @DisplayName("update() refuse un nom porte par une AUTRE categorie")
    void update_withDuplicateNameOfOther_shouldThrowDuplicateResource() {
        // Given : la categorie 2 porte deja ce nom
        given(categoryRepository.findById(1L)).willReturn(Optional.of(category(1L, "Transport")));
        given(categoryRepository.findByNom("Restauration")).willReturn(Optional.of(category(2L, "Restauration")));

        // When / Then
        assertThatThrownBy(() -> service.update(1L, new CategoryRequest("Restauration")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Restauration");

        verify(categoryRepository, never()).save(any());
    }

    // =====================================================================
    // deleteById - controle d'usage
    // =====================================================================

    @Test
    @DisplayName("deleteById() refuse si un plafond reference la categorie")
    void deleteById_whenCategoryInUseByBudget_shouldThrowCategoryInUse() {
        // Given
        given(categoryRepository.findById(1L)).willReturn(Optional.of(category(1L, "Transport")));
        given(budgetLimitRepository.countByCategorieId(1L)).willReturn(2L);
        given(expenseLineRepository.countByCategorieId(1L)).willReturn(0L);

        // When / Then
        assertThatThrownBy(() -> service.deleteById(1L))
                .isInstanceOf(CategoryInUseException.class)
                .hasMessageContaining("Transport");

        verify(categoryRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("deleteById() refuse si une note de frais reference la categorie")
    void deleteById_whenUsedByExpenseLine_shouldThrowCategoryInUse() {
        // Given : aucun plafond, mais des lignes de depense
        given(categoryRepository.findById(1L)).willReturn(Optional.of(category(1L, "Transport")));
        given(budgetLimitRepository.countByCategorieId(1L)).willReturn(0L);
        given(expenseLineRepository.countByCategorieId(1L)).willReturn(3L);

        // When / Then
        assertThatThrownBy(() -> service.deleteById(1L))
                .isInstanceOf(CategoryInUseException.class);

        verify(categoryRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("deleteById() supprime la categorie si rien ne la reference")
    void deleteById_whenNotInUse_shouldDelete() {
        // Given
        given(categoryRepository.findById(1L)).willReturn(Optional.of(category(1L, "Transport")));
        given(budgetLimitRepository.countByCategorieId(1L)).willReturn(0L);
        given(expenseLineRepository.countByCategorieId(1L)).willReturn(0L);

        // When
        service.deleteById(1L);

        // Then
        verify(categoryRepository).deleteById(1L);
    }

    @Test
    @DisplayName("deleteById() renvoie 404 si la categorie n'existe pas")
    void deleteById_withUnknownId_shouldThrowResourceNotFound() {
        // Given
        given(categoryRepository.findById(404L)).willReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.deleteById(404L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(budgetLimitRepository, never()).countByCategorieId(anyLong());
    }
}