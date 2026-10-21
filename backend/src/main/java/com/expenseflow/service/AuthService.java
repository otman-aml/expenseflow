package com.expenseflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.dto.AuthResponse;
import com.expenseflow.dto.LoginRequest;
import com.expenseflow.dto.RegisterRequest;
import com.expenseflow.dto.UserResponse;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.exception.EmailAlreadyExistsException;
import com.expenseflow.exception.ResourceNotFoundException;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.CustomUserDetails;
import com.expenseflow.security.JwtService;

/**
 * Logique métier d'authentification : inscription, connexion, profil courant.
 *
 * <p>
 * Le mot de passe brut n'est jamais conservé en base ni renvoyé au client : il est
 * haché avec BCrypt via {@link PasswordEncoder} avant persistance, et les réponses
 * utilisent exclusivement {@link UserResponse}, qui ne contient aucun champ
 * sensible.
 * </p>
 *
 * <p>
 * La vérification des identifiants est déléguée à l'{@link AuthenticationManager}
 * (donc à {@code CustomUserDetailsService} + {@code PasswordEncoder}) : la logique
 * de comparaison des hachages n'est jamais dupliquée ici.
 * </p>
 */
// TODO (Phase 7) : endpoint /api/admin/users pour créer des comptes
// MANAGER/FINANCE_ADMIN, protégé par @PreAuthorize("hasRole('FINANCE_ADMIN')").
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Schéma d'authentification retourné au client, à préfixer dans l'en-tête Authorization. */
    private static final String BEARER_TYPE = "Bearer";

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final JwtService jwtService;

    private final AuthenticationManager authenticationManager;

    /** Durée de vie du jeton en millisecondes, alignée sur la valeur de {@code jwt.expiration}. */
    private final long expiration;

    /**
     * @param userRepository        dépôt des utilisateurs
     * @param passwordEncoder       encodeur BCrypt
     * @param jwtService            émission/vérification des jetons
     * @param authenticationManager vérificateur d'identifiants
     * @param expiration            durée de vie du jeton (ms)
     */
    public AuthService(UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthenticationManager authenticationManager,
            @Value("${jwt.expiration:3600000}") long expiration) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.expiration = expiration;
    }

    /**
     * Inscrit un nouvel utilisateur et renvoie immédiatement un jeton valide.
     *
     * <p>
     * Le rôle n'est pas lu dans la requête : il est imposé à
     * {@code EMPLOYEE} (voir le commentaire sur la ligne
     * {@code .role(UserRole.EMPLOYEE)}). Aucun champ du payload client ne peut
     * donc influer sur le rôle attribué.
     * </p>
     *
     * @param request données d'inscription validées par Jakarta Validation
     * @return jeton JWT et profil du nouvel utilisateur
     * @throws EmailAlreadyExistsException si l'e-mail est déjà utilisé
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();

        if (userRepository.findByEmail(email).isPresent()) {
            throw new EmailAlreadyExistsException(email);
        }

        User user = User.builder()
                .nom(request.nom().trim())
                .email(email)
                .motDePasse(passwordEncoder.encode(request.motDePasse()))
                // Rôle forcé à EMPLOYEE : le rôle ne doit JAMAIS être
                // auto-attribué depuis le payload client (escalade de
                // privilèges). Les comptes MANAGER/FINANCE_ADMIN seront créés
                // via un mécanisme d'administration dédié (Phase 7).
                .role(UserRole.EMPLOYEE)
                .build();

        User saved = userRepository.save(user);
        log.info("Inscription reussie pour l'utilisateur id={} role={}", saved.getId(), saved.getRole());

        return buildAuthResponse(new CustomUserDetails(saved));
    }

    /**
     * Vérifie les identifiants et renvoie un jeton JWT.
     *
     * <p>
     * L'{@link AuthenticationManager} lève une
     * {@code BadCredentialsException} (convertie en 401 par
     * {@code GlobalExceptionHandler}) si l'e-mail est inconnu ou le mot de passe
     * incorrect. Le message renvoyé est identique dans les deux cas afin de ne
     * pas indiquer quels comptes existent.
     * </p>
     *
     * <p>
     * L'authentification obtenue est replacée dans le {@code SecurityContext} :
     * l'état du requêteur reste ainsi authentifié pour le reste du traitement, au
     * même titre qu'une requête portée par un jeton.
     * </p>
     *
     * @param request e-mail et mot de passe
     * @return jeton JWT et profil de l'utilisateur
     */
    @Transactional(readOnly = true)
    // TODO: pas de refresh token — le jeton expire (1 h) et l'utilisateur est
    //       déconnecté sans prévenir, y compris au milieu d'une saisie. Un
    //       token d'accès court + refresh httpOnly réglerait le cas.
    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.motDePasse()));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        log.info("Connexion reussie pour {}", email);

        return buildAuthResponse(userDetails);
    }

    /**
     * Renvoie le profil de l'utilisateur associé au jeton présenté.
     *
     * <p>
     * L'authentification a déjà été réalisée par {@code JwtAuthenticationFilter} :
     * le contrôleur reçoit donc directement le principal. La lecture est faite en base
     * plutôt qu'en réutilisant l'entité en cache, afin de toujours refléter
     * l'état courant de la ressource.
     * </p>
     *
     * @param userDetails principal extrait du {@code SecurityContext}
     * @return profil de l'utilisateur, sans aucun champ sensible
     * @throws ResourceNotFoundException si l'utilisateur du jeton n'existe plus en base
     */
    @Transactional(readOnly = true)
    public UserResponse me(CustomUserDetails userDetails) {
        User user = userDetails.getUser();

        return userRepository.findById(user.getId())
                .map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Utilisateur introuvable : " + user.getEmail()));
    }

    /**
     * Construit la réponse d'authentification (jeton + profil) pour un utilisateur donné.
     *
     * @param userDetails utilisateur pour lequel émettre le jeton
     * @return réponse contenant le jeton, le schéma et la durée de validité
     */
    private AuthResponse buildAuthResponse(CustomUserDetails userDetails) {
        String token = jwtService.generateToken(userDetails);

        return new AuthResponse(
                token,
                BEARER_TYPE,
                expiration,
                UserResponse.from(userDetails.getUser()));
    }
}
