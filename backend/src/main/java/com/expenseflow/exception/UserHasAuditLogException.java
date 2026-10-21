package com.expenseflow.exception;

/**
 * 409 — suppression d'un compte déjà auteur d'entrées dans le journal d'audit
 * refusée.
 *
 * <p>
 * {@code audit_log.utilisateur_id} porte la contrainte {@code ON DELETE
 * RESTRICT} : sans ce garde, la suppression ferait échouer la transaction en
 * violation de clé et PostgreSQL répondrait par un 500 opaque. Le refus est ici
 * un conflit métier — et il est <strong>définitif</strong> : la traçabilité ne
 * se « détache » pas d'un compte, contrairement à des notes que l'on pourrait
 * archiver. Le message invite donc à conserver le compte plutôt qu'à tenter un
 * contournement.
 * </p>
 */
public class UserHasAuditLogException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Message lisible servi au frontend dans le corps 409. */
    public UserHasAuditLogException() {
        super("Ce compte a déjà effectué des actions tracées et ne peut pas être supprimé");
    }
}