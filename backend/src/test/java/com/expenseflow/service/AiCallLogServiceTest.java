package com.expenseflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.expenseflow.dto.AiStatsResponse;
import com.expenseflow.entity.AiCallLog;
import com.expenseflow.repository.AiCallLogRepository;
import com.expenseflow.repository.UserRepository;

/**
 * Tests du journal d'appels et du calcul des mesures d'usage.
 *
 * <p>
 * Le depot est simule : ces tests verifient l'assemblage des compteurs et les
 * garanties offertes par le service — notamment le fait qu'une panne de
 * journalisation n'interrompt pas l'appel metier.
 * </p>
 */
@DisplayName("AiCallLogService (unitaire)")
class AiCallLogServiceTest {

    private AiCallLogRepository aiCallLogRepository;
    private UserRepository userRepository;
    private AiCallLogService service;

    @BeforeEach
    void setUp() {
        aiCallLogRepository = mock(AiCallLogRepository.class);
        userRepository = mock(UserRepository.class);
        service = new AiCallLogService(aiCallLogRepository, userRepository);
    }

    /**
     * Recupere l'entree enregistree par le dernier appel a {@code log}.
     *
     * @return entite capturee
     */
    private AiCallLog entreeEnregistree() {
        ArgumentCaptor<AiCallLog> captor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(aiCallLogRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------------
    // A) Journalisation
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Un appel reussi est enregistre avec sa duree et ses tokens")
    void log_reussi_enregistreLAppel() {
        // When
        service.log(7L, 1234L, true, null, null, 120, 45, 165, "llm-small-latest");

        // Then
        AiCallLog entree = entreeEnregistree();
        assertThat(entree.getDureeMs()).isEqualTo(1234L);
        assertThat(entree.getSucces()).isTrue();
        assertThat(entree.getTokensPrompt()).isEqualTo(120);
        assertThat(entree.getTokensTotal()).isEqualTo(165);
        assertThat(entree.getModele()).isEqualTo("llm-small-latest");
        assertThat(entree.getTypeErreur()).isNull();
    }

    @Test
    @DisplayName("Un appel en echec conserve le type et le message de l'erreur")
    void log_echec_enregistreLErreur() {
        // When
        service.log(7L, 15000L, false, "LlmTimeoutException",
                "Delai depasse", null, null, null, "llm-small-latest");

        // Then : c'est cette entree qui rendra visible une degradation de la
        // latence, qu'un simple compteur de reussite aurait masquee.
        AiCallLog entree = entreeEnregistree();
        assertThat(entree.getSucces()).isFalse();
        assertThat(entree.getTypeErreur()).isEqualTo("LlmTimeoutException");
        assertThat(entree.getMessageErreur()).contains("Delai depasse");
    }

    @Test
    @DisplayName("Un message d'erreur trop long est abrege")
    void log_messageTropLong_estAbrege() {
        // Given : un message depassant largement la limite de 500 caracteres
        // When
        service.log(null, 10L, false, "LlmException", "x".repeat(2000),
                null, null, null, "m");

        // Then
        assertThat(entreeEnregistree().getMessageErreur()).hasSize(501).endsWith("…");
    }

    @Test
    @DisplayName("Une panne de journalisation n'interrompt pas l'appel")
    void log_panneDeDepot_absorbee() {
        // Given : le depot est indisponible
        willThrow(new RuntimeException("base injoignable"))
                .given(aiCallLogRepository).save(any());

        // When / Then : l'appel metier doit aboutir malgre tout. C'est la
        // garantie centrale du service.
        assertThatCode(() -> service.log(7L, 10L, true, null, null, 1, 1, 2, "m"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Un utilisateur introuvable n'empêche pas l'enregistrement")
    void log_utilisateurInconnu_tolerance() {
        // Given : le compte a ete supprime entre l'appel et le journal
        given(userRepository.findById(99L)).willReturn(Optional.empty());

        // When / Then
        assertThatCode(() -> service.log(99L, 10L, true, null, null, 1, 1, 2, "m"))
                .doesNotThrowAnyException();

        assertThat(entreeEnregistree().getUtilisateur()).isNull();
    }

    // ---------------------------------------------------------------------
    // B) Mesures d'usage
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Les compteurs reflectent l'etat du journal")
    void getStats_compteurs() {
        // Given
        Instant hier = Instant.now().minusSeconds(3600);
        given(aiCallLogRepository.count()).willReturn(30L);
        given(aiCallLogRepository.countBySucces(true)).willReturn(27L);
        given(aiCallLogRepository.countBySucces(false)).willReturn(3L);
        given(aiCallLogRepository.averageDurationMs()).willReturn(1420.5);
        given(aiCallLogRepository.sumTokens()).willReturn(45000L);
        given(aiCallLogRepository.sumTokensSince(any())).willReturn(1200L);
        given(aiCallLogRepository.findFirstByOrderByDateAppelDesc())
                .willReturn(Optional.of(AiCallLog.builder().dateAppel(hier).build()));

        // When
        AiStatsResponse stats = service.getStats();

        // Then
        assertThat(stats.totalAppels()).isEqualTo(30);
        assertThat(stats.appelsReussis()).isEqualTo(27);
        assertThat(stats.appelsEchoues()).isEqualTo(3);
        assertThat(stats.dureeMoyenneMs()).isEqualTo(1420.5);
        assertThat(stats.dernierAppel()).isEqualTo(hier);
        // Le taux n'est pas un champ du record : il se calcule a la demande,
        // ce qui evite qu'un client construise l'objet et oublie un champ derive.
        assertThat(stats.tauxReussite()).isEqualTo(90.0);
    }

    @Test
    @DisplayName("Un journal vide ne produit ni division par zero ni faux positif")
    void getStats_journalVide() {
        // Given
        given(aiCallLogRepository.count()).willReturn(0L);
        given(aiCallLogRepository.countBySucces(true)).willReturn(0L);
        given(aiCallLogRepository.countBySucces(false)).willReturn(0L);
        given(aiCallLogRepository.averageDurationMs()).willReturn(null);
        given(aiCallLogRepository.sumTokens()).willReturn(null);
        given(aiCallLogRepository.sumTokensSince(any())).willReturn(null);
        given(aiCallLogRepository.findFirstByOrderByDateAppelDesc()).willReturn(Optional.empty());

        // When
        AiStatsResponse stats = service.getStats();

        // Then : 0 % et non 100 %. Aucun appel n'a abouti parce qu'aucun n'a
        // eu lieu ; annoncer 100 % serait un mensonge exploitable.
        assertThat(stats.tauxReussite()).isZero();
        assertThat(stats.tokens24h()).isZero();
        assertThat(stats.tokensTotal()).isNull();
        assertThat(stats.dernierAppel()).isNull();
    }

    @Test
    @DisplayName("Le cumul de tokens ne se confond pas avec la fenetre de 24 h")
    void getStats_tokensSepares() {
        // Given : un historique long et une activite recente
        given(aiCallLogRepository.count()).willReturn(100L);
        given(aiCallLogRepository.countBySucces(true)).willReturn(100L);
        given(aiCallLogRepository.countBySucces(false)).willReturn(0L);
        given(aiCallLogRepository.sumTokens()).willReturn(50000L);
        given(aiCallLogRepository.sumTokensSince(any())).willReturn(500L);

        // When
        AiStatsResponse stats = service.getStats();

        // Then
        assertThat(stats.tokensTotal()).isEqualTo(50000L);
        assertThat(stats.tokens24h()).isEqualTo(500L);
    }
}