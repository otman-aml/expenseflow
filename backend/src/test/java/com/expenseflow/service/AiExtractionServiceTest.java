package com.expenseflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.expenseflow.dto.ExtractExpenseLine;
import com.expenseflow.dto.ExtractExpenseResponse;
import com.expenseflow.entity.Category;
import com.expenseflow.exception.llm.LlmExtractionException;
import com.expenseflow.repository.CategoryRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Tests de l'extraction d'une depense depuis un texte libre.
 *
 * <p>
 * Le client Llm est simule : ces tests verifient <em>ce que le service
 * fait de la reponse du modele</em>, pas la possibilite de joindre l'API. C'est
 * precisement ce que la section 3.1 verifie deja par ailleurs.
 * </p>
 *
 * <p>
 * <strong>Les cas d'echec sont le cœur du sujet.</strong> Un modele produit des
 * reponses plausibles mais fausses : montant negatif, date futuree, categorie
 * inventee. Chaque test pins un cas reel de defaillance, pas une commodite.
 * </p>
 */
@DisplayName("AiExtractionService (unitaire)")
class AiExtractionServiceTest {

    private static final Long ID_TRANSPORT = 4L;
    private static final Long ID_AUTRE = 7L;

    private LlmClient llmClient;
    private CategoryRepository categoryRepository;
    private AiCallLogService aiCallLogService;
    private AiExtractionService service;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        // Le nom du modele est desormais demande au client. Sans stub, un mock
        // renverrait null et le journal porterait un modele vide — un test
        // passerait quand meme, alors que la colonne facturee serait fausse.
        given(llmClient.modele()).willReturn("openai/gpt-oss-120b");
        categoryRepository = mock(CategoryRepository.class);
        aiCallLogService = mock(AiCallLogService.class);

        given(categoryRepository.findAll()).willReturn(List.of(
                Category.builder().id(ID_TRANSPORT).nom("Transport").build(),
                Category.builder().id(5L).nom("Restauration").build(),
                Category.builder().id(ID_AUTRE).nom("Autre").build()));

        service = new AiExtractionService(llmClient, categoryRepository,
                new ObjectMapper(), aiCallLogService);
    }

    /**
     * Fait repondre le modele par le contenu fourni.
     *
     * @param contenu texte simule
     */
    private void reponseDuModele(String contenu) {
        given(llmClient.chatCompletionAvecUsage(anyString()))
                .willReturn(new LlmResponse(contenu, 120, 45, 165));
    }

    // ---------------------------------------------------------------------
    // A) Extraction nominale
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Une reponse complete est traduite en ligne pre-remplie")
    void reponseComplete_renvoieLaLigne() {
        // Given
        reponseDuModele("""
                {"montant": 45.00, "dateDepense": "2026-09-20", \
                "categorieId": 4, "description": "Taxi vers l'aeroport"}""");

        // When
        ExtractExpenseResponse resultat = service.extract("taxi 45 dh hier");

        // Then
        ExtractExpenseLine ligne = resultat.ligne();
        assertThat(ligne.montant()).isEqualByComparingTo("45.00");
        assertThat(ligne.dateDepense()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(ligne.categorieId()).isEqualTo(ID_TRANSPORT);
        assertThat(ligne.description()).isEqualTo("Taxi vers l'aeroport");
    }

    @Test
    @DisplayName("Le prompt contient les categories reelles et le texte a analyser")
    void prompt_porteLesCategoriesEtLeTexte() {
        // Given
        reponseDuModele("""
                {"montant": 10, "dateDepense": "2026-09-20", "categorieId": 4, \
                "description": "x"}""");

        // When
        service.extract("dejeuner 10 dh");

        // Then : la liste provient de la base, pas d'un litteral du code. Une
        // categorie ajoutee en base apparaitrait automatiquement au prompt.
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).chatCompletionAvecUsage(prompt.capture());

        assertThat(prompt.getValue())
                .contains("4 : Transport")
                .contains("5 : Restauration")
                .contains("7 : Autre")
                .contains("dejeuner 10 dh");
    }

    @Test
    @DisplayName("Une reponse enchevetree de fences Markdown est acceptee")
    void reponseAvecFences_estAcceptee() {
        // Given : le mode JSON n'empêche pas un modele d'encadrer sa reponse
        reponseDuModele("""
                Voici l'analyse :
                ```json
                {"montant": 30.00, "dateDepense": "2026-09-20", \
                "categorieId": 5, "description": "Dejeuner"}
                ```""");

        // When
        ExtractExpenseResponse resultat = service.extract("dejeuner 30 dh");

        // Then : un detail de mise en forme ne doit pas faire perdre une
        // extraction parfaitement correcte.
        assertThat(resultat.ligne().montant()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("Un appel reussi est journalise avec ses compteurs de tokens")
    void extract_reussi_journalise() {
        // Given
        reponseDuModele("""
                {"montant": 45.00, "dateDepense": "2026-09-20", \
                "categorieId": 4, "description": "Taxi"}""");

        // When
        service.extract("taxi 45 dh");

        // Then : sans cette entree, la degradation de l'API resterait invisible.
        org.mockito.Mockito.verify(aiCallLogService).log(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(120),
                org.mockito.ArgumentMatchers.eq(45),
                org.mockito.ArgumentMatchers.eq(165),
                org.mockito.ArgumentMatchers.eq("openai/gpt-oss-120b"));
    }

    @Test
    @DisplayName("Le modele journalise vient du client, et non d'une constante du service")
    void extract_modeleJournalise_provientDuClient() {
        // Given : un client annonce un autre modele — ce qu'il arriverait apres
        // un changement de cote fournisseur ou de version.
        given(llmClient.modele()).willReturn("llama-3.1-8b-instant");
        reponseDuModele("""
                {"categorieId":%d,"montant":10.0,"dateDepense":"2026-01-15","description":"x"}
                """.formatted(ID_TRANSPORT));

        // When
        service.extract("depense");

        // Then : le journal suit le client. Une constante recopiee dans le
        // service, elle, aurait continue d'annoncer l'ancien modele et
        // aurait fausse la reconstitution de la facture.
        org.mockito.Mockito.verify(aiCallLogService).log(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(120),
                org.mockito.ArgumentMatchers.eq(45),
                org.mockito.ArgumentMatchers.eq(165),
                org.mockito.ArgumentMatchers.eq("llama-3.1-8b-instant"));
    }

    @Test
    @DisplayName("Une reponse rejetee par la validation est journalisee en echec")
    void extract_reponseInvalide_journaliseeEnEchec() {
        // Given : une categorie inventee, refusee par la validation
        reponseDuModele("""
                {"montant": 45.00, "dateDepense": "2026-09-20", "categorieId": 99, \
                "description": "Categorie inventee"}""");

        // When / Then
        assertThatThrownBy(() -> service.extract("taxi"))
                .isInstanceOf(LlmExtractionException.class);

        // La journalisation porte succes = false : une reponse rejetee
        // n'est pas une extraction reussie, et la compter comme telle
        // gonflerait le taux de reussite de la metrique meme a surveiller.
        org.mockito.Mockito.verify(aiCallLogService).log(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("LlmExtractionException"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyString());
    }

    // ---------------------------------------------------------------------
    // B) Reponses inexploitables
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Un texte sans objet JSON est refuse")
    void texteLibre_estRefuse() {
        // Given : le modele ignore la consigne et repond en prose
        reponseDuModele("Je n'ai pas pu determiner le montant de cette depense.");

        // When / Then
        assertThatThrownBy(() -> service.extract("depense variee"))
                .isInstanceOf(LlmExtractionException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    @DisplayName("Un JSON syntaxiquement invalide est refuse")
    void jsonInvalide_estRefuse() {
        reponseDuModele("{\"montant\": 45, \"dateDepense\": }");

        assertThatThrownBy(() -> service.extract("taxi 45"))
                .isInstanceOf(LlmExtractionException.class)
                .hasMessageContaining("illisible");
    }

    @Test
    @DisplayName("Un montant negatif est refuse : le formulaire le refuserait aussi")
    void montantNegatif_estRefuse() {
        reponseDuModele("""
                {"montant": -45.00, "dateDepense": "2026-09-20", "categorieId": 4, \
                "description": "Erreur de signe"}""");

        assertThatThrownBy(() -> service.extract("taxi"))
                .isInstanceOf(LlmExtractionException.class)
                .hasMessageContaining("Montant invalide");
    }

    @Test
    @DisplayName("Un montant absent est signale, pas remplace par zero")
    void montantAbsent_estRefuse() {
        // Given : le modele n'a pas trouve de montant
        reponseDuModele("""
                {"montant": null, "dateDepense": "2026-09-20", "categorieId": 4, \
                "description": "Depense sans montant"}""");

        // When / Then : pre-remplir zero proposerait une valeur fausse que
        // l'utilisateur n'aurait aucune raison de contester.
        assertThatThrownBy(() -> service.extract("une depense"))
                .isInstanceOf(LlmExtractionException.class)
                .hasMessageContaining("Montant non determine");
    }

    @Test
    @DisplayName("Une date dans le futur est refusee : le modele a inverse hier et demain")
    void dateFuture_estRefusee() {
        reponseDuModele("""
                {"montant": 45.00, "dateDepense": "2099-01-01", "categorieId": 4, \
                "description": "Date aberrante"}""");

        assertThatThrownBy(() -> service.extract("taxi"))
                .isInstanceOf(LlmExtractionException.class)
                .hasMessageContaining("futur");
    }

    @Test
    @DisplayName("Une categorie inexistante est refusee, meme si elle figure au prompt")
    void categorieInconnue_estRefusee() {
        // Given : 99 est plausible mais absent de la base
        reponseDuModele("""
                {"montant": 45.00, "dateDepense": "2026-09-20", "categorieId": 99, \
                "description": "Categorie inventee"}""");

        // When / Then
        assertThatThrownBy(() -> service.extract("taxi"))
                .isInstanceOf(LlmExtractionException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("Une date illisible est refusee")
    void dateIllisible_estRefusee() {
        reponseDuModele("""
                {"montant": 45.00, "dateDepense": "hier", "categorieId": 4, \
                "description": "Date en toutes lettres"}""");

        assertThatThrownBy(() -> service.extract("taxi"))
                .isInstanceOf(LlmExtractionException.class)
                .hasMessageContaining("Date");
    }

    @Test
    @DisplayName("Sans categorie configuree, l'extraction est refusee plutot que de deviner")
    void aucuneCategorie_estRefusee() {
        // Given : une base sans categorie, ou aucune ligne n'est constructible
        given(categoryRepository.findAll()).willReturn(List.of());

        // When / Then
        assertThatThrownBy(() -> service.extract("taxi 45"))
                .isInstanceOf(LlmExtractionException.class)
                .hasMessageContaining("Aucune categorie");
    }
}