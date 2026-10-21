package com.expenseflow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.UserRole;

/**
 * Accès aux données des utilisateurs.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Recherche un utilisateur par son adresse e-mail (identifiant de connexion).
     *
     * @param email adresse e-mail recherchée
     * @return l'utilisateur correspondant, s'il existe
     */
    Optional<User> findByEmail(String email);

    /**
     * Liste les utilisateurs d'un rôle.
     *
     * <p>
     * Sert aux notifications (« tous les managers ») et à l'administration
     * (« managers assignables »). Le critère est le rôle seul : la
     * relation d'équipe ({@code manager_id}) décrit qui supervise qui,
     * pas qui est habilité à valider.
     * </p>
     *
     * @param role rôle recherché
     * @return les utilisateurs de ce rôle, dans l'ordre naturel de la table
     */
    List<User> findByRole(UserRole role);

    /**
     * Compte les comptes porteurs d'un rôle.
     *
     * <p>
     * Sert à la garde « dernier ADMIN » de la suppression : vérifier qu'il en
     * restera au moins un <em>avant</em> d'engager l'opération. Compter après
     * coup serait trop tard — la suppression serait déjà irréversible.
     * </p>
     *
     * @param role rôle compté
     * @return nombre de comptes de ce rôle
     */
    long countByRole(UserRole role);

    /**
     * Liste tous les comptes avec leur manager déjà résolu.
     *
     * <p>
     * Requête de l'écran d'administration : sans {@code LEFT JOIN FETCH},
     * la construction des réponses déclencherait une requête par ligne pour
     * lire {@code manager} — dix comptes produiraient dix allers-retours
     * supplémentaires. La jointure externe conserve les comptes sans
     * manager, cas nominal d'un premier niveau.
     * </p>
     *
     * @return tous les comptes, manager inclus
     */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.manager ORDER BY u.id ASC")
    List<User> findAllWithManager();

    /**
     * Liste les employés directs d'un manager ({@code manager_id}).
     *
     * <p>
     * Sert au filtrage par équipe : un manager ne doit voir que les notes de
     * ses propres employés. La relation est portée par la colonne
     * {@code manager_id} de la table {@code users} (présente depuis V1).
     * </p>
     *
     * @param managerId identifiant du manager
     * @return ses employés directs, jamais le manager lui-même
     */
    List<User> findByManagerId(Long managerId);
}
