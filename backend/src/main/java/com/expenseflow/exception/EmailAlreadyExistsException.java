package com.expenseflow.exception;

/**
 * Levée lors d'une tentative d'inscription avec une adresse e-mail déjà utilisée.
 *
 * <p>
 * La contrainte d'unicité {@code users.email UNIQUE} est également protégée au
 * niveau base (et remonterait jusqu'ici en {@code DataIntegrityViolationException},
 * donc en 500). Cette exception applicative permet de renvoyer un <strong>409
 * Conflict</strong> explicite et lisible par le front, avant toute tentative
 * d'insertion.
 * </p>
 */
public class EmailAlreadyExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param email adresse e-mail déjà présente en base
     */
    public EmailAlreadyExistsException(String email) {
        super("Un compte existe déjà pour l'e-mail : " + email);
    }
}