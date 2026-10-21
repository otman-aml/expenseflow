package com.expenseflow.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Modification des informations personnelles de l'utilisateur connecté.
 *
 * <p>
 * <strong>Seuls le nom et l'email sont acceptés.</strong> Ni le rôle ni
 * l'identifiant ne figurent dans la requête : le rôle est une décision
 * d'administration, et l'identifiant vient du jeton. Les exposer ici
 * permettrait à un utilisateur de s'attribuer le rôle {@code FINANCE_ADMIN}.
 * </p>
 *
 * @param nom   nom affiché, 2 à 150 caractères
 * @param email adresse de connexion, trimmed et normalisée en minuscules par
 *               le service avant contrôle d'unicité
 */
public record UpdateProfileRequest(

        @NotBlank(message = "Le nom est requis")
        @Size(min = 2, max = 150, message = "Le nom doit contenir entre 2 et 150 caractères")
        String nom,

        @NotBlank(message = "L'adresse e-mail est requise")
        @Email(message = "Adresse e-mail invalide")
        @Size(max = 255, message = "Adresse e-mail trop longue (255 caractères maximum)")
        String email
) {
}