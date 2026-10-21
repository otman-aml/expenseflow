package com.expenseflow.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

/**
 * Données d'entrée de la création d'une note de frais.
 *
 * <p>
 * Le corps ne contient <strong>que</strong> les lignes. Trois éléments sont
 * délibérément absents du payload, et jamais acceptés depuis le client :
 * </p>
 * <ul>
 * <li><strong>{@code employeeId}</strong> : l'auteur est l'utilisateur
 * authentifié. L'accepter permettrait de créer une note au nom d'autrui — la même
 * classe d'escalade que le champ {@code role} en inscription.</li>
 * <li><strong>{@code statut}</strong> : fixé à {@code DRAFT} par le service. Les
 * transitions d'état relèvent de la machine à états, pas du client.</li>
 * <li><strong>{@code totalMontant}</strong> : c'est la somme des lignes, donc une
 * donnée dérivée. Le laisser saisissable rendrait le total incohérent avec le
 * détail affiché.</li>
 * </ul>
 *
 * @param lignes lignes de la note ; au moins une, sinon la note n'a aucun sens
 *
 * <p>
 * <strong>Message explicite</strong> : sans lui, Jakarta renvoie
 * {@code must not be empty}, qui ne dit pas ce qu'il faut faire.
 * </p>
 */
public record ExpenseRequestCreate(
        @NotEmpty(message = "Ajoutez au moins une ligne de dépense")
        @Valid
        List<ExpenseLineRequest> lignes
) {
}