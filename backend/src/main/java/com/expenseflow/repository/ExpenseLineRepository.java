package com.expenseflow.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.expenseflow.entity.ExpenseLine;

/**
 * Accès aux données des lignes de dépense.
 */
public interface ExpenseLineRepository extends JpaRepository<ExpenseLine, Long> {

    /**
     * Compte les lignes de dépense rattachées à une catégorie donnée.
     *
     * <p>
     * Symétrique de {@code BudgetLimitRepository.countByCategorieId} : évite
     * qu'une catégorie encore utilisée par des notes de frais soit supprimée,
     * la contrainte {@code expense_lines.categorie_id} étant en
     * {@code ON DELETE RESTRICT}.
     * </p>
     *
     * @param categorieId identifiant de la catégorie
     * @return nombre de lignes de dépense rattachées à cette catégorie
     */
    long countByCategorieId(Long categorieId);
}
