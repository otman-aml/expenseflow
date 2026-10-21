package com.expenseflow.exception;

/**
 * Levée lors de la suppression d'une catégorie encore référencée.
 *
 * <p>
 * Les clés étrangères {@code budget_limits.categorie_id} et
 * {@code expense_lines.categorie_id} sont toutes deux en {@code ON DELETE
 * RESTRICT} dans le schéma V1. Sans vérification applicative en amont, la
 * suppression serait rejetée par PostgreSQL et remonterait en
 * {@code DataIntegrityViolationException}, donc en <strong>500</strong> — un
 * statut trompeur qui désigne une panne serveur alors qu'il s'agit d'un
 * conflit métier.
 * </p>
 *
 * <p>
 * Cette exception permet de renvoyer un <strong>409 Conflict</strong> avec un
 * message actionnable pour l'utilisateur.
 * </p>
 */
public class CategoryInUseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param nom libellé de la catégorie concernée
     */
    public CategoryInUseException(String nom) {
        super("Catégorie utilisée par des notes de frais ou des plafonds, impossible de la supprimer : " + nom);
    }
}