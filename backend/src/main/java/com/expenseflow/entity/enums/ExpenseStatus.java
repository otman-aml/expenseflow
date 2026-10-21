package com.expenseflow.entity.enums;

/**
 * Statuts du cycle de vie d'une note de frais.
 * Workflow mono-niveau : MANAGER puis FINANCE.
 * Type SQL associé : {@code expense_status}.
 */
public enum ExpenseStatus {
    DRAFT,
    SUBMITTED,
    PENDING_MANAGER,
    APPROVED_MANAGER,
    REJECTED_MANAGER,
    PENDING_FINANCE,
    REIMBURSED,
    REJECTED_FINANCE
}
