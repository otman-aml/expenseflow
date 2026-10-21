package com.expenseflow.entity;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Journal d'un appel à l'API d'extraction.
 * Table SQL : {@code ai_call_log}.
 *
 * <p>
 * <strong>Ce journal mesure, il ne governs pas.</strong> Contrairement à
 * {@code audit_log}, qui restitue les décisions métier prises, celui-ci décrit
 * une interaction technique : sa valeur est de permettre de dire si l'extraction
 * se dégrade — plus lente, plus coûteuse, en échec — sans avoir àReproduire le
 * problème. Aucun écran utilisateur ne le consomme directement.
 * </p>
 *
 * <p>
 * <strong>Une ligne par appel, y compris en échec.</strong> Exclure les échecs
 * donnerait un taux de réussite flatteux de 100 %, qui est précisément la
 * métrique qu'on cherche à surveiller.
 * </p>
 *
 * @see com.expenseflow.service.AiCallLogService
 */
@Entity
@Table(name = "ai_call_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(exclude = "utilisateur")
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Date de l'appel.
     *
     * <p>
     * Alimentée par la base ({@code DEFAULT NOW()}) et non par l'application :
     * l'horodatage doit être celui du serveur de base de données, cohérent
     * avec les autres tables et indépendant de l'horloge du poste de
     * développement.
     * </p>
     */
    @Column(name = "date_appel", nullable = false, insertable = false, updatable = false)
    private Instant dateAppel;

    /**
     * Auteur de l'appel, s'il est connu.
     *
     * <p>
     * {@code null} après la suppression du compte : la contrainte
     * {@code ON DELETE SET NULL} conserve l'appel pour le diagnostic tout en
     * respectant la suppression. La relation n'est jamais serialisee — elle
     * n'est pas rendue par un DTO, et la laisser exposer le compte entier
     * depuis une entite par ailleurs technique serait une fuite inutile.
     * </p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "utilisateur_id")
    private User utilisateur;

    /** Durée de l'appel en millisecondes, succès ou échec. */
    @Column(name = "duree_ms", nullable = false)
    private Long dureeMs;

    /** Issue de l'appel : {@code false} si une exception a été levée. */
    @Column(name = "succes", nullable = false)
    private Boolean succes;

    /**
     * Type d'erreur, nom court de l'exception levée.
     *
     * <p>
     * Nom de classe plutôt qu'un code : il reste informatif même si l'API
     * evolue. La valeur n'est jamais traduite par un code HTTP, qui
     *dependrait de la couche HTTP et perdrait sa valeur de diagnostic.
     * </p>
     */
    @Column(name = "type_erreur", length = 50)
    private String typeErreur;

    /**
     * Message d'erreur, tronqué par le service.
     *
     * <p>
     * <code>TEXT</code> n'a pas de limite en base ; c'est le service qui borne
     * la longueur, car un message d'exception du SDK peut contenir une réponse
     * d'API entière. Le texte peut par ailleurs contenir une information
     * sensible issue du texte de l'utilisateur : la table reste un journal
     * technique, et le message est abrégé.
     * </p>
     */
    @Column(name = "message_erreur", columnDefinition = "TEXT")
    private String messageErreur;

    /** Tokens de l'invite, si l'API les a communiqués. */
    @Column(name = "tokens_prompt")
    private Integer tokensPrompt;

    /** Tokens de la completion, si l'API les a communiqués. */
    @Column(name = "tokens_completion")
    private Integer tokensCompletion;

    /** Total facturé, si l'API les a communiqués. */
    @Column(name = "tokens_total")
    private Integer tokensTotal;

    /**
     * Modèle utilisé.
     *
     * <p>
     * Conservé par appel et non dans une configuration : changer de modèle
     * ferait perdre l'historique des performances obtenues avec le précédent,
     * qui est précisément l'information cherchée en cas de dégradation.
     * </p>
     */
    @Column(name = "modele", nullable = false, length = 50)
    private String modele;

    /** Instant de création, alimenté par Hibernate. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}