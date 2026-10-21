package com.expenseflow.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.expenseflow.entity.AiCallLog;

/**
 * Accès au journal des appels à l'API d'extraction.
 *
 * <p>
 * Les agrégats sont calculés par la base et non en Java : les charger toutes
 * pour les sommer en mémoire reviendrait à lire l'intégralité d'un journal qui
 * croît sans borne, pour produire un nombre.
 * </p>
 */
public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {

    /**
     * Compte les appels selon leur issue.
     *
     * @param succes issue recherchee
     * @return nombre d'appels correspondants
     */
    long countBySucces(boolean succes);

    /**
     * Compte les appels sur une periode.
     *
     * @param instant debut de la periode
     * @return nombre d'appels posterieurs a cet instant
     */
    long countByDateAppelAfter(Instant instant);

    /**
     * Durée moyenne des appels reussis, en millisecondes.
     *
     * <p>
     * <strong>Les echecs sont exclus</strong> : un appel ayant echoue apres avoir
     * atteint le delai de 15 s mesurerait une latence fausse. Le nombre reflects
     * ce que l'utilisateur a reellement attendu.
     * </p>
     *
     * @return duree moyenne, ou {@code null} si aucun appel n'a abouti
     */
    @Query("SELECT AVG(a.dureeMs) FROM AiCallLog a WHERE a.succes = true")
    Double averageDurationMs();

    /**
     * Total des tokens factures sur toute la vie du journal.
     *
     * @return somme des tokens, ou {@code null} si aucun appel n'en a communique
     */
    @Query("SELECT SUM(a.tokensTotal) FROM AiCallLog a")
    Long sumTokens();

    /**
     * Total des tokens factures depuis un instant.
     *
     * <p>
     * <strong>Les lignes sans compteurs sont ignorées</strong> : en SQL,
     * {@code SUM} sur une colonne majoritairement nulle renvoie {@code null}
     * plutot que 0, ce qui est ici le résultat correct — aucun token
     * communiqué n'a été consommé.
     * </p>
     *
     * @param since debut de la periode
     * @return somme des tokens, ou {@code null} si aucun n'est connu
     */
    @Query("SELECT SUM(a.tokensTotal) FROM AiCallLog a WHERE a.dateAppel >= :since")
    Long sumTokensSince(@Param("since") Instant since);

    /**
     * Dernier appel enregistre, du plus recent au plus ancien.
     *
     * @return dernier appel, ou vide si le journal est vide
     */
    Optional<AiCallLog> findFirstByOrderByDateAppelDesc();
}