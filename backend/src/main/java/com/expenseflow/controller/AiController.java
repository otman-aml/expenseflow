package com.expenseflow.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.dto.AiStatsResponse;
import com.expenseflow.dto.ExtractExpenseRequest;
import com.expenseflow.dto.ExtractExpenseResponse;
import com.expenseflow.security.CustomUserDetails;
import com.expenseflow.service.AiCallLogService;
import com.expenseflow.service.AiExtractionService;
import com.expenseflow.service.LlmClient;

import jakarta.validation.Valid;

/**
 * Points d'entree de la brique IA.
 *
 * <p>
 * <strong>{@code /test} est temporaire</strong> et doit disparaitre a la fin de
 * la Phase 3. {@code /extract-expense} et {@code /stats} sont les points
 * d'entree destines a la production.
 * </p>
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final LlmClient llmClient;
    private final AiExtractionService extractionService;
    private final AiCallLogService aiCallLogService;

    /**
     * @param llmClient     client d'appel au fournisseur de completion
     * @param extractionService service d'extraction d'une depense
     * @param aiCallLogService  journal d'exploitation de la brique
     */
    public AiController(LlmClient llmClient, AiExtractionService extractionService,
            AiCallLogService aiCallLogService) {
        this.llmClient = llmClient;
        this.extractionService = extractionService;
        this.aiCallLogService = aiCallLogService;
    }

    /**
     * Mesures d'usage de la brique d'extraction.
     *
     * <p>
     * <strong>Reserve au role FINANCE_ADMIN.</strong> Ces chiffres revelent le
     * volume et le cout de l'API pour l'organisation ; chaque employe n'a pas a
     * les connaitre, et le role finance est celui qui a competence sur le
     * budget. La restriction s'appuie sur une annotation, donc elle est
     * reelle : contrairement aux ecrans front, elle n'est pas contournable.
     * </p>
     *
     * @return mesures courantes
     */
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    @GetMapping("/stats")
    public ResponseEntity<AiStatsResponse> stats() {
        return ResponseEntity.ok(aiCallLogService.getStats());
    }

    /**
     * Extrait une depense depuis un texte en langage naturel.
     *
     * <p>
     * Le retour est une <em>pre-remplissage</em>, pas une creation : la note
     * n'est enregistree que si l'utilisateur valide le formulaire. Le modele
     * n'a donc aucun droit d'ecrire en base.
     * </p>
     *
     * <p>
     * L'utilisateur du jeton est transmis au service, qui le journalise : sans
     * cela, la consommation de l'API ne serait attribuable a personne et la
     * colonne {@code utilisateur_id} resterait toujours nulle.
     * </p>
     *
     * @param request     texte decrivant la depense
     * @param userDetails utilisateur authentifie, auteur de l'appel
     * @return ligne pre-remplie
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/extract-expense")
    public ResponseEntity<ExtractExpenseResponse> extractExpense(
            @Valid @RequestBody ExtractExpenseRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(extractionService.extract(request.texte(), userDetails.getUser()));
    }

    /**
     * Verifie que l'appel au fournisseur fonctionne, en renvoyant le texte produit.
     *
     * <p>
     * Le prompt demande explicitement un JSON : c'est le mode qui sera utilise
     * par l'extraction, et le tester par un « Dis bonjour » masquerait un
     * {@code response_format} mal transmis — c'est-a-dire un 400 du fournisseur
     * difficile a diagnostiquer.
     * </p>
     *
     * @return reponse brute produite par le modele
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/test")
    public ResponseEntity<TestResponse> test() {
        String reponse = llmClient.chatCompletion(
                "Reponds uniquement par un objet JSON valide contenant une cle \"message\".");

        return ResponseEntity.ok(new TestResponse(reponse));
    }

    /**
     * Enveloppe de reponse du point de test.
     *
     * @param reponse texte produit par le modele
     */
    public record TestResponse(String reponse) {
    }
}