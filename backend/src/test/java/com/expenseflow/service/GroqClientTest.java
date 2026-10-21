package com.expenseflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.expenseflow.exception.llm.LlmAuthException;
import com.expenseflow.exception.llm.LlmConfigException;
import com.expenseflow.exception.llm.LlmException;
import com.expenseflow.exception.llm.LlmRateLimitException;
import com.expenseflow.exception.llm.LlmServerException;
import com.expenseflow.exception.llm.LlmTimeoutException;

/**
 * Tests du transport d'appel au fournisseur, sans appel reseau.
 *
 * <p>
 * {@link MockRestServiceServer} remplace la fabrique de requetes du
 * {@code RestClient} : la construction du client, des en-tetes et du corps
 * JSON est reellement executee, seule la socket est simulee. C'est ce qui
 * permet de verifier que la requete partie est correcte, ce qu'un mock du
 * service ne prouverait pas.
 * </p>
 *
 * <p>
 * <strong>La cle de test est factice</strong> : aucun appel ne sort, et la
 * valeur n'a aucune signification. Le cas de cle absente est traite
 * separement, et ne doit passer par aucune requete.
 * </p>
 */
@DisplayName("GroqClient (unitaire)")
class GroqClientTest {

    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODELE = "openai/gpt-oss-120b";
    private static final String CLE = "cle-de-test";

    private RestClient restClient;
    private MockRestServiceServer serveur;

    @BeforeEach
    void setUp() {
        // L'ordre est capital : bindTo installe la fabrique simulée SUR le
        // builder, et seule la construction qui suit l'utilise. Construire
        // d'abord produirait un client qui part sur la vraie socket — les
        // tests partiraient alors sur l'API du fournisseur réelle.
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.groq.com/openai/v1");
        serveur = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private GroqClient client(String cle) {
        return new GroqClient(restClient, cle, MODELE, 15);
    }

    /**
     * Corps de reponse reussi, minimal mais conforme a la forme de l'API.
     *
     * <p>
     * Le contenu est passe en parametre du {@code formatted} et non ecrit
     * directement dans le modele : un bloc de texte Java n'interprete pas
     * {@code \"} comme un echappement, il l'insere tel quel. Le JSON produit
     * serait alors invalide, et l'echec porterait sur le test lui-meme.
     * </p>
     *
     * @param contenu texte place dans le message
     * @return corps JSON serialise
     */
    private static String reponseValide(String contenu) {
        return """
                {"choices":[{"message":{"role":"assistant","content":%s}}]}
                """.formatted(toJsonString(contenu));
    }

    /**
     * Serialise une chaine en litteral JSON.
     *
     * @param valeur texte a echapper
     * @return litteral JSON valide
     */
    private static String toJsonString(String valeur) {
        return "\"" + valeur.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---------------------------------------------------------------------
    // A) Cas nominal et forme de la requete
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Une reponse 200 renvoie le contenu produit par le modele")
    void succes_renvoieLeContenu() {
        // Given
        serveur.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + CLE))
                .andRespond(withSuccess(reponseValide("{\"montant\":120}"), MediaType.APPLICATION_JSON));

        // When
        String resultat = client(CLE).chatCompletion("extrais");

        // Then
        assertThat(resultat).isEqualTo("{\"montant\":120}");
        serveur.verify();
    }

    @Test
    @DisplayName("Le corps envoye porte le modele, le role et le format de reponse JSON")
    void corpsEnvoye_estComplet() {
        // Given
        serveur.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"model\":\"" + MODELE + "\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"role\":\"user\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"response_format\":{\"type\":\"json_object\"}")))
                // La temperature est explicitement basse : l'extraction doit
                // etre reproductible d'une phrase identique a l'autre.
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"temperature\":0.1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("extrais")))
                .andRespond(withSuccess(reponseValide("{}"), MediaType.APPLICATION_JSON));

        // When / Then : le mode JSON est exige explicitement, sans quoi le
        // modele repond du texte libre que l'analyse devra decortiquer.
        client(CLE).chatCompletion("extrais");
        serveur.verify();
    }

    @Test
    @DisplayName("Une reponse sans choix leve une erreur explicite, pas une chaine vide")
    void reponseSansChoix_leveLlmException() {
        // Given
        serveur.expect(requestTo(URL))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        // When / Then : une chaine vide echouerait plus tard, en validation,
        // sans indiquer que le probleme vient de la reponse du modele.
        assertThatThrownBy(() -> client(CLE).chatCompletion("test"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("aucune completion");
    }

    // ---------------------------------------------------------------------
    // B) Configuration absente
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Une cle absente leve LlmConfigException sans appel reseau")
    void cleAbsente_leveSansAppel() {
        // When / Then
        assertThatThrownBy(() -> client("").chatCompletion("test"))
                .isInstanceOf(LlmConfigException.class)
                .hasMessageContaining("GROQ_API_KEY");

        // Aucune requete n'a ete preparee : le serveur n'est pas sollicite.
        serveur.verify();
    }

    @Test
    @DisplayName("Une cle uniquement composee d'espaces est traitee comme absente")
    void cleBlanche_leveSansAppel() {
        // When / Then : un espace ne rendrait pas l'appel valide, il
        // produirait au mieux un 401 chez le fournisseur.
        assertThatThrownBy(() -> client("   ").chatCompletion("test"))
                .isInstanceOf(LlmConfigException.class);
    }

    // ---------------------------------------------------------------------
    // C) Traduction des codes HTTP
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Un 401 leve LlmAuthException")
    void http401_leveAuth() {
        serveur.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client(CLE).chatCompletion("test"))
                .isInstanceOf(LlmAuthException.class)
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("Un 403 leve LlmAuthException")
    void http403_leveAuth() {
        serveur.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client(CLE).chatCompletion("test"))
                .isInstanceOf(LlmAuthException.class);
    }

    @Test
    @DisplayName("Un 429 leve LlmRateLimitException")
    void http429_leveRateLimit() {
        serveur.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client(CLE).chatCompletion("test"))
                .isInstanceOf(LlmRateLimitException.class)
                .hasMessageContaining("429");
    }

    @Test
    @DisplayName("Un 500 leve LlmServerException")
    void http500_leveServer() {
        serveur.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client(CLE).chatCompletion("test"))
                .isInstanceOf(LlmServerException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("Un 400 leve une LlmException generique, sans pretendre a autre chose")
    void http400_leveGenerique() {
        // Given : une requete malformee de notre cote, ni authentification ni
        // indisponibilite du service.
        serveur.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        // When / Then
        assertThatThrownBy(() -> client(CLE).chatCompletion("test"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("400")
                .isNotInstanceOf(LlmAuthException.class)
                .isNotInstanceOf(LlmRateLimitException.class)
                .isNotInstanceOf(LlmServerException.class);
    }

    // ---------------------------------------------------------------------
    // D) Delai depasse
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Un delai de lecture depasse leve LlmTimeoutException")
    void delaiDepasse_leveTimeout() {
        // Given : le client JDK leve HttpTimeoutException, que RestClient
        // enveloppe dans ResourceAccessException.
        serveur.expect(requestTo(URL)).andRespond(request -> {
            throw new ResourceAccessException("read timed out",
                    new HttpTimeoutException("timeout"));
        });

        // When / Then
        assertThatThrownBy(() -> client(CLE).chatCompletion("test"))
                .isInstanceOf(LlmTimeoutException.class)
                .hasMessageContaining("15");
    }

    @Test
    @DisplayName("Une panne reseau brute leve aussi LlmTimeoutException")
    void panneReseau_leveTimeout() {
        serveur.expect(requestTo(URL)).andRespond(request -> {
            throw new ResourceAccessException("connexion impossible",
                    new SocketTimeoutException("connect timed out"));
        });

        assertThatThrownBy(() -> client(CLE).chatCompletion("test"))
                .isInstanceOf(LlmTimeoutException.class);
    }
}