package com.expenseflow.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.expenseflow.exception.llm.LlmAuthException;
import com.expenseflow.exception.llm.LlmConfigException;
import com.expenseflow.exception.llm.LlmException;
import com.expenseflow.exception.llm.LlmRateLimitException;
import com.expenseflow.exception.llm.LlmServerException;
import com.expenseflow.exception.llm.LlmTimeoutException;

/**
 * Appel a l'API de completion de chat de Groq.
 *
 * <p>
 * <strong>Pourquoi Groq</strong> : le projet a commence sur Mistral, dont le
 * palier gratuit a ete ferme — les 429 recus n'etaient pas un quota epuise
 * mais une restriction d'offre. Groq sert le meme contrat qu'OpenAI, ce qui a
 * permis de basculer sans toucher au prompt ni a la validation. Le choix du
 * fournisseur reste un detail d'injection : {@link LlmClient} est le seul
 * contrat que connaisse {@link AiExtractionService}.
 * </p>
 *
 * <p>
 * Cette classe ne connait que le transport : elle construit la requete, et
 * traduit les codes HTTP en exceptions typees. Elle ne valide ni n'interprete
 * le JSON obtenu — c'est le role du service d'extraction, qui applique les
 * regles metier sur une reponse dont on ne peut pas faire confiance au
 * contenu.
 * </p>
 *
 * <p>
 * <strong>Une cle absente ne provoque aucun appel reseau</strong> : la
 * verification est faite avant. Sans elle, Groq repondrait 401, traduit en
 * erreur d'authentification — alors que le probleme est local et se corrige
 * en une variable d'environnement.
 * </p>
 */
@Service
public class GroqClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GroqClient.class);

    /**
     * Modele par defaut, si la propriete n'en designe aucun.
     *
     * <p>
     * <strong>Ce n'est qu'un filet de securite, pas la source de verite.</strong>
     * La valeur effective vient de {@code llm.model} dans
     * {@code application.properties} ; celle-ci n'existe que si la propriete
     * est absente — ce qui ne doit pas arriver en fonctionnement normal.
     * </p>
     *
     * <p>
     * <strong>Le catalogue d'un fournisseur bouge plus vite que son API.</strong>
     * Llama 3.3 a disparu du catalogue Groq alors que l'API, elle, acceptait
     * toujours les memes requetes : le defaut aurait donc continue de
     * produire un 400 pour un nom de modele valide la veille. C'est pourquoi la
     * propriete, et non cette constante, porte le modele reellement utilise.
     * </p>
     *
     * <p>
     * La tache est une extraction de champs, pas un raisonnement : le modele
     * doit surtout decoder un JSON et choisir un identifiant dans une liste
     * fournie.
     * </p>
     */
    private static final String MODELE_PAR_DEFAUT = "openai/gpt-oss-120b";

    /**
     * Temperature demandee au modele.
     *
     * <p>
     * <strong>Quasi nulle, et c'est deliberé.</strong> Une temperature elevee
     * rendrait l'extraction reproductible seulement par chance : deux memes
     * phrases pourraient produire deux categories differentes, et
     * l'utilisateur verrait le meme texte pre-remplir differemment. Ici
     * l'incertitude est surtout absorbee par la validation, qui refuse une
     * categorie inexistante ou une date future.
     * </p>
     */
    private static final double TEMPERATURE = 0.1;

    /**
     * Corps renvoye par l'API, forme OpenAI.
     *
     * <p>
     * Seule la forme utile est declaree. Le message est un objet libre plutot
     * qu'un type : l'API y ajoute des champs selon les versions, et un
     * enregistrement strict echouerait sur un champ inattendu.
     * </p>
     */
    private record ReponseGroq(List<Choix> choices, Map<String, Object> usage) {
        record Choix(Map<String, Object> message) {
        }
    }

    private final RestClient restClient;
    private final String cleApi;
    private final String modele;
    private final long delaiSecondes;

    /**
     * @param restClient     client HTTP configure par {@code LlmClientConfig}
     * @param cleApi         valeur de {@code llm.api.key}, eventuellement vide
     * @param modele         valeur de {@code llm.model}, avec valeur de repli
     * @param delaiSecondes  valeur de {@code llm.timeout-seconds}
     */
    public GroqClient(RestClient llmRestClient,
            @Value("${llm.api.key:}") String cleApi,
            @Value("${llm.model:openai/gpt-oss-120b}") String modele,
            @Value("${llm.timeout-seconds:15}") long delaiSecondes) {
        this.restClient = llmRestClient;
        this.cleApi = cleApi == null ? "" : cleApi.trim();
        this.modele = modele == null || modele.isBlank() ? MODELE_PAR_DEFAUT : modele.trim();
        this.delaiSecondes = delaiSecondes;

        if (this.cleApi.isEmpty()) {
            // Avertissement et non erreur : l'application doit demarrer meme
            // sans la brique IA, qui seule est indisponible.
            log.warn("llm.api.key absente : les points d'entree IA repondront 503 "
                    + "tant que GROQ_API_KEY n'est pas definie.");
        }
    }


    /**
     * {@inheritDoc}
     *
     * <p>
     * Cette methode est la forme complete ; {@link #chatCompletion(String)} en
     * est un cas particulier, conserve parce que le point d'entree temporaire
     * {@code /api/ai/test} n'a pas besoin des compteurs.
     * </p>
     */
    @Override
    public LlmResponse chatCompletionAvecUsage(String prompt) {
        if (cleApi.isEmpty()) {
            throw new LlmConfigException(
                    "Cle API LLM absente : definissez GROQ_API_KEY pour utiliser l'extraction.");
        }

        Map<String, Object> corps = Map.of(
                "model", modele,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                // Indique au modele de produire un JSON. Cela reduit les
                // marqueurs ```json a stripper, sans les rendre impossibles :
                // la validation stricte reste obligatoire.
                "response_format", Map.of("type", "json_object"),
                "temperature", TEMPERATURE
        );

        ReponseGroq reponse;
        try {
            reponse = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + cleApi)
                    .body(corps)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw traduire(res.getStatusCode().value());
                    })
                    .body(ReponseGroq.class);
        } catch (ResourceAccessException e) {
            // Le client JDK leve HttpTimeoutException, que RestClient enveloppe
            // ici : c'est le seul cas ou le reseau, et non Groq, est en
            // cause.
            //
            // Le delai affiche vient de la propriete, et non d'une constante
            // codee en dur : il est configurable, donc un message fige
            // mentirait des que sa valeur change.
            throw new LlmTimeoutException(
                    "Delai depasse lors de l'appel au fournisseur LLM ("
                            + delaiSecondes + " s).", e);
        } catch (LlmException e) {
            // Relancee telle quelle : une traduction deja faite ne doit pas
            // repasser dans le filet general.
            throw e;
        } catch (Exception e) {
            throw new LlmException("Echec de l'appel au fournisseur LLM.", e);
        }

        return new LlmResponse(
                extraireContenu(reponse),
                lireEntier(reponse.usage(), "prompt_tokens"),
                lireEntier(reponse.usage(), "completion_tokens"),
                lireEntier(reponse.usage(), "total_tokens"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String chatCompletion(String prompt) {
        return chatCompletionAvecUsage(prompt).content();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String modele() {
        return modele;
    }

    /**
     * Lit un compteur de tokens dans l'objet {@code usage}.
     *
     * <p>
     * La valeur est lue comme un {@code Number} plutot que castee : selon la
     * version de l'API et le nombre de valeurs, JSON produit un {@code Integer}
     * ou un {@code Long}, et un cast direct echouerait sur l'un des deux.
     * </p>
     *
     * @param usage objet {@code usage} de la reponse, {@code null} si absent
     * @param cle   nom du compteur
     * @return valeur entiere, ou {@code null} si non communiquee
     */
    private Integer lireEntier(Map<String, Object> usage, String cle) {
        if (usage == null) {
            return null;
        }

        Object valeur = usage.get(cle);
        return valeur instanceof Number nombre ? nombre.intValue() : null;
    }

    /**
     * Traduit un code HTTP du fournisseur en exception typee.
     *
     * @param code statut de la reponse
     * @return exception a lever
     */
    private LlmException traduire(int code) {
        return switch (code) {
            case 401, 403 -> new LlmAuthException(code);
            case 429 -> new LlmRateLimitException(
                    "Quota d'appels LLM atteint (HTTP 429). Reessayez plus tard.");
            default -> code >= 500
                    ? new LlmServerException(code)
                    : new LlmException("Appel LLM refuse (HTTP " + code + ").");
        };
    }

    /**
     * Isole le texte produit dans la reponse enchassee de l'API.
     *
     * <p>
     * La forme attendue est
     * {@code {"choices":[{"message":{"content":"..."}}]}}. Tout ecart — liste
     * vide, premiere choix sans message, contenu absent — est signale plutot
     * que de renvoyer une chaine vide : une reponse vide ferait echouer
     * silencieusement la validation en aval, sans indiquer ou.
     * </p>
     *
     * @param reponse corps deserialise par le client
     * @return contenu de la premiere completion
     * @throws LlmException si la reponse ne contient aucun contenu
     */
    private String extraireContenu(ReponseGroq reponse) {
        if (reponse == null || reponse.choices() == null || reponse.choices().isEmpty()) {
            throw new LlmException("Reponse LLM vide : aucune completion retournee.");
        }

        Map<String, Object> message = reponse.choices().getFirst().message();
        Object contenu = message == null ? null : message.get("content");

        if (!(contenu instanceof String texte) || texte.isBlank()) {
            throw new LlmException("Reponse LLM sans contenu exploitable.");
        }

        return texte;
    }
}
