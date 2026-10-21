package com.expenseflow.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Point d'entrée d'authentification : produit une réponse 401 JSON propre.
 *
 * <p>
 * Sans cette classe, Spring Security renverrait une page d'erreur HTML ou une
 * trace applicative peu exploitable par le front. Le corps renvoyé est toujours :
 * </p>
 *
 * <pre>
 * {"error":"Unauthorized","message":"Authentication required"}
 * </pre>
 *
 * <p>
 * Le JSON statique est encodé directement sans couplage vers une version spécifique
 * de Jackson, assurant une portabilité totale sur tout environnement Spring Boot.
 * Aucune exception n'est propagée : {@code sendError} est volontairement évité
 * afin de ne pas déclencher le rendu de {@code /error} par Spring Boot.
 * </p>
 */
@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String UNAUTHORIZED_JSON =
            "{\"error\":\"Unauthorized\",\"message\":\"Authentication required\"}";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(UNAUTHORIZED_JSON);
        response.getWriter().flush();
    }
}
