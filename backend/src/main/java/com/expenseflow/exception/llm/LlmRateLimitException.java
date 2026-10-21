package com.expenseflow.exception.llm;

/**
 * Quota d'appels depasse chez le fournisseur : 429 (502).
 *
 * <p>
 * Se distingue des autres erreurs parce qu'elle est temporaire et que
 * l'appelant peut agir : reessayer plus tard, ou renvoyer l'utilisateur vers la
 * saisie manuelle. Un plan gratuit d'un projet de portfolio est tres
 * probablement la premiere cause que l'on rencontrera.
 * </p>
 */
public class LlmRateLimitException extends LlmException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description du depassement
     */
    public LlmRateLimitException(String message) {
        super(message);
    }
}