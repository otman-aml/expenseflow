package com.expenseflow.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Client HTTP configure pour l'API de {@link com.expenseflow.service.GroqClient}.
 *
 * <p>
 * <strong>Pourquoi {@code RestClient} et non {@code WebClient}</strong> :
 * {@code RestClient} appartient a {@code spring-web}, deja tire par
 * {@code spring-boot-starter-webmvc}. {@code WebClient} exigerait
 * {@code spring-boot-starter-webflux}, qui ferait basculer l'application en
 * pile reactive — pour un appel synchrone a une API de quelques secondes par
 * requete, un rapport cout/complexite sans avantage.
 * </p>
 *
 * <p>
 * <strong>Pourquoi {@code JdkClientHttpRequestFactory}</strong> : elle s'appuie
 * sur le client HTTP du JDK, donc aucune dependance. Les implementations
 * alternatives ({@code HttpComponents}, {@code OkHttp}) imposeraient un jar
 * supplementaire.
 * </p>
 */
@Configuration
public class LlmClientConfig {

    /**
     * Delai maximal d'un appel au fournisseur, connexion et lecture confondues.
     *
     * <p>
     * Valeur de repli, surchargeable par {@code llm.timeout-seconds}. Quinze
     * secondes laisse une marge large pour un reseau lent ou une file
     * d'attente cote fournisseur, tout en bornant l'attente de l'utilisateur :
     * au-dela, l'ecran doit basculer sur la saisie manuelle plutot que paraitre
     * fige.
     * </p>
     */
    private static final Duration DELAI_PAR_DEFAUT = Duration.ofSeconds(15);

    /**
     * Racine de l'API, compatible OpenAI.
     *
     * <p>
     * <strong>La forme du chemin ({@code /openai/v1}) n'est pas un detail
     * d'URL</strong> : c'est elle qui materialise la compatibilite avec
     * l'API de reference d'OpenAI. Un second fournisseur compatible n'exposerait
     * pas forcement la meme racine, et c'est le seul point qui devrait changer
     * pour y brancher un autre.
     * </p>
     */
    private static final String URL_BASE = "https://api.groq.com/openai/v1";

    /**
     * Construit le client utilise par {@code GroqClient}.
     *
     * <p>
     * <strong>L'en-tete d'authentification n'est PAS pose ici.</strong> Une cle
     * absente ne doit pas empecher l'application de demarrer : sans elle, toute
     * la brique IA est indisponible, mais l'authentification, les notes et le
     * workflow restent pleinement fonctionnels. L'en-tete est donc ajoute au
     * moment de l'appel, par le service, qui refuse de partir si la cle
     * manque.
     * </p>
     *
     * @param urlBase       racine de l'API du fournisseur
     * @param timeoutSecondes delai maximal, en secondes
     * @return client {@code RestClient} a l'URL de base de l'API
     */
    @Bean
    public RestClient llmRestClient(
            @Value("${llm.base-url:https://api.groq.com/openai/v1}") String urlBase,
            @Value("${llm.timeout-seconds:15}") long timeoutSecondes) {
        Duration delai = timeoutSecondes > 0
                ? Duration.ofSeconds(timeoutSecondes)
                : DELAI_PAR_DEFAUT;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(delai)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(delai);

        return RestClient.builder()
                .baseUrl(urlBase == null || urlBase.isBlank() ? URL_BASE : urlBase)
                .requestFactory(factory)
                .build();
    }
}
