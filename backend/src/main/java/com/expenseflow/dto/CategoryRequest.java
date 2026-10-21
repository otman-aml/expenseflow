package com.expenseflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Données d'entrée pour la création ou la modification d'une catégorie.
 *
 * @param nom libellé de la catégorie, unique en base (max 100 caractères)
 *
 * <p>
 * <strong>Messages explicites</strong> : sans message, Jakarta renvoie
 * {@code must not be blank} et {@code size must be between 0 and 100}.
 * </p>
 */
public record CategoryRequest(
        @NotBlank(message = "Le nom de la catégorie est obligatoire")
        @Size(max = 100, message = "Le nom ne doit pas dépasser 100 caractères")
        String nom
) {
}