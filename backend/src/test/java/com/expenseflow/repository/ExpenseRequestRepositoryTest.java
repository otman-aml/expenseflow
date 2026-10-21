package com.expenseflow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.entity.Category;
import com.expenseflow.entity.ExpenseLine;
import com.expenseflow.entity.ExpenseRequest;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.ExpenseStatus;
import com.expenseflow.entity.enums.UserRole;

/**
 * Tests d'integration de {@link ExpenseRequestRepository} sur un vrai
 * PostgreSQL.
 *
 * <p>
 * Ces tests ne pourraient pas etre equivalents en memoire : ils verifient le
 * comportement reel du JPQL, en particulier la clause {@code DISTINCT} et la
 * jointure {@code LEFT JOIN FETCH}. Ces deux decisions ne sont pas visibles a la
 * lecture du resultat Java : seule une base qui rejoue la requete SQL peut
 * prouver qu'elles font effet.
 * </p>
 */
@Transactional
@DisplayName("ExpenseRequestRepository (integration)")
class ExpenseRequestRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private ExpenseRequestRepository expenseRequestRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User employee;
    private Category transport;
    private Category restauration;

    /**
     * Prepare un employe et deux categories reutilises par tous les tests.
     */
    @BeforeEach
    void setUp() {
        employee = userRepository.save(User.builder()
                .nom("Employe Test")
                .email("employe-" + System.nanoTime() + "@expenseflow.ma")
                .motDePasse("$2a$10$3eqYHaI52xan46seTvrU6O.xhPHlySgiZsIkX3izupFO9ALFnj10G")
                .role(UserRole.EMPLOYEE)
                .build());

        transport = categoryRepository.save(Category.builder().nom("Transport " + System.nanoTime()).build());
        restauration = categoryRepository.save(Category.builder().nom("Restauration " + System.nanoTime()).build());
    }

    /**
     * Construit une note portant une ligne par montant fourni.
     *
     * @param montants un montant par ligne, dans l'ordre
     * @return la note enregistree, lignes incluses
     */
    private ExpenseRequest givenRequestWithLines(String... montants) {
        ExpenseRequest request = ExpenseRequest.builder()
                .employee(employee)
                .statut(ExpenseStatus.DRAFT)
                .totalMontant(BigDecimal.ZERO)
                .build();

        BigDecimal total = BigDecimal.ZERO;
        int index = 0;
        for (String montant : montants) {
            Category categorie = (index % 2 == 0) ? transport : restauration;
            request.getLignes().add(ExpenseLine.builder()
                    .request(request)
                    .categorie(categorie)
                    .montant(new BigDecimal(montant))
                    .dateDepense(LocalDate.of(2026, 9, 20))
                    .description("Ligne " + (index + 1))
                    .build());
            total = total.add(new BigDecimal(montant));
            index++;
        }
        request.setTotalMontant(total);

        return expenseRequestRepository.saveAndFlush(request);
    }

    // =====================================================================
    // findByIdWithLignes
    // =====================================================================

    @Test
    @DisplayName("findByIdWithLignes() renvoie la note et ses deux lignes")
    void findByIdWithLignes_shouldReturnRequestWithLignes() {
        // Given
        ExpenseRequest saved = givenRequestWithLines("100.00", "50.00");

        // When
        ExpenseRequest found = expenseRequestRepository.findByIdWithLignes(saved.getId()).orElseThrow();

        // Then
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getEmployee().getId()).isEqualTo(employee.getId());
        assertThat(found.getLignes()).hasSize(2);
        assertThat(found.getLignes())
                .extracting(ligne -> ligne.getCategorie().getId())
                .containsExactlyInAnyOrder(transport.getId(), restauration.getId());
    }

    // Test critique : protège la décision DISTINCT
    //
    // Sans la clause DISTINCT, la jointure sur la collection `lignes` fait
    // apparaitre une fois la note par ligne : Hibernate regrouperait les lignes
    // en memoire, mais la collection contiendrait des doublons (3 lignes
    // deviennent 9), et le totalMontant affiche par l'API serait faux.
    @Test
    @DisplayName("findByIdWithLignes() renvoie exactement 3 lignes, sans doublon (protege DISTINCT)")
    void findByIdWithLignes_with3Lignes_shouldReturnExactly3Lignes() {
        // Given : 3 lignes, dont 2 sur la meme categorie
        ExpenseRequest saved = givenRequestWithLines("100.00", "50.00", "25.00");

        // When
        ExpenseRequest found = expenseRequestRepository.findByIdWithLignes(saved.getId()).orElseThrow();

        // Then
        assertThat(found.getLignes()).hasSize(3);
        assertThat(found.getLignes()).doesNotHaveDuplicates();
        assertThat(found.getLignes())
                .extracting(ExpenseLine::getId)
                .doesNotHaveDuplicates();
        assertThat(found.getTotalMontant()).isEqualByComparingTo("175.00");
    }

    @Test
    @DisplayName("findByIdWithLignes() renvoie un Optional vide pour un identifiant inconnu")
    void findByIdWithLignes_withUnknownId_shouldReturnEmpty() {
        // When / Then
        assertThat(expenseRequestRepository.findByIdWithLignes(999_999L)).isEmpty();
    }

    // =====================================================================
    // findByEmployeeIdWithLignes
    // =====================================================================

    @Test
    @DisplayName("findByEmployeeIdWithLignes() ne renvoie que les notes de l'appelant")
    void findByEmployeeIdWithLignes_shouldReturnOnlyOwnRequests() {
        // Given : un second employe possede sa propre note
        User autre = userRepository.save(User.builder()
                .nom("Autre Employe")
                .email("autre-" + System.nanoTime() + "@expenseflow.ma")
                .motDePasse("$2a$10$3eqYHaI52xan46seTvrU6O.xhPHlySgiZsIkX3izupFO9ALFnj10G")
                .role(UserRole.EMPLOYEE)
                .build());

        givenRequestWithLines("100.00");

        ExpenseRequest noteDeAutre = ExpenseRequest.builder()
                .employee(autre)
                .statut(ExpenseStatus.DRAFT)
                .totalMontant(new BigDecimal("999.00"))
                .build();
        noteDeAutre.getLignes().add(ExpenseLine.builder()
                .request(noteDeAutre)
                .categorie(transport)
                .montant(new BigDecimal("999.00"))
                .dateDepense(LocalDate.of(2026, 9, 20))
                .description("Note de l'autre employe")
                .build());
        expenseRequestRepository.saveAndFlush(noteDeAutre);

        // When
        List<ExpenseRequest> resultats =
                expenseRequestRepository.findByEmployeeIdWithLignes(employee.getId());

        // Then
        assertThat(resultats).hasSize(1);
        assertThat(resultats.get(0).getEmployee().getId()).isEqualTo(employee.getId());
    }

    @Test
    @DisplayName("findByEmployeeIdWithLignes() trie les notes de la plus recente a la plus ancienne")
    void findByEmployeeIdWithLignes_shouldOrderByCreatedAtDesc() {
        // Given : trois notes dont les horodatages sont forces explicitement,
        // les insertions etant trop rapprochees pour garantir un tri naturel
        ExpenseRequest ancienne = givenRequestWithLines("10.00");
        ExpenseRequest recente = givenRequestWithLines("20.00");
        ExpenseRequest intermediaire = givenRequestWithLines("30.00");

        jdbcTemplate.update(
                "UPDATE expense_requests SET created_at = NOW() - INTERVAL '2 days' WHERE id = ?",
                ancienne.getId());
        jdbcTemplate.update(
                "UPDATE expense_requests SET created_at = NOW() WHERE id = ?", recente.getId());
        jdbcTemplate.update(
                "UPDATE expense_requests SET created_at = NOW() - INTERVAL '1 day' WHERE id = ?",
                intermediaire.getId());
        jdbcTemplate.update("UPDATE expense_requests SET updated_at = NOW() WHERE id = ?", recente.getId());
        expenseRequestRepository.flush();

        // When
        List<ExpenseRequest> resultats =
                expenseRequestRepository.findByEmployeeIdWithLignes(employee.getId());

        // Then : la plus recente en tete, la plus ancienne en fin
        assertThat(resultats).hasSize(3);
        assertThat(resultats).extracting(ExpenseRequest::getId)
                .containsExactly(recente.getId(), intermediaire.getId(), ancienne.getId());
    }

    // Test critique : protège la décision LEFT JOIN FETCH
    //
    // Avec un INNER JOIN, la note depourvue de ligne disparaitrait
    // silencieusement du resultat : l'API ne l'afficherait jamais. Le LEFT JOIN
    // FETCH garantit qu'elle est presente, avec une collection vide.
    @Test
    @DisplayName("findByEmployeeIdWithLignes() inclut la note sans ligne (protege LEFT JOIN FETCH)")
    void findByEmployeeIdWithLignes_withNoLignes_shouldIncludeRequest() {
        // Given : une note avec lignes, une note vide inseree en SQL direct
        // (le service interdit les notes sans ligne : ce cas ne peut survenir
        // que par une evolution metier, qu'il faut pouvoir representer)
        givenRequestWithLines("100.00", "50.00");

        jdbcTemplate.update(
                "INSERT INTO expense_requests (employee_id, statut, total_montant, created_at, updated_at) "
                        + "VALUES (?, 'DRAFT', 0, NOW(), NOW())",
                employee.getId());

        // When
        List<ExpenseRequest> resultats =
                expenseRequestRepository.findByEmployeeIdWithLignes(employee.getId());

        // Then
        assertThat(resultats).hasSize(2);
        assertThat(resultats).filteredOn(r -> r.getLignes().isEmpty()).hasSize(1);
    }
}