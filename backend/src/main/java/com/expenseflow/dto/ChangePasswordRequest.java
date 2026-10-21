package com.expenseflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Changement du mot de passe de l'utilisateur connecté.
 *
 * <p>
 * <strong>L'ancien mot de passe est exigé</strong> : sans cette vérification,
 * un jeton volé suffirait à prendre définitivement le compte — le mot de
 * passe est précisément ce qui distingue le possesseur légitime d'un
 * intrusession.
 * </p>
 *
 * <p>
 * <strong>La confirmation est un champ, pas une règle du service.</strong> Elle
 * arrive ici pour que le serveur puisse vérifier que les deux saisies
 * concordent : comparer deux champs du même corps est une règle d'intégrité
 * des données, pas une logique métier. Le message d'erreur peut ainsi
 * nommer le champ fautif.
 * </p>
 *
 * <p>
 * La taille minimale de 8 caractères suit la contrainte appliquée à
 * l'inscription ({@code /auth/register}).
 * </p>
 *
 * @param ancienMotDePasse       mot de passe actuel, vérifié par BCrypt
 * @param nouveauMotDePasse      nouveau mot de passe, 8 à 100 caractères
 * @param confirmationMotDePasse saisie de contrôle, doit égaler le nouveau
 */
public record ChangePasswordRequest(

        @NotBlank(message = "L'ancien mot de passe est requis")
        String ancienMotDePasse,

        @NotBlank(message = "Le nouveau mot de passe est requis")
        @Size(min = 8, max = 100, message = "Le mot de passe doit contenir entre 8 et 100 caractères")
        String nouveauMotDePasse,

        @NotBlank(message = "La confirmation est requise")
        String confirmationMotDePasse
) {
}