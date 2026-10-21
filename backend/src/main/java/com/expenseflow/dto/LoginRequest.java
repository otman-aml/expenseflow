package com.expenseflow.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Données requises pour l'authentification d'un utilisateur existant.
 *
 * @param email      adresse email de connexion
 * @param motDePasse mot de passe de connexion
 *
 * <p>
 * <strong>Messages explicites.</strong> Voir {@link RegisterRequest} pour la
 * raison : sans message, Jakarta renvoie {@code must not be blank}, que
 * l'utilisateur ne comprend pas.
 * </p>
 *
 * <p>
 * <strong>Ces messages ne sont pas identiques a ceux du formulaire de
 * connexion</strong>, qui empechent l'envoi avant qu'il n'ait lieu. Ceux-ci
 * n'apparaissent qu'apres un rejet du serveur.
 * </p>
 */
public record LoginRequest(
        @NotBlank(message = "L'adresse email est obligatoire")
        @Email(message = "L'adresse email n'est pas valide")
        String email,

        @NotBlank(message = "Le mot de passe est obligatoire")
        String motDePasse
) {
}
