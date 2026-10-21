package com.expenseflow.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.expenseflow.security.CustomAccessDeniedHandler;
import com.expenseflow.security.CustomAuthenticationEntryPoint;
import com.expenseflow.security.JwtAuthenticationFilter;

/**
 * Configuration Spring Security de l'API REST ExpenseFlow.
 *
 * <p>
 * Modèle retenu : API <strong>stateless</strong> protégée par un access token JWT
 * (HS256). Conséquences directes :
 * </p>
 * <ul>
 * <li>CSRF désactivé : aucune session ni cookie d'authentification, donc aucune
 * surface CSRF (le jeton est porté explicitement par l'en-tête
 * {@code Authorization}).</li>
 * <li>{@code SessionCreationPolicy.STATELESS} : Spring Security ne crée ni
 * n'utilise de {@code HttpSession}.</li>
 * <li>CORS restreint aux origines declarees par {@code cors.allowed-origins}
 * ({@code http://localhost:4300} en developpement).</li>
 * </ul>
 *
 * <p>
 * {@code @EnableMethodSecurity} active {@code @PreAuthorize} / {@code @PostAuthorize}
 * pour les contrôles de rôle fins (EMPLOYEE, MANAGER, FINANCE_ADMIN) qui seront
 * posés sur les services et contrôleurs métier dans les sections suivantes.
 * </p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;

    private final CustomAccessDeniedHandler customAccessDeniedHandler;

    /**
     * Origines autorisees a appeler l'API, issues de {@code cors.allowed-origins}.
     *
     * <p>
     * <strong>Le defaut reste le front local.</strong> Sans cette propriete, une
     * valeur de repli pointant vers {@code localhost} ferait que le build de
     * production heriterait du developpement : chaque requete depuis le vrai
     * front serait refusee par le navigateur, sans qu'aucune erreur serveur ne
     * distingue la cause d'un bug metier. C'est une defaillance muette, de
     * celles qui se diagnostiquent des heures plus tard.
     * </p>
     */
    private final List<String> allowedOrigins;

    /**
     * @param jwtAuthenticationFilter      filtre d'authentification par jeton
     * @param customAuthenticationEntryPoint repondeur 401 JSON
     * @param customAccessDeniedHandler     repondeur 403 JSON
     * @param corsAllowedOrigins            origines CORS, separees par des virgules
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
            CustomAuthenticationEntryPoint customAuthenticationEntryPoint,
            CustomAccessDeniedHandler customAccessDeniedHandler,
            @Value("${cors.allowed-origins:http://localhost:4300}") String corsAllowedOrigins) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.customAuthenticationEntryPoint = customAuthenticationEntryPoint;
        this.customAccessDeniedHandler = customAccessDeniedHandler;
        this.allowedOrigins = parserOrigines(corsAllowedOrigins);
    }

    /**
     * Decoupe une liste d'origines separees par des virgules.
     *
     * <p>
     * <strong>Le decoupage est fait ici plutot que par Spring</strong>, qui
     * convertirait bien une chaine en liste : il ne supprime pas les espaces.
     * Or une variable d'environnement saisie dans un tableau de bord contient
     * presque toujours des espaces apres la virgule - et une origine espacee
     * ne correspond a rien, ce qui reproduit exactement le defaut qu'on cherche
     * a eviter. Les entrees vides sont ecartees pour la meme raison.
     * </p>
     *
     * @param valeurbrute propriete telle qu'elle est configuree, {@code null} accepte
     * @return origines nettoyees, jamais vide
     */
    private static List<String> parserOrigines(String valeurbrute) {
        if (valeurbrute == null || valeurbrute.isBlank()) {
            // Aucune origine ne vaut mieux qu'une origine invalide : elle rend
            // le refus explicite plutot que d'accorder un acces qu'on croyait
            // restreint.
            return List.of();
        }

        List<String> origines = new ArrayList<>();
        for (String origine : valeurbrute.split(",")) {
            String nettoyee = origine.trim();
            if (!nettoyee.isEmpty()) {
                origines.add(nettoyee);
            }
        }
        return List.copyOf(origines);
    }

    /**
     * Origines autorisees, telles qu'elles seront appliquees.
     *
     * <p>
     * Expose pour que les tests puissent verifier la lecture de la propriete
     * sans passer par une requete HTTP : le refus CORS se constate dans le
     * navigateur, donc beaucoup trop tard.
     * </p>
     *
     * @return origines configurees
     */
    List<String> allowedOrigins() {
        return allowedOrigins;
    }

    /**
     * Chaîne de filtres de l'application.
     *
     * @param http constructeur de configuration fourni par Spring Security
     * @return la chaîne de filtres configurée
     * @throws Exception en cas d'échec de configuration
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // HACK: CSRF désactivé — acceptable ici (API stateless, jeton
                // porté en en-tête, aucun cookie de session à protéger). À
                // réviser si le front bascule sur des cookies httpOnly, qui
                // reintroduisent le vector CSRF.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // /register et /login doivent rester publics. /me est explicitement
                        // EXCLU : il necessite un jeton valide, sinon le 401 JSON de
                        // CustomAuthenticationEntryPoint est produit.
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        // Documentation de l'API : la specification OpenAPI et Swagger UI
                        // doivent etre accessibles sans jeton, sinon la page s'affiche
                        // vide (le 401 bloque aussi le chargement du CSS et du JS).
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        // 401 : requete anonyme ou jeton invalide.
                        .authenticationEntryPoint(customAuthenticationEntryPoint)
                        // 403 : jeton valide mais role insuffisant (controles @PreAuthorize).
                        .accessDeniedHandler(customAccessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Encodeur de mot de passe BCrypt.
     *
     * <p>
     * Utilisé à la fois par {@code AuthService.register} (hachage avant
     * persistance) et par la chaîne Spring Security pour la vérification des
     * identifiants. BCrypt est salé : deux inscriptions avec le même mot de passe
     * produisent donc deux hachages différents, sans impact sur la vérification.
     * </p>
     *
     * @return l'encodeur BCrypt (force par défaut : 10)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Gestionnaire d'authentification Spring Security.
     *
     * <p>
     * Exposé explicitement car {@code AuthService.login} doit vérifier des
     * identifiants en dehors du cycle de vie d'une requête authentifiée par
     * filtre. Spring Security le construit à partir du {@code UserDetailsService}
     * ({@code CustomUserDetailsService}) et du {@link PasswordEncoder} ci-dessus.
     * </p>
     *
     * @param configuration configuration d'authentification Spring Security
     * @return le gestionnaire d'authentification
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * Politique CORS de l'API.
     *
     * <p>
     * <strong>Les origines viennent de {@code cors.allowed-origins}</strong>, et
     * non d'une liste ecrite ici : en production, le front est deploye sur une
     * autre domaine, et une origine figee refuserait silencieusement tout appel
     * depuis le navigateur.
     * </p>
     *
     * <p>
     * NE PAS remplacer allowedOrigins par "*" : incompatible avec
     * allowCredentials(true), ce qui fait echouer le demarrage de Spring.
     * </p>
     *
     * @return la source de configuration CORS appliquee a toutes les routes
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.PATCH.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()));
        // "Origin" et "X-Requested-With" sont requis par Swagger UI : le bouton
        // "Try it out" envoie une requete preflight CORS que le navigateur
        // refuserait sans ces deux en-tetes, meme si la route est en permitAll.
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
