package com.expenseflow.dto;

import com.expenseflow.entity.enums.UserRole;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Demande de création d'un compte, côté administration.
 *
 * <p>
 * <strong>Le rôle est accepté du client</strong>, contrairement à
 * {@code RegisterRequest} qui force EMPLOYEE : c'est précisément le privège
 * d'ADMIN de créer des managers, de la finance, ou d'un autre
 * administrateur. La route est d'ailleurs la seule à accepter un rôle
 * choisi — l'inscription publique reste verrouillée en EMPLOYEE.
 * </p>
 *
 * <p>
 * {@code managerId} est facultatif : un manager de premier niveau n'a pas
 * de supérieur, et créer un compte sans rattachement est un cas nominal,
 * pas une anomalie.
 * </p>
 *
 * @param nom         nom complet (2 à 150 caractères)
 * @param email       adresse e-mail unique
 * @param motDePasse  mot de passe en clair (8 à 100 caractères), haché côté serveur
 * @param role        rôle à attribuer, jamais {@code null}
 * @param managerId   identifiant du manager hiérarchique, ou {@code null}
 */
public record CreateUserRequest(
        @NotBlank(message = "Le nom est requis")
        @Size(min = 2, max = 150, message = "Le nom doit contenir entre 2 et 150 caractères")
        String nom,

        @NotBlank(message = "L'e-mail est requis")
        @Email(message = "Format d'e-mail invalide")
        @Size(max = 255, message = "L'e-mail ne doit pas dépasser 255 caractères")
        String email,

        @NotBlank(message = "Le mot de passe est requis")
        @Size(min = 8, max = 100, message = "Le mot de passe doit contenir entre 8 et 100 caractères")
        @Pattern(regexp = ".*\\d.*",
                message = "Le mot de passe doit contenir au moins un chiffre")
        String motDePasse,

        @NotNull(message = "Le rôle est requis")
        UserRole role,

        Long managerId
) {
}