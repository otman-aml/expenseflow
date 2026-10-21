package com.expenseflow.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Données d'entrée d'une ligne de dépense.
 *
 * <p>
 * Seule entité interne de l'agrégat {@code ExpenseRequest} : elle n'existe
 * qu'au sein d'une note et ne reçoit jamais d'identifiant propre côté client.
 * </p>
 *
 * @param categorieId  identifiant de la catégorie concernée (obligatoire)
 * @param montant      montant de la ligne, strictement positif
 * @param dateDepense  date de la dépense (date pure, sans heure)
 * @param description  commentaire libre, facultatif (500 caractères maximum)
 *
 * <p>
 * <strong>Messages explicites</strong>, nom du champ dans chacun : une note
 * comporte plusieurs lignes, et le message doit dire laquelle est en cause.
 * </p>
 */
public record ExpenseLineRequest(
        @NotNull(message = "La catégorie est obligatoire")
        Long categorieId,

        @NotNull(message = "Le montant est obligatoire")
        @Positive(message = "Le montant doit être supérieur à 0")
        BigDecimal montant,

        @NotNull(message = "La date de dépense est obligatoire")
        LocalDate dateDepense,

        @Size(max = 500, message = "La description ne doit pas dépasser 500 caractères")
        String description
) {
}