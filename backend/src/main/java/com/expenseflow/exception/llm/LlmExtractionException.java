package com.expenseflow.exception.llm;

/**
 * Reponse du modele inexploitable : JSON illisible, champ absent, valeur hors
 * bornes (422).
 *
 * <p>
 * Distincte des pannes de transport ({@link LlmServerException},
 * {@link LlmTimeoutException}) : le service a bien repondu, mais sa reponse
 * ne permet pas de remplir le formulaire. La traduction HTTP differe en
 * consequence — 422 signale « votre demande n'a pas pu etre satisfaite »
 * la, ou 502 signale « service indisponible ». Dans les deux cas l'utilisateur
 * saisit sa depense a la main, mais seule la seconde desactivation merite
 * d'etre signalee comme panne.
 * </p>
 */
public class LlmExtractionException extends LlmException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description de ce qui rend la reponse inexploitable
     */
    public LlmExtractionException(String message) {
        super(message);
    }

    /**
     * @param message description de l'anomalie
     * @param cause   cause d'origine, typiquement une erreur de deserialisation
     */
    public LlmExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}