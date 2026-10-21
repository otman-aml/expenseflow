package com.expenseflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.expenseflow.dto.AdminStatsResponse;
import com.expenseflow.entity.AuditLog;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.AiCallLogRepository;
import com.expenseflow.repository.AuditLogRepository;
import com.expenseflow.repository.ExpenseRequestRepository;
import com.expenseflow.repository.UserRepository;

/**
 * Tests unitaires de {@link AdminStatsService}.
 *
 * <p>
 * Aucun contexte Spring : les quatre depots sont simules. Les tests
 * verrouillent ce qu'une regression casserait silencieusement — la
 * presence des quatre roles meme vides, la fenetre glissante de 30 jours,
 * la page de cinq actions — car le JSON du front assume chacun de ces
 * points sans pouvoir les verifier lui-meme.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminStatsService")
class AdminStatsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ExpenseRequestRepository expenseRequestRepository;

    @Mock
    private AiCallLogRepository aiCallLogRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AdminStatsService service;

    @Captor
    private ArgumentCaptor<Instant> fenetreCaptor;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    /**
     * Les six appeles du service sont stubbees a chaque fois : getStats()
     * les fait toutes, et Mockito echouerait sur un stub inutilise.
     *
     * @param journal entrees renvoyees pour les dernieres actions
     */
    private void stubTout(List<AuditLog> journal) {
        given(userRepository.countByRole(UserRole.ADMIN)).willReturn(1L);
        given(userRepository.countByRole(UserRole.MANAGER)).willReturn(2L);
        given(userRepository.countByRole(UserRole.FINANCE_ADMIN)).willReturn(3L);
        given(userRepository.countByRole(UserRole.EMPLOYEE)).willReturn(4L);
        given(userRepository.count()).willReturn(10L);
        given(expenseRequestRepository.count()).willReturn(7L);
        given(aiCallLogRepository.countByDateAppelAfter(any(Instant.class))).willReturn(5L);
        given(auditLogRepository.findDernieresParEntite(
                eq(AuditLogService.ENTITE_USER), any(Pageable.class))).willReturn(journal);
    }

    @Test
    @DisplayName("Les quatre roles sont presents dans la carte, avec les bons totaux")
    void repartitionCompleteEtTotaux() {
        stubTout(List.of());

        AdminStatsResponse stats = service.getStats();

        // La carte est complete meme pour un role absent : le front affiche
        // "0" plutot que de disparaitre de la liste.
        assertThat(stats.usersByRole())
                .hasSize(4)
                .containsEntry(UserRole.ADMIN, 1L)
                .containsEntry(UserRole.MANAGER, 2L)
                .containsEntry(UserRole.FINANCE_ADMIN, 3L)
                .containsEntry(UserRole.EMPLOYEE, 4L);
        assertThat(stats.totalUsers()).isEqualTo(10L);
        assertThat(stats.totalExpenses()).isEqualTo(7L);
        assertThat(stats.totalAiCalls()).isEqualTo(5L);
        assertThat(stats.recentActions()).isEmpty();
    }

    @Test
    @DisplayName("Les appels IA sont comptes sur 30 jours glissants, pas un mois calendaire")
    void fenetreGlissante30Jours() {
        stubTout(List.of());

        service.getStats();

        verify(aiCallLogRepository).countByDateAppelAfter(fenetreCaptor.capture());
        Instant fenetre = fenetreCaptor.getValue();
        // Marge de +/-1 jour : borne entre now-31j et now-29j, ce qui
        // accepte l'horloge mais rejette tout calcul de mois calendaire.
        assertThat(fenetre)
                .isAfter(Instant.now().minus(31, ChronoUnit.DAYS))
                .isBefore(Instant.now().minus(29, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("Le journal demande cinq lignes au plus, triees par le depot")
    void journalCinqDernieresActions() {
        Instant date = Instant.parse("2026-10-01T10:00:00Z");
        AuditLog entree = AuditLog.builder()
                .entiteConcernee(AuditLogService.ENTITE_USER)
                .entiteId(7L)
                .action("COMPTE_CREE")
                .details("Compte cree : cible@x.ma (EMPLOYEE)")
                .dateAction(date)
                .build();
        stubTout(List.of(entree));

        AdminStatsResponse stats = service.getStats();

        verify(auditLogRepository).findDernieresParEntite(
                eq(AuditLogService.ENTITE_USER), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(5);

        // La cible est le details brut : le mapping ne resolve jamais un
        // compte qui aurait pu etre supprime entre temps.
        assertThat(stats.recentActions()).hasSize(1);
        AdminStatsResponse.AdminActionResponse action = stats.recentActions().get(0);
        assertThat(action.date()).isEqualTo(date);
        assertThat(action.action()).isEqualTo("COMPTE_CREE");
        assertThat(action.cible()).isEqualTo("Compte cree : cible@x.ma (EMPLOYEE)");
    }
}
