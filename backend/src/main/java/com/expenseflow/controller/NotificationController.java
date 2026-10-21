package com.expenseflow.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.dto.NotificationResponse;
import com.expenseflow.security.CustomUserDetails;
import com.expenseflow.service.NotificationService;

/**
 * Points d'entrée REST des notifications.
 *
 * <p>
 * <strong>Toutes les routes sont strictement personnelles.</strong> Aucune
 * n'accepte d'identifiant d'utilisateur : le destinataire est toujours celui
 * du jeton ({@code @AuthenticationPrincipal}). Un utilisateur ne peut donc
 * ni lire, ni marquer comme lue, la notification d'autrui — l'impossibilité
 * est structurelle, pas déclarative.
 * </p>
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * @param notificationService service des notifications
     */
    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Liste les notifications non lues de l'utilisateur authentifié.
     *
     * @param currentUser utilisateur du jeton
     * @return ses notifications non lues, des plus récentes aux plus anciennes
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<List<NotificationResponse>> findNonLues(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(notificationService.findNonLues(userDetails.getUser()));
    }

    /**
     * Marque une notification comme lue.
     *
     * @param id          identifiant de la notification
     * @param currentUser utilisateur du jeton
     * @return 204 No Content ; 404 si la notification n'existe pas ou appartient à autrui
     */
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{id}/lue")
    public ResponseEntity<Void> marquerLue(@PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        notificationService.marquerLue(id, userDetails.getUser());
        return ResponseEntity.noContent().build();
    }

    /**
     * Marque toutes les notifications de l'utilisateur comme lues.
     *
     * @param currentUser utilisateur du jeton
     * @return 204 No Content
     */
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/toutes-lues")
    public ResponseEntity<Void> marquerToutesLues(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        notificationService.marquerToutesLues(userDetails.getUser());
        return ResponseEntity.noContent().build();
    }
}
