package com.expenseflow.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.expenseflow.entity.ExpenseRequest;
import com.expenseflow.entity.enums.ExpenseStatus;

/**
 * Représentation publique d'une note de frais.
 *
 * <p>
 * Les lignes sont imbriquées dans la réponse : le front affiche le détail d'une
 * note dans un écran unique, et cela évite un appel supplémentaire par note.
 * </p>
 *
 * @param id             identifiant de la note
 * @param employeeId     identifiant de l'auteur
 * @param employeeNom    nom de l'auteur
 * @param statut         état courant dans la machine à états
 * @param dateSoumission date de soumission, {@code null} tant que la note est en DRAFT
 * @param totalMontant   somme des montants des lignes, calculée par le service
 * @param lignes         lignes de dépense de la note
 * @param createdAt      date de création
 * @param updatedAt      date de dernière mise à jour
 */
public record ExpenseRequestResponse(
        Long id,
        Long employeeId,
        String employeeNom,
        ExpenseStatus statut,
        Instant dateSoumission,
        BigDecimal totalMontant,
        List<ExpenseLineResponse> lignes,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Construit une instance {@link ExpenseRequestResponse} à partir d'une
     * entité {@link ExpenseRequest}.
     *
     * <p>
     * Les associations {@code employee}, {@code categorie} (des lignes) et
     * {@code lignes} sont toutes résolues dans la transaction du service, le
     * repository les chargeant explicitement par {@code JOIN FETCH}. Une
     * conversion hors transaction lèverait une
     * {@code LazyInitializationException}.
     * </p>
     *
     * @param expenseRequest l'entité source
     * @return le DTO correspondant, ou {@code null} si l'entité est {@code null}
     */
    public static ExpenseRequestResponse from(ExpenseRequest expenseRequest) {
        if (expenseRequest == null) {
            return null;
        }
        return new ExpenseRequestResponse(
                expenseRequest.getId(),
                expenseRequest.getEmployee().getId(),
                expenseRequest.getEmployee().getNom(),
                expenseRequest.getStatut(),
                expenseRequest.getDateSoumission(),
                expenseRequest.getTotalMontant(),
                expenseRequest.getLignes().stream()
                        .map(ExpenseLineResponse::from)
                        .toList(),
                expenseRequest.getCreatedAt(),
                expenseRequest.getUpdatedAt()
        );
    }
}