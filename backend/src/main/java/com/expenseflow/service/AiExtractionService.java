package com.expenseflow.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.expenseflow.dto.ExtractExpenseLine;
import com.expenseflow.dto.ExtractExpenseResponse;
import com.expenseflow.entity.Category;
import com.expenseflow.entity.User;
import com.expenseflow.exception.llm.LlmExtractionException;
import com.expenseflow.repository.CategoryRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Extraction d'une depense depuis un texte en langage naturel.
 *
 * <p>
 * <strong>La sortie du modele n'est jamais reprise telle quelle.</strong> Un LLM
 * produit une reponse plausible, pas une reponse correcte : il peut halluciner
 * un identifiant de categorie, rendre un montant negatif, ou dater la depense
 * dans le futur. Chaque champ est donc verifie avant d'atteindre le
 * formulaire, et toute anomalie est signalee plutot que corrigee en silence.
 * </p>
 *
 * <p>
 * <strong>Les categories viennent de la base</strong>, et non d'une liste
 * ecrite dans le prompt. Une liste figee deriverait des de la premiere
 * categorie ajoutee ou supprimee : le modele proposerait alors un identifiant
 * inexistant, refuse ici mais cause pour l'utilisateur. Construire le prompt
 * depuis la base le rend incapable de diverger.
 * </p>
 */
@Service
public class AiExtractionService {

    private static final Logger log = LoggerFactory.getLogger(AiExtractionService.class);

    /** Catégorie retenue par défaut lorsque le modèle n'en distingue aucune. */
    private static final String CATEGORIE_PAR_DEFAUT = "Autre";

    private final LlmClient llmClient;
    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;
    private final AiCallLogService aiCallLogService;

    /**
     * @param llmClient         client d'appel au fournisseur de completion
     * @param categoryRepository source des categories proposees au modele
     * @param objectMapper      deserialiseur JSON de l'application
     * @param aiCallLogService  journal d'exploitation
     */
    public AiExtractionService(LlmClient llmClient,
            CategoryRepository categoryRepository,
            ObjectMapper objectMapper,
            AiCallLogService aiCallLogService) {
        this.llmClient = llmClient;
        this.categoryRepository = categoryRepository;
        this.objectMapper = objectMapper;
        this.aiCallLogService = aiCallLogService;
    }

    /**
     * Extrait une depense a partir de sa description en langage naturel.
     *
     * @param texte description libre de la depense
     * @return ligne pre-remplie, verifiee
     * @throws LlmExtractionException si la reponse est illisible ou hors bornes
     * @throws com.expenseflow.exception.llm.LlmException si l'appel au fournisseur echoue
     */
    public ExtractExpenseResponse extract(String texte, User currentUser) {
        // TODO: findAll() à chaque appel — les catégories changent rarement ;
        //       un simple cache applicatif éviterait une requête par extraction.
        List<Category> categories = categoryRepository.findAll();

        if (categories.isEmpty()) {
            // Sans categorie, aucune ligne ne peut etre construite : la note
            // exige une categorie par ligne. Le dire vaut mieux que renvoyer
            // une categorie nulle que le formulaire ne saurait pas traiter.
            throw new LlmExtractionException(
                    "Aucune categorie n'est configuree : l'extraction est indisponible.");
        }

        Long userId = currentUser == null ? null : currentUser.getId();
        String prompt = construirePrompt(texte, categories);
        long debut = System.nanoTime();

        try {
            LlmResponse reponse = llmClient.chatCompletionAvecUsage(prompt);

            ExtractExpenseResponse resultat =
                    new ExtractExpenseResponse(valider(analyser(reponse.content()), categories));

            // Journalise apres validation, non avant : une reponse que la
            // validation refuse n'est pas une extraction reussie, et la
            // compter comme telle gonflerait le taux de reussite d'exactement
            // la mesure qui doit surveiller les defaillances.
            journaliser(userId, debut, reponse, null);

            return resultat;
        } catch (Exception e) {
            // L'appel a echoue, ou sa reponse a ete rejetee : les deux cas
            // meritent une entree, le second portant type_erreur
            // LlmExtractionException.
            journaliser(userId, debut, null, e);
            throw e;
        }
    }

    /**
     * Extrait une depense depuis un texte en langage naturel.
     *
     * <p>
     * Variante sans auteur, reservee aux traitements internes ou aux tests.
     * L'appel HTTP passe l'utilisateur du jeton, afin que la consommation de
     * l'API soit attribuable.
     * </p>
     *
     * @param texte description libre de la depense
     * @return ligne pre-remplie, verifiee
     * @throws LlmExtractionException si la reponse est illisible ou hors bornes
     * @throws com.expenseflow.exception.llm.LlmException si l'appel au fournisseur echoue
     */
    public ExtractExpenseResponse extract(String texte) {
        return extract(texte, null);
    }

    /**
     * Enregistre un appel dans le journal d'exploitation.
     *
     * <p>
     * <strong>Jamais bloquant</strong> : {@link AiCallLogService#log} absorbe
     * lui-meme ses propres pannes, et l'appel ici n'est pas entoure d'un
     * {@code try/catch} supplementaire — la double protection n'apporterait
     * rien et masquerait les erreurs de programmation.
     * </p>
     *
     * @param userId   auteur de l'appel, {@code null} si inconnu
     * @param debut    instant de debut, en nanosecondes
     * @param reponse  reponse du modele, ou {@code null} si l'appel a echoue
     * @param echec    exception rencontree, ou {@code null} si tout s'est bien passe
     */
    private void journaliser(Long userId, long debut, LlmResponse reponse, Exception echec) {
        long dureeMs = (System.nanoTime() - debut) / 1_000_000L;

        aiCallLogService.log(userId, dureeMs, echec == null,
                echec == null ? null : echec.getClass().getSimpleName(),
                echec == null ? null : echec.getMessage(),
                reponse == null ? null : reponse.tokensPrompt(),
                reponse == null ? null : reponse.tokensCompletion(),
                reponse == null ? null : reponse.tokensTotal(),
                // Le nom du modele est demande au client plutot que recopie ici :
                // la colonne sert a reconstituer une facture, et deux chaines
                // identiques dans deux fichiers finissent par diverger a la
                // premiere evolution de l'une des deux.
                llmClient.modele());
    }

    /**
     * Compose le prompt adresse au modele.
     *
     * <p>
     * Le prompt travaille par <em>contraintes explicites</em> plutot que par
     * exemple seul : le format de sortie est decrit, la liste des categories
     * est fournie, et le comportement attendu en cas d'information manquante
     * est precise. Un exemple illustre la reponse sans la rendre obligatoire.
     * </p>
     *
     * <p>
     * <strong>Les categories sont injectees depuis la base</strong>, avec leur
     * identifiant reel. Le modele ne peut donc pas proposer une categorie
     * inventee : tout identifiant hors liste sera refuse a la validation.
     * </p>
     *
     * @param texte      description libre de la depense
     * @param categories categories disponibles
     * @return prompt complet
     */
    private String construirePrompt(String texte, List<Category> categories) {
        StringJoiner listeCategories = new StringJoiner("\n");
        for (Category categorie : categories) {
            listeCategories.add("- " + categorie.getId() + " : " + categorie.getNom());
        }

        // Categorie de repli : « Autre » si elle existe, sinon la premiere
        // trouvee. Un identifiant figure dans le prompt doit toujours exister,
        // meme dans une base dont la categorie « Autre » aurait ete supprimee.
        Long idParDefaut = categories.stream()
                .filter(c -> CATEGORIE_PAR_DEFAUT.equalsIgnoreCase(c.getNom()))
                .map(Category::getId)
                .findFirst()
                .orElse(categories.getFirst().getId());

        String aujourdhui = LocalDate.now().toString();
        String hier = LocalDate.now().minusDays(1).toString();

        return """
                Tu analyses une depense professionnelle decrite en langage naturel.

                Reponds UNIQUEMENT par un objet JSON valide, sans aucun texte
                autour, ni explication, ni bloc de code. Le format exact est :

                {
                  "montant": <nombre decimal positif>,
                  "dateDepense": "<AAAA-MM-JJ>",
                  "categorieId": <identifiant de categorie>,
                  "description": "<resume court, sans guillemets>"
                }

                CATEGORIES DISPONIBLES (identifiant : libelle) :
                %s

                REGLES
                - Si le montant est absent ou illisible, mets null.
                - Si la date est absente, mets "%s".
                - Si la depense n'entre dans aucune categorie, utilise %d.
                - Sans devise ecrite, suppose le dirham marocain.
                - Une depense ne peut pas avoir lieu dans le futur.

                Exemple pour "taxi 45 dh vers l'aeroport hier" :
                {"montant": 45.00, "dateDepense": "%s", "categorieId": %d, \
                "description": "Taxi vers l'aeroport"}

                Texte a analyser :
                "%s"
                """.formatted(listeCategories, aujourdhui, idParDefaut, hier, idParDefaut, texte);
    }

    /**
     * Extrait l'objet JSON de la reponse textuelle du modele.
     *
     * <p>
     * <strong>Les fences Markdown sont tolerees.</strong> Meme en
     * {@code response_format: json_object}, un modele peut encadrer sa reponse
     * de <code>```json</code>. Refuser ce cas revient a perdre une extraction
     * parfaitement correcte pour un detail de mise en forme ; la regle reste
     * toutefois stricte sur le fond, car le contenu est valide ensuite.
     * </p>
     *
     * @param brut texte produit par le modele
     * @return objet deserialise
     * @throws LlmExtractionException si aucun objet JSON n'est trouvable
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> analyser(String brut) {
        String json = brut.trim();

        int debutFence = json.indexOf("```");
        if (debutFence >= 0) {
            int finFence = json.indexOf("```", debutFence + 3);
            if (finFence < 0) {
                throw new LlmExtractionException("Reponse du modele tronquee.");
            }
            // Le nom de langage ("json") suit directement les trois accents.
            String interieur = json.substring(debutFence + 3, finFence);
            int premiere = interieur.indexOf('\n');
            json = premiere >= 0 ? interieur.substring(premiere + 1) : interieur;
        }

        json = json.trim();

        // Le modele peut prefixer le JSON d'une phrase. On ne retient que la
        // portion entre la premiere et la derniere accolade, qui contient
        // l'objet attendu.
        int debutObjet = json.indexOf('{');
        int finObjet = json.lastIndexOf('}');
        if (debutObjet < 0 || finObjet <= debutObjet) {
            throw new LlmExtractionException(
                    "La reponse du modele ne contient pas d'objet JSON.");
        }

        try {
            return objectMapper.readValue(json.substring(debutObjet, finObjet + 1), Map.class);
        } catch (Exception e) {
            // La cause est conservee pour le journal ; le message rendu a
            // l'utilisateur reste generique, un detail de deserialisation
            // n'ayant aucun interet pour lui.
            log.debug("Reponse du modele illisible : {}", e.getMessage());
            throw new LlmExtractionException("La reponse du modele est illisible.", e);
        }
    }

    /**
     * Verifie chaque champ et renvoie la ligne exploitable.
     *
     * @param brut       objet deserialise
     * @param categories categories autorisees
     * @return ligne validee
     * @throws LlmExtractionException si un champ depasse les bornes admises
     */
    private ExtractExpenseLine valider(Map<String, Object> brut, List<Category> categories) {
        return new ExtractExpenseLine(
                lireCategorie(brut.get("categorieId"), categories),
                lireMontant(brut.get("montant")),
                lireDate(brut.get("dateDepense")),
                lireDescription(brut.get("description")));
    }

    /**
     * Lit et verifie le montant.
     *
     * <p>
     * Un montant absent est signale, pas comble par zero : le formulaire
     * refuserait une ligne sans montant, et pre-remplir zero proposerait a
     * l'utilisateur une valeur fausse qu'il n'aurait aucune raison de
     * contester.
     * </p>
     *
     * @param valeur valeur brute, telle que deserialisee
     * @return montant a deux decimales
     * @throws LlmExtractionException si le montant est absent ou hors bornes
     */
    private BigDecimal lireMontant(Object valeur) {
        if (valeur == null) {
            throw new LlmExtractionException(
                    "Montant non determine : completez-le manuellement.");
        }

        BigDecimal montant;
        try {
            // Passage par une chaine : selon la forme ecrite, un nombre peut
            // etre deserialise en Integer, Double ou BigDecimal.
            montant = new BigDecimal(String.valueOf(valeur));
        } catch (NumberFormatException e) {
            throw new LlmExtractionException("Montant illisible : " + valeur, e);
        }

        if (montant.signum() <= 0) {
            // Nul ou negatif : la regle @Positive du formulaire refuserait la
            // ligne a la creation. Mieux vaut le dire maintenant.
            throw new LlmExtractionException("Montant invalide : " + valeur);
        }

        return montant.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Lit et verifie la date de depense.
     *
     * @param valeur valeur brute
     * @return date de la depense
     * @throws LlmExtractionException si la date est illisible ou future
     */
    private LocalDate lireDate(Object valeur) {
        if (valeur == null) {
            // Le prompt demande la date du jour dans ce cas ; le modele peut
            // ne pas l'appliquer. La retenir evite un formulaire vide.
            return LocalDate.now();
        }

        LocalDate date;
        try {
            date = LocalDate.parse(String.valueOf(valeur).trim());
        } catch (DateTimeParseException e) {
            throw new LlmExtractionException("Date de depense illisible : " + valeur, e);
        }

        if (date.isAfter(LocalDate.now())) {
            // Signe d'hallucination : le modele a inverse hier et demain.
            // Signaler vaut mieux que proposer une date aberrante.
            throw new LlmExtractionException("Date de depense dans le futur : " + date);
        }

        return date;
    }

    /**
     * Verifie que la categorie proposee existe reellement en base.
     *
     * @param valeur     valeur brute
     * @param categories categories autorisees
     * @return identifiant de la categorie
     * @throws LlmExtractionException si la categorie est absente ou inconnue
     */
    private Long lireCategorie(Object valeur, List<Category> categories) {
        if (valeur == null) {
            throw new LlmExtractionException(
                    "Categorie non determinee : choisissez-la manuellement.");
        }

        Long identifiant;
        try {
            identifiant = Long.parseLong(String.valueOf(valeur).trim());
        } catch (NumberFormatException e) {
            throw new LlmExtractionException("Categorie illisible : " + valeur, e);
        }

        boolean existe = categories.stream().anyMatch(c -> c.getId().equals(identifiant));
        if (!existe) {
            // Cas attendu, et le plus interessant : malgre la liste des
            // categories fournie dans le prompt, le modele propose un
            // identifiant qui n'existe pas.
            throw new LlmExtractionException("Categorie inconnue : " + identifiant);
        }

        return identifiant;
    }

    /**
     * Normalise la description.
     *
     * <p>
     * Le champ est facultatif : une reponse sans description n'empêche pas de
     * pre-remplir le formulaire, et le modele echoue parfois a en produire une.
     * Seule une longueur excessive est signalee, plutot que de tronquer en
     * silence une donnee deja copiee dans un formulaire.
     * </p>
     *
     * @param valeur valeur brute
     * @return description, ou {@code null} si absente
     * @throws LlmExtractionException si la description depasse la limite
     */
    private String lireDescription(Object valeur) {
        if (valeur == null) {
            return null;
        }

        String description = String.valueOf(valeur).trim();
        if (description.isEmpty()) {
            return null;
        }

        if (description.length() > 500) {
            // Meme limite que @Size(max = 500) du formulaire : au-dela, la
            // ligne serait refusee a la creation, apres avoir ete affichee.
            throw new LlmExtractionException("Description trop longue.");
        }

        return description;
    }
}