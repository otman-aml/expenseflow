package com.expenseflow.dto;

import com.expenseflow.entity.enums.UserRole;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Demande de modification d'un compte, côté administration.
 *
 * <p>
 * <strong>Le mot de passe n'appartient pas à ce DTO.</strong> Un
 * administrateur ne change jamais le mot de passe d'un utilisateur : il
 * serait en clair dans la requête, puis haché par l'admin plutôt que choisi
 * par le titulaire. La réinitialisation reste hors périmètre — l'admin peut
 * supprimer et recréer, opération tracée par le contexte de la demande.
 * </p>
 *
 * @param nom       nouveau nom
 * @param email     nouvel e-mail, unique
 * @param role      nouveau rôle
 * @param managerId nouveau manager, ou {@code null} pour détacher
 */
public record UpdateUserRequest(
        @NotBlank(message = "Le nom est requis")
        @Size(min = 2, max = 150, message = "Le nom doit contenir entre 2 et 150 caractères")
        String nom,

        @NotBlank(message = "L'e-mail est requis")
        @Email(message = "Format d'e-mail invalide")
        @Size(max = 255, message = "L'e-mail ne doit pas dépasser 255 caractères")
        String email,

        @NotNull(message = "Le rôle est requis")
        UserRole role,

        Long managerId
) {
}