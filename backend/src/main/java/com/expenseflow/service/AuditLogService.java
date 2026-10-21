package com.expenseflow.service;

import org.springframework.stereotype.Service;

import com.expenseflow.entity.AuditLog;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.ExpenseStatus;
import com.expenseflow.repository.AuditLogRepository;

/**
 * Écriture dans le journal d'audit.
 *
 * <p>
 * <strong>Ce journal n'est pas l'historique des décisions</strong>, rôle tenu
 * par {@code approvals} qui n'enregistre que les validations humaines. Ici
 * chaque <em>changement d'état</em> est journalisé, y compris les transitions
 * système qu'aucun utilisateur ne déclenche : le passage automatique de
 * {@code SUBMITTED} à {@code PENDING_MANAGER} fait partie de la trace, car il
 * explique pourquoi une note a changé d'état sans qu'aucune décision ne soit
 * intervenue.
 * </p>
 *
 * <p>
 * <strong>Aucune méthode de lecture ici.</strong> La lecture d'un historique
 * passe par le service métier concerné ({@code ExpenseRequestService}), qui
 * doit au préalable vérifier les droits de l'appelant. L'exposer ici
 * court-circuiterait ce contrôle.
 * </p>
 *
 * <p>
 * <strong>Même transaction que l'action journalisée.</strong> La méthode est
 * appelée depuis un service déjà annoté {@code @Transactional} : si l'action
 * échoue, son journal est annulé avec elle. Une trace décrivant un changement
 * qui n'a pas eu lieu serait pire qu'une absence de trace.
 * </p>
 */
@Service
public class AuditLogService {

    /**
     * Nom logique de l'entité {@code ExpenseRequest}, seule entité journalisée
     * pour l'instant. Centralisé ici pour qu'une faute de frappe dans un
     * appel ne disperse pas deslibellés variants dans la même colonne.
     */
    public static final String ENTITE_EXPENSE_REQUEST = "ExpenseRequest";

    /**
     * Action correspondant à un changement de statut. Les transitions
     * système utilisent la même action : ce qui compte est le <em>changement
     * d'état</em>, pas son caractère automatique ou humain — ce dernier est
     * lisible dans le champ {@code details}.
     */
    public static final String ACTION_CHANGEMENT_STATUT = "STATUS_CHANGE";

    /**
     * Nom logique de l'entité {@code User} : les opérations d'administration
     * des comptes sont journalisées avec la même genericité que les notes,
     * ce qui permet de croiser un compte et son historique sans jointure
     * spécialisée.
     */
    public static final String ENTITE_USER = "User";

    /**
     * Actions d'administration des comptes. Chaque mutation est tracée avec
     * l'admin appelant comme auteur — sans cette trace, une création ou une
     * suppression de compte serait invisible après coup.
     */
    public static final String ACTION_COMPTE_CREE = "COMPTE_CREE";

    public static final String ACTION_COMPTE_MODIFIE = "COMPTE_MODIFIE";

    public static final String ACTION_COMPTE_SUPPRIME = "COMPTE_SUPPRIME";

    private final AuditLogRepository auditLogRepository;

    /**
     * @param auditLogRepository dépôt du journal d'audit
     */
    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Journalise une action sur une entité.
     *
     * @param user              auteur de l'action
     * @param entite            nom logique de l'entité concernée
     * @param entiteId          identifiant de l'entité, null si non applicable
     * @param action            action réalisée
     * @param details           précision libre, lisible par un humain
     */
    public void log(User user, String entite, Long entiteId, String action, String details) {
        // La colonne date_action n'est pas renseignée : elle porte
        // insertable = false / updatable = false et reçoit DEFAULT NOW().
        // L'horodatage est donc celui de PostgreSQL, ce qui évite un décalage
        // entre l'ordre des lignes dans la table et l'horloge de la JVM.
        auditLogRepository.save(AuditLog.builder()
                .entiteConcernee(entite)
                .entiteId(entiteId)
                .utilisateur(user)
                .action(action)
                .details(details)
                .build());
    }

    /**
     * Raccourci journalisant un changement de statut sur une note de frais.
     *
     * <p>
     * Le format du détail est « {@code DEPUIS -> VERS} ». Il est produit ici
     * et non par l'appelant, pour que toutes les transitions de l'application
     * s'écrivent de la même façon.
     * </p>
     *
     * @param user          auteur du changement
     * @param entiteId      identifiant de la note
     * @param ancienStatut  statut avant le changement
     * @param nouveauStatut statut après le changement
     */
    public void logChangementStatut(User user, Long entiteId,
            ExpenseStatus ancienStatut, ExpenseStatus nouveauStatut) {
        log(user, ENTITE_EXPENSE_REQUEST, entiteId, ACTION_CHANGEMENT_STATUT,
                ancienStatut + " -> " + nouveauStatut);
    }
}