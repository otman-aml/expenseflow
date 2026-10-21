package com.expenseflow.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.expenseflow.entity.enums.UserRole;

/**
 * Statistiques d'entreprise pour le tableau de bord administrateur.
 *
 * <p>
 * <strong>La vue porte sur toute la base, pas sur l'appelant.</strong>
 * C'est la difference avec les compteurs cotes de la vue employe : un
 * ADMIN pilote l'application entiere, et ses indicateurs doivent couvrir
 * tous les comptes, toutes les notes et tous les appels IA.
 * </p>
 *
 * @param totalUsers    nombre total de comptes
 * @param usersByRole   comptage par role — les quatre roles de
 *                      {@link UserRole} y figurent toujours, valant 0
 *                      s'ils sont absents de la table
 * @param totalExpenses nombre total de notes de frais, tous statuts
 * @param totalAiCalls  appels IA des 30 derniers jours
 * @param recentActions cinq dernieres actions d'administration des comptes,
 *                      de la plus recente a la plus ancienne
 */
public record AdminStatsResponse(
        long totalUsers,
        Map<UserRole, Long> usersByRole,
        long totalExpenses,
        long totalAiCalls,
        List<AdminActionResponse> recentActions) {

    /**
     * Une ligne du journal des actions de compte.
     *
     * @param date   horodatage de l'action ({@code audit_log.date_action})
     * @param action action brute ({@code COMPTE_CREE}, {@code COMPTE_MODIFIE},
     *               {@code COMPTE_SUPPRIME})
     * @param cible  compte concerne tel qu'il figure dans
     *               {@code audit_log.details} — e-mail et role. La cible
     *               supprimee n'a plus de ligne users a resolver : ce
     *               texte reste la seule source fidele.
     */
    public record AdminActionResponse(Instant date, String action, String cible) {
    }
}
