package com.expenseflow.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ligne de depense pre-remplie a partir d'un texte libre.
 *
 * <p>
 * Les champs reprennent exactement la forme de {@link ExpenseLineRequest} : le
 * formulaire de creation peut ainsi reutiliser ses valeurs sans translation.
 * Une forme differente obligerait a convertir deux fois — une fois depuis le
 * JSON du modele, une fois vers le formulaire.
 * </p>
 *
 * <p>
 * <strong>Une valeur manquante est signalee, pas comblee.</strong> Le modele
 * indique {@code null} quand il n'a pas trouve l'information, plutot que
 * d'inventer une valeur. Remplir un montant manquant par zero ou par une
 * date du jour produirait un formulaire silencieusement faux, que
 * l'utilisateur nancyerait pas de verifier.
 * </p>
 *
 * @param categorieId identifiant de la categorie retenue
 * @param montant     montant de la depense
 * @param dateDepense date de la depense
 * @param description resume, ou {@code null} si le modele n'a rien produit
 */
public record ExtractExpenseLine(
        Long categorieId,
        BigDecimal montant,
        LocalDate dateDepense,
        String description
) {
}