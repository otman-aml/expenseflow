package com.expenseflow.workflow;

/**
 * Levée lorsqu'une transition d'état est interdite par la machine à états.
 *
 * <p>
 * Traduite en <strong>409 Conflict</strong> par
 * {@code GlobalExceptionHandler} : la requête est syntaxiquement valide et
 * l'utilisateur est authentifié, mais elle entre en conflit avec l'état
 * courant de la ressource. Un 403 serait trompeur — l'utilisateur a bien le
 * droit d'agir sur <em>une</em> note, pas sur <em>celle-ci, dans son état
 * actuel</em>.
 * </p>
 *
 * <p>
 * <strong>409 plutôt que 400</strong> : l'erreur est temporaire et liée à
 * l'état. Rejouer la même requête après que la note aura changé d'état peut
 * réussir, ce qui est la sémantique de {@code Conflict}.
 * </p>
 */
public class WorkflowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description lisible de la transition refusée
     */
    public WorkflowException(String message) {
        super(message);
    }
}