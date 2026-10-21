package com.expenseflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests de la lecture des origines CORS.
 *
 * <p>
 * <strong>Pourquoi tester une simple liste.</strong> Une origine CORS erronee
 * ne produit aucune erreur serveur : le navigateur refuse l'appel, la
 * reponse arrive en CORS dans la console du front, et rien dans les logs ne
 * relie le symptome a sa cause. Sans ces tests, une faute de frappe dans une
 * variable d'environnement ne se decouvrirait qu'a la premiere requete faite
 * depuis le navigateur, donc apres deploiement.
 * </p>
 *
 * <p>
 * <strong>Pas de contexte Spring.</strong> La classe est construite
 * directement : la conversion d'une propriete en liste est ici une simple
 * methode, et un demarrage de contexte pour la verifier coutait un conteneur
 * PostgreSQL pour une assertion de chainage de caracteres.
 * </p>
 */
@DisplayName("Origines CORS (unitaire)")
class SecurityConfigTest {

    private SecurityConfig config(String corsAllowedOrigins) {
        return new SecurityConfig(
                Mockito.mock(com.expenseflow.security.JwtAuthenticationFilter.class),
                Mockito.mock(com.expenseflow.security.CustomAuthenticationEntryPoint.class),
                Mockito.mock(com.expenseflow.security.CustomAccessDeniedHandler.class),
                corsAllowedOrigins);
    }

    @Test
    @DisplayName("Une origine unique est reprise telle quelle")
    void origineUnique_estReprise() {
        assertThat(config("https://expenseflow.vercel.app").allowedOrigins())
                .containsExactly("https://expenseflow.vercel.app");
    }

    @Test
    @DisplayName("Plusieurs origines separees par des virgules sont toutes retenues")
    void plusieursOrigines_sontToutesRetenues() {
        // Cas du basculement : l'ancien front de recette et le nouveau doivent
        // cohabiter. N'en garder qu'une ferait echouer l'autre silencieusement.
        assertThat(config("http://localhost:4300,https://expenseflow.vercel.app")
                .allowedOrigins())
                .containsExactly("http://localhost:4300", "https://expenseflow.vercel.app");
    }

    @Test
    @DisplayName("Les espaces autour des virgules sont supprimes")
    void espaces_autourDesVirgules_sontSupprimes() {
        // Un tableau de bord de deploiement fournit naturellement
        // "a, b". Sans nettoyage, l'origine " b" ne correspondrait a aucun
        // domaine : le navigateur refuserait, sans message exploitable.
        assertThat(config("http://localhost:4300, https://expenseflow.vercel.app")
                .allowedOrigins())
                .containsExactly("http://localhost:4300", "https://expenseflow.vercel.app");
    }

    @Test
    @DisplayName("Une virgule trainiere ne produit pas d'origine vide")
    void virguleTrainiere_neProduitPasDOrigineVide() {
        // Une origine vide ferait echouer la comparaison de facon obscure ;
        // mieux vaut l'ignorer silencieusement que la transmettre au navigateur.
        assertThat(config("https://expenseflow.vercel.app,").allowedOrigins())
                .containsExactly("https://expenseflow.vercel.app");
    }

    @Test
    @DisplayName("Une valeur nulle ou vide n'autorise aucune origine")
    void valeurVide_nAutoriseAucuneOrigine() {
        // Aucune origine vaut mieux qu'une origine invalide : le refus est
        // alors explicite, la ou une valeur erronee donnerait un faux sentiment
        // de securite en semblant restreindre l'acces.
        assertThat(config(null).allowedOrigins()).isEmpty();
        assertThat(config("   ").allowedOrigins()).isEmpty();
    }

    @Test
    @DisplayName("La liste renvoyee est immuable")
    void listeRenvoyee_estImmuable() {
        // La configuration CORS est partagee : une liste modifiable pourrait
        // etre alteree par un autre composant, sans que rien ne le signale.
        List<String> origines = config("https://expenseflow.vercel.app").allowedOrigins();

        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> origines.add("https://pirate.example"));
    }
}
