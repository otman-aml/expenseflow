package com.expenseflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
import com.expenseflow.security.CustomUserDetailsService;
import com.expenseflow.security.JwtService;

/**
 * Tests d'intégration de l'administration {@code /api/admin/users/**}.
 *
 * <p>
 * La classe vérifie la SOUCHE de sécurité : {@code @PreAuthorize("ADMIN")}
 * sur la classe entière. Chaque cas d'accès — jeton absent, rôle faible,
 * rôle ADMIN — est éprouvé sur une même route : un unique maillon cassé
 * (une annotation oubliée sur un handler) laisserait la porte entière
 * ouverte, et c'est exactement ce que ces tests cherchent.
 * </p>
 *
 * <p>
 * Comme les autres IT, chaque test crée ses propres comptes et roule dans
 * une transaction annulée : rien ne subsiste après le test.
 * </p>
 */
@Transactional
@AutoConfigureMockMvc
@DisplayName("Administration des comptes (integration)")
class AdminUserControllerIT extends AbstractIntegrationTest {

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
    private AuditLogRepository auditLogRepository;

    // ---------------------------------------------------------------------
    // 1. Sécurité : le carcan ADMIN
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/admin/users sans jeton renvoie 401")
    void getSansJeton401() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/admin/users en EMPLOYEE renvoie 403")
    void getEmploye403() throws Exception {
        User employe = createCompte("Employe Curieux", UserRole.EMPLOYEE);

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/users en ADMIN renvoie 200 et la liste des comptes")
    void getAdmin200() throws Exception {
        User admin = createCompte("Admin Test", UserRole.ADMIN);
        User boss = createCompte("Boss List", UserRole.MANAGER);
        User employe = createCompte("Cible Listee", UserRole.EMPLOYEE);
        employe.setManager(boss);
        userRepository.save(employe);

        // La requête est triée par id ASC : admin=0, boss=1, employé=2.
        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].managerNom").value("Boss List"));
    }

    // ---------------------------------------------------------------------
    // 2. Création
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("POST crée un compte haché en base et renvoie 201")
    void postCreeUnCompte201() throws Exception {
        User admin = createCompte("Admin Create", UserRole.ADMIN);
        String body = """
                {"nom": "Nouvel Employe", "email": "nouvel@expenseflow.ma",
                 "motDePasse": "MotDePasse1", "role": "EMPLOYEE", "managerId": null}
                """;

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("nouvel@expenseflow.ma"))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"));

        // La base reçoit un HASH, pas la saisie
        User cree = userRepository.findByEmail("nouvel@expenseflow.ma").orElseThrow();
        assertThat(cree.getMotDePasse()).isNotEqualTo("MotDePasse1");
        assertThat(passwordEncoder.matches("MotDePasse1", cree.getMotDePasse())).isTrue();
    }

    @Test
    @DisplayName("POST crée un compte avec doitChangerMdp = true — visible dès la connexion")
    void postCreeUnCompteAvecDoitChangerMdp() throws Exception {
        User admin = createCompte("Admin Provisoire", UserRole.ADMIN);
        String body = """
                {"nom": "Salarie Provisoire", "email": "provisoire@expenseflow.ma",
                 "motDePasse": "MotDePasse1", "role": "EMPLOYEE", "managerId": null}
                """;

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // La connexion reçoit le drapeau : le front force alors le changement
        // de mot de passe (redirection vers /profile).
        String connexion = """
                {"email": "provisoire@expenseflow.ma", "motDePasse": "MotDePasse1"}
                """;
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(connexion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.doitChangerMdp").value(true));
    }

    @Test
    @DisplayName("POST avec un e-mail déjà pris renvoie 409")
    void postEmailDuplique409() throws Exception {
        User admin = createCompte("Admin Dup", UserRole.ADMIN);
        User existant = createCompte("Existant", UserRole.EMPLOYEE);
        String body = """
                {"nom": "Doublon", "email": "%s",
                 "motDePasse": "MotDePasse1", "role": "EMPLOYEE", "managerId": null}
                """.formatted(existant.getEmail());

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------------
    // 3. Édition et suppression
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("PUT modifie nom, rôle et rattachement, puis le reflète en base")
    void putModifieLeCompte() throws Exception {
        User admin = createCompte("Admin Edit", UserRole.ADMIN);
        User cible = createCompte("Avant", UserRole.EMPLOYEE);
        User manager = createCompte("Boss", UserRole.MANAGER);
        String body = """
                {"nom": "Apres", "email": "%s",
                 "role": "MANAGER", "managerId": %d}
                """.formatted(cible.getEmail(), manager.getId());

        mockMvc.perform(put("/api/admin/users/{id}", cible.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nom").value("Apres"))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.managerId").value(manager.getId()));

        User modifie = userRepository.findById(cible.getId()).orElseThrow();
        assertThat(modifie.getRole()).isEqualTo(UserRole.MANAGER);
        assertThat(modifie.getManager().getId()).isEqualTo(manager.getId());
    }

    @Test
    @DisplayName("DELETE sur un compte sans note renvoie 204 et efface le compte")
    void deleteCompteVierge204() throws Exception {
        User admin = createCompte("Admin Del", UserRole.ADMIN);
        User cible = createCompte("Supprimable", UserRole.EMPLOYEE);

        mockMvc.perform(delete("/api/admin/users/{id}", cible.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(cible.getId())).isEmpty();
    }

    @Test
    @DisplayName("DELETE sur un compte AVEC des notes renvoie 409")
    void deleteCompteAvecNotes409() throws Exception {
        User admin = createCompte("Admin Del2", UserRole.ADMIN);
        User employe = createCompte("Employe Note", UserRole.EMPLOYEE);
        creerBrouillon(employe);

        mockMvc.perform(delete("/api/admin/users/{id}", employe.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());

        assertThat(userRepository.findById(employe.getId())).isPresent();
    }

    @Test
    @DisplayName("DELETE de soi-même renvoie 403 (anti lock-out)")
    void deleteSoiMeme403() throws Exception {
        User admin = createCompte("Admin Auto", UserRole.ADMIN);
        // Un second admin : l'invariant « dernier ADMIN » est satisfait, c'est
        // bien l'auto-refus d'identité qui doit trancher (403, pas 409).
        createCompte("Admin Pair", UserRole.ADMIN);

        mockMvc.perform(delete("/api/admin/users/{id}", admin.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE du dernier compte ADMIN renvoie 409 (invariant préservé)")
    void deleteDernierAdmin_shouldReturn409() throws Exception {
        // Un seul administrateur dans toute la table : lui-même. Le contrôleur
        // accepte la requête (le jeton porte bien ROLE_ADMIN), mais le service
        // refuse — après l'opération, plus aucun compte ne pourrait rouvrir
        // l'accès d'administration. Le message distingue ce 409 du 403
        // d'auto-refus : il nomme explicitement le dernier administrateur.
        User seulAdmin = createCompte("Seul Admin", UserRole.ADMIN);

        mockMvc.perform(delete("/api/admin/users/{id}", seulAdmin.getId())
                        .header("Authorization", bearer(seulAdmin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("dernier")));

        // Le compte est intact : aucune suppression n'a eu lieu
        assertThat(userRepository.findById(seulAdmin.getId())).isPresent();
    }

    @Test
    @DisplayName("GET /managers ne retourne que les comptes de rôle MANAGER")
    void getManagersUniquementManagers() throws Exception {
        User admin = createCompte("Admin Mgr", UserRole.ADMIN);
        createCompte("Boss", UserRole.MANAGER);
        createCompte("Pas Boss", UserRole.EMPLOYEE);

        mockMvc.perform(get("/api/admin/users/managers")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].role").value("MANAGER"));
    }

    // ---------------------------------------------------------------------
    // 4. Journal d'audit : les 3 mutations de compte sont tracées
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Création, édition et suppression génèrent chacune une entrée d'audit")
    void troisOperationsGenerentDesAudits() throws Exception {
        User admin = createCompte("Admin Audit", UserRole.ADMIN);
        String body = """
                {"nom": "Cible Audit", "email": "cible.audit@expenseflow.ma",
                 "motDePasse": "MotDePasse1", "role": "EMPLOYEE", "managerId": null}
                """;

        // 1. Création — 201 attendu, puis lecture du compte persisté
        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        User cible = userRepository.findByEmail("cible.audit@expenseflow.ma").orElseThrow();

        // 2. Édition : promotion MANAGER
        String maj = """
                {"nom": "Cible Auditee", "email": "cible.audit@expenseflow.ma",
                 "role": "MANAGER", "managerId": null}
                """;
        mockMvc.perform(put("/api/admin/users/{id}", cible.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maj))
                .andExpect(status().isOk());

        // 3. Suppression du compte nu (aucune note) — 204 attendu
        mockMvc.perform(delete("/api/admin/users/{id}", cible.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        // Chaque opération laisse UNE entrée ciblée sur le compte manipulé.
        // Sans cette trace, une création ou un changement de rôle ADMIN
        // serait impossible à attribuer après coup.
        var entrees = auditLogRepository.findAll().stream()
                .filter(e -> e.getEntiteId() != null && e.getEntiteId().equals(cible.getId()))
                .toList();

        assertThat(entrees)
                .extracting(AuditLog::getAction)
                .containsExactlyInAnyOrder("COMPTE_CREE", "COMPTE_MODIFIE", "COMPTE_SUPPRIME");
        assertThat(entrees).allSatisfy(e -> {
            assertThat(e.getEntiteConcernee()).isEqualTo("User");
            assertThat(e.getUtilisateur().getId()).isEqualTo(admin.getId());
        });
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Crée un compte de test jetable, jamais un compte de démonstration.
     *
     * @param nom  nom affiché (sert aussi à dériver l'e-mail)
     * @param role rôle du compte
     * @return compte persisté
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
     * Crée une note en brouillon pour le compte — nécessaire au 409 du
     * DELETE (clé {@code expense_requests.employee_id} en RESTRICT).
     *
     * @param proprietaire auteur de la note
     * @return identifiant de la note
     */
    private Long creerBrouillon(User proprietaire) {
        Category categorie = categoryRepository.save(Category.builder()
                .nom("Cat-Admin-" + System.nanoTime())
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
     * Construit un en-tête Authorization pour l'utilisateur donné.
     *
     * @param utilisateur utilisateur pour lequel signer
     * @return valeur prête pour l'en-tête {@code Authorization}
     */
    private String bearer(User utilisateur) {
        return "Bearer " + jwtService.generateToken(
                customUserDetailsService.loadUserByUsername(utilisateur.getEmail()));
    }
}