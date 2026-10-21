package com.expenseflow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.AbstractIntegrationTest;

/**
 * Test de fumee : verifie que l'infrastructure de test d'integration fonctionne.
 *
 * <p>
 * Ce test ne valide aucune requete metier. Il sert uniquement a prouver, avant
 * d'ecrire les tests de repository, que les trois briques fonctionnent ensemble :
 * le conteneur PostgreSQL jetable demarre, la migration Flyway V1 cree le
 * schema, et Spring se branche dessus.
 * </p>
 *
 * <p>
 * Le conteneur est herite de {@link AbstractIntegrationTest} : aucun bloc
 * {@code @Container} ni {@code @DynamicPropertySource} n'est donc necessaire
 * ici. C'est tout l'interet de la mutualisation.
 * </p>
 *
 * <p>
 * Ce test n'est volontairement pas un {@code @DataJpaTest} : le slice JPA charge
 * ses propres auto-configurations et ignore celles de la classe parente, ce qui
 * ferait perdre la connexion {@code @ServiceConnection}. Il s'execute donc avec
 * le contexte complet, plus couteux mais coherent.
 * </p>
 */
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
@DisplayName("Infrastructure de test d'integration")
class UserRepositorySmokeTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Le conteneur demarre, Flyway cree le schema et le repository est operationnel")
    void infrastructure_shouldBeOperational() {
        // La migration Flyway V1 s'est executee : les 7 tables existent.
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'",
                Integer.class);

        assertThat(tableCount).isNotNull();
        assertThat(tableCount).isGreaterThanOrEqualTo(7);

        // Le repository repond a une requete sur la base du conteneur.
        // On ne verifie PAS que la table est vide : le conteneur est partage
        // entre les classes de test d'integration, et l'ordre d'execution
        // n'est pas garanti. Ce qu'on veut prouver ici, c'est que la chaine
        // base <-> repository fonctionne.
        assertThat(userRepository.count()).isNotNull();
    }
}