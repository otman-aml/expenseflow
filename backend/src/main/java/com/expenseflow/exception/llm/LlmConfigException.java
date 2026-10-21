package com.expenseflow.exception.llm;

/**
 * Configuration absente ou invalide : la cle API n'est pas definie (503).
 *
 * <p>
 * Levée <em>avant</em> tout appel reseau. Sans ce controle, une cle manquante
 * produirait un 401 cote Llm, que l'on traduirait par une erreur
 * d'authentification — alors que le vrai probleme est local, et se corrige en
 * une variable d'environnement.
 * </p>
 */
public class LlmConfigException extends LlmException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description de la propriete manquante
     */
    public LlmConfigException(String message) {
        super(message);
    }
}