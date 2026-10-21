package com.expenseflow.dto;

/**
 * Reponse d'une extraction de depense.
 *
 * <p>
 * Un seul champ, {@code ligne} : une extraction porte sur une depense. Le
 * format reste celui d'un objet contenant une liste, plutot qu'un objet nu,
 * pour que l'ajout ulterieur d'un champ (confiance du modele, texte
 * d'avertissement) ne casse pas le contrat consomme par le front.
 * </p>
 *
 * @param ligne ligne pre-remplie
 */
public record ExtractExpenseResponse(ExtractExpenseLine ligne) {
}