package com.expenseflow.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.dto.BudgetLimitRequest;
import com.expenseflow.dto.BudgetLimitResponse;
import com.expenseflow.security.CustomUserDetails;
import com.expenseflow.service.BudgetLimitService;

import jakarta.validation.Valid;

/**
 * Points d'entrée REST des plafonds budgétaires.
 *
 * <p>
 * Deux niveaux de lecture coexistent volontairement :
 * </p>
 * <ul>
 * <li>{@code GET /api/budget-limits} renvoie la grille <strong>complète</strong>,
 * tous rôles confondus, et est réservée au FINANCE_ADMIN qui doit administrer
 * l'ensemble des plafonds.</li>
 * <li>{@code GET /api/budget-limits/me} renvoie uniquement les plafonds du
 * <strong>rôle de l'appelant</strong>, extrait du jeton. Cette restriction évite
 * qu'un employé découvre les montants accordés aux autres rôles, qui ne le
 * concernent pas.</li>
 * </ul>
 *
 * <p>
 * L'écriture est dans tous les cas réservée au FINANCE_ADMIN.
 * </p>
 */
@RestController
@RequestMapping("/api/budget-limits")
public class BudgetLimitController {

    private final BudgetLimitService budgetLimitService;

    /**
     * @param budgetLimitService service des plafonds
     */
    public BudgetLimitController(BudgetLimitService budgetLimitService) {
        this.budgetLimitService = budgetLimitService;
    }

    /**
     * Liste tous les plafonds, tous rôles confondus.
     *
     * @return la grille complète des plafonds
     */
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    @GetMapping
    public ResponseEntity<List<BudgetLimitResponse>> findAll() {
        return ResponseEntity.ok(budgetLimitService.findAll());
    }

    /**
     * Liste les plafonds applicables au rôle de l'appelant.
     *
     * <p>
     * Le rôle est lu depuis le principal authentifié — donc du jeton signé — et
     * non depuis un paramètre de requête : un client ne peut ainsi pas demander
     * les plafonds d'un autre rôle en forçant un identifiant.
     * </p>
     *
     * @param userDetails principal authentifié placé dans le {@code SecurityContext}
     * @return les plafonds du rôle de l'appelant
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    public ResponseEntity<List<BudgetLimitResponse>> findMine(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(budgetLimitService.findForCurrentUserRole(userDetails.getRole()));
    }

    /**
     * Crée un plafond pour un couple (catégorie, rôle).
     *
     * @param request catégorie, rôle et montant
     * @return 201 Created avec le plafond créé
     */
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    @PostMapping
    public ResponseEntity<BudgetLimitResponse> create(@Valid @RequestBody BudgetLimitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(budgetLimitService.create(request));
    }

    /**
     * Met à jour un plafond existant.
     *
     * @param id      identifiant du plafond à modifier
     * @param request nouvelles valeurs
     * @return le plafond mis à jour
     */
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<BudgetLimitResponse> update(@PathVariable Long id,
            @Valid @RequestBody BudgetLimitRequest request) {
        return ResponseEntity.ok(budgetLimitService.update(id, request));
    }

    /**
     * Supprime un plafond.
     *
     * @param id identifiant du plafond à supprimer
     * @return 204 No Content
     */
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteById(@PathVariable Long id) {
        budgetLimitService.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}