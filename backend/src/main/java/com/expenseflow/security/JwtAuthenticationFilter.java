package com.expenseflow.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Filtre d'authentification JWT exécuté avant
 * {@code UsernamePasswordAuthenticationFilter}.
 *
 * <p>
 * Il lit l'en-tête {@code Authorization: Bearer &lt;token&gt;} et, si le jeton est
 * valide, place une authentification complète dans le
 * {@link SecurityContextHolder} pour le reste de la chaîne.
 * </p>
 *
 * <p>
 * Choix assumé : ce filtre <strong>ne lève jamais d'exception</strong>. Un jeton
 * absent, malformé, mal signé, expiré ou rattaché à un utilisateur inconnu laisse
 * simplement la requête non authentifiée : c'est
 * {@link CustomAuthenticationEntryPoint} qui produit la réponse 401 JSON propre,
 * et les endpoints publics restent accessibles même avec un en-tête corrompu.
 * </p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    private final CustomUserDetailsService customUserDetailsService;

    /**
     * @param jwtService              service d'émission/vérification des jetons
     * @param customUserDetailsService chargement des utilisateurs depuis la base
     */
    public JwtAuthenticationFilter(JwtService jwtService,
            CustomUserDetailsService customUserDetailsService) {
        this.jwtService = jwtService;
        this.customUserDetailsService = customUserDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER);

        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();

        try {
            String email = jwtService.extractUsername(token);

            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = customUserDetailsService.loadUserByUsername(email);

                if (jwtService.isTokenValid(token, userDetails)) {
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
            // Requete laissee non authentifiee : le 401 JSON sera produit par l'entry point.
            // Aucun token ni secret n'est journalise.
            log.debug("Jeton JWT rejete sur {} {} : {}", request.getMethod(), request.getRequestURI(),
                    e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
