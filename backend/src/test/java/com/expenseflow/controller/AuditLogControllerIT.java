package com.expenseflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.entity.AuditLog;
import com.expenseflow.entity.Category;
import com.expenseflow.entity.ExpenseLine;
import com.expenseflow.entity.ExpenseRequest;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.ExpenseStatus;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.AuditLogRepository;
import com.expenseflow.repository.CategoryRepository;
import com.expenseflow.repository.ExpenseRequestRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.JwtService;
import com.expenseflow.security.CustomUserDetailsService;
import com.expenseflow.service.AuditLogService;

/**
 * Tests d'integration de l'historique d'audit d'une note de frais.
 *
 * <p>
 * Le journal est le seul endroit ou se lit <em>pourquoi</em> une note a change
 * d'etat. Les tests de la section 2.3 en verifiaient l'ecriture au niveau du
 * service et du depot ; ici, c'est la restitution HTTP qui est verifiee, avec
 * l'ordre des entrees et le nom de leur auteur.
 * </p>
 *
 * <p>
 * <strong>Point d'attention sur l'ordre.</strong> Les deux transitions d'un meme
 * appel partagent leur horodatage : {@code date_action} vaut
 * {@code DEFAULT NOW()}, c'est-a-dire le <em>debut</em> de la transaction en
 * PostgreSQL. L'ordre reste donc determine par la cle secondaire {@code id},
 * que ces tests verifient en comparant des identifiants strictement croissants.
 * </p>
 */
@Transactional
@AutoConfigureMockMvc
@DisplayName("Historique d'audit (integration)")
class AuditLogControllerIT extends AbstractIntegrationTest {

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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    // ---------------------------------------------------------------------
    // A) Acces autorise
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Le proprietaire lit l'historique de sa note")
    void audit_parProprietaire_shouldReturn200() throws Exception {
        // Given
        User employe = createUser("Employe Historique", UserRole.EMPLOYEE);
        User manager = createUser("Manager Historique", UserRole.MANAGER);
        Long noteId = creerBrouillon(employe, "100.00");
        soumettreEtApprouver(noteId, employe, manager);

        // When / Then : quatre entrees, non pas trois. Le submit en produit
        // deux, et l'approbation du manager en produit deux aussi : le detail
        // d'approbation est un etat distinct du niveau d'attente finance.
        mockMvc.perform(get("/api/expense-requests/{id}/audit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    @DisplayName("Le MANAGER lit l'historique qu'il doit arbitrer")
    void audit_parManager_shouldReturn200() throws Exception {
        // Given
        User employe = createUser("Employe Valide", UserRole.EMPLOYEE);
        User manager = createUser("Manager Arbitre", UserRole.MANAGER);
        Long noteId = creerBrouillon(employe, "100.00");
        soumettreEtApprouver(noteId, employe, manager);

        // When / Then
        mockMvc.perform(get("/api/expense-requests/{id}/audit", noteId)
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    @DisplayName("La FINANCE lit l'historique qu'elle doit rembourser")
    void audit_parFinance_shouldReturn200() throws Exception {
        // Given
        User employe = createUser("Employe Financee", UserRole.EMPLOYEE);
        User finance = createUser("Finance Historique", UserRole.FINANCE_ADMIN);
        Long noteId = creerBrouillon(employe, "100.00");

        // When / Then
        mockMvc.perform(get("/api/expense-requests/{id}/audit", noteId)
                        .header("Authorization", bearer(finance)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------------------------------------------------------------------
    // B) Acces refuse
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Un autre EMPLOYEE ne peut pas lire l'historique : 403")
    void audit_parTiers_shouldReturn403() throws Exception {
        // Given : le tiers n'est ni proprietaire, ni validateur
        User proprietaire = createUser("Employe Victime", UserRole.EMPLOYEE);
        User tiers = createUser("Employe Indiscret", UserRole.EMPLOYEE);
        Long noteId = creerBrouillon(proprietaire, "100.00");

        // When / Then
        mockMvc.perform(get("/api/expense-requests/{id}/audit", noteId)
                        .header("Authorization", bearer(tiers)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Sans jeton, l'historique renvoie 401")
    void audit_sansJeton_shouldReturn401() throws Exception {
        // Given
        User employe = createUser("Employe Anonyme", UserRole.EMPLOYEE);
        Long noteId = creerBrouillon(employe, "100.00");

        // When / Then
        mockMvc.perform(get("/api/expense-requests/{id}/audit", noteId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("L'historique d'une note inexistante renvoie 404")
    void audit_noteInconnue_shouldReturn404() throws Exception {
        // Given
        User employe = createUser("Employe Chercheur", UserRole.EMPLOYEE);

        // When / Then
        mockMvc.perform(get("/api/expense-requests/{id}/audit", 999999L)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------
    // C) Coherence de l'historique apres le circuit complet
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Le circuit complet laisse cinq entrees, dans l'ordre chronologique")
    void audit_apresCircuitComplet_shouldReturnFiveOrderedEntries() throws Exception {
        // Given
        User employe = createUser("Employe Circuit", UserRole.EMPLOYEE);
        User manager = createUser("Manager Circuit", UserRole.MANAGER);
        User finance = createUser("Finance Circuit", UserRole.FINANCE_ADMIN);
        Long noteId = creerBrouillon(employe, "100.00");

        // When : submit, approve, reimburse
        soumettreEtApprouver(noteId, employe, manager);
        mockMvc.perform(post("/api/expense-requests/{id}/reimburse", noteId)
                        .header("Authorization", bearer(finance)))
                .andExpect(status().isOk());

        // Then : cinq changements d'etat pour quatre appels. Le submit en
        // produit deux, les autres un chacun.
        mockMvc.perform(get("/api/expense-requests/{id}/audit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].details").value("DRAFT -> SUBMITTED"))
                .andExpect(jsonPath("$[1].details").value("SUBMITTED -> PENDING_MANAGER"))
                .andExpect(jsonPath("$[2].details").value("PENDING_MANAGER -> APPROVED_MANAGER"))
                .andExpect(jsonPath("$[3].details").value("APPROVED_MANAGER -> PENDING_FINANCE"))
                .andExpect(jsonPath("$[4].details").value("PENDING_FINANCE -> REIMBURSED"));
    }

    @Test
    @DisplayName("Les identifiants des entrees sont strictement croissants")
    void audit_ordonneParIdentifiant() throws Exception {
        // Given
        User employe = createUser("Employe Ordre", UserRole.EMPLOYEE);
        User manager = createUser("Manager Ordre", UserRole.MANAGER);
        Long noteId = creerBrouillon(employe, "100.00");

        // When : les deux transitions du submit partagent le meme horodatage
        soumettreEtApprouver(noteId, employe, manager);

        // Then : seul l'identifiant distingue leur ordre
        List<AuditLog> entrees = auditLogRepository
                .findByEntiteConcerneeAndEntiteIdOrderByDateActionAscIdAsc(
                        AuditLogService.ENTITE_EXPENSE_REQUEST, noteId);
        assertThat(entrees).hasSize(4);
        assertThat(entrees.get(0).getId()).isLessThan(entrees.get(1).getId());
        assertThat(entrees.get(1).getId()).isLessThan(entrees.get(2).getId());
        assertThat(entrees.get(2).getId()).isLessThan(entrees.get(3).getId());
    }

    @Test
    @DisplayName("Chaque entree porte l'identite de son auteur, lue par JOIN FETCH")
    void audit_exposeLeNomDeLAuteur() throws Exception {
        // Given : le submit est de l'employe, l'approbation du manager
        User employe = createUser("Employe Nom", UserRole.EMPLOYEE);
        User manager = createUser("Manager Nom", UserRole.MANAGER);
        Long noteId = creerBrouillon(employe, "100.00");

        // When
        soumettreEtApprouver(noteId, employe, manager);

        // Then : les deux premieres transitions sont de l'employe, la troisieme
        // du manager. Le nom est resolu sans requete supplementaire.
        mockMvc.perform(get("/api/expense-requests/{id}/audit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].utilisateurNom").value("Employe Nom"))
                .andExpect(jsonPath("$[0].utilisateurId").value(employe.getId()))
                .andExpect(jsonPath("$[2].utilisateurNom").value("Manager Nom"))
                .andExpect(jsonPath("$[2].utilisateurId").value(manager.getId()));
    }

    // ---------------------------------------------------------------------
    // D) Cas limites
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Une note jamais soumise a un historique vide, pas une erreur")
    void audit_noteJamaisSoumise_shouldReturnEmptyArray() throws Exception {
        // Given : le brouillon n'a jamais quitte DRAFT
        User employe = createUser("Employe Intact", UserRole.EMPLOYEE);
        Long noteId = creerBrouillon(employe, "100.00");

        // When / Then
        mockMvc.perform(get("/api/expense-requests/{id}/audit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("L'historique est isole par note : deux notes ne se melangent pas")
    void audit_isoleParNote() throws Exception {
        // Given : deux notes, une seule transitionnee
        User employe = createUser("Employe Isole", UserRole.EMPLOYEE);
        User manager = createUser("Manager Isole", UserRole.MANAGER);
        Long noteTransitionnee = creerBrouillon(employe, "100.00");
        Long noteIntacte = creerBrouillon(employe, "200.00");
        soumettreEtApprouver(noteTransitionnee, employe, manager);

        // When / Then : entiteConcernee est identique pour les deux, c'est
        // entiteId qui discrimine. Sans lui, les historiques seraient confondus.
        mockMvc.perform(get("/api/expense-requests/{id}/audit", noteIntacte)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        assertThat(detailsEnBase(noteTransitionnee)).hasSize(4);
        assertThat(detailsEnBase(noteIntacte)).isEmpty();
    }

    @Test
    @DisplayName("Une transition refusee n'apparait pas dans l'historique")
    void audit_transitionRefusee_absente() throws Exception {
        // Given : note soumise, donc un second submit echouera
        User employe = createUser("Employe Refus", UserRole.EMPLOYEE);
        Long noteId = creerBrouillon(employe, "100.00");
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk());

        // When
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isConflict());

        // Then : le journal ne conserve que les deux transitions reussies
        mockMvc.perform(get("/api/expense-requests/{id}/audit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
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
     * Cree une note en brouillon d'une seule ligne.
     *
     * @param employe proprietaire
     * @param montant montant de la ligne
     * @return l'identifiant de la note
     */
    private Long creerBrouillon(User employe, String montant) {
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

    /**
     * Enchaine submit puis approve par le manager.
     *
     * @param noteId  note concernee
     * @param employe proprietaire
     * @param manager validateur
     */
    private void soumettreEtApprouver(Long noteId, User employe, User manager) throws Exception {
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/expense-requests/{id}/approve", noteId)
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk());
    }

    private String bearer(User utilisateur) {
        return "Bearer " + jwtService.generateToken(
                customUserDetailsService.loadUserByUsername(utilisateur.getEmail()));
    }

    /**
     * Details de l'historique, dans l'ordre chronologique, lus en base.
     *
     * @param noteId note concernee
     * @return la sequence des details
     */
    private List<String> detailsEnBase(Long noteId) {
        return auditLogRepository
                .findByEntiteConcerneeAndEntiteIdOrderByDateActionAscIdAsc(
                        AuditLogService.ENTITE_EXPENSE_REQUEST, noteId)
                .stream()
                .map(AuditLog::getDetails)
                .toList();
    }
}