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

import com.expenseflow.dto.AdminUserResponse;
import com.expenseflow.dto.CreateUserRequest;
import com.expenseflow.dto.UpdateUserRequest;
import com.expenseflow.security.CustomUserDetails;
import com.expenseflow.service.AdminUserService;

import jakarta.validation.Valid;

/**
 * Points d'entrée REST de l'administration des comptes.
 *
 * <p>
 * <strong>La sécurité est déclarée UNE fois, sur la classe</strong> :
 * {@code @PreAuthorize("hasRole('ADMIN')")} au niveau de la classe couvre
 * les cinq routes, y compris celles ajoutées ultérieurement — oublier
 * l'annotation sur un seul handler produirait une route ouverte au lieu
 * d'un échec visible.
 * </p>
 *
 * <p>
 * Le seul identifiant transmis est celui du compte ciblé ({@code {id}}) :
 * le destinataire de l'opération est le compte lui-même, jamais le porteur
 * du jeton. {@code DELETE} accepte néanmoins l'appelant pour l'auto-refus —
 * le service interdit à un admin de se supprimer lui-même.
 * </p>
 */
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    /**
     * @param adminUserService service d'administration des comptes
     */
    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /**
     * Crée un compte avec le rôle et le manager demandés.
     *
     * @param request corps valide (nom, e-mail, mot de passe, rôle, manager)
     * @return 201 Created avec le compte créé
     */
    @PostMapping
    public ResponseEntity<AdminUserResponse> createUser(@Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminUserService.createUser(request, currentUser.getUser()));
    }

    /**
     * Liste tous les comptes.
     *
     * @return la liste complète des comptes
     */
    @GetMapping
    public ResponseEntity<List<AdminUserResponse>> listUsers() {
        return ResponseEntity.ok(adminUserService.listUsers());
    }

    /**
     * Liste les comptes éligibles au rôle de manager.
     *
     * <p>
     * Déclaré <em>avant</em> {@code /{id}} : Spring résout les chemins
     * littéraux en priorité, mais l'ordre de déclaration lève toute
     * ambiguïté — sans lui, {@code managers} pourrait être lu comme un
     * identifiant et échouer en 400.
     * </p>
     *
     * @return tous les MANAGER
     */
    @GetMapping("/managers")
    public ResponseEntity<List<AdminUserResponse>> listManagers() {
        return ResponseEntity.ok(adminUserService.listManagers());
    }

    /**
     * Modifie nom, e-mail, rôle et manager d'un compte.
     *
     * @param id      identifiant du compte
     * @param request corps valide
     * @return 200 OK avec le compte modifié
     */
    @PutMapping("/{id}")
    public ResponseEntity<AdminUserResponse> updateUser(@PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        return ResponseEntity.ok(adminUserService.updateUser(id, request, currentUser.getUser()));
    }

    /**
     * Supprime un compte sans note.
     *
     * @param id          identifiant du compte
     * @param currentUser administrateur appelant (auto-refus possible)
     * @return 204 No Content ; 404 si absent, 409 s'il a des notes, 403 auto-suppression
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        adminUserService.deleteUser(id, currentUser.getUser());
        return ResponseEntity.noContent().build();
    }
}