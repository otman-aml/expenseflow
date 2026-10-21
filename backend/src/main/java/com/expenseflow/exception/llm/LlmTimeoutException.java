package com.expenseflow.exception.llm;

/**
 * Delai depasse (504).
 *
 * <p>
 * Distinguee des autres erreurs car elle se traite diffremement : un delai
 * depasse n'indique ni une faute de configuration ni une panne du fournisseur, mais
 * une requete trop lente. Un nouvel essai a du sens ; il n'a aucun sens apres
 * un 401.
 * </p>
 */
public class LlmTimeoutException extends LlmException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description du depassement
     */
    public LlmTimeoutException(String message) {
        super(message);
    }

    /**
     * @param message description du depassement
     * @param cause   cause d'origine
     */
    public LlmTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}