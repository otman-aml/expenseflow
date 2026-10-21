package com.expenseflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Demande de rejet d'une note de frais, avec son motif.
 *
 * <p>
 * <strong>Le commentaire est obligatoire</strong> ({@code @NotBlank}) : un rejet
 * sans motif est inexploitable pour l'employé, qui ne peut pas savoir quoi
 * corriger. La contrainte est posée côté serveur — un client qui l'ignorerait
 * se verrait renvoyer un 400.
 * </p>
 *
 * <p>
 * {@code @NotBlank} rejette également une chaîne composed uniquement
 * d'espaces, ce que {@code @NotNull} aurait laissé passer.
 * </p>
 *
 * @param commentaire motif du rejet, obligatoire (500 caractères maximum)
 *
 * <p>
 * <strong>Messages explicites</strong> : le motif est lu par l'employé, qui doit
 * savoir quoi corriger. « must not be blank » ne le lui dit pas.
 * </p>
 */
public record RejectRequest(
        @NotBlank(message = "Le motif du rejet est obligatoire")
        @Size(max = 500, message = "Le motif ne doit pas dépasser 500 caractères")
        String commentaire
) {
}