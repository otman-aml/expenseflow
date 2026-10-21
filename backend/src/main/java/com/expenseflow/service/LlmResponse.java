package com.expenseflow.service;

/**
 * Reponse d'une completion, accompagnee de ses compteurs de tokens.
 *
 * <p>
 * <strong>Les compteurs sont nullable</strong> : tous les fournisseurs ne les
 * fournissent pas, et {@code null} signifie « non communique » alors que 0
 * signifierait « appel sans cout », qui n'existe pas.
 * </p>
 *
 * <p>
 * <strong>Le nommage est neutre</strong> ({@code content}, {@code tokensPrompt})
 * plutot que traduit. Ces identifiants reprennent les noms de la forme OpenAI,
 * que tous les fournisseurs compatibles reprennent : ils font reference a un
 * contrat externe, et non a une traduction de ce contrat.
 * </p>
 *
 * @param content           texte produit par le modele
 * @param tokensPrompt      tokens de l'invite, ou {@code null}
 * @param tokensCompletion  tokens de la reponse, ou {@code null}
 * @param tokensTotal       total facture, ou {@code null}
 */
public record LlmResponse(String content, Integer tokensPrompt, Integer tokensCompletion,
        Integer tokensTotal) {
}
