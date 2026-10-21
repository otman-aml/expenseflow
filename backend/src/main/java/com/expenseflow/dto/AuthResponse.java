package com.expenseflow.dto;

/**
 * Réponse renvoyée lors d'une authentification réussie.
 *
 * @param token     jeton d'accès JWT signé
 * @param type      schéma d'authentification (toujours "Bearer")
 * @param expiresIn durée de validité du jeton en millisecondes
 * @param user      profil de l'utilisateur authentifié
 */
public record AuthResponse(
        String token,
        String type,
        long expiresIn,
        UserResponse user
) {
}
