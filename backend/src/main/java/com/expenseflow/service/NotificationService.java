package com.expenseflow.service;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.dto.NotificationResponse;
import com.expenseflow.entity.ExpenseRequest;
import com.expenseflow.entity.Notification;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.NotificationType;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.exception.ResourceNotFoundException;
import com.expenseflow.repository.NotificationRepository;
import com.expenseflow.repository.UserRepository;

/**
 * Notifications déclenchées par les transitions d'une note de frais.
 *
 * <p>
 * <strong>Un échec de notification n'annule jamais la transition.</strong>
 * Chaque appel de déclenchement est entouré d'un {@code try / catch} qui
 * journalise et poursuit : la notification est un confort, la décision
 * métier est l'essentiel. Un employé dont la note a été approuvée doit
 * voir « approuvée », même si l'écriture de son message a échoué ; faire
 * remonter l'exception ruinerait toute la transaction, transition comprise.
 * </p>
 *
 * <p>
 * <strong>« Les managers » se définissent par le rôle.</strong> Le modèle ne
 * comporte ni équipe ni manager attitré : tout manager voit toutes les notes
 * soumises, donc toute soumission notifie tous les managers.
 * </p>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;

    private final UserRepository userRepository;

    /**
     * @param notificationRepository dépôt des notifications
     * @param userRepository         dépôt des utilisateurs (cible « les managers »)
     */
    public NotificationService(NotificationRepository notificationRepository,
            UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    /**
     * Notifie les managers qu'une note attend leur validation.
     *
     * @param note note soumise
     */
    public void notifierSoumission(ExpenseRequest note) {
        try {
            String message = String.format(
                    "Note #%d de %s (%s dh) soumise, en attente de votre validation.",
                    note.getId(), note.getEmployee().getNom(), formatMontant(note.getTotalMontant()));

            // FIXME: incohérence connue — la soumission notifie TOUS les
            //        managers (par rôle), alors que la file de validation est
            //        filtrée par équipe via users.manager_id. Avec deux
            //        équipes, un manager reçoit des notifications pour des
            //        notes qu'il ne verra jamais. Le filtrage par
            //        userRepository.findByManagerId(note.getEmployee().getId())
            //        existe déjà côté lecture : à réutiliser ici.
            for (User manager : userRepository.findByRole(UserRole.MANAGER)) {
                enregistrer(manager, NotificationType.NOTE_SOUMISE, message, lien(note));
            }
        } catch (RuntimeException e) {
            log.warn("Notification de soumission impossible pour la note {} : {}",
                    note.getId(), e.getMessage());
        }
    }

    /**
     * Notifie l'employé que sa note a été approuvée au niveau manager.
     *
     * @param note note approuvée
     */
    public void notifierApprouvee(ExpenseRequest note) {
        try {
            enregistrer(note.getEmployee(), NotificationType.NOTE_APPROUVEE,
                    String.format("Votre note #%d a été approuvée et transmise à la finance.",
                            note.getId()),
                    lien(note));
        } catch (RuntimeException e) {
            log.warn("Notification d'approbation impossible pour la note {} : {}",
                    note.getId(), e.getMessage());
        }
    }

    /**
     * Notifie l'employé que sa note a été rejetée par un manager.
     *
     * @param note       note rejetée
     * @param commentaire motif du rejet, peut être null
     */
    public void notifierRejetee(ExpenseRequest note, String commentaire) {
        try {
            enregistrer(note.getEmployee(), NotificationType.NOTE_REJETEE,
                    messageRejet("rejetée par un validateur", note, commentaire), lien(note));
        } catch (RuntimeException e) {
            log.warn("Notification de rejet impossible pour la note {} : {}",
                    note.getId(), e.getMessage());
        }
    }

    /**
     * Notifie l'employé que sa note a été rejetée par la finance.
     *
     * @param note       note rejetée
     * @param commentaire motif du rejet, peut être null
     */
    public void notifierRejetFinance(ExpenseRequest note, String commentaire) {
        try {
            enregistrer(note.getEmployee(), NotificationType.NOTE_REJETEE_FINANCE,
                    messageRejet("rejetée par la finance", note, commentaire), lien(note));
        } catch (RuntimeException e) {
            log.warn("Notification de rejet finance impossible pour la note {} : {}",
                    note.getId(), e.getMessage());
        }
    }

    /**
     * Notifie l'employé que sa note a été remboursée : fin du circuit.
     *
     * @param note note remboursée
     */
    public void notifierRemboursee(ExpenseRequest note) {
        try {
            enregistrer(note.getEmployee(), NotificationType.NOTE_REMBOURSEE,
                    String.format("Votre note #%d a été remboursée.", note.getId()),
                    lien(note));
        } catch (RuntimeException e) {
            log.warn("Notification de remboursement impossible pour la note {} : {}",
                    note.getId(), e.getMessage());
        }
    }

    /**
     * Notifications non lues de l'utilisateur authentifié.
     *
     * @param currentUser utilisateur authentifié
     * @return ses notifications non lues, des plus récentes aux plus anciennes
     */
    @Transactional(readOnly = true)
    public List<NotificationResponse> findNonLues(User currentUser) {
        return notificationRepository.findNonLuesByUtilisateurId(currentUser.getId()).stream()
                .map(NotificationResponse::from)
                .toList();
    }

    /**
     * Marque une notification comme lue.
     *
     * <p>
     * <strong>404 si la notification n'appartient pas à l'appelant.</strong>
     * Un 403 révélerait l'existence d'une notification d'autrui ; le 404 est
     * la même réponse que pour un identifiant inventé, ce qui ne renseigne sur
     * rien.
     * </p>
     *
     * @param id          identifiant de la notification
     * @param currentUser utilisateur authentifié
     * @throws ResourceNotFoundException si la notification n'existe pas ou n'appartient pas à l'appelant
     */
    @Transactional
    public void marquerLue(Long id, User currentUser) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification " + id + " introuvable"));

        if (!notification.getUtilisateur().getId().equals(currentUser.getId())) {
            throw new ResourceNotFoundException("Notification " + id + " introuvable");
        }

        notification.setLue(true);
        notificationRepository.save(notification);
    }

    /**
     * Marque toutes les notifications de l'utilisateur comme lues.
     *
     * @param currentUser utilisateur authentifié
     * @return nombre de notifications passées à « lue »
     */
    @Transactional
    public int marquerToutesLues(User currentUser) {
        return notificationRepository.marquerToutesLues(currentUser.getId());
    }

    /**
     * Persiste une notification destinée à un utilisateur.
     *
     * @param destinataire destinataire du message
     * @param type         nature de l'événement
     * @param message      message lisible, figé à la création
     * @param lien         lien interne de l'application, ou null
     */
    private void enregistrer(User destinataire, NotificationType type, String message, String lien) {
        notificationRepository.save(Notification.builder()
                .utilisateur(destinataire)
                .type(type)
                .message(message)
                .lien(lien)
                .lue(false)
                .build());
    }

    /**
     * Construit le message d'un rejet, avec motif s'il y en a un.
     *
     * @param libelle     libellé du rejet (« rejetée par un validateur »…)
     * @param note        note concernée
     * @param commentaire motif, peut être null
     * @return message complet, motif inclus le cas échéant
     */
    private String messageRejet(String libelle, ExpenseRequest note, String commentaire) {
        String base = String.format("Votre note #%d a été %s.", note.getId(), libelle);
        if (commentaire == null || commentaire.isBlank()) {
            return base;
        }
        return base + " Motif : " + commentaire.trim();
    }

    /**
     * Lien interne vers la fiche d'une note.
     *
     * @param note note concernée
     * @return chemin relatif de la page de détail
     */
    private String lien(ExpenseRequest note) {
        return "/notes/" + note.getId();
    }

    /**
     * Formate un montant pour le message, sans notation scientifique.
     *
     * @param montant montant à formater
     * @return représentation décimale, ou "?" si absent
     */
    private String formatMontant(BigDecimal montant) {
        return montant == null ? "?" : montant.toPlainString();
    }
}
