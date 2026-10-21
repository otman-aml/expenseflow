package com.expenseflow.workflow;

import java.math.BigDecimal;

import com.expenseflow.entity.enums.UserRole;

/**
 * Dépassement d'un plafond budgétaire, constaté sur une ligne de note.
 *
 * <p>
 * Objet de valeur immuable : il décrit un constat, il ne porte aucun
 * comportement. Sa présence évite à {@link BudgetValidationService} de
 * renvoyer des tuples bruts, qui obligeraient l'appelant à connaître l'ordre
 * des champs.
 * </p>
 *
 * @param categorieId identifiant de la catégorie concernée
 * @param categorieNom libellé de la catégorie, pour le message d'erreur
 * @param role         rôle dont le plafond s'applique
 * @param montant      montant de la ligne fautive
 * @param plafond      plafond configuré pour ce couple (catégorie, rôle)
 */
public record BudgetViolation(
        Long categorieId,
        String categorieNom,
        UserRole role,
        BigDecimal montant,
        BigDecimal plafond
) {

    /**
     * Décrit le dépassement en une phrase lisible.
     *
     * @return message du type « Transport (EMPLOYEE) : 600,00 MAD > 500,00 MAD »
     */
    public String description() {
        return categorieNom + " (" + role + ") : " + montant + " MAD > " + plafond + " MAD";
    }
}