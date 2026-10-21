package com.expenseflow.dto;

import java.math.BigDecimal;

import com.expenseflow.entity.BudgetLimit;
import com.expenseflow.entity.enums.UserRole;

/**
 * Représentation publique d'un plafond budgétaire.
 *
 * <p>
 * Le nom de la catégorie est inclus ({@code categorieNom}) afin d'éviter au
 * front un appel supplémentaire pour chaque ligne : les plafonds servent
 * quasi toujours à être affichés avec leur libellé.
 * </p>
 *
 * @param id           identifiant unique du plafond
 * @param categorieId  identifiant de la catégorie concernée
 * @param categorieNom libellé de la catégorie
 * @param role         rôle auquel le plafond s'applique
 * @param plafond      montant maximal autorisé
 */
public record BudgetLimitResponse(
        Long id,
        Long categorieId,
        String categorieNom,
        UserRole role,
        BigDecimal plafond
) {

    /**
     * Construit une instance {@link BudgetLimitResponse} à partir d'une entité
     * {@link BudgetLimit}.
     *
     * <p>
     * L'association {@code categorie} est en {@code FetchType.LAZY} : elle est
     * donc lue dans la transaction du service appelant. Une lecture hors
     * transaction lèverait une {@code LazyInitializationException}.
     * </p>
     *
     * @param budgetLimit l'entité source
     * @return le DTO correspondant, ou {@code null} si l'entité est {@code null}
     */
    public static BudgetLimitResponse from(BudgetLimit budgetLimit) {
        if (budgetLimit == null) {
            return null;
        }
        return new BudgetLimitResponse(
                budgetLimit.getId(),
                budgetLimit.getCategorie().getId(),
                budgetLimit.getCategorie().getNom(),
                budgetLimit.getRole(),
                budgetLimit.getPlafond()
        );
    }
}