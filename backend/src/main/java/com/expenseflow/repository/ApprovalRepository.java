package com.expenseflow.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.expenseflow.entity.Approval;

/**
 * Accès aux données des décisions d'approbation.
 */
public interface ApprovalRepository extends JpaRepository<Approval, Long> {
}
