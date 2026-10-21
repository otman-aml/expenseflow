package com.expenseflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.expenseflow.entity.AuditLog;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.ExpenseStatus;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.AuditLogRepository;

/**
 * Tests unitaires de {@link AuditLogService}.
 *
 * <p>
 * Aucun contexte Spring : seul le dépôt est simulé. Ces tests verrouillent la
 * forme exacte de l'entrée écrite — c'est la donnée qui alimentera
 * l'historique affiché à l'utilisateur.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogService")
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AuditLogService service;

    @Captor
    private ArgumentCaptor<AuditLog> logCaptor;

    private final User auteur = User.builder()
            .id(9L).nom("Employe Demo").email("e@x.ma").role(UserRole.EMPLOYEE)
            .build();

    @Test
    @DisplayName("log() enregistre l'auteur, l'entité, l'identifiant, l'action et le détail")
    void logEnregistreTousLesChamps() {
        service.log(auteur, "Category", 7L, "CREATED", "categorie creee");

        verify(auditLogRepository, times(1)).save(logCaptor.capture());
        AuditLog log = logCaptor.getValue();

        assertThat(log.getUtilisateur()).isSameAs(auteur);
        assertThat(log.getEntiteConcernee()).isEqualTo("Category");
        assertThat(log.getEntiteId()).isEqualTo(7L);
        assertThat(log.getAction()).isEqualTo("CREATED");
        assertThat(log.getDetails()).isEqualTo("categorie creee");
    }

    @Test
    @DisplayName("log() accepte un identifiant d'entité nul (entité non identifiée numériquement)")
    void logAccepteEntiteIdNul() {
        service.log(auteur, "Reference", null, "READ", "consultation");

        verify(auditLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getEntiteId()).isNull();
    }

    @Test
    @DisplayName("logChangementStatut() construit le détail au format DEPUIS -> VERS")
    void logChangementStatutFormateLeDetail() {
        service.logChangementStatut(auteur, 42L, ExpenseStatus.DRAFT, ExpenseStatus.SUBMITTED);

        verify(auditLogRepository).save(logCaptor.capture());
        AuditLog log = logCaptor.getValue();

        assertThat(log.getDetails()).isEqualTo("DRAFT -> SUBMITTED");
    }

    @Test
    @DisplayName("logChangementStatut() utilise les constantes ExpenseRequest et STATUS_CHANGE")
    void logChangementStatutUtiliseLesConstantes() {
        service.logChangementStatut(auteur, 42L,
                ExpenseStatus.PENDING_FINANCE, ExpenseStatus.REIMBURSED);

        verify(auditLogRepository).save(logCaptor.capture());
        AuditLog log = logCaptor.getValue();

        // Le couple (entiteConcernee, action) est le critère de recherche de
        // l'historique : une faute de frappe disperserait des libellés variants
        // dans la meme colonne et casserait silencieusement toutes les requêtes.
        assertThat(log.getEntiteConcernee()).isEqualTo(AuditLogService.ENTITE_EXPENSE_REQUEST);
        assertThat(log.getEntiteConcernee()).isEqualTo("ExpenseRequest");
        assertThat(log.getAction()).isEqualTo(AuditLogService.ACTION_CHANGEMENT_STATUT);
        assertThat(log.getAction()).isEqualTo("STATUS_CHANGE");
    }

    @Test
    @DisplayName("logChangementStatut() porte l'identifiant de l'entité concernée")
    void logChangementStatutPorteLEntiteId() {
        service.logChangementStatut(auteur, 7L,
                ExpenseStatus.PENDING_MANAGER, ExpenseStatus.REJECTED_MANAGER);

        verify(auditLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getEntiteId()).isEqualTo(7L);
    }

    /**
     * La colonne {@code date_action} n'est pas renseignée par l'application.
     *
     * <p>
     * Elle porte {@code insertable = false, updatable = false} et reçoit
     * {@code DEFAULT NOW()}. L'application doit donc laisser le champ à null :
     * le fournir lèverait une {@code PropertyValueException} à l'insertion, et
     * surtout l'horodatage deviendrait celui de la JVM, susceptible de diverger
     * de l'ordre réel des lignes dans la table.
     * </p>
     */
    @Test
    @DisplayName("La date d'action est laissée à la base (DEFAULT NOW())")
    void dateActionLaisseeALaBase() {
        service.logChangementStatut(auteur, 42L, ExpenseStatus.DRAFT, ExpenseStatus.SUBMITTED);

        verify(auditLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getDateAction()).isNull();
    }

    /**
     * Chaque transition produit une entrée distincte : une inversion des
     * arguments produirait « REIMBURSED -> PENDING_FINANCE », un historique
     * absurde.
     */
    @Test
    @DisplayName("L'ordre des arguments ancien/nouveau est respecté")
    void ordreDesArgumentsRespecte() {
        service.logChangementStatut(auteur, 1L, ExpenseStatus.REIMBURSED, ExpenseStatus.SUBMITTED);

        verify(auditLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getDetails())
                .isEqualTo("REIMBURSED -> SUBMITTED");
    }
}