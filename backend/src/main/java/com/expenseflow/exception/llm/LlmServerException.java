package com.expenseflow.exception.llm;

/**
 * Panne du service du fournisseur : 5xx (502).
 *
 * <p>
 * Distincte d'un delai depasse bien que les deux signifient « on n'a pas eu de
 * reponse ». Le delai est imputable a la latence, la panne au service ; les
 * journaliser separement permet de distinguer une configuration de timeout
 * mal reglee d'une API momentanement indisponible.
 * </p>
 */
public class LlmServerException extends LlmException {

    private static final long serialVersionUID = 1L;

    /**
     * @param codeStatus code HTTP recus du fournisseur
     */
    public LlmServerException(int codeStatus) {
        super("Service du fournisseur indisponible (HTTP " + codeStatus + ")");
    }

    /**
     * @param codeStatus code HTTP recus du fournisseur
     * @param cause      cause d'origine
     */
    public LlmServerException(int codeStatus, Throwable cause) {
        super("Service du fournisseur indisponible (HTTP " + codeStatus + ")", cause);
    }
}