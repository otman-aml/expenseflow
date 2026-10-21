package com.expenseflow.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.expenseflow.entity.AuditLog;

/**
 * Accès aux données du journal d'audit.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Historique d'une entité, du plus ancien au plus récent.
     *
     * <p>
     * Le {@code JOIN FETCH} lève le {@code FetchType.LAZY} de
     * {@code utilisateur} dès la requête. Sans lui, la construction des
     * {@code AuditLogResponse} — qui lit le nom de l'auteur — déclencherait
     * une requête par entrée : un historique de dix transitions produirait
     * dix allers-retours supplémentaires.
     * </p>
     *
     * <p>
     * <strong>Tri explicite sur deux critères.</strong> La colonne
     * {@code date_action} reçoit {@code DEFAULT NOW()}, qui vaut en PostgreSQL
     * le <em>début</em> de la transaction et non l'heure courante : les deux
     * transitions d'un même appel — {@code DRAFT -> SUBMITTED} puis
     * {@code SUBMITTED -> PENDING_MANAGER} — portent donc exactement le même
     * horodatage. Trier sur la seule date laisserait leur ordre au hasard ;
     * la clé secondaire {@code id} le rend déterministe. Le tri en base reste
     * préférable à un tri en mémoire, qui obligerait à charger toutes les
     * lignes avant de pouvoir en renvoyer une.
     * </p>
     *
     * @param entiteConcernee nom logique de l'entité
     * @param entiteId        identifiant de l'entité
     * @return entrées de l'historique, triées par date puis par id
     */
    @Query("SELECT al FROM AuditLog al JOIN FETCH al.utilisateur "
            + "WHERE al.entiteConcernee = :entiteConcernee AND al.entiteId = :entiteId "
            + "ORDER BY al.dateAction ASC, al.id ASC")
    List<AuditLog> findByEntiteConcerneeAndEntiteIdOrderByDateActionAscIdAsc(
            @Param("entiteConcernee") String entiteConcernee,
            @Param("entiteId") Long entiteId);

    /**
     * Compte les entrées dont l'utilisateur est l'auteur.
     *
     * <p>
     * Sert à la garde de suppression : {@code audit_log.utilisateur_id} est en
     * {@code ON DELETE RESTRICT} — retirer un compte déjà cité comme auteur
     * ferait échouer la suppression en violation de clé (500 opaque). Compter
     * <em>avant</em> permet de traduire ce refus en 409 lisible. La traçabilité
     * est par nature définitive : contrairement aux notes, aucune action
     * journalisée ne peut être « détachée » du compte.
     * </p>
     *
     * @param userId identifiant de l'auteur
     * @return nombre d'entrées d'audit écrites par ce compte
     */
    long countByUtilisateurId(Long userId);

    /**
     * Les {@code pageable} dernieres actions sur une entite, de la plus
     * recente a la plus ancienne.
     *
     * <p>
     * Sert au tableau de bord ADMIN : les dernieres actions de compte
     * ({@code COMPTE_CREE/MODIFIE/SUPPRIME}). Le tri reutilise la cle
     * secondaire {@code id} pour la meme raison que l'historique de note :
     * {@code date_action} porte {@code DEFAULT NOW()} — le debut de la
     * transaction — et deux actions d'un meme appel partagent l'horodatage.
     * </p>
     *
     * @param entiteConcernee nom logique de l'entite (ex. {@code User})
     * @param pageable        taille de page = nombre de lignes voulues
     * @return entrees triees de la plus recente a la plus ancienne
     */
    @Query("SELECT al FROM AuditLog al WHERE al.entiteConcernee = :entiteConcernee "
            + "ORDER BY al.dateAction DESC, al.id DESC")
    List<AuditLog> findDernieresParEntite(@Param("entiteConcernee") String entiteConcernee,
            Pageable pageable);
}
