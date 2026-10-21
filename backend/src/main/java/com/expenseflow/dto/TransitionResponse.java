package com.expenseflow.dto;

import java.time.Instant;

import com.expenseflow.entity.enums.ExpenseStatus;

/**
 * Réponse à une transition d'état.
 *
 * <p>
 * <strong>Volontairement minimale</strong>, contrairement aux autres endpoints
 * qui renvoient un {@code ExpenseRequestResponse} complet. Une transition est
 * une opération d'écriture dont l'appelant cherche à savoir deux choses : la
 * note concernée, et son nouvel état. Renvoyer toutes les lignes et tous les
 * totaux ferait transiter un payload sans usage, et le front serait tenté de
 * le stocker comme s'il s'agissait d'une lecture fraîche.
 * </p>
 *
 * <p>
 * L'horodatage est renvoyé pour permettre au client d'afficher « mis à jour
 * il y a X » sans refaire un GET.
 * </p>
 *
 * @param id        identifiant de la note
 * @param statut    nouvel état de la note
 * @param updatedAt horodatage de la transition
 */
public record TransitionResponse(
        Long id,
        ExpenseStatus statut,
        Instant updatedAt
) {
}