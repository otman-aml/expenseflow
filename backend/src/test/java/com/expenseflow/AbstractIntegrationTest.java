package com.expenseflow;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base commune des tests d'integration adosses a une vraie base PostgreSQL.
 *
 * <p>
 * Le conteneur est declare ici, une seule fois pour l'ensemble des tests
 * d'integration : toutes les classes de test heritent de la meme
 * configuration de contexte, donc Spring demarre une seule fois et reutilise
 * ensuite le meme conteneur pour toutes les autres.
 * </p>
 *
 * <p>
 * <strong>Pourquoi pas {@code @Testcontainers}</strong> : l'annotation n'est pas
 * necessaire des lors qu'on utilise {@link ServiceConnection}. Pire, elle est
 * source de bogues : l'extension JUnit tente de demarrer le conteneur
 * <em>a chaque classe de test</em>, alors que Spring l'a deja demarre pour le
 * contexte. On obtenait alors plusieurs conteneurs, dont les premiers
 * etaient arretes, pendant que le contexte Spring conservait l'URL du premier :
 * les tests suivants echouaient alors sur
 * {@code Connection refused}. {@code @ServiceConnection} suffit, Spring Boot
 * gerant seul le demarrage et l'arret.
 * </p>
 *
 * <p>
 * Le conteneur est independant de l'instance de developpement locale, qui ecoute
 * sur le port 5434 : les tests d'integration ne touchent jamais les donnees du
 * poste de travail. Le profil {@code test} surcharge par ailleurs le profil
 * {@code local} declare dans {@code application.properties}, afin que
 * {@code application-local.properties} ne soit pas charge.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    /**
     * Conteneur PostgreSQL jetable, partage par toutes les classes de test
     * d'integration.
     *
     * <p>
     * Le type n'est plus parametre depuis Testcontainers 2.x.
     * </p>
     */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
}