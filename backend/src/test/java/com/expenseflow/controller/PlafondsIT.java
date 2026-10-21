package com.expenseflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.entity.AuditLog;
import com.expenseflow.entity.BudgetLimit;
import com.expenseflow.entity.Category;
import com.expenseflow.entity.ExpenseLine;
import com.expenseflow.entity.ExpenseRequest;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.ExpenseStatus;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.AuditLogRepository;
import com.expenseflow.repository.BudgetLimitRepository;
import com.expenseflow.repository.CategoryRepository;
import com.expenseflow.repository.ExpenseRequestRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.JwtService;
import com.expenseflow.security.CustomUserDetailsService;
import com.expenseflow.service.AuditLogService;
import com.jayway.jsonpath.JsonPath;

/**
 * Tests d'integration du controle des plafonds budgetaires a la soumission.
 *
 * <p>
 * Le controle est applique au moment du {@code submit}, et non a la creation :
 * l'employe doit pouvoir corriger freely un brouillon avant de s'engager. Ces
 * tests verifient donc que la soumission est refusee, <em>et</em> que le refus
 * ne laisse aucune trace : ni transition journalisee, ni note deplacee.
 * </p>
 *
 * <p>
 * <strong>Le depassement est atomique.</strong> Une note franchissant deux
 * plafonds est refusee en bloc. Accepter la partie conforme et rejeter
 * l'autre obligerait l'employe a deviner laquelle l'a bloquee.
 * </p>
 */
@Transactional
@AutoConfigureMockMvc
@DisplayName("Plafonds budgetaires (integration)")
class PlafondsIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private BudgetLimitRepository budgetLimitRepository;

    @Autowired
    private ExpenseRequestRepository expenseRequestRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    // ---------------------------------------------------------------------
    // A) Depassement refuse
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Un depassement refuse la soumission et laisse la note en DRAFT")
    void submit_depassement_shouldReturn409() throws Exception {
        // Given : plafond Transport a 500, ligne a 600
        User employe = createUser("Employe Depasse", UserRole.EMPLOYEE);
        Category transport = categorie("Transport");
        creerPlafond(transport, UserRole.EMPLOYEE, "500.00");
        Long noteId = creerBrouillon(employe, transport, "600.00");

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"))
                // Le message nomme le role, le montant et le plafond : l'employe
                // sait ainsi de combien il faut reduire.
                .andExpect(jsonPath("$.message").value("Plafond depasse : "
                        + transport.getNom() + " (EMPLOYEE) : 600.00 MAD > 500.00 MAD"));

        // Then : la note n'a pas bouge
        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.DRAFT);
    }

    @Test
    @DisplayName("Une soumission refusee pour depassement ne journalise aucune transition")
    void submit_depassement_neJournalisePas() throws Exception {
        // Given
        User employe = createUser("Employe Silence", UserRole.EMPLOYEE);
        Category transport = categorie("Transport");
        creerPlafond(transport, UserRole.EMPLOYEE, "500.00");
        Long noteId = creerBrouillon(employe, transport, "600.00");

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isConflict());

        // Then : le controle des plafonds precede la machine a etats. Aucune
        // transition n'est simulee, donc rien n'est journalise.
        assertThat(entreesDe(noteId)).isEmpty();
    }

    @Test
    @DisplayName("Deux depassements sont refuses ensemble, separes par un point-virgule")
    void submit_deuxDepassements_shouldReturnBoth() throws Exception {
        // Given : deux categories plafonnees, deux lignes fautives
        User employe = createUser("Employe Double", UserRole.EMPLOYEE);
        Category transport = categorie("Transport");
        Category restauration = categorie("Restauration");
        creerPlafond(transport, UserRole.EMPLOYEE, "500.00");
        creerPlafond(restauration, UserRole.EMPLOYEE, "200.00");

        BigDecimal t = new BigDecimal("600.00");
        BigDecimal r = new BigDecimal("300.00");
        ExpenseRequest note = ExpenseRequest.builder()
                .employee(employe)
                .statut(ExpenseStatus.DRAFT)
                .totalMontant(t.add(r))
                .build();
        note.getLignes().add(ExpenseLine.builder().request(note).categorie(transport)
                .montant(t).dateDepense(LocalDate.of(2026, 9, 20)).description("Taxi").build());
        note.getLignes().add(ExpenseLine.builder().request(note).categorie(restauration)
                .montant(r).dateDepense(LocalDate.of(2026, 9, 20)).description("Dejeuner").build());
        Long noteId = expenseRequestRepository.save(note).getId();

        // When
        MvcResult resultat = mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isConflict())
                .andReturn();

        // Then : les deux depassements sont nommes. En nommer un seul
        // obligerait l'employe a soumettre a nouveau pour decouvrir l'autre.
        String message = lectureJson(resultat, "$.message");
        assertThat(message)
                .contains(transport.getNom() + " (EMPLOYEE) : 600.00 MAD > 500.00 MAD")
                .contains(restauration.getNom() + " (EMPLOYEE) : 300.00 MAD > 200.00 MAD")
                // Point-virgule et non virgule : les montants contiennent eux-memes
                // une virgule decimale, un separateur virgule serait ambigu.
                .contains(" ; ");

        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.DRAFT);
    }

    // ---------------------------------------------------------------------
    // B) Soumission acceptee
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Une ligne sous le plafond est acceptee")
    void submit_sousPlafond_shouldSucceed() throws Exception {
        // Given : plafond 500, ligne a 400
        User employe = createUser("Employe Conforme", UserRole.EMPLOYEE);
        Category transport = categorie("Transport");
        creerPlafond(transport, UserRole.EMPLOYEE, "500.00");
        Long noteId = creerBrouillon(employe, transport, "400.00");

        // When / Then
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("PENDING_MANAGER"));

        assertThat(entreesDe(noteId)).hasSize(2);
    }

    @Test
    @DisplayName("Une categorie sans plafond n bloque pas : 10000 est accepte")
    void submit_sansPlafond_shouldSucceed() throws Exception {
        // Given : aucune limite configuree pour cette categorie
        User employe = createUser("Employe Sans Plafond", UserRole.EMPLOYEE);
        Category autre = categorie("Autre");
        Long noteId = creerBrouillon(employe, autre, "10000.00");

        // When / Then : l'absence de limite vaut absence de restriction
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("PENDING_MANAGER"));
    }

    @Test
    @DisplayName("Une ligne exactement au plafond est acceptee : la comparaison est stricte")
    void submit_egalAuPlafond_shouldSucceed() throws Exception {
        // Given : plafond 500, ligne exactement a 500
        User employe = createUser("Employe Limite", UserRole.EMPLOYEE);
        Category transport = categorie("Transport");
        creerPlafond(transport, UserRole.EMPLOYEE, "500.00");
        Long noteId = creerBrouillon(employe, transport, "500.00");

        // When / Then : depasser, c'est depasser ; atteindre le plafond est
        // encore conforme.
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("PENDING_MANAGER"));
    }

    @Test
    @DisplayName("Un plafond de MANAGER ne s'applique pas a un employe")
    void submit_plafondDUnAutreRole_ignore() throws Exception {
        // Given : plafond configure pour MANAGER, note d'un EMPLOYEE
        User employe = createUser("Employe Bon Role", UserRole.EMPLOYEE);
        Category transport = categorie("Transport");
        creerPlafond(transport, UserRole.MANAGER, "500.00");
        Long noteId = creerBrouillon(employe, transport, "600.00");

        // When / Then : les plafonds sont indexes par couple (categorie, role).
        // L'employe n'est pas concerne par la limite du manager.
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("PENDING_MANAGER"));
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private User createUser(String nom, UserRole role) {
        return userRepository.save(User.builder()
                .nom(nom)
                .email(nom.toLowerCase().replace(' ', '-') + "-" + System.nanoTime() + "@expenseflow.ma")
                .motDePasse(passwordEncoder.encode("MotDePasse1!"))
                .role(role)
                .build());
    }

    /**
     * Enregistre un plafond pour un couple categorie / role.
     *
     * @param categorie categorie concernee
     * @param role      role concerne
     * @param plafond   montant maximal
     */
    private void creerPlafond(Category categorie, UserRole role, String plafond) {
        budgetLimitRepository.save(BudgetLimit.builder()
                .categorie(categorie)
                .role(role)
                .plafond(new BigDecimal(plafond))
                .build());
    }

    /**
     * Cree une categorie de test.
     *
     * <p>
     * Le nom est suffixe par {@link System#nanoTime()} car la colonne
     * {@code categories.nom} porte une contrainte d'unicite. Les assertions
     * doivent donc reprendre le nom <em>reellement enregistre</em>, et non le
     * libelle passe ici.
     * </p>
     *
     * @param nom libelle de base
     * @return la categorie enregistree
     */
    private Category categorie(String nom) {
        return categoryRepository.save(Category.builder()
                .nom(nom + " " + System.nanoTime())
                .build());
    }

    /**
     * Cree un brouillon d'une ligne.
     *
     * @param employe   proprietaire
     * @param cat       categorie de la ligne
     * @param montant   montant de la ligne
     * @return l'identifiant de la note
     */
    private Long creerBrouillon(User employe, Category cat, String montant) {
        BigDecimal valeur = new BigDecimal(montant).setScale(2);
        ExpenseRequest note = ExpenseRequest.builder()
                .employee(employe)
                .statut(ExpenseStatus.DRAFT)
                .totalMontant(valeur)
                .build();
        note.getLignes().add(ExpenseLine.builder()
                .request(note)
                .categorie(cat)
                .montant(valeur)
                .dateDepense(LocalDate.of(2026, 9, 20))
                .description("Ligne de test")
                .build());
        return expenseRequestRepository.save(note).getId();
    }

    private String bearer(User utilisateur) {
        return "Bearer " + jwtService.generateToken(
                customUserDetailsService.loadUserByUsername(utilisateur.getEmail()));
    }

    private ExpenseStatus statutDe(Long noteId) {
        return expenseRequestRepository.findById(noteId).orElseThrow().getStatut();
    }

    private List<String> entreesDe(Long noteId) {
        return auditLogRepository
                .findByEntiteConcerneeAndEntiteIdOrderByDateActionAscIdAsc(
                        AuditLogService.ENTITE_EXPENSE_REQUEST, noteId)
                .stream()
                .map(AuditLog::getDetails)
                .toList();
    }

    /**
     * Lit une valeur du corps JSON d'une reponse, sans passer par jsonPath.
     *
     * <p>
     * {@code jsonPath} ne peut pas s'utiliser sur un {@code MvcResult} : il
     * s'applique au resultat d'un {@code andExpect}, ou rend un matcher. Lire
     * la chaine puis l'analyser permet d'exprimer plusieurs assertions sur le
     * meme message, ce qu'un matcher unique ne permet pas.
     * </p>
     *
     * @param resultat reponse a analyser
     * @param champ    champ JSON wished
     * @return valeur du champ
     */
    private String lectureJson(MvcResult resultat, String champ) throws Exception {
        return JsonPath.read(resultat.getResponse().getContentAsString(), champ);
    }
}