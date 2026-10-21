package com.expenseflow.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.dto.ChangePasswordRequest;
import com.expenseflow.dto.UpdateProfileRequest;
import com.expenseflow.dto.UserResponse;
import com.expenseflow.security.CustomUserDetails;
import com.expenseflow.service.UserProfileService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Profil de l'utilisateur connecté.
 *
 * <p>
 * <strong>Trois routes, toutes en {@code /me}</strong> : aucune ne prend
 * d'identifiant en paramètre. Le compte visé est toujours celui du porteur
 * du jeton, déduit de {@link CustomUserDetails}. Un {@code GET /api/users/42}
 * auraità un utilisateur de lire — ou pire, de modifier — le profil
 * d'autrui ; en l'absence de vérification supplémentaire, l'identifiant de
 * l'URL ferait autorité. Aucune donnée du corps n'est non plus utilisée pour
 * désigner la cible.
 * </p>
 *
 * <p>
 * <strong>Le contrôleur ne fait que déléger.</strong> Normalisation,
 * unicité et contrôle du mot de passe appartiennent au service ; ici on se
 * contente de nommer l'opération et de choisir le code de retour.
 * </p>
 *
 * <p>
 * <strong>{@code @PreAuthorize("isAuthenticated()")}</strong> est ici
 * explicite alors que {@code .anyRequest().authenticated()} couvre déjà ces
 * routes : il documente l'intention au même endroit que les autres
 * contrôleurs, qui annotent aussi leurs endpoints {@code /me}.
 * </p>
 */
@Tag(name = "Profil", description = "Informations personnelles et mot de passe")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserProfileService userProfileService;

    /**
     * @param userProfileService opérations sur le profil
     */
    public UserController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    /**
     * Profil courant.
     *
     * @param userDetails principal authentifié
     * @return nom, e-mail et rôle
     */
    @Operation(summary = "Consulter son profil")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMe(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(userProfileService.getProfile(userDetails.getId()));
    }

    /**
     * Met à jour le nom et l'e-mail.
     *
     * @param userDetails principal authentifié
     * @param request     nouveau nom et nouvel e-mail
     * @return le profil enregistré
     */
    @Operation(summary = "Modifier son nom et son e-mail")
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateMe(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userProfileService.updateProfile(userDetails.getId(), request));
    }

    /**
     * Change le mot de passe.
     *
     * <p>
     * <strong>204 et non 200.</strong> Aucun corps n'est renvoyé : renvoyer
     * le profil ou un message de succès obligerait le client à le parser,
     * alors qu'il n'a rien à en faire et risque d'afficher ce texte à
     * l'utilisateur comme si c'était une donnée.
     * </p>
     *
     * <p>
     * <strong>Un ancien mot de passe erroné produit un 400</strong>, pas un
     * 401 : la session reste ouverte, l'utilisateur corrige sa saisie. Voir
     * {@code InvalidPasswordException}.
     * </p>
     *
     * @param userDetails principal authentifié
     * @param request     ancien, nouveau et confirmation
     * @return 204 sans corps
     */
    @Operation(summary = "Changer son mot de passe")
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/me/password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest request) {
        userProfileService.changePassword(userDetails.getId(), request);
        return ResponseEntity.noContent().build();
    }
}