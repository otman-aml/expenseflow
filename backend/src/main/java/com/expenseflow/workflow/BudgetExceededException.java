package com.expenseflow.workflow;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Levée lorsqu'une note dépasse un ou plusieurs plafonds budgétaires.
 *
 * <p>
 * Traduite en <strong>409 Conflict</strong> : la demande est recevable, mais
 * elle entre en conflit avec les règles de gestion. Même code que
 * {@link WorkflowException} — qui couvre déjà le cycle de vie — pour que le
 * client traite de façon homogeneous les deux refus fonctionnels.
 * </p>
 *
 * <p>
 * <strong>Toutes les violations sont énumérées</strong>, et non seulement la
 * première. Un employé qui corrige sa note doit connaître tous les dépassements
 * d'un coup : lui en révéler un seul l'obligerait à corriger, soumettre,
 * échouer, recommencer.
 * </p>
 */
public class BudgetExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description lisible listant tous les dépassements
     */
    public BudgetExceededException(String message) {
        super(message);
    }

    /**
     * Construit le message à partir des violations détectées.
     *
     * <p>
     * Les violations sont séparées par un point-virgule plutôt que par une
     * virgule : un montant s'écrit lui-même avec une virgule décimale
     * (« 600,00 MAD »), et un simple « , » rendrait le message ambigu.
     * </p>
     *
     * @param violations dépassements constatés, au moins un
     * @return message prêt à être renvoyé au client
     */
    public static String buildMessage(List<BudgetViolation> violations) {
        String detail = violations.stream()
                .map(BudgetViolation::description)
                .collect(Collectors.joining(" ; "));

        return "Plafond depasse : " + detail;
    }
}