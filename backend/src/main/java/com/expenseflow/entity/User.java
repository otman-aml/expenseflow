package com.expenseflow.entity;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.expenseflow.entity.enums.UserRole;

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
 * Utilisateur de l'application (employé, manager ou administrateur finance).
 * Table SQL : {@code users}.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(exclude = "manager")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "nom", nullable = false, length = 150)
    private String nom;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "mot_de_passe", nullable = false, length = 255)
    private String motDePasse;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "role", nullable = false)
    private UserRole role;

    /**
     * Mot de passe provisoire a remplacer a la premiere connexion.
     * Nait a {@code true} chez {@code AdminUserService.createUser}, retombe a
     * {@code false} chez {@code UserProfileService.changePassword}. Voir la
     * migration V6.
     */
    @Column(name = "doit_changer_mdp", nullable = false)
    private boolean doitChangerMdp;

    /**
     * Manager hiérarchique (auto-référence). Chaîne d'approbation mono-niveau :
     * ce manager valide en premier, puis la finance rembourse.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private User manager;

    /**
     * Colonne SQL {@code TIMESTAMPTZ} alimentee par le {@code DEFAULT NOW()} de
     * PostgreSQL ({@code insertable = false}).
     *
     * <p>
     * Le type est {@link Instant} et non {@code LocalDateTime} : la colonne est
     * declaree {@code TIMESTAMPTZ}, donc PostgreSQL la restitue en
     * {@code Instant}. Mapper sur {@code LocalDateTime} provoquerait un
     * {@code Could not convert 'java.time.Instant' to 'java.time.LocalDateTime'}
     * a la lecture, c'est-a-dire a chaque rechargement de l'utilisateur.
     * Aucun {@code @JdbcTypeCode} n'est necessaire : Hibernate mappe
     * nativement {@link Instant} sur le type {@code timestamp}.
     * </p>
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * @see #getCreatedAt()
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;
}
