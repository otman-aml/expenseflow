package com.expenseflow.dto;

import java.time.Instant;

import com.expenseflow.entity.AuditLog;

/**
 * Représentation publique d'une entrée du journal d'audit.
 *
 * <p>
 * Le nom de l'auteur est inclus ({@code utilisateurNom}) pour que le front
 * affiche « Manager Demo a approuvé » sans appel supplémentaire par entrée.
 * L'identifiant est conservé : le client en a besoin pour d'éventuelles
 * actions, et il est la seule valeur fiable si le nom change.
 * </p>
 *
 * @param id              identifiant de l'entrée
 * @param entiteConcernee nom logique de l'entité
 * @param entiteId        identifiant de l'entité
 * @param action          action réalisée
 * @param utilisateurId   identifiant de l'auteur
 * @param utilisateurNom  nom de l'auteur
 * @param dateAction      horodatage de l'action
 * @param details         précision lisible
 */
public record AuditLogResponse(
        Long id,
        String entiteConcernee,
        Long entiteId,
        String action,
        Long utilisateurId,
        String utilisateurNom,
        Instant dateAction,
        String details
) {

    /**
     * Convertit une entité de journal en réponse.
     *
     * <p>
     * La lecture de {@code utilisateur} est sûre ici : le repository
     * récupère l'historique avec un {@code JOIN FETCH}, et la conversion
     * s'effectue dans la transaction du service appelant. L'accès paresseux
     * lèverait une {@code LazyInitializationException} hors transaction.
     * </p>
     *
     * @param log entrée de journal
     * @return réponse correspondante
     */
    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getEntiteConcernee(),
                log.getEntiteId(),
                log.getAction(),
                log.getUtilisateur().getId(),
                log.getUtilisateur().getNom(),
                log.getDateAction(),
                log.getDetails());
    }
}