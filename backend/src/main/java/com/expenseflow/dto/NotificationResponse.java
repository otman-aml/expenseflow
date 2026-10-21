package com.expenseflow.dto;

import java.time.Instant;

import com.expenseflow.entity.Notification;
import com.expenseflow.entity.enums.NotificationType;

/**
 * Représentation publique d'une notification.
 *
 * <p>
 * Le destinataire est toujours l'utilisateur authentifié : aucune route ne
 * permet de lire la notification d'autrui, et l'identifiant du destinataire
 * n'est donc jamais exposé dans la réponse — l'invariant est porté par le
 * service, pas par le DTO.
 * </p>
 *
 * @param id        identifiant de la notification
 * @param type      nature de l'événement
 * @param message   message lisible, figé à la création
 * @param lien      lien interne de l'application, ou {@code null}
 * @param lue       état de lecture
 * @param createdAt horodatage de création
 */
public record NotificationResponse(
        Long id,
        NotificationType type,
        String message,
        String lien,
        boolean lue,
        Instant createdAt
) {

    /**
     * Convertit une entité de notification en réponse.
     *
     * @param notification entité de notification
     * @return réponse correspondante
     */
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getMessage(),
                notification.getLien(),
                notification.isLue(),
                notification.getCreatedAt());
    }
}