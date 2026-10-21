package com.expenseflow.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Répondeur 403 : produit une réponse JSON cohérente lorsqu'un utilisateur est
 * authentifié mais ne dispose pas du rôle requis.
 *
 * <p>
 * Sans cette classe, Spring Security renvoie un 403 avec un corps vide ou une
 * page d'erreur HTML, que le front ne sait pas exploiter. Le corps renvoyé est
 * toujours :
 * </p>
 *
 * <pre>
 * {"error":"Forbidden","message":"Access denied"}
 * </pre>
 *
 * <p>
 * <strong>Complémentarité avec {@link CustomAuthenticationEntryPoint}</strong> :
 * les deux produisant le même format JSON, le front peut traiter les erreurs
 * d'authentification (401) et d'autorisation (403) de façon homogène.
 * </p>
 *
 * <p>
 * Distinction importante : une requête <em>anonyme</em> ne passe jamais ici.
 * Elle est d'abord rejetée en 401 par l'entry point, car un utilisateur non
 * authentifié n'a pas de rôle du tout. Le 403 signifie donc toujours « authentifié
 * mais rôle insuffisant ».
 * </p>
 *
 * <p>
 * <strong>Complémentarité avec {@code GlobalExceptionHandler}</strong> : cette
 * classe ne traite que les refus décidés au niveau de la <em>chaîne de
 * filtres</em>. Les refus issus d'une annotation {@code @PreAuthorize} sont
 * évalués dans le {@code DispatcherServlet} et remontent donc comme une
 * exception MVC : ils sont capturés par le handler
 * {@code handleAccessDenied} de {@code GlobalExceptionHandler}, qui renvoie
 * exactement le même corps JSON. Les deux chemins doivent être couverts, sinon
 * un refus par {@code @PreAuthorize} se traduit par un 500 trompeur.
 * </p>
 *
 * <p>
 * Comme pour l'entry point, le message reste volontairement générique : il ne
 * révèle ni le rôle attendu ni le chemin exact, ce qui éviterait de fuiter la
 * cartographie des zones protégées de l'API.
 * </p>
 */
@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private static final String FORBIDDEN_JSON =
            "{\"error\":\"Forbidden\",\"message\":\"Access denied\"}";

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(FORBIDDEN_JSON);
        response.getWriter().flush();
    }
}