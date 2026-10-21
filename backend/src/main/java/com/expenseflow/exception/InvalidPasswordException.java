package com.expenseflow.exception;

/**
 * Le mot de passe actuel fourni ne correspond pas à celui enregistré.
 *
 * <p>
 * <strong>Deliberement distincte de {@code BadCredentialsException}.</strong>
 * Celle-ci est rendue en 401, et le client Axios déconnecte automatiquement
 * sur un 401 (voir {@code client.js}) : l'utilisateur verrait sa session
 * fermée pour une simple faute de frappe, et perdrait la page de profil
 * qu'il était en train de remplir.
 * </p>
 *
 * <p>
 * Ici l'utilisateur <em>est</em> authentifié — son jeton est valide, et le
 * serveur l'a accepté. Ce qui échoue, c'est la saisie d'un second facteur
 * dans le formulaire, pas l'authentification. La réponse est donc un 400 :
 * l'utilisateur reste connecté et peut corriger sa saisie.
 * </p>
 *
 * <p>
 * Le message reste explicite : « ancien mot de passe incorrect » ne dit pas
 * si le compte existe, contrairement à un message générique.
 * </p>
 */
public class InvalidPasswordException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message explication adressée à l'utilisateur
     */
    public InvalidPasswordException(String message) {
        super(message);
    }
}