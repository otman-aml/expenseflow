package com.expenseflow.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;

import com.expenseflow.dto.AuthResponse;
import com.expenseflow.dto.LoginRequest;
import com.expenseflow.dto.RegisterRequest;
import com.expenseflow.dto.UserResponse;
import com.expenseflow.security.CustomUserDetails;
import com.expenseflow.service.AuthService;

import jakarta.validation.Valid;

/**
 * Points d'entrée REST de l'authentification.
 *
 * <p>
 * Les routes {@code /api/auth/**} sont publiques ({@code permitAll} dans
 * {@code SecurityConfig}) : l'inscription et la connexion ne peuvent pas exiger un
 * jeton. En revanche, {@code /me} exige un jeton valide — c'est
 * {@code CustomAuthenticationEntryPoint} qui renvoie le 401 JSON si l'en-tête
 * {@code Authorization} est absent, expiré ou corrompu.
 * </p>
 *
 * <p>
 * Le contrôleur reste volontairement mince : validation des entrées
 * ({@code @Valid}), délégation au service, statut HTTP. Aucune règle métier ni
 * aucun accès à la base n'est effectué ici.
 * </p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    /**
     * @param authService service d'authentification
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Inscription d'un nouvel utilisateur.
     *
     * <p>
     * {@code @SecurityRequirements} vide : l'exigence {@code bearerAuth} est
     * declaree globalement dans {@code OpenApiConfig}. Sans cet
     * surcharges, Swagger afficherait un cadenas sur ces deux operations
     * alors qu'elles n'exigent aucun jeton — et l'utilisateur cherchant
     * pourquoi devrait fournir un jeton pour s'inscrire.
     * </p>
     *
     * @param request données d'inscription, validées par Jakarta Validation
     * @return 201 Created avec le jeton et le profil créé
     */
    @SecurityRequirements
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    /**
     * Connexion et émission du jeton d'accès.
     *
     * <p>
     * Meme surcharge que {@code register} : exiger un jeton pour obtenir un
     * jeton serait circulaire dans l'interface de documentation.
     * </p>
     *
     * @param request identifiants, validés par Jakarta Validation
     * @return 200 OK avec le jeton et le profil
     */
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * Profil de l'utilisateur associé au jeton présenté.
     *
     * @param userDetails principal authentifié par {@code JwtAuthenticationFilter}
     * @return 200 OK avec le profil courant
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(authService.me(userDetails));
    }
}