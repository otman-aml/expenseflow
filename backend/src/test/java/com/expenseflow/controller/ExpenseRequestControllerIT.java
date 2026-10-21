package com.expenseflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.entity.Category;
import com.expenseflow.entity.ExpenseLine;
import com.expenseflow.entity.ExpenseRequest;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.ExpenseStatus;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.CategoryRepository;
import com.expenseflow.repository.ExpenseRequestRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.JwtService;
import com.expenseflow.security.CustomUserDetailsService;

/**
 * Tests d'integration du CRUD des notes de frais et de leur modification.
 *
 * <p>
 * Completes {@link WorkflowControllerIT}, qui ne s'interesse qu'aux
 * transitions. Ici, l'objet est le contenu de la note : son auteur, son total
 * derive, ses lignes, et qui a le droit de la lire ou de la modifier.
 * </p>
 *
 * <p>
 * <strong>La matrice de lecture merite une precision.</strong> Un tiers qui
 * n'est ni le proprietaire, ni manager, ni finance, est refuse sur une note
 * <em>individuelle</em> — mais la liste {@code GET /api/expense-requests} ne
 * renvoie que les notes de l'appelant, sans jamais exposer celles des autres.
 * L'asymetrie est voulue : une liste ne peut pas filtrer ce qu'elle ne
 * contient pas, et l'yoyo d'une note par identifiant meriterait un controle
 * d'acces a part entiere.
 * </p>
 */
@Transactional
@AutoConfigureMockMvc
@DisplayName("Notes de frais (integration)")
class ExpenseRequestControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ExpenseRequestRepository expenseRequestRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    // ---------------------------------------------------------------------
    // A) POST /api/expense-requests - creation
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Creation avec deux lignes : 201, total calcule, statut DRAFT")
    void create_deuxLignes_shouldReturn201() throws Exception {
        // Given
        User employe = createUser("Employe Createur", UserRole.EMPLOYEE);
        Long catA = createCategory().getId();
        Long catB = createCategory().getId();

        // When
        mockMvc.perform(post("/api/expense-requests")
                        .header("Authorization", bearer(employe))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps(catA + ":100.00:Taxi",
                                catB + ":50.50:Dejeuner")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statut").value("DRAFT"))
                // Le total est une donnee derivee : le client ne l'envoie pas
                .andExpect(jsonPath("$.totalMontant").value(150.50))
                .andExpect(jsonPath("$.lignes.length()").value(2))
                .andExpect(jsonPath("$.employeeId").value(employe.getId()));

        // Then : l'auteur est bien deduit du jeton, jamais du corps
        assertThat(expenseRequestRepository.findByEmployeeIdWithLignes(employe.getId()))
                .singleElement()
                .satisfies(note -> {
                    assertThat(note.getTotalMontant()).isEqualByComparingTo("150.50");
                    assertThat(note.getLignes()).hasSize(2);
                });
    }

    @Test
    @DisplayName("Creation sans ligne renvoie 400")
    void create_sansLigne_shouldReturn400() throws Exception {
        // Given
        User employe = createUser("Employe Vide", UserRole.EMPLOYEE);

        // When / Then
        mockMvc.perform(post("/api/expense-requests")
                        .header("Authorization", bearer(employe))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps()))
                .andExpect(status().isBadRequest());

        assertThat(expenseRequestRepository.findByEmployeeIdWithLignes(employe.getId())).isEmpty();
    }

    @Test
    @DisplayName("Creation avec un montant negatif renvoie 400")
    void create_montantNegatif_shouldReturn400() throws Exception {
        // Given
        User employe = createUser("Employe Negatif", UserRole.EMPLOYEE);
        Long cat = createCategory().getId();

        // When / Then
        mockMvc.perform(post("/api/expense-requests")
                        .header("Authorization", bearer(employe))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps(cat + ":-50:Invalide")))
                .andExpect(status().isBadRequest());

        assertThat(expenseRequestRepository.findByEmployeeIdWithLignes(employe.getId())).isEmpty();
    }

    @Test
    @DisplayName("Creation avec une categorie inexistante renvoie 404")
    void create_categorieInconnue_shouldReturn404() throws Exception {
        // Given : l'identifiant 999999 ne correspond a aucune categorie
        User employe = createUser("Employe Mauvaise Categorie", UserRole.EMPLOYEE);

        // When / Then
        mockMvc.perform(post("/api/expense-requests")
                        .header("Authorization", bearer(employe))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps("999999:80:Fantome")))
                .andExpect(status().isNotFound());

        // Une categorie invalide ne doit pas laisser de note a moitie
        assertThat(expenseRequestRepository.findByEmployeeIdWithLignes(employe.getId())).isEmpty();
    }

    @Test
    @DisplayName("Creation sans jeton renvoie 401")
    void create_sansJeton_shouldReturn401() throws Exception {
        // Given / When / Then
        mockMvc.perform(post("/api/expense-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps("1:80:Test")))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------
    // B) GET /api/expense-requests - liste
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("La liste ne contient que les notes de l'appelant")
    void liste_neContientQueSesNotes() throws Exception {
        // Given : deux employes, chacun avec sa note
        User alice = createUser("Employe Alice", UserRole.EMPLOYEE);
        User bob = createUser("Employe Bob", UserRole.EMPLOYEE);
        creerBrouillon(alice, "100.00");
        creerBrouillon(bob, "200.00");

        // When / Then : Bob ne voit pas la note d'Alice
        mockMvc.perform(get("/api/expense-requests")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].employeeId").value(alice.getId()));
    }

    @Test
    @DisplayName("Un employe sans note recoit une liste vide, pas une erreur")
    void liste_sansNote_shouldReturnEmptyArray() throws Exception {
        // Given : l'employe n'a rien cree. L'appel porte sur la collection
        // vide plutot que sur une note, pour ne pas confondre avec un 403.
        User employe = createUser("Employe Sans Note", UserRole.EMPLOYEE);

        // When / Then
        mockMvc.perform(get("/api/expense-requests")
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------------------------------------------------------------------
    // C) GET /api/expense-requests/{id} - matrice de lecture
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Le proprietaire lit sa note")
    void findById_parProprietaire_shouldReturn200() throws Exception {
        // Given
        User employe = createUser("Employe Lecteur", UserRole.EMPLOYEE);
        Long noteId = creerBrouillon(employe, "120.00");

        // When / Then
        mockMvc.perform(get("/api/expense-requests/{id}", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(noteId))
                .andExpect(jsonPath("$.totalMontant").value(120));
    }

    @Test
    @DisplayName("Le MANAGER lit la note de son equipe")
    void findById_parManager_shouldReturn200() throws Exception {
        // Given : le manager est bien le superieur de l'employe
        User employe = createUser("Employe Valide", UserRole.EMPLOYEE);
        User manager = createUser("Manager Lecteur", UserRole.MANAGER);
        employe.setManager(manager);
        userRepository.save(employe);
        Long noteId = creerBrouillon(employe, "90.00");

        // When / Then : le validateur a besoin de lire la note qu'il arbitre
        mockMvc.perform(get("/api/expense-requests/{id}", noteId)
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(noteId));
    }

    @Test
    @DisplayName("La FINANCE lit la note qu'elle doit rembourser")
    void findById_parFinance_shouldReturn200() throws Exception {
        // Given
        User employe = createUser("Employe Rembourse", UserRole.EMPLOYEE);
        User finance = createUser("Finance Lectrice", UserRole.FINANCE_ADMIN);
        Long noteId = creerBrouillon(employe, "140.00");

        // When / Then
        mockMvc.perform(get("/api/expense-requests/{id}", noteId)
                        .header("Authorization", bearer(finance)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(noteId));
    }

    @Test
    @DisplayName("Un autre EMPLOYEE ne peut pas lire la note : 403")
    void findById_parTiers_shouldReturn403() throws Exception {
        // Given
        User proprietaire = createUser("Employe Victime", UserRole.EMPLOYEE);
        User tiers = createUser("Employe Curieux", UserRole.EMPLOYEE);
        Long noteId = creerBrouillon(proprietaire, "60.00");

        // When / Then : ni proprietaire, ni validateur
        mockMvc.perform(get("/api/expense-requests/{id}", noteId)
                        .header("Authorization", bearer(tiers)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Une note inexistante renvoie 404")
    void findById_noteInconnue_shouldReturn404() throws Exception {
        // Given
        User employe = createUser("Employe Chercheur", UserRole.EMPLOYEE);

        // When / Then
        mockMvc.perform(get("/api/expense-requests/{id}", 999999L)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------
    // D) PUT /api/expense-requests/{id} - modification du brouillon
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Modification par le proprietaire : lignes remplacees, total recalcule")
    void update_parProprietaire_shouldRecalculateTotal() throws Exception {
        // Given : une note d'une seule ligne de 100.00
        User employe = createUser("Employe Modificateur", UserRole.EMPLOYEE);
        Category categorie = createCategory();
        Long noteId = creerBrouillon(employe, "100.00", categorie);

        // When : deux lignes, dont une dans une categorie differente
        Long autre = createCategory().getId();
        mockMvc.perform(put("/api/expense-requests/{id}", noteId)
                        .header("Authorization", bearer(employe))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps(categorie.getId() + ":200.00:Taxi",
                                autre + ":50.00:Hotel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lignes.length()").value(2))
                .andExpect(jsonPath("$.totalMontant").value(250));

        // Then : l'ancienne ligne a disparu, sans trace en base
        assertThat(expenseRequestRepository.findByIdWithLignes(noteId).orElseThrow().getLignes())
                .hasSize(2)
                .extracting(ligne -> ligne.getMontant())
                .containsExactlyInAnyOrder(new BigDecimal("200.00"), new BigDecimal("50.00"));
    }

    @Test
    @DisplayName("Modification d'une note non DRAFT renvoie 409")
    void update_horsBrouillon_shouldReturn409() throws Exception {
        // Given : la note est soumise, le proprietaire est bien l'appelant
        User employe = createUser("Employe Trop Tard", UserRole.EMPLOYEE);
        Long noteId = creerBrouillon(employe, "100.00");
        placerEn(noteId, ExpenseStatus.PENDING_MANAGER);

        // When
        mockMvc.perform(put("/api/expense-requests/{id}", noteId)
                        .header("Authorization", bearer(employe))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps("1:500.00:Trop")))
                .andExpect(status().isConflict());

        // Then : le contenu d'origine est intact
        assertThat(expenseRequestRepository.findByIdWithLignes(noteId).orElseThrow()
                .getTotalMontant()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Modification par le MANAGER renvoie 403 : valider n'est pas editer")
    void update_parManager_shouldReturn403() throws Exception {
        // Given
        User employe = createUser("Employe Gere", UserRole.EMPLOYEE);
        User manager = createUser("Manager Editeur", UserRole.MANAGER);
        Long noteId = creerBrouillon(employe, "100.00");

        // When
        mockMvc.perform(put("/api/expense-requests/{id}", noteId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps("1:500.00:Manager")))
                .andExpect(status().isForbidden());

        // Then
        assertThat(expenseRequestRepository.findByIdWithLignes(noteId).orElseThrow()
                .getTotalMontant()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Modification d'une note inexistante renvoie 404")
    void update_noteInconnue_shouldReturn404() throws Exception {
        // Given
        User employe = createUser("Employe Fantome", UserRole.EMPLOYEE);

        // When / Then
        mockMvc.perform(put("/api/expense-requests/{id}", 999999L)
                        .header("Authorization", bearer(employe))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps("1:80:Fantome")))
                .andExpect(status().isNotFound());
    }
    // ---------------------------------------------------------------------
    // E) GET /api/expense-requests?statut=... - file de validation
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Le MANAGER liste ses notes en attente de validation")
    void liste_parStatut_parManager() throws Exception {
        // Given : rattachement obligatoire depuis le filtrage par equipe
        User employe = createUser("Employe File", UserRole.EMPLOYEE);
        User manager = createUser("Manager File", UserRole.MANAGER);
        employe.setManager(manager);
        userRepository.save(employe);
        Long noteId = creerBrouillon(employe, "100.00");
        placerEn(noteId, ExpenseStatus.PENDING_MANAGER);

        // When / Then
        mockMvc.perform(get("/api/expense-requests")
                        .param("statut", "PENDING_MANAGER")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(noteId));
    }

    @Test
    @DisplayName("La FINANCE liste les notes qui l'attendent")
    void liste_parStatut_parFinance() throws Exception {
        // Given
        User employe = createUser("Employe Financee", UserRole.EMPLOYEE);
        User finance = createUser("Finance File", UserRole.FINANCE_ADMIN);
        Long noteId = creerBrouillon(employe, "150.00");
        placerEn(noteId, ExpenseStatus.PENDING_FINANCE);

        // When / Then : le role et le statut concordent
        mockMvc.perform(get("/api/expense-requests")
                        .param("statut", "PENDING_FINANCE")
                        .header("Authorization", bearer(finance)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(noteId));
    }

    @Test
    @DisplayName("La FINANCE peut aussi lister PENDING_MANAGER : elle y approuve")
    void liste_parStatut_parFinanceSurPendingManager() throws Exception {
        // Given
        User employe = createUser("Employe Exception", UserRole.EMPLOYEE);
        User finance = createUser("Finance Exception", UserRole.FINANCE_ADMIN);
        Long noteId = creerBrouillon(employe, "120.00");
        placerEn(noteId, ExpenseStatus.PENDING_MANAGER);

        // When / Then : meme regle que sur POST /approve, qui admet la finance
        mockMvc.perform(get("/api/expense-requests")
                        .param("statut", "PENDING_MANAGER")
                        .header("Authorization", bearer(finance)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("Un EMPLOYEE ne peut pas lister par statut : 403")
    void liste_parStatut_parEmploye() throws Exception {
        // Given
        User employe = createUser("Employe Curieux", UserRole.EMPLOYEE);
        creerBrouillon(employe, "100.00");

        // When / Then : meme EMPLOYEE qui peut lister ses propres notes ne peut
        // pas interroger une file de validation
        mockMvc.perform(get("/api/expense-requests")
                        .param("statut", "PENDING_MANAGER")
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Un MANAGER ne peut pas lister PENDING_FINANCE : 403")
    void liste_parStatut_managerSurPendingFinance() throws Exception {
        // Given
        User manager = createUser("Manager Curieux", UserRole.MANAGER);

        // When / Then : le refus porte sur le couple (role, statut)
        mockMvc.perform(get("/api/expense-requests")
                        .param("statut", "PENDING_FINANCE")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Un statut inconnu renvoie 400, pas 500")
    void liste_parStatut_inconnu() throws Exception {
        // Given
        User manager = createUser("Manager Faussaire", UserRole.MANAGER);

        // When : une faute de frappe dans l'enumeration
        mockMvc.perform(get("/api/expense-requests")
                        .param("statut", "PENDING_FINANC")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("Sans le parametre, l'endpoint garde son comportement d'origine")
    void liste_sansStatut_listeSesNotes() throws Exception {
        // Given : l'employe a une note, le manager en a une autre
        User employe = createUser("Employe Origine", UserRole.EMPLOYEE);
        User manager = createUser("Manager Origine", UserRole.MANAGER);
        creerBrouillon(employe, "100.00");
        creerBrouillon(manager, "200.00");

        // When / Then : le cas nominal de la section 1.8 ne doit pas avoir
        // regresse sous l'effet du nouveau parametre
        mockMvc.perform(get("/api/expense-requests")
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].employeeId").value(employe.getId()));
    }

    @Test
    @DisplayName("La file de validation ne contient que les notes du statut demande")
    void liste_parStatut_filtreCorrectement() throws Exception {
        // Given : trois notes, une seule dans le statut cherche. L'employe
        // est rattache au manager : depuis le filtrage par equipe, un
        // manager ne voit que SES employés, et sans ce rattachement la file
        // serait legitiment vide.
        User employe = createUser("Employe Filtre", UserRole.EMPLOYEE);
        User manager = createUser("Manager Filtre", UserRole.MANAGER);
        employe.setManager(manager);
        userRepository.save(employe);
        Long attendue = creerBrouillon(employe, "100.00");
        creerBrouillon(employe, "200.00");
        creerBrouillon(employe, "300.00");
        placerEn(attendue, ExpenseStatus.PENDING_MANAGER);

        // When / Then
        mockMvc.perform(get("/api/expense-requests")
                        .param("statut", "PENDING_MANAGER")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(attendue));
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Enregistre un utilisateur de test, avec un mot de passe hache.
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
     * Enregistre une categorie de test.
     *
     * <p>
     * Le nom est suffixe par {@link System#nanoTime()} : la colonne
     * {@code categories.nom} porte une contrainte d'unicite.
     * </p>
     *
     * @return la categorie enregistree
     */
    private Category createCategory() {
        return categoryRepository.save(Category.builder()
                .nom("Categorie " + System.nanoTime())
                .build());
    }

    /**
     * Cree une note en brouillon avec une seule ligne.
     *
     * @param employe proprietaire
     * @param montant montant de la ligne
     * @return l'identifiant de la note
     */
    private Long creerBrouillon(User employe, String montant) {
        return creerBrouillon(employe, montant, createCategory());
    }

    /**
     * Cree une note en brouillon dans une categorie donnee.
     *
     * @param employe   proprietaire
     * @param montant   montant de la ligne
     * @param categorie categorie de la ligne
     * @return l'identifiant de la note
     */
    private Long creerBrouillon(User employe, String montant, Category categorie) {
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
     * Change le statut d'une note sans passer par le service.
     *
     * <p>
     * Reserve aux tests dont l'objet est la lecture ou la modification, et
     * non la transition : contourner le workflow evite d'y consacrer trois
     * appels qui testeraient autre chose.
     * </p>
     *
     * @param noteId note a deplacer
     * @param statut statut cible
     */
    private void placerEn(Long noteId, ExpenseStatus statut) {
        ExpenseRequest note = expenseRequestRepository.findById(noteId).orElseThrow();
        note.setStatut(statut);
        expenseRequestRepository.save(note);
    }

    /**
     * Construit un corps de creation ou de modification.
     *
     * @param lignes lignes au format {@code categorieId:montant:description}
     * @return JSON serialise
     */
    private String corps(String... lignes) {
        StringBuilder json = new StringBuilder("{\"lignes\":[");
        for (int i = 0; i < lignes.length; i++) {
            String[] champs = lignes[i].split(":");
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"categorieId\":").append(champs[0])
                    .append(",\"montant\":").append(champs[1])
                    .append(",\"dateDepense\":\"2026-09-20\"")
                    .append(",\"description\":\"").append(champs[2]).append("\"}");
        }
        return json.append("]}").toString();
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