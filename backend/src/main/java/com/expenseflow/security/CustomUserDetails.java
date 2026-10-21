package com.expenseflow.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.UserRole;

/**
 * Adaptateur entre l'entité {@link User} et le contrat Spring Security
 * {@link UserDetails}.
 *
 * <p>
 * L'adresse e-mail sert d'identifiant de connexion ({@code username}) et le rôle
 * applicatif ({@link UserRole}) est exposé sous la forme d'une autorité préfixée
 * par {@code ROLE_} (ex. {@code ROLE_EMPLOYEE}), convention attendue par
 * {@code hasRole(...)} et {@code @PreAuthorize}.
 * </p>
 *
 * <p>
 * MVP : les quatre indicateurs de statut de compte ({@code accountNonExpired},
 * {@code accountNonLocked}, {@code credentialsNonExpired}, {@code enabled})
 * renvoient toujours {@code true} — aucun workflow de désactivation de compte
 * n'existe dans le schéma actuel.
 * </p>
 */
public class CustomUserDetails implements UserDetails {

    private static final long serialVersionUID = 1L;

    private final User user;

    /**
     * Enveloppe l'entité utilisateur.
     *
     * @param user utilisateur chargé depuis la base
     */
    public CustomUserDetails(User user) {
        this.user = user;
    }

    /**
     * @return l'entité {@link User} sous-jacente (utile pour les services métier)
     */
    public User getUser() {
        return user;
    }

    /**
     * @return l'identifiant technique de l'utilisateur
     */
    public Long getId() {
        return user.getId();
    }

    /**
     * @return le rôle applicatif de l'utilisateur
     */
    public UserRole getRole() {
        return user.getRole();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getPassword() {
        return user.getMotDePasse();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
