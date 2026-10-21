package com.expenseflow.entity.enums;

/**
 * Décision d'un validateur sur une note de frais.
 * Workflow mono-niveau : MANAGER puis FINANCE.
 * Type SQL associé : {@code approval_status}.
 */
public enum ApprovalStatus {
    APPROVED_MANAGER,
    REJECTED_MANAGER,
    APPROVED_FINANCE,
    REJECTED_FINANCE
}
