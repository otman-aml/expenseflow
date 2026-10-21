package com.expenseflow.exception.llm;

/**
 * Cle rejetee par le fournisseur : 401 ou 403 (502).
 *
 * <p>
 * Couvre les deux codes parce qu'ils appellent la meme correction — une cle
 * expiree, revoquee ou erronee. Un 403 signale ici une politique d'acces
 * refusee sur ce compte, pas un acces refuse a un utilisateur de
 * l'application : ce dernier releve du 403 de {@code AccessDeniedException},
 * traite ailleurs, et le confondre ferait croire a un probleme d'habilitation.
 * </p>
 */
public class LlmAuthException extends LlmException {

    private static final long serialVersionUID = 1L;

    /**
     * @param codeStatus code HTTP recus du fournisseur
     */
    public LlmAuthException(int codeStatus) {
        super("Cle API du fournisseur refusee (HTTP " + codeStatus + ")");
    }
}