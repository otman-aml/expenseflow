package com.expenseflow.exception;

/**
 * 409 — suppression du dernier compte porteur du rôle {@code ADMIN} refusée.
 *
 * <p>
 * Protège l'invariant « la table garde toujours au moins un administrateur ».
 * Sans ce garde, une suppression imprudente verrouillerait le système pour de
 * bon : plus aucun compte ne pourrait rouvrir l'accès d'administration, créer
 * de comptes ou en supprimer. Le refus est un conflit métier (409), pas un
 * refus d'autorisation (403) : l'appelant est habilité, c'est l'état de la
 * table qui rend l'opération impossible — le message doit d'ailleurs inviter
 * à promouvoir un autre compte avant de revenir.
 * </p>
 */
public class LastAdminException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Message lisible servi au frontend dans le corps 409. */
    public LastAdminException() {
        super("Impossible de supprimer le dernier compte administrateur : la table resterait sans ADMIN.");
    }
}