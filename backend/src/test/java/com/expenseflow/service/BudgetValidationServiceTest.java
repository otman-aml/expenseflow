package com.expenseflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.expenseflow.entity.BudgetLimit;
import com.expenseflow.entity.Category;
import com.expenseflow.entity.ExpenseLine;
import com.expenseflow.entity.ExpenseRequest;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.BudgetLimitRepository;
import com.expenseflow.workflow.BudgetViolation;

/**
 * Tests unitaires de {@link BudgetValidationService}.
 *
 * <p>
 * Aucun contexte Spring : les plafonds sont simulés. Ces tests verrouillent la
 * règle de comparaison — strictement supérieure, au centime près — qu'aucun test
 * d'intégration ne pourrait couvrir de façon exhaustive.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BudgetValidationService")
class BudgetValidationServiceTest {

    private static final long CAT_TRANSPORT = 4L;
    private static final long CAT_RESTAURATION = 5L;

    @Mock
    private BudgetLimitRepository budgetLimitRepository;

    @InjectMocks
    private BudgetValidationService service;

    private User employe;

    @BeforeEach
    void setUp() {
        employe = User.builder().id(9L).nom("Employe").email("e@x.ma").role(UserRole.EMPLOYEE).build();
    }

    /**
     * @param id     identifiant de catégorie
     * @param nom    libellé
     * @param valeur montant maximal
     * @return plafond simulé
     */
    private BudgetLimit plafond(long id, String nom, String valeur) {
        Category categorie = Category.builder().id(id).nom(nom).build();
        return BudgetLimit.builder().categorie(categorie).role(UserRole.EMPLOYEE)
                .plafond(new BigDecimal(valeur)).build();
    }

    /**
     * @param categorieId catégorie de la ligne
     * @param montant     montant de la ligne
     * @return ligne détachée
     */
    private ExpenseLine ligne(long categorieId, String montant) {
        // Le libellé doit correspondre a celui du plafond de la meme
        // categorie : le service lit le nom sur la LIGNE (et non sur le
        // plafond) pour composer le message, donc deux objets Category
        // distincts meme id produiraient un message incoherent.
        String nom = switch ((int) categorieId) {
            case 4 -> "Transport";
            case 5 -> "Restauration";
            default -> "Cat" + categorieId;
        };
        Category categorie = Category.builder().id(categorieId).nom(nom).build();
        return ExpenseLine.builder()
                .categorie(categorie)
                .montant(new BigDecimal(montant))
                .build();
    }

    /**
     * @param lignes lignes de la note
     * @return note complète
     */
    private ExpenseRequest note(ExpenseLine... lignes) {
        ExpenseRequest request = ExpenseRequest.builder().employee(employe).build();
        for (ExpenseLine l : lignes) {
            l.setRequest(request);
            request.getLignes().add(l);
        }
        return request;
    }

    @Test
    @DisplayName("Aucun dépassement : liste de violations vide")
    void aucunDepassement() {
        given(budgetLimitRepository.findByRoleWithCategorie(UserRole.EMPLOYEE))
                .willReturn(List.of(plafond(CAT_TRANSPORT, "Transport", "500.00")));

        assertThat(service.validateExpense(note(ligne(CAT_TRANSPORT, "400.00")), employe))
                .isEmpty();
    }

    @Test
    @DisplayName("Un dépassement : une violation portant catégorie, rôle et les deux montants")
    void unDepassement() {
        given(budgetLimitRepository.findByRoleWithCategorie(UserRole.EMPLOYEE))
                .willReturn(List.of(plafond(CAT_TRANSPORT, "Transport", "500.00")));

        List<BudgetViolation> resultat =
                service.validateExpense(note(ligne(CAT_TRANSPORT, "600.00")), employe);

        assertThat(resultat).hasSize(1);
        BudgetViolation v = resultat.get(0);
        assertThat(v.categorieNom()).isEqualTo("Transport");
        assertThat(v.role()).isEqualTo(UserRole.EMPLOYEE);
        assertThat(v.montant()).isEqualByComparingTo("600.00");
        assertThat(v.plafond()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("Deux dépassements : deux violations, une par ligne")
    void deuxDepassements() {
        given(budgetLimitRepository.findByRoleWithCategorie(UserRole.EMPLOYEE))
                .willReturn(List.of(
                        plafond(CAT_TRANSPORT, "Transport", "500.00"),
                        plafond(CAT_RESTAURATION, "Restauration", "200.00")));

        List<BudgetViolation> resultat = service.validateExpense(
                note(ligne(CAT_TRANSPORT, "600.00"), ligne(CAT_RESTAURATION, "300.00")), employe);

        assertThat(resultat).hasSize(2);
        assertThat(resultat).extracting(BudgetViolation::categorieNom)
                .containsExactly("Transport", "Restauration");
    }

    @Test
    @DisplayName("Catégorie sans plafond configuré : aucune violation (ouvert par défaut)")
    void categorieSansPlafond() {
        given(budgetLimitRepository.findByRoleWithCategorie(UserRole.EMPLOYEE))
                .willReturn(List.of(plafond(CAT_TRANSPORT, "Transport", "500.00")));

        // Hebergement (cat. 6) n'a pas de plafond : un montant énorme passe.
        assertThat(service.validateExpense(note(ligne(6L, "99999.00")), employe)).isEmpty();
    }

    @Test
    @DisplayName("Aucun plafond du tout : aucune violation")
    void aucunPlafondConfigure() {
        given(budgetLimitRepository.findByRoleWithCategorie(UserRole.EMPLOYEE))
                .willReturn(List.of());

        assertThat(service.validateExpense(note(ligne(CAT_TRANSPORT, "99999.00")), employe))
                .isEmpty();
    }

    /**
     * Test critique : la comparaison est strictement supérieure.
     *
     * <p>
     * Une note d'exactement 500,00 sur un plafond de 500,00 est conforme. Une
     * comparaison {@code >=} la refuserait à tort, et passerait les tests
     * fonctionnels les plus courants : le bug n'apparaîtrait que sur les notes
     * « pile justes », les plus rares.
     * </p>
     */
    @Test
    @DisplayName("Montant exactement égal au plafond : accepté (comparaison stricte)")
    void montantExactementAuPlafond() {
        given(budgetLimitRepository.findByRoleWithCategorie(UserRole.EMPLOYEE))
                .willReturn(List.of(plafond(CAT_TRANSPORT, "Transport", "500.00")));

        assertThat(service.validateExpense(note(ligne(CAT_TRANSPORT, "500.00")), employe))
                .isEmpty();
    }

    /**
     * Un centime au-dessus doit être refusé.
     *
     * <p>
     * Ce test verrouille le {@code compareTo} : une comparaison par
     * {@code equals} sur des {@code BigDecimal} laisserait passer certains cas,
     * car {@code 500.00.equals(500.000)} est faux alors que les deux valeurs
     * sont numériquement identiques.
     * </p>
     */
    @Test
    @DisplayName("Un centime au-dessus du plafond : refusé")
    void unCentimeAuDessus() {
        given(budgetLimitRepository.findByRoleWithCategorie(UserRole.EMPLOYEE))
                .willReturn(List.of(plafond(CAT_TRANSPORT, "Transport", "500.00")));

        assertThat(service.validateExpense(note(ligne(CAT_TRANSPORT, "500.01")), employe))
                .hasSize(1);
    }

    /**
     * Une seule requête est émise, quel que soit le nombre de lignes : sans
     * cela, une recherche par ligne émettrait trois requêtes (problème N+1).
     */
    @Test
    @DisplayName("Une seule requête est émise quel que soit le nombre de lignes")
    void uneSeuleRequete() {
        given(budgetLimitRepository.findByRoleWithCategorie(UserRole.EMPLOYEE))
                .willReturn(List.of(
                        plafond(CAT_TRANSPORT, "Transport", "500.00"),
                        plafond(CAT_RESTAURATION, "Restauration", "200.00")));

        service.validateExpense(note(
                ligne(CAT_TRANSPORT, "100.00"),
                ligne(CAT_RESTAURATION, "100.00"),
                ligne(CAT_TRANSPORT, "200.00")), employe);

        verify(budgetLimitRepository, times(1)).findByRoleWithCategorie(UserRole.EMPLOYEE);
    }

    /**
     * Deux lignes de la même catégorie sont contrôlées indépendamment.
     *
     * <p>
     * 600 dépasse le plafond de 500, 400 le respecte. La règle étant par ligne
     * et non par cumul, seule la première est signalée : un cumul donnerait
     * 1000 &gt; 500 et bloquerait à tort les deux.
     * </p>
     */
    @Test
    @DisplayName("Deux lignes de la même catégorie sont contrôlées indépendamment")
    void deuxLignesMemeCategorie() {
        given(budgetLimitRepository.findByRoleWithCategorie(UserRole.EMPLOYEE))
                .willReturn(List.of(plafond(CAT_TRANSPORT, "Transport", "500.00")));

        List<BudgetViolation> resultat = service.validateExpense(
                note(ligne(CAT_TRANSPORT, "600.00"), ligne(CAT_TRANSPORT, "400.00")), employe);

        assertThat(resultat).hasSize(1);
        assertThat(resultat.get(0).montant()).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("Le message d'une violation cite catégorie, rôle et les deux montants")
    void descriptionDeLaViolation() {
        given(budgetLimitRepository.findByRoleWithCategorie(UserRole.EMPLOYEE))
                .willReturn(List.of(plafond(CAT_TRANSPORT, "Transport", "500.00")));

        BudgetViolation v = service
                .validateExpense(note(ligne(CAT_TRANSPORT, "600.00")), employe)
                .get(0);

        assertThat(v.description()).isEqualTo("Transport (EMPLOYEE) : 600.00 MAD > 500.00 MAD");
    }
}
