package com.expenseflow.entity;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
 * Journal d'audit : trace les actions réalisées sur les entités métier.
 * Table SQL : {@code audit_log}.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(exclude = "utilisateur")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Nom logique de l'entité concernée (ex. {@code ExpenseRequest}). */
    @Column(name = "entite_concernee", nullable = false, length = 100)
    private String entiteConcernee;

    /**
     * Identifiant de l'entité concernée au sein de son type.
     *
     * <p>
     * Ajouté par la migration {@code V2}. La colonne est
     * <strong>nullable</strong> : le journal est générique et toutes les
     * entités ne sont pas identifiées par un identifiant numérique. La
     * combinaison (entiteConcernee, entiteId) est ce qui cible l'historique
     * d'un objet — le nom seul est insuffisant, puisqu'il est partagé par
     * toutes les notes.
     * </p>
     */
    @Column(name = "entite_id")
    private Long entiteId;

    /** Action réalisée (ex. {@code SUBMIT}, {@code APPROVE}). */
    @Column(name = "action", nullable = false, length = 100)
    private String action;

    /** Auteur de l'action. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "utilisateur_id", nullable = false)
    private User utilisateur;

    /**
     * Date de l'action. Valeur pilotée par la base (DEFAULT NOW()) :
     * la colonne n'est ni insérée ni mise à jour par Hibernate.
     */
    @Column(name = "date_action", nullable = false, insertable = false, updatable = false)
    private Instant dateAction;

    @Column(name = "details")
    private String details;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;
}
