package com.expenseflow.entity;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import com.expenseflow.entity.enums.NotificationType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Notification adressée à un utilisateur.
 * Table SQL : {@code notifications}.
 *
 * <p>
 * <strong>Une notification est un message, pas un journal.</strong> Elle est
 * créée pour un destinataire précis, passée à {@code lue} quand il l'a vue, et
 * supprimée avec son compte ({@code ON DELETE CASCADE}). Rien d'autre dans
 * l'application ne la consomme : aucune règle métier ne s'appuie sur son
 * existence, et son rejet éventuel (en base) n'affecte aucune transition.
 * </p>
 *
 * <p>
 * <strong>Le message est figé à la création.</strong> Il n'est jamais recalculé
 * à l'affichage : le destinataire doit lire ce qui était vrai au moment du
 * déclenchement (« la note #177 a été approuvée »), pas une reconstitution
 * depuis l'état courant de la note, qui aura pu changer entre-temps.
 * </p>
 *
 * @see com.expenseflow.service.NotificationService
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(exclude = "utilisateur")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Destinataire. NOT NULL en base : contrairement au journal d'exploitation
     * ({@code ai_call_log}), une notification sans destinataire n'a aucun sens —
     * elle est supprimée avec le compte.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false)
    private User utilisateur;

    /** Nature de l'événement, qui conditionne le libellé côté front. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private NotificationType type;

    /**
     * Message lisible, figé à la création.
     *
     * <p>
     * Contient l'identifiant de la note pour que le destinataire s'y retrouve
     * même dans une liste de plusieurs notifications : « Note #177 approuvée »
     * se distingue immédiatement de « Note #178 approuvée ».
     * </p>
     */
    @Column(name = "message", nullable = false)
    private String message;

    /**
     * Lien interne de l'application (ex. {@code /notes/177}), nullable.
     *
     * <p>
     * {@code null} signifie « aucune destination cliquable » — un message
     * d'information sans fiche associée. Le front affiche alors l'entrée sans
     * la rendre cliquable plutôt que de fabriquer un lien mort.
     * </p>
     */
    @Column(name = "lien", length = 200)
    private String lien;

    /** État de lecture ; {@code false} à la création. */
    @Column(name = "lue", nullable = false)
    private boolean lue;

    /**
     * Horodatage de création, alimenté par la base ({@code DEFAULT NOW()}).
     * Distinct d'une éventuelle horloge applicative : c'est PostgreSQL qui
     * tranche, comme pour les autres tables du projet.
     */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
