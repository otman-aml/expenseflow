package com.expenseflow.dto;

/**
 * Demande de soumission d'une note de frais.
 *
 * <p>
 * <strong>Record volontairement vide.</strong> Aucun paramètre n'est nécessaire :
 * l'auteur est déduit du jeton, le statut est fixé par le workflow, et le total
 * a déjà été calculé à la création. Le corps de la requête est donc vide.
 * </p>
 *
 * <p>
 * La classe existe pour que l'endpoint puisse accepter un corps JSON vide
 * ({@code {}}) sans erreur, et pour que l'intention « soumission, sans
 * paramètre » soit explicite dans la signature du contrôleur.
 * </p>
 */
public record SubmitRequest() {
}