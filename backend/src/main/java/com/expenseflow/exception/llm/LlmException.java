package com.expenseflow.exception.llm;

/**
 * Erreur d'appel au fournisseur de completion, quel qu'en soit le debut.
 *
 * <p>
 * Cette classe n'est pas levee directement : elle sert de mere aux
 * exceptions specialisees, et de filet de securite pour un echec dont le code
 * HTTP ne correspond a aucun cas connu. Un {@code catch (LlmException)}
 * traite donc tout ce qui peut sortir d'un appel LLM, sans avoir a prevoir
 * chaque cas.
 * </p>
 *
 * <p>
 * <strong>Ces exceptions sont techniques, pas metier.</strong> Elles signifient
 * « la brique IA n'a pas pu repondre », et jamais « la demande est mauvaise ».
 * Le gestionnaire global les traduit toutes en 5xx : l'utilisateur doit voir un
 * formulaire vide et utilisable a la main, pas une faute de sa part.
 * </p>
 */
public class LlmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description lisible de la panne
     */
    public LlmException(String message) {
        super(message);
    }

    /**
     * @param message description lisible de la panne
     * @param cause   cause d'origine
     */
    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}