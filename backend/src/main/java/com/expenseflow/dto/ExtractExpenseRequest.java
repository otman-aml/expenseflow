package com.expenseflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Requete d'extraction d'une depense depuis un texte libre.
 *
 * <p>
 * <strong>Une seule depense par requete</strong> : le prompt construit par
 * {@code AiExtractionService} demande un objet unique. Pour plusieurs depenses,
 * l'utilisateur repete l'operation — plus simple et plus fiable qu'un
 * schema de liste, qu'un modele petit remplit partiellement ou renvoie un
 * objet par depense non demandee.
 * </p>
 *
 * @param texte description de la depense en langage naturel
 */
public record ExtractExpenseRequest(

        // 500 caracteres : au-dela, le texte est rarement une depense unique, et
        // le prompt conditioningne deja fortement la reponse du modele.
        @NotBlank(message = "Décrivez la dépense en quelques mots")
        @Size(max = 500, message = "Description trop longue (500 caractères maximum)")
        String texte
) {
}