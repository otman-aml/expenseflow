package com.expenseflow.entity.enums;

/**
 * Rôles applicatifs d'un utilisateur.
 * Type SQL associé : {@code user_role} (migration {@code V5}).
 *
 * <p>
 * {@code ADMIN} est un rôle d'administration pure : gestion des comptes
 * ({@code /api/admin/**}) et lecture intégrale des notes. Il ne valide
 * aucune note — ce privège reste réservé à {@code MANAGER} et
 * {@code FINANCE_ADMIN}, pour qu'un administrateur ne puisse pas se
 * retrouver approbateur de ses propres équipes par effet de bord.
 * </p>
 */
public enum UserRole {
    EMPLOYEE,
    MANAGER,
    FINANCE_ADMIN,
    ADMIN
}
