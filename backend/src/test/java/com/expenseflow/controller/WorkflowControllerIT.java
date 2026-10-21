package com.expenseflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import java.util.Comparator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.entity.Approval;
import com.expenseflow.entity.AuditLog;
import com.expenseflow.entity.Category;
import com.expenseflow.entity.ExpenseLine;
import com.expenseflow.entity.ExpenseRequest;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.ApprovalStatus;
import com.expenseflow.entity.enums.ExpenseStatus;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.ApprovalRepository;
import com.expenseflow.repository.AuditLogRepository;
import com.expenseflow.repository.CategoryRepository;
import com.expenseflow.repository.ExpenseRequestRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.JwtService;
import com.expenseflow.security.CustomUserDetailsService;
import com.expenseflow.service.AuditLogService;

/**
 * Tests d'integration des cinq transitions du workflow d'approbation.
 *
 * <p>
 * Ces tests completent les tests unitaires de la section 2.1, qui verifient
 * {@code ExpenseWorkflow} isolement. Ici, la machine a etats est traversee par
 * HTTP, avec un vrai jeton JWT, une vraie base PostgreSQL et les memes controles
 * d'acces que la production.
 * </p>
 *
 * <p>
 * <strong>Ce que ces tests apportent que les precedents ne pouvaient pas.</strong>
 * Le controle d'acces des transitions vit dans le service. Un test unitaire peut
 * verifier qu'un role est refuse ; seul un test de bout en bout verifie que le
 * filtre de securite ne renvoie pas 401 avant que le service n'ait l'occasion de
 * repondre 403, et qu'un acces refuse n'a rien ecrit en base.
 * </p>
 */
@Transactional
@AutoConfigureMockMvc
@DisplayName("Workflow (integration)")
class WorkflowControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ExpenseRequestRepository expenseRequestRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ApprovalRepository approvalRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    // ---------------------------------------------------------------------
    // A) Scenario nominal complet
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Le circuit complet DRAFT -> REIMBURSED enchaine les quatre appels")
    void circuitComplet_shouldReachReimbursed() throws Exception {
        // Given
        User employe = createUser("Employe Circuit", UserRole.EMPLOYEE);
        User manager = createUser("Manager Circuit", UserRole.MANAGER);
        User finance = createUser("Finance Circuit", UserRole.FINANCE_ADMIN);
        Long noteId = creerNote(employe, "100.00");

        // When / Then : quatre appels, cinq changements d'etat. Le circuit est
        // mono-niveau : il n'existe pas d'approbation finance distincte, la
        // finance passe directement de PENDING_FINANCE a REIMBURSED.
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);
        transition(noteId, "/approve", manager, ExpenseStatus.PENDING_FINANCE);
        transition(noteId, "/reimburse", finance, ExpenseStatus.REIMBURSED);

        // Then : cinq entrees de journal, tous du meme type
        assertThat(entrees(noteId))
                .hasSize(5)
                .extracting(AuditLog::getAction)
                .containsOnly("STATUS_CHANGE");
    }

    @Test
    @DisplayName("submit() enchaine DRAFT -> SUBMITTED -> PENDING_MANAGER et journalise les deux")
    void submit_shouldChainTwoTransitions() throws Exception {
        // Given
        User employe = createUser("Employe Submit", UserRole.EMPLOYEE);
        Long noteId = creerNote(employe, "75.00");

        // When
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);

        // Then : l'etat intermediaire reste trace. L'appelant ne voit que
        // PENDING_MANAGER, mais le journal conserve SUBMITTED, qui distingue
        // une note volontairement soumise d'une note placee directement en
        // attente de validation.
        assertThat(entrees(noteId)).hasSize(2);
        assertThat(entrees(noteId).get(0).getDetails()).isEqualTo("DRAFT -> SUBMITTED");
        assertThat(entrees(noteId).get(1).getDetails()).isEqualTo("SUBMITTED -> PENDING_MANAGER");
    }

    @Test
    @DisplayName("Une approbation humaine laisse une trace dans approvals")
    void approbation_traceDansApprovals() throws Exception {
        // Given
        User employe = createUser("Employe Approvals", UserRole.EMPLOYEE);
        User manager = createUser("Manager Approvals", UserRole.MANAGER);
        Long noteId = creerNote(employe, "120.00");
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);

        // When
        transition(noteId, "/approve", manager, ExpenseStatus.PENDING_FINANCE);

        // Then : une seule decision humaine, contre trois changements d'etat
        // a ce stade. L'ecart mesure ce que le journal d'audit ne dit pas :
        // qui a approuve, et a quel niveau.
        assertThat(approbations(noteId))
                .singleElement()
                .extracting(Approval::getStatut)
                .isEqualTo(ApprovalStatus.APPROVED_MANAGER);
    }

    // ---------------------------------------------------------------------
    // B) Rejets
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("reject() par le manager conduit a REJECTED_MANAGER et enregistre le motif")
    void reject_manager_shouldRecordMotif() throws Exception {
        // Given
        User employe = createUser("Employe Rejet M", UserRole.EMPLOYEE);
        User manager = createUser("Manager Rejet M", UserRole.MANAGER);
        Long noteId = creerNote(employe, "60.00");
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);

        // When
        transition(noteId, "/reject", manager, ExpenseStatus.REJECTED_MANAGER, rejet("Recette trop elevee"));

        // Then : le motif est conserve, sans quoi l'employe ignore pourquoi
        // sa note est revenue.
        assertThat(approbations(noteId))
                .singleElement()
                .extracting(a -> a.getCommentaire())
                .isEqualTo("Recette trop elevee");
    }

    @Test
    @DisplayName("reject-finance par la finance conduit a REJECTED_FINANCE")
    void rejectFinance_shouldReachRejectedFinance() throws Exception {
        // Given
        User employe = createUser("Employe Rejet F", UserRole.EMPLOYEE);
        User manager = createUser("Manager Rejet F", UserRole.MANAGER);
        User finance = createUser("Finance Rejet F", UserRole.FINANCE_ADMIN);
        Long noteId = creerNote(employe, "60.00");
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);
        transition(noteId, "/approve", manager, ExpenseStatus.PENDING_FINANCE);

        // When / Then
        transition(noteId, "/reject-finance", finance, ExpenseStatus.REJECTED_FINANCE,
                rejet("Remboursement echeoue"));
    }

    @Test
    @DisplayName("Un rejet sans motif renvoie 400 et ne deplace pas la note")
    void reject_sansMotif_shouldReturn400() throws Exception {
        // Given
        User employe = createUser("Employe Rejet Vide", UserRole.EMPLOYEE);
        User manager = createUser("Manager Rejet Vide", UserRole.MANAGER);
        Long noteId = creerNote(employe, "60.00");
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/reject", noteId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commentaire\":\"\"}"))
                .andExpect(status().isBadRequest());

        // Then
        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.PENDING_MANAGER);
    }

    // ---------------------------------------------------------------------
    // C) Controle d'acces : la matrice des roles
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("submit() par un tiers renvoie 403 et ne journalise rien")
    void submit_parUnTiers_shouldReturn403() throws Exception {
        // Given : le tiers est lui-meme employe, seul le proprietaire est refuse
        User proprietaire = createUser("Employe Propre", UserRole.EMPLOYEE);
        User tiers = createUser("Employe Tiers", UserRole.EMPLOYEE);
        Long noteId = creerNote(proprietaire, "80.00");

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(tiers)))
                .andExpect(status().isForbidden());

        // Then : le refus ne doit rien avoir ecrit en base
        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.DRAFT);
        assertThat(entrees(noteId)).isEmpty();
    }

    @Test
    @DisplayName("submit() par le MANAGER de la note renvoie 403 : soumettre n'est pas approuver")
    void submit_parManager_shouldReturn403() throws Exception {
        // Given : le manager est le superieur de l'employe, sa note donc visible
        User employe = createUser("Employe Soumis", UserRole.EMPLOYEE);
        User manager = createUser("Manager Soumis", UserRole.MANAGER);
        employe.setManager(manager);
        userRepository.save(employe);
        Long noteId = creerNote(employe, "80.00");

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden());

        // Then
        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.DRAFT);
    }

    @Test
    @DisplayName("approve() par un EMPLOYEE renvoie 403")
    void approve_parEmploye_shouldReturn403() throws Exception {
        // Given
        User employe = createUser("Employe Faux Validateur", UserRole.EMPLOYEE);
        User autre = createUser("Employe Autre", UserRole.EMPLOYEE);
        Long noteId = creerNote(employe, "80.00");
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/approve", noteId)
                        .header("Authorization", bearer(autre)))
                .andExpect(status().isForbidden());

        // Then
        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.PENDING_MANAGER);
    }

    @Test
    @DisplayName("reimburse() par le MANAGER renvoie 403 : rembourser est une decision finance")
    void reimburse_parManager_shouldReturn403() throws Exception {
        // Given
        User employe = createUser("Employe Rembourse", UserRole.EMPLOYEE);
        User manager = createUser("Manager Rembourse", UserRole.MANAGER);
        Long noteId = creerNote(employe, "80.00");
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);
        transition(noteId, "/approve", manager, ExpenseStatus.PENDING_FINANCE);

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/reimburse", noteId)
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden());

        // Then
        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.PENDING_FINANCE);
    }

    @Test
    @DisplayName("reject-finance() par le MANAGER renvoie 403")
    void rejectFinance_parManager_shouldReturn403() throws Exception {
        // Given
        User employe = createUser("Employe RF", UserRole.EMPLOYEE);
        User manager = createUser("Manager RF", UserRole.MANAGER);
        User finance = createUser("Finance RF", UserRole.FINANCE_ADMIN);
        Long noteId = creerNote(employe, "80.00");
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);
        transition(noteId, "/approve", manager, ExpenseStatus.PENDING_FINANCE);

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/reject-finance", noteId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rejet("Hors competence")))
                .andExpect(status().isForbidden());

        // Then
        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.PENDING_FINANCE);
    }

    @Test
    @DisplayName("Sans jeton, une transition renvoie 401")
    void transition_sansJeton_shouldReturn401() throws Exception {
        // Given
        User employe = createUser("Employe Anonyme", UserRole.EMPLOYEE);
        Long noteId = creerNote(employe, "80.00");

        // When / Then
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId))
                .andExpect(status().isUnauthorized());

        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.DRAFT);
    }

    @Test
    @DisplayName("Une transition sur une note inexistante renvoie 404")
    void transition_noteInconnue_shouldReturn404() throws Exception {
        // Given
        User employe = createUser("Employe Orphelin", UserRole.EMPLOYEE);

        // When / Then
        mockMvc.perform(post("/api/expense-requests/{id}/submit", 999999L)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------
    // D) Transitions interdites par la machine a etats
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("reimburse() sur une note en PENDING_MANAGER renvoie 409")
    void reimburse_tropTot_shouldReturn409() throws Exception {
        // Given : le role est correct, l'etat ne l'est pas
        User employe = createUser("Employe Trop Tot", UserRole.EMPLOYEE);
        User finance = createUser("Finance Trop Tot", UserRole.FINANCE_ADMIN);
        Long noteId = creerNote(employe, "80.00");
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/reimburse", noteId)
                        .header("Authorization", bearer(finance)))
                .andExpect(status().isConflict());

        // Then
        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.PENDING_MANAGER);
    }

    @Test
    @DisplayName("submit() deux fois renvoie 409 et n'ajoute aucune entree au journal")
    void submit_deuxFois_shouldReturn409() throws Exception {
        // Given
        User employe = createUser("Employe Double", UserRole.EMPLOYEE);
        Long noteId = creerNote(employe, "80.00");
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isConflict());

        // Then : le journal ne compte toujours que les deux transitions du
        // premier submit. Une transition refusee ne laisse pas de trace.
        assertThat(entrees(noteId)).hasSize(2);
    }

    @Test
    @DisplayName("Une note rejetee est definitive : meme son proprietaire ne peut la resoumettre")
    void submit_apresRejet_shouldReturn409() throws Exception {
        // Given
        User employe = createUser("Employe Rejete", UserRole.EMPLOYEE);
        User manager = createUser("Manager Rejete", UserRole.MANAGER);
        Long noteId = creerNote(employe, "80.00");
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);
        transition(noteId, "/reject", manager, ExpenseStatus.REJECTED_MANAGER, rejet("Non eligible"));

        // When / Then
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isConflict());

        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.REJECTED_MANAGER);
    }

    // ---------------------------------------------------------------------
    // E) Transitions refusees : etat incompatible avec le role de l'acteur
    //
    // Ces cas se distinguent des refus de role (section C) : ici l'acteur
    // aurait le droit d'agir, mais la note n'est pas dans l'etat qui le
    // permet. Le code repond 409 et non 403.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("approve() par la FINANCE sur PENDING_MANAGER renvoie 200 : le role est admis")
    void approve_parFinance_shouldSucceed() throws Exception {
        // Given : une note en attente de validation manager
        User employe = createUser("Employe Finance", UserRole.EMPLOYEE);
        User finance = createUser("Finance Valideuse", UserRole.FINANCE_ADMIN);
        Long noteId = creerNote(employe, "80.00");
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);

        // When
        transition(noteId, "/approve", finance, ExpenseStatus.PENDING_FINANCE);

        // Then : approve() admet MANAGER <em>et</em> FINANCE_ADMIN. La finance
        // peut donc valider au niveau manager, sans attendre son tour. C'est
        // un choix assume : le controle d'acces porte sur la competence, pas
        // sur l'afficheur de la demande.
        assertThat(approbations(noteId))
                .singleElement()
                .extracting(Approval::getStatut)
                .isEqualTo(ApprovalStatus.APPROVED_MANAGER);
    }

    @Test
    @DisplayName("approve() sur une note en DRAFT renvoie 409")
    void approve_surBrouillon_shouldReturn409() throws Exception {
        // Given : la note n'a jamais ete soumise, le role est pourtant valide
        User employe = createUser("Employe Approfondit", UserRole.EMPLOYEE);
        User manager = createUser("Manager Approfondit", UserRole.MANAGER);
        Long noteId = creerNote(employe, "80.00");

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/approve", noteId)
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isConflict());

        // Then : rien n'a bouge, et le journal est vide
        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.DRAFT);
        assertThat(entrees(noteId)).isEmpty();
    }

    @Test
    @DisplayName("approve() sur une note REMBURSEE renvoie 409 : l'etat est terminal")
    void approve_surRemboursee_shouldReturn409() throws Exception {
        // Given : note deja remboursee
        User employe = createUser("Employe Terminal", UserRole.EMPLOYEE);
        User manager = createUser("Manager Terminal", UserRole.MANAGER);
        User finance = createUser("Finance Terminale", UserRole.FINANCE_ADMIN);
        Long noteId = creerNote(employe, "80.00");
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);
        transition(noteId, "/approve", manager, ExpenseStatus.PENDING_FINANCE);
        transition(noteId, "/reimburse", finance, ExpenseStatus.REIMBURSED);
        int entreesAvant = entrees(noteId).size();

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/approve", noteId)
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isConflict());

        // Then : un etat terminal ne se rediscute pas, et le refus n'ajoute
        // aucune entree au journal.
        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.REIMBURSED);
        assertThat(entrees(noteId)).hasSize(entreesAvant);
    }

    @Test
    @DisplayName("reject() par un EMPLOYEE renvoie 403, avant meme de regarder l'etat")
    void reject_parEmploye_shouldReturn403() throws Exception {
        // Given : la note est bien en attente de validation, seul le role
        // est incorrect.
        User employe = createUser("Employe Faux Rejecteur", UserRole.EMPLOYEE);
        Long noteId = creerNote(employe, "80.00");
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/reject", noteId)
                        .header("Authorization", bearer(employe))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rejet("Je me rejette moi-meme")))
                .andExpect(status().isForbidden());

        // Then
        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.PENDING_MANAGER);
    }

    @Test
    @DisplayName("reject() sur une note en DRAFT renvoie 409")
    void reject_surBrouillon_shouldReturn409() throws Exception {
        // Given
        User employe = createUser("Employe Rejet Precoce", UserRole.EMPLOYEE);
        User manager = createUser("Manager Rejet Precoce", UserRole.MANAGER);
        Long noteId = creerNote(employe, "80.00");

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/reject", noteId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rejet("Trop tot")))
                .andExpect(status().isConflict());

        // Then : le corps etait valide et le role correct, seul l'etat bloque
        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.DRAFT);
        assertThat(approbations(noteId)).isEmpty();
    }

    @Test
    @DisplayName("reject-finance() sans motif renvoie 400 et laisse la note en attente")
    void rejectFinance_sansMotif_shouldReturn400() throws Exception {
        // Given
        User employe = createUser("Employe RF Vide", UserRole.EMPLOYEE);
        User manager = createUser("Manager RF Vide", UserRole.MANAGER);
        User finance = createUser("Finance RF Vide", UserRole.FINANCE_ADMIN);
        Long noteId = creerNote(employe, "80.00");
        transition(noteId, "/submit", employe, ExpenseStatus.PENDING_MANAGER);
        transition(noteId, "/approve", manager, ExpenseStatus.PENDING_FINANCE);

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/reject-finance", noteId)
                        .header("Authorization", bearer(finance))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commentaire\":\"   \"}"))
                .andExpect(status().isBadRequest());

        // Then : une suite d'espaces ne vaut pas un motif. Le rejetant ne peut
        // pas se debattre d'un refus qu'il n'a pas motive.
        assertThat(statutDe(noteId)).isEqualTo(ExpenseStatus.PENDING_FINANCE);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Enregistre un utilisateur de test, avec un mot de passe reellement hache.
     *
     * <p>
     * L'adresse est suffixe par {@link System#nanoTime()} : la colonne
     * {@code users.email} porte une contrainte d'unicite, et deux methodes de
     * test distinctes ne doivent pas pouvoir se chevaucher.
     * </p>
     *
     * @param nom  nom affiche
     * @param role role applicatif
     * @return l'utilisateur enregistre
     */
    private User createUser(String nom, UserRole role) {
        return userRepository.save(User.builder()
                .nom(nom)
                .email(nom.toLowerCase().replace(' ', '-') + "-" + System.nanoTime() + "@expenseflow.ma")
                .motDePasse(passwordEncoder.encode("MotDePasse1!"))
                .role(role)
                .build());
    }

    /**
     * Cree une note en brouillon appartenant a l'employe donne.
     *
     * <p>
     * La categorie est creee a la volee : la colonne {@code categories.nom} porte
     * une contrainte d'unicite, et un nom suffixe evite toute collision.
     * </p>
     *
     * @param employe proprietaire de la note
     * @param montant montant de l'unique ligne
     * @return l'identifiant de la note creee
     */
    private Long creerNote(User employe, String montant) {
        Category categorie = categoryRepository.save(Category.builder()
                .nom("Categorie " + System.nanoTime())
                .build());

        BigDecimal valeur = new BigDecimal(montant).setScale(2);
        ExpenseRequest note = ExpenseRequest.builder()
                .employee(employe)
                .statut(ExpenseStatus.DRAFT)
                .totalMontant(valeur)
                .build();
        note.getLignes().add(ExpenseLine.builder()
                .request(note)
                .categorie(categorie)
                .montant(valeur)
                .dateDepense(LocalDate.of(2026, 9, 20))
                .description("Ligne de test")
                .build());

        return expenseRequestRepository.save(note).getId();
    }

    private ExpenseStatus statutDe(Long noteId) {
        return expenseRequestRepository.findById(noteId).orElseThrow().getStatut();
    }

    /**
     * Decisions d'approbation d'une note, dans l'ordre de creation.
     *
     * <p>
     * Le depot ne declare aucune methode derivee : le tri est applique en
     * memoire, ce qui convient a une liste de quelques elements.
     * </p>
     *
     * @param noteId note concernee
     * @return ses decisions d'approbation
     */
    private List<Approval> approbations(Long noteId) {
        return approvalRepository.findAll().stream()
                .filter(a -> a.getRequest().getId().equals(noteId))
                .sorted(Comparator.comparing(Approval::getId))
                .toList();
    }

    /**
     * Journal d'audit d'une note, dans l'ordre chronologique.
     *
     * @param noteId note concernee
     * @return ses entrees de journal
     */
    private List<AuditLog> entrees(Long noteId) {
        return auditLogRepository.findByEntiteConcerneeAndEntiteIdOrderByDateActionAscIdAsc(
                AuditLogService.ENTITE_EXPENSE_REQUEST, noteId);
    }

    /**
     * Execute une transition sans corps de requete.
     *
     * @param noteId  note concernee
     * @param action  suffixe de l'endpoint, {@code /submit} par exemple
     * @param acteur  utilisateur authentifie
     * @param attendu statut attendu apres la transition
     */
    private void transition(Long noteId, String action, User acteur, ExpenseStatus attendu) throws Exception {
        transition(noteId, action, acteur, attendu, null);
    }

    /**
     * Execute une transition et verifie le statut obtenu.
     *
     * @param noteId  note concernee
     * @param action  suffixe de l'endpoint
     * @param acteur  utilisateur authentifie
     * @param attendu statut attendu apres la transition
     * @param body    corps JSON, ou {@code null} pour une transition sans corps
     */
    private void transition(Long noteId, String action, User acteur, ExpenseStatus attendu, String body)
            throws Exception {
        MockHttpServletRequestBuilder requete = post("/api/expense-requests/{id}" + action, noteId)
                .header("Authorization", bearer(acteur));
        if (body != null) {
            requete = requete.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mockMvc.perform(requete)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(noteId))
                .andExpect(jsonPath("$.statut").value(attendu.name()));
    }

    /**
     * Construit un corps de rejet valide.
     *
     * @param commentaire motif du rejet
     * @return JSON serialise
     */
    private String rejet(String commentaire) {
        return "{\"commentaire\":\"" + commentaire + "\"}";
    }

    /**
     * Construit un en-tete Authorization pour l'utilisateur donne.
     *
     * @param utilisateur utilisateur pour lequel signer
     * @return valeur prete pour l'en-tete {@code Authorization}
     */
    private String bearer(User utilisateur) {
        return "Bearer " + jwtService.generateToken(
                customUserDetailsService.loadUserByUsername(utilisateur.getEmail()));
    }
}