package com.expenseflow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.expenseflow.entity.BudgetLimit;
import com.expenseflow.entity.enums.UserRole;

/**
 * Accès aux données des plafonds budgétaires (catégorie, rôle).
 */
public interface BudgetLimitRepository extends JpaRepository<BudgetLimit, Long> {

    /**
     * Récupère tous les plafonds avec leur catégorie déjà chargée.
     *
     * <p>
     * Le {@code JOIN FETCH} lève le {@code FetchType.LAZY} de l'association
     * {@code categorie} dès la requête SQL. Sans lui, chaque DTO renvoyé
     * déclencherait une requête supplémentaire (problème N+1) au moment de la
     * construction de {@code BudgetLimitResponse}, qui lit
     * {@code getCategorie().getNom()}.
     * </p>
     *
     * @return tous les plafonds, catégorie incluse
     */
    @Query("SELECT bl FROM BudgetLimit bl JOIN FETCH bl.categorie")
    List<BudgetLimit> findAllWithCategorie();

    /**
     * Récupère les plafonds d'un rôle donné, avec leur catégorie déjà chargée.
     *
     * <p>
     * Utilisé par {@code GET /api/budget-limits/me} : un utilisateur ne peut
     * consulter que les plafonds correspondant à son propre rôle, ce qui évite
     * toute fuite sur les plafonds des autres rôles.
     * </p>
     *
     * @param role rôle applicatif de l'appelant
     * @return les plafonds de ce rôle, catégorie incluse
     */
    @Query("SELECT bl FROM BudgetLimit bl JOIN FETCH bl.categorie WHERE bl.role = :role")
    List<BudgetLimit> findByRoleWithCategorie(@Param("role") UserRole role);

    /**
     * Compte les plafonds référençant une catégorie donnée.
     *
     * <p>
     * Utilisé avant suppression d'une catégorie : la clé étrangère
     * {@code budget_limits.categorie_id} étant en {@code ON DELETE RESTRICT},
     * laisser la contrainte SQL rejeter l'opération produirait une
     * {@code DataIntegrityViolationException} (500) au lieu d'une erreur
     * métier explicite (409).
     * </p>
     *
     * @param categorieId identifiant de la catégorie
     * @return nombre de plafonds rattachés à cette catégorie
     */
    long countByCategorieId(Long categorieId);

    /**
     * Recherche un plafond par le couple (catégorie, rôle), qui constitue la
     * contrainte d'unicité {@code uk_budget_limits_categorie_role}.
     *
     * @param categorieId identifiant de la catégorie
     * @param role        rôle concerné
     * @return le plafond correspondant, s'il existe
     */
    @Query("SELECT bl FROM BudgetLimit bl WHERE bl.categorie.id = :categorieId AND bl.role = :role")
    Optional<BudgetLimit> findByCategorieIdAndRole(@Param("categorieId") Long categorieId,
            @Param("role") UserRole role);
}
