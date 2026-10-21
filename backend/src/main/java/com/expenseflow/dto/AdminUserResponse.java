package com.expenseflow.dto;

import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.UserRole;

/**
 * Représentation publique d'un compte, vue administration.
 *
 * <p>
 * {@code managerNom} est déni-normalisé comme dans les autres DTO du
 * projet : l'écran d'administration affiche « Manager Demo » sans appel
 * supplémentaire par ligne. Le {@code managerId} reste l'autorité pour les
 * écritures — le nom n'est qu'un libellé d'affichage, susceptible de
 * changer sans invalider l'identifiant.
 * </p>
 *
 * <p>
 * Le mot de passe n'apparaît jamais, sous aucune forme — ni hashé. Un hash
 * BCrypt exposé serait une attaque à coût constant hors ligne.
 * </p>
 *
 * @param id          identifiant du compte
 * @param nom         nom complet
 * @param email       adresse e-mail
 * @param role        rôle applicatif
 * @param managerId   identifiant du manager, ou {@code null}
 * @param managerNom  nom du manager, ou {@code null} s'il n'y en a pas
 */
public record AdminUserResponse(
        Long id,
        String nom,
        String email,
        UserRole role,
        Long managerId,
        String managerNom
) {

    /**
     * Convertit un compte en réponse d'administration.
     *
     * <p>
     * La lecture paresseuse de {@code manager} est sûre ici : les listes du
     * service sont chargées avec un {@code JOIN FETCH} (une seule requête
     * par liste), et la conversion a lieu dans la transaction.
     * </p>
     *
     * @param user compte à représenter
     * @return réponse correspondante
     */
    public static AdminUserResponse from(User user) {
        User manager = user.getManager();
        return new AdminUserResponse(
                user.getId(),
                user.getNom(),
                user.getEmail(),
                user.getRole(),
                manager != null ? manager.getId() : null,
                manager != null ? manager.getNom() : null);
    }
}