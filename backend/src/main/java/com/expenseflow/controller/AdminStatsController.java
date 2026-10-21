package com.expenseflow.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.dto.AdminStatsResponse;
import com.expenseflow.service.AdminStatsService;

/**
 * Point d'entree des statistiques d'administration.
 *
 * <p>
 * <strong>Securite sur la classe</strong>, comme
 * {@code AdminUserController} : {@code @PreAuthorize("hasRole('ADMIN')")}
 * couvre les routes actuelles et futures. Le tableau de bord agrège des
 * comptes entiers, des notes et un journal — oublier l'annotation sur un
 * handler ouvrirait la porte a la lecture de toute la base.
 * </p>
 */
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/admin/stats")
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    /**
     * @param adminStatsService service de statistiques
     */
    public AdminStatsController(AdminStatsService adminStatsService) {
        this.adminStatsService = adminStatsService;
    }

    /**
     * Statistiques d'entreprise pour le tableau de bord ADMIN.
     *
     * @return 200 OK avec totaux, repartition par role et dernieres actions
     */
    @GetMapping
    public ResponseEntity<AdminStatsResponse> getStats() {
        return ResponseEntity.ok(adminStatsService.getStats());
    }
}
