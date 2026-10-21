package com.expenseflow.exception;

/**
 * Levée lorsqu'une ressource attendue est absente (404).
 *
 * <p>
 * Cas d'usage immédiat : {@code GET /api/auth/me} avec un jeton valide dont
 * l'utilisateur correspondant a été supprimé de la base entre-temps.
 * </p>
 */
public class ResourceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description lisible de la ressource manquante
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}