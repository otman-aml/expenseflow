package com.expenseflow.exception;

/**
 * Levée lorsqu'une entité entre en conflit d'unicité avec une ressource
 * existante.
 *
 * <p>
 * Utilisée pour deux cas distincts, tous deux renvoyant un <strong>409
 * Conflict</strong> :
 * </p>
 * <ul>
 * <li>le nom d'une catégorie existe déjà ({@code categories.nom} est unique) ;</li>
 * <li>un plafond existe déjà pour le couple (catégorie, rôle) — contrainte
 * {@code uk_budget_limits_categorie_role}.</li>
 * </ul>
 *
 * <p>
 * Comme pour {@link EmailAlreadyExistsException}, la vérification applicative
 * précède l'insertion : la contrainte SQL reste le filet de sécurité pour les
 * insertions concurrentes, mais elle remonterait alors en 500 sans ce contrôle.
 * </p>
 */
public class DuplicateResourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description du conflit, nommant la ressource en cause
     */
    public DuplicateResourceException(String message) {
        super(message);
    }
}