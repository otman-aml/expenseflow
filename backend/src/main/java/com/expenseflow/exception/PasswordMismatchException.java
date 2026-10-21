package com.expenseflow.exception;

/**
 * Les deux saisies du nouveau mot de passe ne concordent pas.
 *
 * <p>
 * <strong>Distincte de {@code BadCredentialsException}</strong> : celle-ci
 * traduit en 401 « votre ancien mot de passe est faux » et fait
 * déconnecter le client via l'intercepteur Axios, ce qui — sur la page de
 * profil — viderait la session pour une simple faute de frappe dans la
 * confirmation. Ici la réponse est un 400 : l'utilisateur est connecté,
 * aucune authentification n'a échoué.
 * </p>
 *
 * <p>
 * Le même type sert lorsque le nouveau mot de passe est identique à
 * l'ancien : dans les deux cas, la saisie est incohérente et l'attente est
 * la même — un message de correction.
 * </p>
 */
public class PasswordMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message explication adressée à l'utilisateur
     */
    public PasswordMismatchException(String message) {
        super(message);
    }
}