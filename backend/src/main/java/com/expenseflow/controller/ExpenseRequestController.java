package com.expenseflow.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.dto.AuditLogResponse;
import com.expenseflow.dto.ExpenseRequestCreate;
import com.expenseflow.dto.ExpenseRequestResponse;
import com.expenseflow.dto.RejectRequest;
import com.expenseflow.dto.TransitionResponse;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.ExpenseStatus;
import com.expenseflow.security.CustomUserDetails;
import com.expenseflow.service.ExpenseRequestService;

import jakarta.validation.Valid;

/**
 * Points d'entrée REST des notes de frais.
 *
 * <p>
 * Les trois routes exigent simplement une authentification. Le contrôle fin —
 * savoir qui a le droit de consulter quelle note — est délégué au service, car il
 * dépend de l'identité du propriétaire, qui n'est connue qu'une fois la note
 * chargée. Une annotation {@code @PreAuthorize} serait ici incapable d'exprimer
 * cette règle sans dupliquer la logique métier dans le contrôleur.
 * </p>
 *
 * <p>
 * L'auteur des notes est toujours l'utilisateur authentifié : il provient du
 * jeton via {@code userDetails.getUser()}, jamais d'un champ du corps de la
 * requête.
 * </p>
 *
 * <p>
 * <strong>Workflow : pourquoi {@code @PreAuthorize("isAuthenticated()")} sur les
 * routes de transition.</strong> Une expression plus précise, du type
 * {@code hasRole('MANAGER')}, serait évaluée <em>avant</em> que le contrôleur ne
 * soit appelé, donc avant le chargement de la note : un EMPLOYEE recevrait 403
 * y compris pour une note inexistante. Impossible alors de renvoyer 404 à celui
 * qui a le droit de savoir si elle existe.
 * </p>
 *
 * <p>
 * Le rôle est donc vérifié dans le service ({@code exigerRole}), qui ordonne
 * correctement les refus : 403 pour le rôle, 404 pour l'absence, 409 pour l'état
 * incompatible. L'annotation ne garantit ici que le minimum — l'utilisateur est
 * authentifié.
 * </p>
 */
@RestController
@RequestMapping("/api/expense-requests")
public class ExpenseRequestController {

    private final ExpenseRequestService expenseRequestService;

    /**
     * @param expenseRequestService service des notes de frais
     */
    public ExpenseRequestController(ExpenseRequestService expenseRequestService) {
        this.expenseRequestService = expenseRequestService;
    }

    /**
     * Crée une note de frais à l'état DRAFT.
     *
     * @param request     lignes de la note ; l'auteur, le statut et le total
     *                    sont déterminés par le service
     * @param userDetails principal authentifié, auteur de la note
     * @return 201 Created avec la note enregistrée
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<ExpenseRequestResponse> create(
            @Valid @RequestBody ExpenseRequestCreate request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(expenseRequestService.create(request, userDetails.getUser()));
    }

    /**
     * Liste les notes de l'utilisateur authentifié.
     *
     * @param userDetails principal authentifié
     * @return 200 OK avec ses notes, de la plus récente à la plus ancienne
     */
    /**
     * Liste les notes de frais, selon le rôle de l'appelant.
     *
     * <p>
     * <strong>Deux usages distincts, un seul endpoint.</strong> Sans
     * {@code statut}, l'appelant obtient ses propres notes : c'est le
     * comportement d'origine, inchange. Avec {@code statut}, il interroge la file
     * de validation correspondant a ce qu'il a le droit de valider, ce qui
     * suppose un controle de role dans le service.
     * </p>
     *
     * <p>
     * <strong>Pourquoi ne pas creer une URL separee</strong> ({@code /pending})
     * : chaque file de validation aurait alors son propre controleur, alors que
     * seul le statut distingue les deux. Un parametre rend la regle visible a la
     * lecture de l'URL — {@code ?statut=PENDING_FINANCE} se lit sans connaitre
     * l'implémentation.
     * </p>
     *
     * <p>
     * FIXME: paragraphe périmé — le filtrage par équipe (users.manager_id) a
     * été ajouté depuis ; un MANAGER ne voit QUE les notes de son équipe
     * (voir ExpenseRequestService.findByStatut). Seul le rôle détermine
     * toujours quels statuts sont lisibles.
     * </p>
     *
     * @param statut      statut a filtrer ; absent pour lister ses propres notes
     * @param userDetails utilisateur authentifie
     * @return 200 avec les notes correspondant a la demande
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<List<ExpenseRequestResponse>> findMyRequests(
            @RequestParam(required = false) ExpenseStatus statut,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        User currentUser = userDetails.getUser();

        if (statut == null) {
            return ResponseEntity.ok(expenseRequestService.findMyRequests(currentUser));
        }

        return ResponseEntity.ok(expenseRequestService.findByStatut(statut, currentUser));
    }

    /**
     * Récupère une note par son identifiant.
     *
     * @param id          identifiant de la note
     * @param userDetails principal authentifié
     * @return 200 OK avec la note ; 404 si elle n'existe pas ;
     *         403 si l'appelant n'est ni propriétaire, ni manager, ni finance
     */
    /**
     * Modifie une note de frais en brouillon.
     *
     * <p>
     * Le corps reprend exactement le format de création : mêmes champs, mêmes
     * règles de validation. Aucun DTO distinct n'est nécessaire, et un format
     * divergent obligerait le client à deux champs de construction.
     * </p>
     *
     * @param id          identifiant de la note
     * @param request     nouvelles lignes
     * @param userDetails propriétaire de la note
     * @return la note modifiée
     */
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{id}")
    public ResponseEntity<ExpenseRequestResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody ExpenseRequestCreate request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(
                expenseRequestService.update(id, request, userDetails.getUser()));
    }

    /**
     * Historique des changements d'état d'une note de frais.
     *
     * <p>
     * Déclaré <strong>avant</strong> {@code /{id}} : le routeur matche dans
     * l'ordre, et un {@code /{id}} placé plus haut absorberait le chemin
     * {@code /7/audit} en tentant de convertir « audit » en identifiant.
     * </p>
     *
     * @param id          identifiant de la note
     * @param userDetails utilisateur authentifié
     * @return transitions, de la plus ancienne à la plus récente
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}/audit")
    public ResponseEntity<List<AuditLogResponse>> auditHistory(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(expenseRequestService.getAuditHistory(id, userDetails.getUser()));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public ResponseEntity<ExpenseRequestResponse> findById(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(expenseRequestService.findById(id, userDetails.getUser()));
    }

    // =====================================================================
    // Workflow : les cinq transitions
    //
    // Toutes répondent 200 avec un TransitionResponse minimal, y compris
    // quand la transition produit deux changements d'état : la réponse
    // décrit l'état final, qui est le seul que l'appelant constate.
    // =====================================================================

    /**
     * Soumet une note au circuit d'approbation.
     *
     * <p>
     * Réservée au propriétaire : c'est le contrôleur d'identité, pas de rôle.
     * </p>
     *
     * @param id          identifiant de la note
     * @param userDetails auteur de la note
     * @return état atteint après soumission
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/submit")
    public ResponseEntity<TransitionResponse> submit(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(expenseRequestService.submit(id, userDetails.getUser()));
    }

    /**
     * Approuve une note au niveau manager.
     *
     * @param id          identifiant de la note
     * @param userDetails manager ou finance
     * @return état atteint, PENDING_FINANCE
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/approve")
    public ResponseEntity<TransitionResponse> approve(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(expenseRequestService.approve(id, userDetails.getUser()));
    }

    /**
     * Rejette une note au niveau manager.
     *
     * @param id          identifiant de la note
     * @param request     motif du rejet, obligatoire
     * @param userDetails manager ou finance
     * @return état atteint, REJECTED_MANAGER
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/reject")
    public ResponseEntity<TransitionResponse> reject(
            @PathVariable Long id,
            @Valid @RequestBody RejectRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(
                expenseRequestService.reject(id, userDetails.getUser(), request.commentaire()));
    }

    /**
     * Marque une note comme remboursée.
     *
     * @param id          identifiant de la note
     * @param userDetails administrateur finance
     * @return état atteint, REIMBURSED
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/reimburse")
    public ResponseEntity<TransitionResponse> reimburse(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(expenseRequestService.reimburse(id, userDetails.getUser()));
    }

    /**
     * Rejette une note au niveau finance.
     *
     * @param id          identifiant de la note
     * @param request     motif du rejet, obligatoire
     * @param userDetails administrateur finance
     * @return état atteint, REJECTED_FINANCE
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/reject-finance")
    public ResponseEntity<TransitionResponse> rejectFinance(
            @PathVariable Long id,
            @Valid @RequestBody RejectRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(
                expenseRequestService.rejectFinance(id, userDetails.getUser(), request.commentaire()));
    }
}