package com.expenseflow.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.dto.AiStatsResponse;
import com.expenseflow.entity.AiCallLog;
import com.expenseflow.entity.User;
import com.expenseflow.repository.AiCallLogRepository;
import com.expenseflow.repository.UserRepository;

/**
 * Journalisation et consultation des appels à l'API d'extraction.
 *
 * <p>
 * <strong>Un échec de journalisation n'interrompt jamais l'appel métier</strong>.
 * Les methodes {@code REQUIRES_NEW} et {@code try/catch} ci-dessous ne sont pas
 * défensives : sans elles, une base momentanément indisponible ferait échouer
 * une extraction parfaitement réussie. Le journal est un outil de mesure, il
 * n'a pas à devenir une dépendance du service qu'il mesure.
 * </p>
 */
@Service
public class AiCallLogService {

    private static final Logger log = LoggerFactory.getLogger(AiCallLogService.class);

    /**
     * Longueur maximale du message d'erreur conserve.
     *
     * <p>
     * 500 caracteres suffisent a identifier une panne sans recopier une reponse
     * d'API entiere. Le message d'une exception du SDK peut contenir le corps
     * JSON de l'erreur — et le texte de l'utilisateur qui a declenche l'appel.
     * </p>
     */
    private static final int LONGUEUR_MAX_MESSAGE = 500;

    private static final int FENETRE_24H = 24;

    private final AiCallLogRepository aiCallLogRepository;
    private final UserRepository userRepository;

    /**
     * @param aiCallLogRepository dépôt du journal
     * @param userRepository      résolution de l'auteur de l'appel
     */
    public AiCallLogService(AiCallLogRepository aiCallLogRepository, UserRepository userRepository) {
        this.aiCallLogRepository = aiCallLogRepository;
        this.userRepository = userRepository;
    }

    /**
     * Enregistre un appel.
     *
     * <p>
     * <strong>Transaction separee ({@code REQUIRES_NEW})</strong> : l'entrée doit
     * survivre même si la transaction métier est annulée en aval — ce qui est le
     * cas pour un appel en échec, précisément celui qu'on veut mesurer.
     * </p>
     *
     * @param userId            auteur de l'appel, {@code null} si inconnu
     * @param dureeMs           durée de l'appel en millisecondes
     * @param succes            issue de l'appel
     * @param typeErreur         nom court de l'exception, {@code null} si succès
     * @param messageErreur      message de l'erreur, tronqué si besoin
     * @param tokensPrompt      tokens de l'invite, {@code null} si non communiqués
     * @param tokensCompletion  tokens de la completion, {@code null} si non communiqués
     * @param tokensTotal       total facturé, {@code null} si non communiqué
     * @param modele            modèle utilisé
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long userId, long dureeMs, boolean succes, String typeErreur,
            String messageErreur, Integer tokensPrompt, Integer tokensCompletion,
            Integer tokensTotal, String modele) {
        try {
            User utilisateur = userId == null ? null : userRepository.findById(userId).orElse(null);

            aiCallLogRepository.save(AiCallLog.builder()
                    .utilisateur(utilisateur)
                    .dureeMs(dureeMs)
                    .succes(succes)
                    .typeErreur(typeErreur)
                    .messageErreur(abriger(messageErreur))
                    .tokensPrompt(tokensPrompt)
                    .tokensCompletion(tokensCompletion)
                    .tokensTotal(tokensTotal)
                    .modele(modele)
                    .build());
        } catch (Exception e) {
            // Journalise et laisse passer : l'appel métier a deja reussi ou
            // echoue pour sa propre raison, et cette panne ne doit pas en
            // devenir une autre.
            log.warn("Journalisation d'un appel IA impossible : {}", e.getMessage());
        }
    }

    /**
     * Calcule les mesures d'usage.
     *
     * @return mesures courantes
     */
    @Transactional(readOnly = true)
    public AiStatsResponse getStats() {
        long total = aiCallLogRepository.count();
        long reussis = aiCallLogRepository.countBySucces(true);
        long echoues = aiCallLogRepository.countBySucces(false);
        Double dureeMoyenne = aiCallLogRepository.averageDurationMs();

        // Deux agregats distincts : le cumul sert a reconstituer la facture,
        // la fenetre de 24 h a suivre une tendance recente. Les confondre ne
        // servirait ni l'un ni l'autre.
        Long tokensCumules = aiCallLogRepository.sumTokens();
        Long tokens24h = aiCallLogRepository
                .sumTokensSince(Instant.now().minus(FENETRE_24H, ChronoUnit.HOURS));

        return new AiStatsResponse(
                total,
                reussis,
                echoues,
                dureeMoyenne,
                tokensCumules,
                tokens24h == null ? 0L : tokens24h,
                aiCallLogRepository.findFirstByOrderByDateAppelDesc()
                        .map(AiCallLog::getDateAppel)
                        .orElse(null));
    }

    /**
     * Tronque un message d'erreur a une longueur lisible.
     *
     * @param message message d'origine, {@code null} accepte
     * @return message abrege, ou {@code null} si rien a conserver
     */
    private String abriger(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }

        if (message.length() <= LONGUEUR_MAX_MESSAGE) {
            return message;
        }

        return message.substring(0, LONGUEUR_MAX_MESSAGE) + "…";
    }
}