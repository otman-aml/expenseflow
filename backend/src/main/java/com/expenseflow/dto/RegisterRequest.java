package com.expenseflow.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Données requises pour l'inscription d'un nouvel utilisateur.
 *
 * <p>
 * Le rôle est <strong>volontairement absent</strong> de ce DTO : il est fixé à
 * {@code EMPLOYEE} côté service. L'accepter dans le payload permettrait à un
 * client de s'auto-attribuer {@code MANAGER} ou {@code FINANCE_ADMIN}, ce qui
 * constituerait une escalade de privilèges. La création de comptes de rôle
 * supérieur passera par un endpoint d'administration protégé, hors périmètre
 * du MVP.
 * </p>
 *
 * @param nom        nom complet de l'utilisateur (2 à 150 caractères)
 * @param email      adresse email unique (max 255 caractères)
 * @param motDePasse mot de passe brut (8 à 100 caractères)
 *
 * <p>
 * <strong>Chaque contrainte porte un message explicite.</strong> Sans lui,
 * Jakarta renvoie sa formulation technique ({@code must not be blank}), que
 * l'utilisateur ne comprend pas et qui ne dit pas quoi faire. La regle
 * appliquee ici : nommer le champ, puis dire ce qui manque.
 * </p>
 *
 * <p>
 * <strong>Ces messages ne sont pas identiques a ceux du formulaire.</strong>
 * Zod s'affiche avant l'envoi, ancre sur le champ ; Jakarta ne s'affiche
 * qu'apres un rejet du serveur, donc en dernier recours. Les deux ne sont
 * jamais vus cote a cote, et les formuler differemment evite la redondance.
 * </p>
 */
public record RegisterRequest(
        @NotBlank(message = "Le nom est obligatoire")
        @Size(min = 2, max = 150, message = "Le nom doit contenir entre 2 et 150 caractères")
        String nom,

        @NotBlank(message = "L'adresse email est obligatoire")
        @Email(message = "L'adresse email n'est pas valide")
        @Size(max = 255, message = "L'adresse email ne doit pas dépasser 255 caractères")
        String email,

        @NotBlank(message = "Le mot de passe est obligatoire")
        @Size(min = 8, max = 100,
                message = "Le mot de passe doit contenir entre 8 et 100 caractères")
        String motDePasse
) {
}
