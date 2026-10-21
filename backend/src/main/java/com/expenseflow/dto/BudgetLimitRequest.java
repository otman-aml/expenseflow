package com.expenseflow.dto;

import java.math.BigDecimal;

import com.expenseflow.entity.enums.UserRole;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Données d'entrée pour la création ou la modification d'un plafond budgétaire.
 *
 * @param categorieId identifiant de la catégorie concernée (obligatoire)
 * @param role        rôle auquel le plafond s'applique (obligatoire)
 * @param plafond     montant maximal autorisé, strictement positif
 *
 * <p>
 * <strong>Messages explicites</strong>, et nom du champ dans chacun : ces
 * trois saisies sont toutes des listes déroulantes dans l'interface, et
 * « not null » ne dit pas laquelle des trois a été omise.
 * </p>
 */
public record BudgetLimitRequest(
        @NotNull(message = "La catégorie est obligatoire")
        Long categorieId,

        @NotNull(message = "Le rôle est obligatoire")
        UserRole role,

        @NotNull(message = "Le plafond est obligatoire")
        @Positive(message = "Le plafond doit être supérieur à 0")
        BigDecimal plafond
) {
}