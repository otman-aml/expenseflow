package com.expenseflow.dto;

import java.time.Instant;

/**
 * Mesures d'usage de la brique d'extraction.
 *
 * <p>
 * <strong>Les compteurs de tokens sont separes du total historique</strong> :
 * le total cumule sert a la reconstitution de la facture, le total sur 24 h sert
 * au suivi de tendance. Les confondre ne permettrait ni l'un ni l'autre.
 * </p>
 *
 * <p>
 * Un {@code null} sur {@link #dureeMoyenneMs} ou {@link #tokensTotal} signifie
 * « aucune donnee » et non « zéro » : les deux se distinguent en pratique, le
 * premier avant tout appel réussi, le second avant que l'API ne communique
 * ses compteurs.
 * </p>
 *
 * @param totalAppels      nombre total d'appels journalises
 * @param appelsReussis    nombre d'appels ayant abouti
 * @param appelsEchoues    nombre d'appels ayant echoue
 * @param dureeMoyenneMs   duree moyenne des appels reussis, ou {@code null}
 * @param tokensTotal      tokens cumules, ou {@code null} si jamais communiques
 * @param tokens24h        tokens des dernieres 24 heures
 * @param dernierAppel     date du dernier appel, ou {@code null} si journal vide
 */
public record AiStatsResponse(
        long totalAppels,
        long appelsReussis,
        long appelsEchoues,
        Double dureeMoyenneMs,
        Long tokensTotal,
        long tokens24h,
        Instant dernierAppel
) {

    /**
     * Taux de reussite, en pourcentage.
     *
     * <p>
     * <strong>{@code 0} sur un journal vide</strong> plutot qu'une division
     * par zero. Un taux de 100 % serait un mensonge : aucun appel n'a
     * reussi parce qu'aucun n'a eu lieu.
     * </p>
     *
     * @return pourcentage de reussite, arrondi a une décimale
     */
    public double tauxReussite() {
        if (totalAppels == 0) {
            return 0d;
        }
        return Math.round(appelsReussis * 1000d / totalAppels) / 10d;
    }
}