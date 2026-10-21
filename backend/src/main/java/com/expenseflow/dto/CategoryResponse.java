package com.expenseflow.dto;

import com.expenseflow.entity.Category;

/**
 * Représentation publique d'une catégorie de dépense.
 *
 * @param id  identifiant unique
 * @param nom libellé de la catégorie
 */
public record CategoryResponse(
        Long id,
        String nom
) {

    /**
     * Construit une instance {@link CategoryResponse} à partir d'une entité
     * {@link Category}.
     *
     * <p>
     * Les métadonnées techniques ({@code createdAt}, {@code updatedAt}) sont
     * volontairement omises : elles n'ont aucun intérêt pour le front et
     * exposeraient des détails d'implémentation de la persistance.
     * </p>
     *
     * @param category l'entité source
     * @return le DTO correspondant, ou {@code null} si l'entité est {@code null}
     */
    public static CategoryResponse from(Category category) {
        if (category == null) {
            return null;
        }
        return new CategoryResponse(
                category.getId(),
                category.getNom()
        );
    }
}