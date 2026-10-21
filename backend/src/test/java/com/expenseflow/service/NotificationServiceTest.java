package com.expenseflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.expenseflow.entity.ExpenseRequest;
import com.expenseflow.entity.Notification;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.NotificationType;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.NotificationRepository;
import com.expenseflow.repository.UserRepository;

/**
 * Tests unitaires de {@link NotificationService}.
 *
 * <p>
 * Aucun contexte Spring : les deux dépôts sont simulés. Les tests verrouillent
 * deux invariants : la destination de chaque déclenchement (qui est prévenu,
 * avec quel type), et la tolérance aux pannes (un échec d'écriture ne doit
 * jamais se propager jusqu'à la transition métier).
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService")
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private NotificationService service;

    @Captor
    private ArgumentCaptor<Notification> notificationCaptor;

    private final User employe = User.builder()
            .id(9L).nom("Alice Employee").email("alice@x.ma").role(UserRole.EMPLOYEE)
            .build();

    private final User manager = User.builder()
            .id(7L).nom("Bob Manager").email("bob@x.ma").role(UserRole.MANAGER)
            .build();

    /**
     * Note de test : identifiant fixe pour que les assertions de message et de
     * lien soient déterministes.
     *
     * @return note appartenant à {@link #employe}
     */
    private ExpenseRequest note() {
        return ExpenseRequest.builder()
                .id(42L)
                .employee(employe)
                .totalMontant(new BigDecimal("300.00"))
                .build();
    }

    // ---------------------------------------------------------------------
    // 1. Déclenchement : destination et contenu
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("notifierSoumission() prévient CHAQUE manager, une notification par manager")
    void notifierSoumissionPrevientTousLesManagers() {
        // Given : deux managers, comme dans une organisation à plusieurs validateurs
        User manager2 = User.builder()
                .id(8L).nom("Carla Manager").email("carla@x.ma").role(UserRole.MANAGER)
                .build();
        given(userRepository.findByRole(UserRole.MANAGER)).willReturn(List.of(manager, manager2));

        // When
        service.notifierSoumission(note());

        // Then : une notification par manager, même type, même lien
        verify(notificationRepository, times(2)).save(notificationCaptor.capture());
        List<Notification> enregistrees = notificationCaptor.getAllValues();

        assertThat(enregistrees).allSatisfy(n -> {
            assertThat(n.getType()).isEqualTo(NotificationType.NOTE_SOUMISE);
            assertThat(n.isLue()).isFalse();
            assertThat(n.getLien()).isEqualTo("/notes/42");
        });
        assertThat(enregistrees).extracting(n -> n.getUtilisateur().getId())
                .containsExactly(7L, 8L);
    }

    @Test
    @DisplayName("notifierSoumission() met le nom de l'auteur et le montant dans le message")
    void notifierSoumissionContientAuteurEtMontant() {
        given(userRepository.findByRole(UserRole.MANAGER)).willReturn(List.of(manager));

        service.notifierSoumission(note());

        verify(notificationRepository).save(notificationCaptor.capture());
        String message = notificationCaptor.getValue().getMessage();

        assertThat(message).contains("Alice Employee").contains("300.00").contains("#42");
    }

    @Test
    @DisplayName("notifierApprouvee() prévient l'employé, jamais les managers")
    void notifierApprouveeCibleLEmploye() {
        // When
        service.notifierApprouvee(note());

        // Then : une seule notification, destinée au propriétaire de la note
        verify(notificationRepository).save(notificationCaptor.capture());
        Notification notification = notificationCaptor.getValue();

        assertThat(notification.getUtilisateur()).isSameAs(employe);
        assertThat(notification.getType()).isEqualTo(NotificationType.NOTE_APPROUVEE);
        assertThat(notification.getMessage()).contains("approuvée").contains("#42");
        assertThat(notification.getLien()).isEqualTo("/notes/42");
        verify(userRepository, never()).findByRole(any());
    }

    @Test
    @DisplayName("notifierRejetee() inclut le motif quand il y en a un")
    void notifierRejeteeInclutLeMotif() {
        service.notifierRejetee(note(), "Justificatif manquant");

        verify(notificationRepository).save(notificationCaptor.capture());
        Notification notification = notificationCaptor.getValue();

        assertThat(notification.getType()).isEqualTo(NotificationType.NOTE_REJETEE);
        assertThat(notification.getMessage())
                .contains("rejetée")
                .contains("Motif : Justificatif manquant");
    }

    @Test
    @DisplayName("notifierRejetee() omet la mention de motif si le commentaire est vide")
    void notifierRejeteeSansMotif() {
        service.notifierRejetee(note(), "   ");

        verify(notificationRepository).save(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().getMessage())
                .doesNotContain("Motif")
                .endsWith(".");
    }

    @Test
    @DisplayName("notifierRejetFinance() porte le type de rejet finance, distinct du rejet manager")
    void notifierRejetFinanceUtiliseSonType() {
        service.notifierRejetFinance(note(), "Plafond interne");

        verify(notificationRepository).save(notificationCaptor.capture());
        Notification notification = notificationCaptor.getValue();

        assertThat(notification.getType()).isEqualTo(NotificationType.NOTE_REJETEE_FINANCE);
        assertThat(notification.getMessage()).contains("finance").contains("Plafond interne");
        assertThat(notification.getUtilisateur()).isSameAs(employe);
    }

    @Test
    @DisplayName("notifierRemboursee() prévient l'employé du terme du circuit")
    void notifierRemboursee() {
        service.notifierRemboursee(note());

        verify(notificationRepository).save(notificationCaptor.capture());
        Notification notification = notificationCaptor.getValue();

        assertThat(notification.getType()).isEqualTo(NotificationType.NOTE_REMBOURSEE);
        assertThat(notification.getMessage()).contains("remboursée").contains("#42");
        assertThat(notification.getUtilisateur()).isSameAs(employe);
    }

    // ---------------------------------------------------------------------
    // 2. Tolérance aux pannes : la notification ne doit jamais faire échouer
    //    la transition qui l'a déclenchée
    // ---------------------------------------------------------------------

    /**
     * <p>
     * C'est l'invariant central du service : une panne de la table
     * notifications ne doit pas annuler une approbation déjà prise. Le
     * try/catch de chaque méthode de déclenchement est la seule raison pour
     * laquelle {@code ExpenseRequestService} peut appeler ce service sans
     * risque.
     * </p>
     */
    @Test
    @DisplayName("Un échec d'écriture ne se propage PAS : la transition survit")
    void echecEcritureResteLocal() {
        given(userRepository.findByRole(UserRole.MANAGER)).willReturn(List.of(manager));
        doThrow(new RuntimeException("table indisponible"))
                .when(notificationRepository).save(any(Notification.class));

        // When / Then : aucune exception, malgré le panne simulée
        assertThatCode(() -> service.notifierSoumission(note()))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------
    // 3. Lecture et mise à jour
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("findNonLues() ne retourne que les notifications de l'appelant, non lues")
    void findNonLuesNeFiltrePasDansLeService() {
        // Le tri et le filtre « non lues » sont délégués au dépôt : le service
        // ne fait que convertir. On vérifie qu'il délègue bien avec le BON id.
        given(notificationRepository.findNonLuesByUtilisateurId(9L))
                .willReturn(List.of());

        // When
        var resultat = service.findNonLues(employe);

        // Then : requête ciblée sur le destinataire, jamais sur un id fourni
        // par le client (aucun endpoint n'en accepte).
        assertThat(resultat).isEmpty();
        verify(notificationRepository).findNonLuesByUtilisateurId(9L);
    }

    @Test
    @DisplayName("marquerLue() passe la notification à lue et la sauvegarde")
    void marquerLuePasseALue() {
        Notification notification = Notification.builder()
                .id(5L).utilisateur(employe).type(NotificationType.NOTE_APPROUVEE)
                .message("m").lue(false)
                .build();
        given(notificationRepository.findById(5L)).willReturn(java.util.Optional.of(notification));

        // When
        service.marquerLue(5L, employe);

        // Then
        assertThat(notification.isLue()).isTrue();
        verify(notificationRepository).save(notification);
    }

    @Test
    @DisplayName("marquerLue() renvoie 404 pour la notification d'AUTRUI (pas 403)")
    void marquerLueNotificationDautruiRenvoie404() {
        // Given : notification appartenant à un autre utilisateur (id 99)
        User autre = User.builder().id(99L).nom("Autre").email("a@x.ma")
                .role(UserRole.EMPLOYEE).build();
        Notification dautrui = Notification.builder()
                .id(5L).utilisateur(autre).type(NotificationType.NOTE_APPROUVEE)
                .message("m").lue(false)
                .build();
        given(notificationRepository.findById(5L)).willReturn(java.util.Optional.of(dautrui));

        // When / Then : 404 et non 403 — ne pas révéler l'existence
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.marquerLue(5L, employe))
                .isInstanceOf(com.expenseflow.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("marquerToutesLues() délègue au dépôt avec l'id de l'appelant")
    void marquerToutesLuesDelegueAuDepot() {
        given(notificationRepository.marquerToutesLues(9L)).willReturn(3);

        // When : le retour (nombre) est utile pour le front, qui met à jour sa
        // pastille sans un second appel.
        int combine = service.marquerToutesLues(employe);

        // Then
        org.assertj.core.api.Assertions.assertThat(combine).isEqualTo(3);
        verify(notificationRepository).marquerToutesLues(9L);
    }
}