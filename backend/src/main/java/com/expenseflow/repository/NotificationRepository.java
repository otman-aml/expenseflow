package com.expenseflow.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.expenseflow.entity.Notification;

/**
 * Accès aux données des notifications.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Notifications non lues d'un utilisateur, des plus récentes aux plus
     * anciennes.
     *
     * <p>
     * <strong>Tri en base, pas en mémoire.</strong> Le parcours le plus
     * fréquent de l'application est le chargement de la cloche — quelques
     * lignes au plus — et un tri en mémoire supposerait de charger l'intégralité
     * des notifications de l'utilisateur avant d'en renvoyer une seule. L'index
     * composite {@code idx_notifications_utilisateur_lue} (utilisateur_id, lue)
     * sert exactement cette requête.
     * </p>
     *
     * <p>
     * La clé secondaire {@code id} rend le tri déterministe : deux
     * notifications créées dans la même transaction partagent le même
     * {@code created_at} (début de transaction en PostgreSQL), et leur ordre
     * resterait au hasard sur la seule date.
     * </p>
     *
     * @param utilisateurId identifiant du destinataire
     * @return ses notifications non lues, triées
     */
    @Query("SELECT n FROM Notification n WHERE n.utilisateur.id = :utilisateurId AND n.lue = false "
            + "ORDER BY n.createdAt DESC, n.id DESC")
    List<Notification> findNonLuesByUtilisateurId(@Param("utilisateurId") Long utilisateurId);

    /**
     * Nombre de notifications non lues, pour la pastille de la cloche.
     *
     * <p>
     * Requête de comptage séparée de la liste : le front interroge les deux à
     * chaque rafraîchissement, et un {@code COUNT(*)} reste nettement moins
     * coûteux qu'un chargement de lignes entières — le décompte est la donnée
     * visible en permanence, la liste n'est lue qu'à l'ouverture du panneau.
     * </p>
     *
     * @param utilisateurId identifiant du destinataire
     * @return nombre de notifications non lues
     */
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.utilisateur.id = :utilisateurId AND n.lue = false")
    long countNonLuesByUtilisateurId(@Param("utilisateurId") Long utilisateurId);

    /**
     * Marque toutes les notifications d'un utilisateur comme lues.
     *
     * <p>
     * Opération bulk JPQL : elle contourne le cycle de vie JPA (pas de SELECT
     * préalable, pas de dirty checking) et produit une seule instruction SQL.
     * Sans elle, « tout marquer comme lu » supposerait de charger chaque
     * notification de l'utilisateur en mémoire pour la modifier une à une.
     * </p>
     *
     * @param utilisateurId identifiant du destinataire
     * @return nombre de lignes mises à jour
     */
    @Modifying
    @Query("UPDATE Notification n SET n.lue = true "
            + "WHERE n.utilisateur.id = :utilisateurId AND n.lue = false")
    int marquerToutesLues(@Param("utilisateurId") Long utilisateurId);
}