package com.expenseflow.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.entity.AiCallLog;
import com.expenseflow.entity.Category;
import com.expenseflow.entity.ExpenseLine;
import com.expenseflow.entity.ExpenseRequest;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.ExpenseStatus;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.AiCallLogRepository;
import com.expenseflow.repository.CategoryRepository;
import com.expenseflow.repository.ExpenseRequestRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.CustomUserDetailsService;
import com.expenseflow.security.JwtService;

/**
 * Tests d'integration de {@code GET /api/admin/stats}.
 *
 * <p>
 * Le meme carcan que {@code AdminUserControllerIT} : 401 sans jeton,
 * 403 hors role, 200 ADMIN — puis les cinq champs que le tableau de bord
 * consomme, un par un. Un champ disparu du JSON casserait le rendu sans
 * que le backend ne se plaigne : c'est ce que ces assertions empechent.
 * </p>
 *
 * <p>
 * La base de test est vide (les donnees de demo vivent dans
 * {@code demo-data.sql}, hors Flyway) : les totaux sont donc des nombres
 * exacts, pas des bornes inferieures.
 * </p>
 *
 * <p>
 * Chaque test cree ses propres comptes et roule dans une transaction
 * annulee : rien ne subsiste apres le test.
 * </p>
 */
@Transactional
@AutoConfigureMockMvc
@DisplayName("Statistiques d'administration (integration)")
class AdminStatsControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    @Autowired
    private ExpenseRequestRepository expenseRequestRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private AiCallLogRepository aiCallLogRepository;

    /**
     * Cache de premiere niveau du test — utile pour vider la session avant
     * le GET de verrouillage du journal.
     */
    @PersistenceContext
    private EntityManager entityManager;

    // ------------------------------------------------------------------
    // 1. Securite : le carcan ADMIN, identique a /admin/users
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/admin/stats sans jeton renvoie 401")
    void getSansJeton401() throws Exception {
        mockMvc.perform(get("/api/admin/stats"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/admin/stats en EMPLOYEE renvoie 403")
    void getEmploye403() throws Exception {
        User employe = createCompte("Employe Curieux", UserRole.EMPLOYEE);

        mockMvc.perform(get("/api/admin/stats")
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // 2. Contenu : le contrat des cinq champs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("200 ADMIN : totaux, repartition par role et journal vide")
    void getAdmin200Chiffres() throws Exception {
        User admin = createCompte("Admin Stats", UserRole.ADMIN);
        createCompte("Boss Stats", UserRole.MANAGER);
        createCompte("Employee Stats", UserRole.EMPLOYEE);
        createCompte("Finance Stats", UserRole.FINANCE_ADMIN);
        creerBrouillon(admin);
        aiCallLogRepository.save(AiCallLog.builder()
                .utilisateur(admin)
                .dureeMs(1200L)
                .succes(true)
                // Colonnes facturees NOT NULL — le vrai service les remplit
                // aussi, depuis la reponse du modele.
                .modele("openai/gpt-oss-120b")
                .tokensPrompt(40)
                .tokensCompletion(25)
                .tokensTotal(65)
                .build());

        // Aucune creation de compte dans ce test : le journal des actions
        // de compte est vide, et le champ existe quand meme (tableau vide).
        mockMvc.perform(get("/api/admin/stats")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(4))
                .andExpect(jsonPath("$.usersByRole.ADMIN").value(1))
                .andExpect(jsonPath("$.usersByRole.MANAGER").value(1))
                .andExpect(jsonPath("$.usersByRole.EMPLOYEE").value(1))
                .andExpect(jsonPath("$.usersByRole.FINANCE_ADMIN").value(1))
                .andExpect(jsonPath("$.totalExpenses").value(1))
                .andExpect(jsonPath("$.totalAiCalls").value(1))
                .andExpect(jsonPath("$.recentActions").isEmpty());
    }

    @Test
    @DisplayName("recentActions reflete la derniere creation de compte")
    void recentActionsRefleteCreationDeCompte() throws Exception {
        User admin = createCompte("Admin Journal", UserRole.ADMIN);
        String body = """
                {"nom": "Cible Journal", "email": "cible.journal@expenseflow.ma",
                 "motDePasse": "MotDePasse1", "role": "EMPLOYEE", "managerId": null}
                """;

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // L'entree d'audit vit dans le cache de premiere niveau : son
        // date_action (DEFAULT NOW(), insertable = false) n'a jamais ete
        // relu depuis la base dans cette transaction. On vide le cache pour
        // que le GET relise des lignes fraiches — c'est ce qui se passe en
        // production, chaque requete ouvrant un nouveau contexte.
        entityManager.flush();
        entityManager.clear();

        // L'action la plus recente est en tete de liste, avec sa cible telle
        // qu'ecrite dans audit_log.details : un compte supprime plus tard n'a
        // plus de ligne users a resolver — ce texte reste le seul fidele.
        mockMvc.perform(get("/api/admin/stats")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentActions[0].date").exists())
                .andExpect(jsonPath("$.recentActions[0].action").value("COMPTE_CREE"))
                .andExpect(jsonPath("$.recentActions[0].cible",
                        containsString("cible.journal@expenseflow.ma")))
                .andExpect(jsonPath("$.totalUsers").value(2));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Cree un compte de test jetable, jamais un compte de demonstration.
     *
     * @param nom  nom affiche (sert aussi a deriver l'e-mail)
     * @param role role du compte
     * @return compte persiste
     */
    private User createCompte(String nom, UserRole role) {
        return userRepository.save(User.builder()
                .nom(nom)
                .email(nom.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                        + "-" + System.nanoTime() + "@expenseflow.ma")
                .motDePasse(passwordEncoder.encode("Password123!"))
                .role(role)
                .build());
    }

    /**
     * Cree une note en brouillon — une ligne pour totalExpenses.
     *
     * @param proprietaire auteur de la note
     * @return identifiant de la note
     */
    private Long creerBrouillon(User proprietaire) {
        Category categorie = categoryRepository.save(Category.builder()
                .nom("Cat-Stats-" + System.nanoTime())
                .build());
        ExpenseRequest note = expenseRequestRepository.save(ExpenseRequest.builder()
                .employee(proprietaire)
                .statut(ExpenseStatus.DRAFT)
                .totalMontant(new BigDecimal("100.00"))
                .build());
        note.getLignes().add(ExpenseLine.builder()
                .request(note)
                .categorie(categorie)
                .montant(new BigDecimal("100.00"))
                .dateDepense(LocalDate.now())
                .build());
        return expenseRequestRepository.save(note).getId();
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
