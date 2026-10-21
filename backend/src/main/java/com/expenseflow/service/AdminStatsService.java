package com.expenseflow.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.dto.AdminStatsResponse;
import com.expenseflow.dto.AdminStatsResponse.AdminActionResponse;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.AiCallLogRepository;
import com.expenseflow.repository.AuditLogRepository;
import com.expenseflow.repository.ExpenseRequestRepository;
import com.expenseflow.repository.UserRepository;

/**
 * Statistiques globales pour le tableau de bord ADMIN.
 *
 * <p>
 * Service en lecture seule : il agrège des compteurs, il ne decide de
 * rien. Aucune regle métier n'y est appliquee — les totaux portent sur
 * toute la base indifferemment du statut ou du role.
 * </p>
 */
@Service
public class AdminStatsService {

    /** Nombre de lignes renvoyees pour les dernieres actions. */
    private static final int DERNIERES_ACTIONS = 5;

    /** Fenetre glissante des appels IA, en jours. */
    private static final int FENETRE_APPELS_IA_JOURS = 30;

    private final UserRepository userRepository;
    private final ExpenseRequestRepository expenseRequestRepository;
    private final AiCallLogRepository aiCallLogRepository;
    private final AuditLogRepository auditLogRepository;

    /**
     * @param userRepository           comptes
     * @param expenseRequestRepository notes de frais
     * @param aiCallLogRepository      journal des appels IA
     * @param auditLogRepository       journal d'audit
     */
    public AdminStatsService(UserRepository userRepository,
            ExpenseRequestRepository expenseRequestRepository,
            AiCallLogRepository aiCallLogRepository,
            AuditLogRepository auditLogRepository) {
        this.userRepository = userRepository;
        this.expenseRequestRepository = expenseRequestRepository;
        this.aiCallLogRepository = aiCallLogRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Assemble les statistiques d'entreprise.
     *
     * <p>
     * <strong>Quatre comptages par role, pas un GROUP BY.</strong> La
     * table compte quelques dizaines de lignes : quatre compteurs
     * indexes cotent moins cher qu'un pivot a interpreter, et reutilisent
     * {@code countByRole}, deja eprouve par la garde du dernier ADMIN.
     * Les roles manquants valent 0 : {@code UserRole.values()} les parcoure
     * tous, et la carte livree au front est donc toujours complete.
     * </p>
     *
     * <p>
     * <strong>Fenetre glissante de 30 jours</strong> pour les appels IA,
     * contre un mois calendaire pour les totaux de la vue employe : ici le
     * libelle dit « 30 derniers jours », et c'est ce calcul qui le tient.
     * </p>
     *
     * @return statistiques pretes a serialiser
     */
    @Transactional(readOnly = true)
    public AdminStatsResponse getStats() {
        Map<UserRole, Long> parRole = new EnumMap<>(UserRole.class);
        for (UserRole role : UserRole.values()) {
            parRole.put(role, userRepository.countByRole(role));
        }

        long totalUsers = userRepository.count();
        long totalExpenses = expenseRequestRepository.count();
        long totalAiCalls = aiCallLogRepository.countByDateAppelAfter(
                Instant.now().minus(FENETRE_APPELS_IA_JOURS, ChronoUnit.DAYS));

        // Les actions de compte, pas toutes les transitions de note : le
        // tableau de bord ne parle que de ce que l'admin pilote ici-meme.
        List<AdminActionResponse> actions = auditLogRepository
                .findDernieresParEntite(AuditLogService.ENTITE_USER,
                        PageRequest.of(0, DERNIERES_ACTIONS))
                .stream()
                .map(entree -> new AdminActionResponse(
                        entree.getDateAction(), entree.getAction(), entree.getDetails()))
                .toList();

        return new AdminStatsResponse(totalUsers, parRole, totalExpenses, totalAiCalls, actions);
    }
}
