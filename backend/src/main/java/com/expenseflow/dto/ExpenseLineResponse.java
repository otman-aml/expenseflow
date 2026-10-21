package com.expenseflow.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.expenseflow.entity.ExpenseLine;

/**
 * Représentation publique d'une ligne de dépense.
 *
 * <p>
 * Le nom de la catégorie est inclus ({@code categorieNom}) afin d'éviter au
 * front un appel supplémentaire par ligne.
 * </p>
 *
 * @param id           identifiant de la ligne
 * @param categorieId  identifiant de la catégorie
 * @param categorieNom libellé de la catégorie
 * @param montant      montant de la ligne
 * @param dateDepense  date de la dépense
 * @param description  commentaire, éventuellement {@code null}
 */
public record ExpenseLineResponse(
        Long id,
        Long categorieId,
        String categorieNom,
        BigDecimal montant,
        LocalDate dateDepense,
        String description
) {

    /**
     * Construit une instance {@link ExpenseLineResponse} à partir d'une entité
     * {@link ExpenseLine}.
     *
     * <p>
     * L'association {@code categorie} est en {@code FetchType.LAZY} : elle doit
     * être lue dans la transaction du service appelant.
     * </p>
     *
     * @param expenseLine l'entité source
     * @return le DTO correspondant, ou {@code null} si l'entité est {@code null}
     */
    public static ExpenseLineResponse from(ExpenseLine expenseLine) {
        if (expenseLine == null) {
            return null;
        }
        return new ExpenseLineResponse(
                expenseLine.getId(),
                expenseLine.getCategorie().getId(),
                expenseLine.getCategorie().getNom(),
                expenseLine.getMontant(),
                expenseLine.getDateDepense(),
                expenseLine.getDescription()
        );
    }
}