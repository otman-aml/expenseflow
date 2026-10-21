package com.expenseflow.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.expenseflow.entity.Category;

/**
 * Accès aux données des catégories de dépense.
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /**
     * Recherche une catégorie par son nom (unique en base).
     *
     * @param nom nom de la catégorie
     * @return la catégorie correspondante, si elle existe
     */
    Optional<Category> findByNom(String nom);
}
