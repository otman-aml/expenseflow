package com.expenseflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;

/**
 * Documentation de l'API : informations générales et schéma d'authentification
 * exposés à Swagger UI.
 *
 * <p>
 * Deux mécanismes complémentaires sont utilisés, volontairement :
 * </p>
 * <ul>
 * <li>les annotations {@link OpenAPIDefinition} et {@link SecurityScheme}
 * déclarent les métadonnées, qui alimentent le document OpenAPI et le bouton
 * « Authorize » de l'interface ;</li>
 * <li>le bean {@link OpenAPI} applique en plus une exigence de sécurité
 * <em>globale</em>, pour que le jeton soit proposé sur les 16 endpoints sans
 * devoir annoter chaque méthode. Les routes publiques
 * ({@code /api/auth/register}, {@code /api/auth/login}) restent néanmoins
 * appelables sans jeton : l'exigence déclarée dans le document OpenAPI n'a
 * aucune incidence sur la sécurité réelle, qui reste appliquée par Spring
 * Security.</li>
 * </ul>
 *
 * <p>
 * {@code @SecurityRequirement} par endpoint pourra être ajouté plus tard, dans
 * le cadre de l'enrichissement de la documentation.
 * </p>
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "ExpenseFlow API",
                version = "v1.0.0",
                description = """
                        API de gestion des notes de frais B2B avec workflow d'approbation \
                        multi-rôles. Authentification JWT avec contrôle d'accès par rôle \
                        (EMPLOYEE, MANAGER, FINANCE_ADMIN).""",
                contact = @Contact(
                        name = "ExpenseFlow",
                        email = "contact@expenseflow.ma")))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = """
                Collez votre JWT (sans le préfixe 'Bearer ' au début). \
                Obtenez-le via POST /api/auth/login.""")
public class OpenApiConfig {

    /**
     * Applique l'exigence d'authentification à l'ensemble de l'API.
     *
     * <p>
     * Le schéma lui-même n'est pas redéfini ici : il est déjà déclaré par
     * l'annotation {@link SecurityScheme} de cette classe. Ce bean se contente
     * d'ajouter l'exigence globale, ce qui évite d'entretenir la même
     * description à deux endroits — et le risque qu'elles divergent.
     * </p>
     *
     * @return le document OpenAPI complété de l'exigence de sécurité
     */
    @Bean
    public OpenAPI expenseFlowOpenApi() {
        return new OpenAPI()
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}