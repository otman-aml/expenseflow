package com.expenseflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.ApplicationContext;

import com.expenseflow.controller.AuthController;
import com.expenseflow.controller.BudgetLimitController;
import com.expenseflow.controller.CategoryController;
import com.expenseflow.controller.ExpenseRequestController;
import com.expenseflow.security.JwtService;
import com.expenseflow.service.AuthService;

/**
 * Verifie que le contexte applicatif complet demarre.
 *
 * <p>
 * Le test herite de {@link AbstractIntegrationTest}, qui porte deja
 * {@code @SpringBootTest}, {@code @Testcontainers} et {@code @ServiceConnection}.
 * Aucun de ces elements n'est donc repete ici : les redclarer crerait une
 * seconde cle de contexte et Spring demarrerait un second conteneur, faisant
 * doubler le temps de la suite.
 * </p>
 *
 * <p>
 * {@code @AutoConfigureTestDatabase(replace = NONE)} neutralise la
 * substitution de source de donnees par une base embarquee, qui casserait la
 * connexion au conteneur et l'initialisation par Flyway.
 * </p>
 */
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("Demarrage du contexte applicatif")
class ExpenseflowBackendApplicationTests extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * Controle que le contexte monte et que les briques centrales sont
     * effectivement assemblees.
     */
    @Test
    @DisplayName("Le contexte Spring demarre avec ses controleurs et ses services")
    void contextLoads_withApplicationBeans() {
        assertThat(applicationContext).isNotNull();

        // Les controleurs des trois domaines sont enregistres.
        assertThat(applicationContext.getBean(AuthController.class)).isNotNull();
        assertThat(applicationContext.getBean(CategoryController.class)).isNotNull();
        assertThat(applicationContext.getBean(BudgetLimitController.class)).isNotNull();
        assertThat(applicationContext.getBean(ExpenseRequestController.class)).isNotNull();

        // La chaine de securite est cablee.
        assertThat(applicationContext.getBean(AuthService.class)).isNotNull();
        assertThat(applicationContext.getBean(JwtService.class)).isNotNull();
    }
}
