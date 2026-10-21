package com.expenseflow.entity.enums;

/**
 * Nature d'une notification.
 *
 * <p>
 * Chaque constante correspond à une transition de note de frais telle qu'elle
 * est déclenchée par {@code ExpenseRequestService} : la soumission, les trois
 * issues possibles du circuit de validation, et le rejet initial. Le type
 * conditionne le libellé affiché et la destination de la notification, jamais
 * la logique métier — celle-ci reste portée par {@code ExpenseWorkflow}.
 * </p>
 *
 * <p>
 * Le stockage en base est un {@code VARCHAR} ; cet enum fait foi côté
 * application. Voir le commentaire de choix dans la migration
 * {@code V4__create_notifications.sql} pour la raison d'une absence d'enum SQL.
 * </p>
 */
public enum NotificationType {

    /** Note soumise par l'employé : les managers sont invités à valider. */
    NOTE_SOUMISE,

    /** Note approuvée au niveau manager. */
    NOTE_APPROUVEE,

    /** Note rejetée par un manager. */
    NOTE_REJETEE,

    /** Note rejetée par la finance. */
    NOTE_REJETEE_FINANCE,

    /** Note remboursée : fin du circuit. */
    NOTE_REMBOURSEE
}
