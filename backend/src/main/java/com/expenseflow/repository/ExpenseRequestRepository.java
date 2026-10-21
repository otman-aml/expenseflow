package com.expenseflow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.expenseflow.entity.ExpenseRequest;
import com.expenseflow.entity.enums.ExpenseStatus;

/**
 * Accès aux données des notes de frais.
 */
public interface ExpenseRequestRepository extends JpaRepository<ExpenseRequest, Long> {

    /**
     * Liste les notes de frais appartenant à un employé.
     *
     * <p>
     * Conservée telle quelle : elle ne charge pas les lignes. Elle reste utile
     * aux traitements internes (contrôles, statistiques) qui n'ont pas besoin du
     * détail. Pour afficher une note, utiliser
     * {@link #findByEmployeeIdWithLignes(Long)}.
     * </p>
     *
     * @param employeeId identifiant de l'employé propriétaire
     * @return les notes de frais de l'employé
     */
    List<ExpenseRequest> findByEmployeeId(Long employeeId);

    /**
     * Compte les notes d'un employé.
     *
     * <p>
     * Sert au contrôle préalable à la suppression d'un compte : la clé
     * {@code expense_requests.employee_id} est {@code ON DELETE RESTRICT},
     * et compter avant d'effacer transforme un rejet base (500) en un
     * 409 message actionnable.
     * </p>
     *
     * @param employeeId identifiant de l'employé
     * @return nombre de notes qu'il possède
     */
    long countByEmployeeId(Long employeeId);

    /**
     * Liste les notes d'un employé avec leurs lignes déjà chargées, de la plus
     * récente à la plus ancienne.
     *
     * <p>
     * <strong>{@code LEFT JOIN FETCH}</strong> et non {@code JOIN FETCH} : la
     * jointure externe conserve les notes dépourvies de lignes. Le
     * {@code INNER JOIN} les aurait silencieusement supprimées du résultat, ce
     * qui deviendrait un bug dès qu'une note sans ligne serait autorisée.
     * </p>
     *
     * <p>
     * <strong>{@code DISTINCT}</strong> est indispensable dès qu'un
     * {@code JOIN FETCH} cible une collection : la requête SQL produit une ligne
     * par couple (note, ligne), donc une note à trois lignes apparaît trois fois.
     * Sans cette clause, la collection contiendrait des doublons — le
     * {@code totalMontant} affiché serait alors faux.
     * </p>
     *
     * @param employeeId identifiant de l'employé propriétaire
     * @return les notes de l'employé, lignes incluses
     */
    @Query("SELECT DISTINCT er FROM ExpenseRequest er "
            + "LEFT JOIN FETCH er.lignes "
            + "WHERE er.employee.id = :employeeId "
            + "ORDER BY er.createdAt DESC")
    List<ExpenseRequest> findByEmployeeIdWithLignes(@Param("employeeId") Long employeeId);

    /**
     * Charge une note par son identifiant, lignes et catégories incluses.
     *
     * <p>
     * Utilisé par {@code GET /api/expense-requests/{id}} afin de renvoyer la
     * note complète en une seule requête, sans accès paresseux à la collection
     * une fois la transaction refermée.
     * </p>
     *
     * @param id identifiant de la note
     * @return la note demandée, lignes incluses, ou vide si elle n'existe pas
     */
    @Query("SELECT DISTINCT er FROM ExpenseRequest er "
            + "LEFT JOIN FETCH er.lignes "
            + "WHERE er.id = :id")
    Optional<ExpenseRequest> findByIdWithLignes(@Param("id") Long id);

    /**
     * Liste les notes dans un statut donné, lignes déjà chargées.
     *
     * <p>
     * <strong>Appelé pour les rôles qui voient tout</strong> (FINANCE_ADMIN,
     * ADMIN). Le filtrage par équipe — réservé au MANAGER — passe par
     * {@link #findByStatutAndEmployeeIdIn(ExpenseStatus, List)}, le service
     * choisissant la branche selon le rôle : la base ne décide jamais
     * seule de qui voit quoi.
     * </p>
     *
     * <p>
     * Le {@code LEFT JOIN FETCH} et le {@code DISTINCT} suivent la même
     * logique que {@link #findByEmployeeIdWithLignes(Long)} : le premier
     * conserve les notes sans ligne, le second évite les doublons introduits
     * par la jointure sur une collection.
     * </p>
     *
     * @param statut statut recherché
     * @return les notes correspondantes, lignes incluses
     */
    @Query("SELECT DISTINCT er FROM ExpenseRequest er "
            + "LEFT JOIN FETCH er.lignes "
            + "WHERE er.statut = :statut "
            + "ORDER BY er.createdAt ASC")
    List<ExpenseRequest> findByStatutWithLignes(@Param("statut") ExpenseStatus statut);

    /**
     * Liste les notes d'un statut données, restreintes à une liste
     * d'employés — la file de validation d'un manager.
     *
     * <p>
     * <strong>Le filtre porte sur l'auteur de la note</strong>
     * ({@code employee_id}), jamais sur le manager déclaré dans la note :
     * c'est la relation {@code users.manager_id} qui définit l'équipe, source
     * unique et modifiable sans toucher aux notes déjà soumises.
     * </p>
     *
     * <p>
     * {@code JOIN FETCH} plutôt que {@code LEFT JOIN} : une note de la file
     * de validation a toujours des lignes (une note vide n'est pas
     * soumissible), et la distinction DISTINCT reste de rigueur par sécurité
     * sur la collection.
     * </p>
     *
     * @param statut      statut recherché
     * @param employeeIds identifiants des employés de l'équipe, non vide
     * @return les notes de l'équipe dans ce statut, lignes incluses
     */
    @Query("SELECT DISTINCT er FROM ExpenseRequest er "
            + "JOIN FETCH er.lignes "
            + "WHERE er.statut = :statut "
            + "AND er.employee.id IN :employeeIds "
            + "ORDER BY er.createdAt ASC")
    List<ExpenseRequest> findByStatutAndEmployeeIdIn(
            @Param("statut") ExpenseStatus statut,
            @Param("employeeIds") List<Long> employeeIds);
}
