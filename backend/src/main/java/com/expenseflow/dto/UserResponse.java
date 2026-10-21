package com.expenseflow.dto;

import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.UserRole;

/**
 * Représentation publique d'un utilisateur (sans aucune information sensible).
 *
 * @param id    identifiant unique de l'utilisateur
 * @param nom   nom complet de l'utilisateur
 * @param email adresse email
 * @param role  rôle applicatif
 * @param doitChangerMdp {@code true} si le mot de passe provisoire doit etre
 *                      remplace a la premiere connexion
 */
public record UserResponse(
        Long id,
        String nom,
        String email,
        UserRole role,
        boolean doitChangerMdp
) {

    /**
     * Construit une instance {@link UserResponse} à partir d'une entité {@link User}.
     *
     * @param user l'entité source
     * @return le DTO correspondant ou {@code null} si l'entité est {@code null}
     */
    public static UserResponse from(User user) {
        if (user == null) {
            return null;
        }
        return new UserResponse(
                user.getId(),
                user.getNom(),
                user.getEmail(),
                user.getRole(),
                user.isDoitChangerMdp()
        );
    }
}
