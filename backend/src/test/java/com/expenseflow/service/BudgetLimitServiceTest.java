package com.expenseflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
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

import com.expenseflow.dto.BudgetLimitRequest;
import com.expenseflow.dto.BudgetLimitResponse;
import com.expenseflow.entity.BudgetLimit;
import com.expenseflow.entity.Category;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.exception.DuplicateResourceException;
import com.expenseflow.exception.ResourceNotFoundException;
import com.expenseflow.repository.BudgetLimitRepository;
import com.expenseflow.repository.CategoryRepository;

/**
 * Tests unitaires de {@link BudgetLimitService}.
 *
 * <p>
 * Comme pour les categories, l'attention porte sur l'exclusion de l'entite en
 * cours de modification : sans le parametre {@code excludeId}, un {@code PUT}
 * qui renvoie un plafond identique echouerait a tort avec un conflit
 * d'unicite.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BudgetLimitService")
class BudgetLimitServiceTest {

    @Mock
    private BudgetLimitRepository budgetLimitRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private BudgetLimitService service;

    @Captor
    private ArgumentCaptor<BudgetLimit> limitCaptor;

    private static Category category(Long id, String nom) {
        return Category.builder().id(id).nom(nom).build();
    }

    /** Construit un plafond rattache a une categorie. */
    private static BudgetLimit limit(Long id, Long categorieId, UserRole role, String montant) {
        return BudgetLimit.builder()
                .id(id)
                .categorie(category(categorieId, "Cat" + categorieId))
                .role(role)
                .plafond(new BigDecimal(montant))
                .build();
    }

    /**
     * Simule le {@code save} d'un plafond, en lui attribuant un identifiant
     * uniquement s'il est absent (comportement reel de JPA).
     */
    private void givenSaveAssignsId() {
        given(budgetLimitRepository.save(any(BudgetLimit.class))).willAnswer(i -> {
            BudgetLimit entity = i.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(88L);
            }
            return entity;
        });
    }

    // =====================================================================
    // Lectures
    // =====================================================================

    @Test
    @DisplayName("findAll() renvoie tous les plafonds avec leur categorie")
    void findAll_shouldReturnAllBudgetLimits() {
        // Given
        given(budgetLimitRepository.findAllWithCategorie())
                .willReturn(List.of(limit(1L, 4L, UserRole.EMPLOYEE, "500.00")));

        // When
        List<BudgetLimitResponse> result = service.findAll();

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).categorieNom()).isEqualTo("Cat4");
        assertThat(result.get(0).plafond()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("findForCurrentUserRole() ne renvoie que les plafonds du rôle demande")
    void findForCurrentUserRole_shouldFilterByRole() {
        // Given
        given(budgetLimitRepository.findByRoleWithCategorie(UserRole.MANAGER))
                .willReturn(List.of(limit(2L, 4L, UserRole.MANAGER, "1000.00")));

        // When
        List<BudgetLimitResponse> result = service.findForCurrentUserRole(UserRole.MANAGER);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).role()).isEqualTo(UserRole.MANAGER);
        verify(budgetLimitRepository).findByRoleWithCategorie(UserRole.MANAGER);
    }

    // =====================================================================
    // create
    // =====================================================================

    @Test
    @DisplayName("create() accepte un couple (categorie, rôle) libre")
    void create_withUniquePair_shouldSucceed() {
        // Given
        given(categoryRepository.findById(4L)).willReturn(Optional.of(category(4L, "Transport")));
        given(budgetLimitRepository.findByCategorieIdAndRole(4L, UserRole.EMPLOYEE))
                .willReturn(Optional.empty());
        givenSaveAssignsId();

        // When
        BudgetLimitResponse result = service.create(
                new BudgetLimitRequest(4L, UserRole.EMPLOYEE, new BigDecimal("500.00")));

        // Then
        assertThat(result.plafond()).isEqualByComparingTo("500.00");
        verify(budgetLimitRepository).save(limitCaptor.capture());
        assertThat(limitCaptor.getValue().getCategorie().getId()).isEqualTo(4L);
        assertThat(limitCaptor.getValue().getRole()).isEqualTo(UserRole.EMPLOYEE);
    }

    @Test
    @DisplayName("create() refuse un couple (categorie, rôle) deja occupe")
    void create_withDuplicatePair_shouldThrowDuplicateResource() {
        // Given
        given(categoryRepository.findById(4L)).willReturn(Optional.of(category(4L, "Transport")));
        given(budgetLimitRepository.findByCategorieIdAndRole(4L, UserRole.EMPLOYEE))
                .willReturn(Optional.of(limit(1L, 4L, UserRole.EMPLOYEE, "500.00")));

        // When / Then
        assertThatThrownBy(() -> service.create(
                new BudgetLimitRequest(4L, UserRole.EMPLOYEE, new BigDecimal("900.00"))))
                .isInstanceOf(DuplicateResourceException.class);

        verify(budgetLimitRepository, never()).save(any());
    }

    @Test
    @DisplayName("create() refuse le meme couple pour un ROLE different")
    void create_withSameCategoryButDifferentRole_shouldSucceed() {
        // Given : la requete porte sur MANAGEE, un plafond EMPLOYEE existe deja
        given(categoryRepository.findById(4L)).willReturn(Optional.of(category(4L, "Transport")));
        given(budgetLimitRepository.findByCategorieIdAndRole(4L, UserRole.MANAGER))
                .willReturn(Optional.empty());
        givenSaveAssignsId();

        // When
        BudgetLimitResponse result = service.create(
                new BudgetLimitRequest(4L, UserRole.MANAGER, new BigDecimal("1000.00")));

        // Then : l'unicite porte bien sur le COUPLE, pas sur la categorie seule
        assertThat(result.role()).isEqualTo(UserRole.MANAGER);
    }

    @Test
    @DisplayName("create() renvoie 404 si la categorie n'existe pas")
    void create_withUnknownCategory_shouldThrowResourceNotFound() {
        // Given
        given(categoryRepository.findById(999L)).willReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.create(
                new BudgetLimitRequest(999L, UserRole.EMPLOYEE, new BigDecimal("100.00"))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("999");

        verify(budgetLimitRepository, never()).save(any());
    }

    // =====================================================================
    // update - le piege du excludeId
    // =====================================================================

    @Test
    @DisplayName("update() accepte de renvoyer un plafond identique")
    void update_withSamePair_shouldSucceed() {
        // Given : le plafond occupe est celui qu'on modifie
        BudgetLimit existing = limit(1L, 4L, UserRole.EMPLOYEE, "500.00");
        given(budgetLimitRepository.findById(1L)).willReturn(Optional.of(existing));
        given(categoryRepository.findById(4L)).willReturn(Optional.of(category(4L, "Transport")));
        given(budgetLimitRepository.findByCategorieIdAndRole(4L, UserRole.EMPLOYEE))
                .willReturn(Optional.of(existing));
        givenSaveAssignsId();

        // When : aucun conflit ne doit etre leve
        BudgetLimitResponse result = service.update(1L,
                new BudgetLimitRequest(4L, UserRole.EMPLOYEE, new BigDecimal("500.00")));

        // Then
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.plafond()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("update() refuse un couple occupe par un AUTRE plafond")
    void update_withDuplicatePairOfOther_shouldThrowDuplicateResource() {
        // Given : le plafond 1 est deplace sur un couple deja pris par le plafond 2
        given(budgetLimitRepository.findById(1L)).willReturn(Optional.of(limit(1L, 4L, UserRole.EMPLOYEE, "500.00")));
        given(categoryRepository.findById(5L)).willReturn(Optional.of(category(5L, "Restauration")));
        given(budgetLimitRepository.findByCategorieIdAndRole(5L, UserRole.MANAGER))
                .willReturn(Optional.of(limit(2L, 5L, UserRole.MANAGER, "200.00")));

        // When / Then
        assertThatThrownBy(() -> service.update(1L,
                new BudgetLimitRequest(5L, UserRole.MANAGER, new BigDecimal("300.00"))))
                .isInstanceOf(DuplicateResourceException.class);

        verify(budgetLimitRepository, never()).save(any());
    }

    @Test
    @DisplayName("update() renvoie 404 si la categorie n'existe pas")
    void update_withUnknownCategory_shouldThrowResourceNotFound() {
        // Given
        given(budgetLimitRepository.findById(1L)).willReturn(Optional.of(limit(1L, 4L, UserRole.EMPLOYEE, "500.00")));
        given(categoryRepository.findById(999L)).willReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.update(1L,
                new BudgetLimitRequest(999L, UserRole.EMPLOYEE, new BigDecimal("100.00"))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =====================================================================
    // deleteById
    // =====================================================================

    @Test
    @DisplayName("deleteById() supprime le plafond existant")
    void deleteById_withExistingId_shouldDelete() {
        // Given
        given(budgetLimitRepository.findById(1L)).willReturn(Optional.of(limit(1L, 4L, UserRole.EMPLOYEE, "500.00")));

        // When
        service.deleteById(1L);

        // Then
        verify(budgetLimitRepository).deleteById(1L);
    }

    @Test
    @DisplayName("deleteById() renvoie 404 si le plafond n'existe pas")
    void deleteById_withUnknownId_shouldThrowResourceNotFound() {
        // Given
        given(budgetLimitRepository.findById(404L)).willReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.deleteById(404L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("404");

        verify(budgetLimitRepository, never()).deleteById(anyLong());
    }
}
