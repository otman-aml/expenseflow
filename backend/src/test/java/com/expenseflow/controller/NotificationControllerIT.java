package com.expenseflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import com.expenseflow.entity.Notification;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.ExpenseStatus;
import com.expenseflow.entity.enums.NotificationType;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.CategoryRepository;
import com.expenseflow.repository.ExpenseRequestRepository;
import com.expenseflow.repository.NotificationRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.CustomUserDetailsService;
import com.expenseflow.security.JwtService;

/**
 * Tests d'intégration des notifications {@code /api/notifications/**}.
 *
 * <p>
 * Trois couches sont couvertes dans la même classe, parce qu'elles ne se
 * comprennent qu'ensemble : la table (migration V4 appliquée par Flyway sur le
 * conteneur PostgreSQL), les transitions qui déclenchent l'écriture, et les
 * routes qui la lisent. Un test de « badge qui apparaît après une action »
 * sans le repository manquerait la moitié du circuit.
 * </p>
 *
 * <p>
 * Comme les autres IT, chaque test crée ses propres comptes et roule dans une
 * transaction annulée : rien ne subsiste après le test.
 * </p>
 */
@Transactional
@AutoConfigureMockMvc
@DisplayName("Notifications (integration)")
class NotificationControllerIT extends AbstractIntegrationTest {

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
    private NotificationRepository notificationRepository;

    @Autowired
    private ExpenseRequestRepository expenseRequestRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    // ---------------------------------------------------------------------
    // 1. Déclenchement : une transition produit bien une notification
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Soumettre une note prévient le manager : la notification apparaît en base")
    void submit_declencheUneNotificationManager() throws Exception {
        // Given : un employé propriétaire, un manager cible, une note en DRAFT
        User employe = createUser("Alice Soumet", UserRole.EMPLOYEE);
        createUser("Bob Manager", UserRole.MANAGER);
        Long noteId = creerBrouillon(employe);

        // When : l'employé soumet sa propre note
        mockMvc.perform(post("/api/expense-requests/{id}/submit", noteId)
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk());

        // Then : le manager a reçu UNE notification, non lue, avec le lien
        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        Notification notification = notifications.get(0);
        assertThat(notification.getType()).isEqualTo(NotificationType.NOTE_SOUMISE);
        assertThat(notification.isLue()).isFalse();
        assertThat(notification.getLien()).isEqualTo("/notes/" + noteId);
        assertThat(notification.getMessage()).contains(employe.getNom());
    }

    @Test
    @DisplayName("GET sans jeton renvoie 401")
    void getSansJetonRenvoie401() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET ne retourne que les notifications non lues de l'appelant")
    void getNeRetourneQueLesSiennesNonLues() throws Exception {
        // Given : deux notifications pour l'appelant (dont une déjà lue),
        // et une pour un autre — qui ne doit JAMAIS apparaître.
        User appelant = createUser("Alice Lecture", UserRole.EMPLOYEE);
        User autre = createUser("Autre Employe", UserRole.EMPLOYEE);
        notifier(appelant, NotificationType.NOTE_APPROUVEE, "Votre note #1 approuvee.", false);
        notifier(appelant, NotificationType.NOTE_REMBOURSEE, "Votre note #2 remboursee.", true);
        notifier(autre, NotificationType.NOTE_REJETEE, "Message prive.", false);

        // When / Then : une seule entrée — la lue et celle d'autrui sont absentes
        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(appelant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].message").value("Votre note #1 approuvee."))
                .andExpect(jsonPath("$[0].lue").value(false));
    }

    @Test
    @DisplayName("PUT /{id}/lue marque la notification et la retire de la liste")
    void marquerLueLaRetireDeLaListe() throws Exception {
        User appelant = createUser("Alice Lue", UserRole.EMPLOYEE);
        Notification notif = notifier(appelant, NotificationType.NOTE_SOUMISE, "Note #7.", false);

        // When : marquage
        mockMvc.perform(put("/api/notifications/{id}/lue", notif.getId())
                        .header("Authorization", bearer(appelant)))
                .andExpect(status().isNoContent());

        // Then : la liste non lue est vide, la notification existe toujours
        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(appelant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(notificationRepository.findById(notif.getId()).orElseThrow().isLue()).isTrue();
    }

    @Test
    @DisplayName("PUT /{id}/lue sur la notification d'AUTRUI renvoie 404 (pas 403)")
    void marquerLueDautruiRenvoie404() throws Exception {
        User appelant = createUser("Alice Curieuse", UserRole.EMPLOYEE);
        User victime = createUser("Bob Cible", UserRole.EMPLOYEE);
        Notification dautrui = notifier(victime, NotificationType.NOTE_REJETEE, "Secret.", false);

        // When / Then : 404 — même réponse que pour un id inventé
        mockMvc.perform(put("/api/notifications/{id}/lue", dautrui.getId())
                        .header("Authorization", bearer(appelant)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /toutes-lues vide la liste non lue de l'appelant seul")
    void toutesLuesNeVideQueLesSiennes() throws Exception {
        User appelant = createUser("Alice Tout", UserRole.EMPLOYEE);
        User autre = createUser("Bob Resiste", UserRole.EMPLOYEE);
        notifier(appelant, NotificationType.NOTE_APPROUVEE, "A.", false);
        notifier(appelant, NotificationType.NOTE_REMBOURSEE, "B.", false);
        Notification dautrui = notifier(autre, NotificationType.NOTE_SOUMISE, "C.", false);

        // When
        mockMvc.perform(put("/api/notifications/toutes-lues")
                        .header("Authorization", bearer(appelant)))
                .andExpect(status().isNoContent());

        // Then : l'appelant n'a plus rien, l'autre n'a pas bougé
        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(appelant)))
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(notificationRepository.findById(dautrui.getId()).orElseThrow().isLue())
                .isFalse();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Crée une catégorie de test, réutilisée par les brouillons.
     *
     * @return catégorie persistée
     */
    private Category creerCategorie() {
        return categoryRepository.save(Category.builder()
                .nom("Cat-Notif-" + System.nanoTime())
                .build());
    }

    /**
     * Crée une note en brouillon appartenant à l'employé, montant 100 —
     * sous tous les plafonds de démonstration, donc soumissible.
     *
     * @param proprietaire auteur de la note
     * @return identifiant de la note
     */
    private Long creerBrouillon(User proprietaire) {
        Category categorie = creerCategorie();
        ExpenseRequest note = expenseRequestRepository.save(ExpenseRequest.builder()
                .employee(proprietaire)
                .statut(ExpenseStatus.DRAFT)
                .totalMontant(new BigDecimal("100.00"))
                .build());
        note.getLignes().add(ExpenseLine.builder()
                .request(note)
                .categorie(categorie)
                .montant(new BigDecimal("100.00"))
                .dateDepense(java.time.LocalDate.now())
                .build());
        return expenseRequestRepository.save(note).getId();
    }

    /**
     * Crée une note directement en attente de manager (transition système,
     * sans appel HTTP : le test cible une transition suivante).
     *
     * @param proprietaire auteur de la note
     * @return identifiant de la note
     */
    private Long creerSoumise(User proprietaire) {
        Long id = creerBrouillon(proprietaire);
        ExpenseRequest note = expenseRequestRepository.findById(id).orElseThrow();
        note.setStatut(ExpenseStatus.PENDING_MANAGER);
        return expenseRequestRepository.save(note).getId();
    }

    /**
     * Persiste une notification pour un destinataire, sans passer par le
     * service — les tests de lecture n'ont pas besoin d'une transition.
     *
     * @param destinataire destinataire
     * @param type         nature de l'événement
     * @param message      message
     * @param lue          état de lecture initial
     * @return notification persistée
     */
    private Notification notifier(User destinataire, NotificationType type,
            String message, boolean lue) {
        return notificationRepository.save(Notification.builder()
                .utilisateur(destinataire)
                .type(type)
                .message(message)
                .lien("/notes/1")
                .lue(lue)
                .build());
    }

    /**
     * Crée un compte de test jetable, jamais un compte de démonstration.
     *
     * @param nom  nom affiché
     * @param role rôle du compte
     * @return compte persisté
     */
    private User createUser(String nom, UserRole role) {
        String email = "notif-" + System.nanoTime() + "@expenseflow.ma";
        return userRepository.save(User.builder()
                .nom(nom)
                .email(email)
                .motDePasse(passwordEncoder.encode("Password123!"))
                .role(role)
                .build());
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