package com.expenseflow.exception;

/**
 * Levée lors de la suppression d'un compte qui possède encore des notes.
 *
 * <p>
 * La clé étrangère {@code expense_requests.employee_id} est en
 * {@code ON DELETE RESTRICT} depuis V1, et {@code users.manager_id} en
 * {@code ON DELETE SET NULL}. Sans vérification applicative en amont, la
 * suppression d'un employé noté serait rejetée par PostgreSQL et
 * remonterait en {@code DataIntegrityViolationException}, donc en
 * <strong>500</strong> — un statut trompeur qui désigne une panne serveur
 * alors qu'il s'agit d'un conflit métier.
 * </p>
 *
 * <p>
 * Cette exception permet de renvoyer un <strong>409 Conflict</strong> avec
 * un message actionnable : l'administrateur doit d'abord archiver ou
 * rattacher les notes du compte.
 * </p>
 */
public class UserInUseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param nom nom du compte concerné
     * @param nombreNotes nombre de notes encore rattachées
     */
    public UserInUseException(String nom, long nombreNotes) {
        super("Compte utilise par " + nombreNotes + " note(s) de frais, impossible de le supprimer : " + nom);
    }
}