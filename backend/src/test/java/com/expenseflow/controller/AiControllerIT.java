package com.expenseflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.entity.AiCallLog;
import com.expenseflow.entity.Category;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.AiCallLogRepository;
import com.expenseflow.repository.CategoryRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.CustomUserDetailsService;
import com.expenseflow.security.JwtService;

/**
 * Tests d'integration des points d'entree de la brique IA.
 *
 * <p>
 * <strong>Ce que ces tests ajoutent aux tests unitaires.</strong> Le client et
 * le service d'extraction sont deja couverts separement ; ici on verifie ce qui
 * n'existe qu'assemble : la chaine de securite (le jeton suffit-il, le role
 * suffit-il), la validation Jakarta du corps de requete, la traduction des
 * exceptions en codes HTTP, et le fait que l'appel est bien enregistre en base
 * avec son auteur.
 * </p>
 *
 * <p>
 * <strong>Aucun appel a Groq n'est emis.</strong> Le {@code RestClient} du
 * contexte est remplace par un client dont la fabrique de requetes est simulee
 * (voir {@code ConfigurationIAdeTest}) : la construction de la requete, les
 * en-tetes et la deserialisation de la reponse sont reellement executes, seule
 * la socket est simulee. Les tests sont donc deterministes et ne consumment
 * aucun quota, ce qui compte pour une suite destinee a tourner en CI.
 * </p>
 *
 * <p>
 * <strong>Pas de {@code @Transactional}.</strong> Le journal utilise
 * {@code REQUIRES_NEW} : une transaction separee, qui ne voit pas les
 * donnees non commitees du test. Sous {@code @Transactional},
 * l'utilisateur cree par le test resterait invisible a la journalisation
 * et la colonne utilisateur_id serait nulle : l attribution, qui est
 * precisement ce qu il faut verifier, ne pourrait pas l etre. Chaque test
 * nettoie donc ses donnees apres coup.
 * </p>
 */
@AutoConfigureMockMvc
@Import(AiControllerIT.ConfigurationIAdeTest.class)
@DisplayName("Brique IA (integration)")
class AiControllerIT extends AbstractIntegrationTest {

    /** URL complete appelee par le client : la racine se combine au chemin. */
    private static final String URL_CHAT = "https://api.groq.com/openai/v1/chat/completions";

    /** Cle factice : elle ne sort jamais du processus de test. */
    private static final String CLE = "cle-de-test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MockRestServiceServer serveur;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private AiCallLogRepository aiCallLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    /**
     * Remet le serveur simule a zero avant chaque test.
     *
     * <p>
     * <strong>Sans cette remise a zero, les attentes d'un test s'appliqueraient
     * au suivant</strong> : le serveur est partage par toute la classe, et une
     * attente non consommee ferait echouer le test suivant sur une requete
     * qu'il n'a pas formulee. Le diagnostic serait alors trompeur, pointant vers
     * un comportement faux plutot que vers un etat partage.
     * </p>
     */
    @BeforeEach
    void resetServeur() {
        serveur.reset();
    }

    /**
     * Supprime les donnees creees par le test.
     *
     * <p>
     * <strong>Ce nettoyage remplace le rollback de {@code @Transactional}.</strong>
     * Il est indispensable : le journal ecrit en {@code REQUIRES_NEW} est
     * reellement committe, donc un rollback ne le reprendrait pas. Sans ce
     * {@code deleteAll}, les entrees s'accumuleraient d'un test a l'autre et
     * le test des statistiques verrait des totaux qui ne sont pas les siens.
     * </p>
     *
     * <p>
     * L'ordre suit les dependances : le journal reference l'utilisateur, qui
     * reference lui-meme ses notes. Supprimer dans l'autre sens echouerait
     * sur une cle etrangere.
     * </p>
     */
    @AfterEach
    void nettoyerApresTest() {
        aiCallLogRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }


    // =====================================================================
    // A) POST /api/ai/extract-expense
    // =====================================================================

    @Test
    @DisplayName("Un EMPLOYEE extrait une depense et recoit la ligne pre-remplie")
    void extract_employeValide_renvoie200() throws Exception {
        // Given : une categorie reellement en base, car la validation refuse un
        // identifiant inconnu : c'est elle, et non le modele, qui garantit que c'est elle, et non le modele, qui garantit que
        // la ligne pre-remplie est registrable.
        Long categorieId = creerCategorie("Transport");
        User employe = createUser("Employe Extraction", UserRole.EMPLOYEE);

        serveur.expect(requestTo(URL_CHAT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + CLE))
                .andRespond(couronneJson(categorieId, 450, "2026-03-14", "Taxi chez Acme"));

        // When / Then
        mockMvc.perform(post("/api/ai/extract-expense")
                        .header("Authorization", bearer(employe))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(texteJson("J'ai paye 450 dh de taxi chez Acme le 14 mars")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ligne.categorieId").value(categorieId))
                .andExpect(jsonPath("$.ligne.montant").value(450))
                .andExpect(jsonPath("$.ligne.dateDepense").value("2026-03-14"))
                .andExpect(jsonPath("$.ligne.description").value("Taxi chez Acme"));

        // Then : l'appel est attribuable. La reponse HTTP ne dit rien du
        // journal, donc un oubli ici serait invisible : c'est la seule
        // verification qui prouve que la consommation est imputable a quelqu'un.
        assertThat(aiCallLogRepository.findAll())
                .anySatisfy(entree -> {
                    assertThat(entree.getUtilisateur()).isNotNull();
                    assertThat(entree.getUtilisateur().getId()).isEqualTo(employe.getId());
                    assertThat(entree.getSucces()).isTrue();
                    assertThat(entree.getModele()).isEqualTo("openai/gpt-oss-120b");
                });
    }

    @Test
    @DisplayName("Une reponse de modele hors bornes est traduite en 422, pas en 500")
    void extract_reponseInvalide_renvoie422() throws Exception {
        // Given : le modele propose un montant negatif, que la validation
        // refuse. Le statut attendu distingue « la brique est tombee » de
        // « la reponse n'etait pas exploitable » : dans le premier cas le
        // front propose la saisie manuelle, dans le second il signale une
        // anomalie qu'un developpeur doit regarder.
        Long categorieId = creerCategorie("Restauration");
        User employe = createUser("Employe Montant Negatif", UserRole.EMPLOYEE);

        serveur.expect(requestTo(URL_CHAT))
                .andRespond(couronneJson(categorieId, -5, "2026-03-14", "Remboursement"));

        // When / Then
        mockMvc.perform(post("/api/ai/extract-expense")
                        .header("Authorization", bearer(employe))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(texteJson("J'ai paye -5 dh")))
                .andExpect(status().isUnprocessableEntity());

        // Une reponse rejetee n'est pas une reponse inexploitee : la compter
        // comme un succes gonflerait le taux de reussite qui doit surveiller
        // les defaillances.
        assertThat(aiCallLogRepository.findAll())
                .anySatisfy(entree -> assertThat(entree.getSucces()).isFalse());
    }

    @Test
    @DisplayName("Un texte vide est refuse 400 avant tout appel au fournisseur")
    void extract_texteVide_renvoie400() throws Exception {
        // Given : un EMPLOYEE valide, pour que seule la validation du corps
        // puisse expliquer le 400.
        User employe = createUser("Employe Texte Vide", UserRole.EMPLOYEE);

        // When / Then : aucune attente n'est declaree sur le serveur. Si une
        // requete partait malgre tout, le test echouerait ; ce qui prouve que ce qui prouve que
        // la validation court avant l'appel, et non apres.
        mockMvc.perform(post("/api/ai/extract-expense")
                        .header("Authorization", bearer(employe))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(texteJson("")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.texte").exists());
    }

    @Test
    @DisplayName("Un texte de plus de 500 caracteres est refuse 400")
    void extract_texteTropLong_renvoie400() throws Exception {
        User employe = createUser("Employe Texte Long", UserRole.EMPLOYEE);
        String tropLong = "a".repeat(501);

        mockMvc.perform(post("/api/ai/extract-expense")
                        .header("Authorization", bearer(employe))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(texteJson(tropLong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.texte").exists());
    }

    @Test
    @DisplayName("Sans jeton, l'extraction est refusee 401")
    void extract_sansJeton_renvoie401() throws Exception {
        // Aucune attente sur le serveur : si une requete partait, le test
        // echouerait sur une attente non formulee ; ce qui prouve du meme coup ce qui prouve du meme coup
        // qu'un appel non authentifie n'atteint pas le fournisseur et ne
        // consomme aucun quota.
        mockMvc.perform(post("/api/ai/extract-expense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(texteJson("taxi 450 dh")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Un MANAGER peut aussi extraire : l'extraction n'est pas reservee a l'employe")
    void extract_managerValide_renvoie200() throws Exception {
        // Le controle d'acces porte sur le fait d'etre authentifie, pas sur un
        // role : tous les roles de gestion font usage de l'extraction. Ce test
        // le verrouille, parce qu'un durcissement posterieur du role serait
        // alors visible ici et non en production.
        Long categorieId = creerCategorie("Hotel");
        User manager = createUser("Manager Extraction", UserRole.MANAGER);

        serveur.expect(requestTo(URL_CHAT))
                .andRespond(couronneJson(categorieId, 800, "2026-03-10", "Nuit hotel"));

        mockMvc.perform(post("/api/ai/extract-expense")
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(texteJson("J'ai paye 800 dh d'hotel le 10 mars")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ligne.montant").value(800));
    }


    // =====================================================================
    // B) GET /api/ai/stats
    // =====================================================================

    @Test
    @DisplayName("Le FINANCE_ADMIN lit les mesures d'usage")
    void stats_parFinance_renvoie200() throws Exception {
        // Given : des mesures reellement enregistrees, pour que la reponse ne
        // soit pas une structure vide qui passerait le test sans prouver
        // qu'elle est alimentee.
        User finance = createUser("Finance Mesures", UserRole.FINANCE_ADMIN);
        enregistrerAppel(finance, true, 120, 45);

        // When / Then
        mockMvc.perform(get("/api/ai/stats")
                        .header("Authorization", bearer(finance)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAppels").value(1))
                .andExpect(jsonPath("$.appelsReussis").value(1))
                .andExpect(jsonPath("$.appelsEchoues").value(0))
                .andExpect(jsonPath("$.tokensTotal").value(165));
        // Pas d'assertion sur tauxReussite : cette methode du record n'est
        // pas un composant, donc Jackson ne la serialise pas. C'est un calcul
        // interne, hors du contrat HTTP consomme par le front.
    }

    @Test
    @DisplayName("Un EMPLOYEE ne lit pas les mesures : 403")
    void stats_parEmploye_renvoie403() throws Exception {
        // Ces chiffres revelent le volume et le cout de l'API pour
        // l'organisation. Un employe n'a pas a les connaitre, et le role
        // finance est le seul competent sur le budget.
        User employe = createUser("Employe Curieux", UserRole.EMPLOYEE);

        mockMvc.perform(get("/api/ai/stats")
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Un MANAGER ne lit pas les mesures non plus : 403")
    void stats_parManager_renvoie403() throws Exception {
        // Un MANAGER arbitre les notes, il ne tient pas les comptes de
        // l'organisation. Lui ouvrir ces mesures reviendrait a elargir la
        // restriction au role suivant, ce que le role finance ne permet pas.
        User manager = createUser("Manager Curieux", UserRole.MANAGER);

        mockMvc.perform(get("/api/ai/stats")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Sans jeton, les mesures sont refusees 401")
    void stats_sansJeton_renvoie401() throws Exception {
        mockMvc.perform(get("/api/ai/stats"))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================
    // C) POST /api/ai/test (point d'entree temporaire de diagnostic)
    // =====================================================================

    @Test
    @DisplayName("Le point de test renvoie le texte brut produit par le modele")
    void testAvecJeton_renvoie200() throws Exception {
        // Ce point d'entree sera supprime en fin de Phase 3 ; il est teste
        // parce qu'il existe, et pour que sa suppression soit prouvee par un
        // test qui disparait avec lui.
        User employe = createUser("Employe Diagnostic", UserRole.EMPLOYEE);

        serveur.expect(requestTo(URL_CHAT))
                .andRespond(couronneTexte("{\"message\":\"Bonjour\"}"));

        mockMvc.perform(post("/api/ai/test")
                        .header("Authorization", bearer(employe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reponse").value("{\"message\":\"Bonjour\"}"));
    }

    @Test
    @DisplayName("Le point de test est refuse 401 sans jeton")
    void testSansJeton_renvoie401() throws Exception {
        mockMvc.perform(post("/api/ai/test"))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Construit un utilisateur avec un email unique.
     *
     * <p>
     * {@code System.nanoTime()} dans l'email evite la collision entre tests,
     * la colonne portant une contrainte d'unicite.
     * </p>
     *
     * @param nom  libelle lisible, utilise pour construire l'email
     * @param role role attribue
     * @return utilisateur enregistre
     */
    private User createUser(String nom, UserRole role) {
        return userRepository.save(User.builder()
                .nom(nom)
                .email(nom.toLowerCase().replace(' ', '-') + "-" + System.nanoTime()
                        + "@expenseflow.ma")
                .motDePasse(passwordEncoder.encode("MotDePasse1!"))
                .role(role)
                .build());
    }

    /**
     * Cree une categorie et renvoie son identifiant.
     *
     * <p>
     * <strong>Le nom est suffixe par {@code nanoTime}</strong> : la colonne porte
     * une contrainte d'unicite, et deux tests creant la meme categorie se
     * heurteraient.
     * </p>
     *
     * @param nom libelle de la categorie
     * @return identifiant attribue par la base
     */
    private Long creerCategorie(String nom) {
        return categoryRepository.save(Category.builder()
                .nom(nom + " " + System.nanoTime())
                .build())
                .getId();
    }

    /**
     * Forge un en-tete d'authentification.
     *
     * @param utilisateur utilisateur pour qui emettre le jeton
     * @return valeur de l'en-tete {@code Authorization}
     */
    private String bearer(User utilisateur) {
        return "Bearer " + jwtService.generateToken(
                customUserDetailsService.loadUserByUsername(utilisateur.getEmail()));
    }

    /**
     * Enregistre une entree de journal pour alimenter les mesures.
     *
     * @param auteur           utilisateur impute de l'appel
     * @param succes           issue de l'appel
     * @param tokensPrompt     tokens de l'invite
     * @param tokensCompletion tokens de la reponse
     */
    private void enregistrerAppel(User auteur, boolean succes, int tokensPrompt,
            int tokensCompletion) {
        aiCallLogRepository.save(AiCallLog.builder()
                .utilisateur(auteur)
                .dureeMs(1200L)
                .succes(succes)
                .tokensPrompt(tokensPrompt)
                .tokensCompletion(tokensCompletion)
                .tokensTotal(tokensPrompt + tokensCompletion)
                .modele("openai/gpt-oss-120b")
                .build());
    }

    /**
     * Enveloppe un texte libre dans le corps attendu par le controleur.
     *
     * <p>
     * <strong>L'echappement est fait ici, et non dans un bloc de texte</strong> :
     * un bloc de texte n'interprete pas un antislash comme un echappement, il
     * l'insere tel quel, ce qui produirait un JSON invalide et un 400 trompeur
     * - un echec attribuable au mauvais code.
     * </p>
     *
     * @param texte texte de la depense
     * @return corps JSON de la requete
     */
    private String texteJson(String texte) {
        return "{\"texte\":\"" + texte.replace("\"", "\\\"") + "\"}";
    }

    /**
     * Enveloppe une ligne extraite dans la reponse Groq attendue.
     *
     * @param categorieId identifiant de categorie
     * @param montant     montant de la depense
     * @param date        date au format {@code YYYY-MM-DD}
     * @param description resume produit par le modele
     * @return reponse du serveur simule
     */
    private ResponseCreator couronneJson(long categorieId, double montant, String date,
            String description) {
        String ligne = "{\"categorieId\":" + categorieId
                + ",\"montant\":" + montant
                + ",\"dateDepense\":\"" + date + "\""
                + ",\"description\":" + jsonString(description) + "}";
        return couronneTexte(ligne);
    }

    /**
     * Enveloppe un contenu quelconque dans la forme de reponse de Groq.
     *
     * <p>
     * <strong>Les compteurs de tokens sont fournis</strong> : leur absence
     * ferait echouer l'ecriture de {@code AiCallLog} sur une colonne NOT NULL,
     * et le test echouerait sur une contrainte de base plutot que sur la
     * traduction HTTP qu'il est cense couvrir.
     * </p>
     *
     * @param contenu texte place dans {@code choices[0].message.content}
     * @return reponse du serveur simule
     */
    private ResponseCreator couronneTexte(String contenu) {
        String corps = "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\","
                + "\"model\":\"openai/gpt-oss-120b\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":" + jsonString(contenu) + "},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":45,"
                + "\"total_tokens\":165}}";
        return withSuccess(corps, MediaType.APPLICATION_JSON);
    }

    /**
     * Produit un litteral JSON echappe.
     *
     * @param valeur texte brut
     * @return litteral JSON valide
     */
    private String jsonString(String valeur) {
        return "\"" + valeur.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // =====================================================================
    // Substitution du client HTTP
    // =====================================================================

    /**
     * Remplace le {@code RestClient} du contexte par un client simule.
     *
     * <p>
     * <strong>Pourquoi un bean {@code @Primary} plutot qu'un
     * {@code @MockBean}</strong> : un mock du client court-circuiterait
     * exactement ce que ces tests doivent verifier - la construction de la
     * requete, l'en-tete d'authentification et la lecture du JSON. Ici la
     * totalite du code de production s'execute, seule la socket est simulee.
     * </p>
     *
     * <p>
     * Le bean porte un nom different de {@code llmRestClient} et est
     * {@code @Primary} : il l'emporte donc sur le bean de production, qui reste
     * construit sans etre utilise. Aucun conflit de nom, aucune surcharge
     * de definitions.
     * </p>
     */
    @TestConfiguration
    static class ConfigurationIAdeTest {

        /**
         * Builder partage, seul moyen que le serveur simule et le client
         * utilisent la meme base.
         *
         * <p>
         * <strong>Un builder par bean aurait suffi a tout casser</strong> : le
         * serveur serait attache a un builder et le client construit sur un
         * autre, ce qui n'echouerait pas - les tests partiraient sur la vraie
         * API Groq, en consumant un quota a chaque execution et en rendant la
         * suite non deterministe.
         * </p>
         *
         * @return builder portant l'URL de base du fournisseur
         */
        @Bean
        RestClient.Builder builderIA() {
            return RestClient.builder().baseUrl("https://api.groq.com/openai/v1");
        }

        /**
         * Serveur attache au builder partage.
         *
         * <p>
         * L'ordre compte : {@code bindTo} installe la fabrique simulee sur le
         * builder, et seule la construction du client qui suit l'utilise.
         * Construire le client d'abord produirait un client partant sur la
         * vraie socket.
         * </p>
         *
         * @param builder builder partage
         * @return serveur simule injecte dans les tests
         */
        @Bean
        MockRestServiceServer serveurIA(RestClient.Builder builder) {
            return MockRestServiceServer.bindTo(builder).build();
        }

        /**
         * Client construit sur le builder deja equipe par {@code bindTo}.
         *
         * <p>
         * <strong>Aucune fabrique n'est posee ici</strong> :
         * {@code bindTo} a deja installe la sienne sur le builder partage.
         * Reposer {@code requestFactory(serveur)} echouerait, car le serveur
         * n'est pas une fabrique mais un verificateur d'attentes.
         * </p>
         *
         * <p>
         * Le serveur est declare en parametre pour garantir qu'il est cree —
         * et donc que le builder est equipe — avant la construction du client.
         * Spring respecte l'ordre des dependances.
         * </p>
         *
         * @param builder builder partage, deja equipe
         * @param serveur  serveur declare pour garantir l'ordre de creation
         * @return client utilise par la production, ici simule
         */
        @Bean
        @Primary
        RestClient restClientSimule(RestClient.Builder builder, MockRestServiceServer serveur) {
            return builder.build();
        }
    }
}


