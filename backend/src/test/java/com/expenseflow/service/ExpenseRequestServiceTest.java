package com.expenseflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import com.expenseflow.dto.AuditLogResponse;
import com.expenseflow.dto.ExpenseLineRequest;
import com.expenseflow.dto.ExpenseRequestCreate;
import com.expenseflow.dto.ExpenseRequestResponse;
import com.expenseflow.entity.AuditLog;
import com.expenseflow.entity.Category;
import com.expenseflow.entity.ExpenseLine;
import com.expenseflow.entity.ExpenseRequest;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.ExpenseStatus;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.exception.ResourceNotFoundException;
import com.expenseflow.repository.ApprovalRepository;
import com.expenseflow.repository.AuditLogRepository;
import com.expenseflow.repository.CategoryRepository;
import com.expenseflow.repository.ExpenseRequestRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.workflow.BudgetExceededException;
import com.expenseflow.workflow.BudgetViolation;
import com.expenseflow.workflow.WorkflowException;

/**
 * Tests unitaires de {@link ExpenseRequestService}.
 *
 * <p>
 * Aucun contexte Spring n'est démarré : les dépendances sont simulées, ce qui
 * rend ces tests rapides et déterministes. Le service est ici le seul porteur
 * de règles métier, les tests se concentrent donc sur ce qui ne peut pas se
 * déduire d'une simple lecture du code : le calcul du total, les garde-fous
 * contre le contrôle client (rôle imposé, auteur imposé) et la matrice d'accès.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ExpenseRequestService")
class ExpenseRequestServiceTest {

    @Mock
    private ExpenseRequestRepository expenseRequestRepository;

    @Mock
    private CategoryRepository categoryRepository;

    /**
     * Dépôt des décisions d'approbation, injecté depuis l'ajout du workflow.
     *
     * <p>
     * Aucun test de la section 1.8 ne l'utilise : les méthodes de transition
     * arrivent en section 2.5. Le mock est déclaré ici pour que
     * {@code @InjectMocks} fournisse bien les trois dépendances attendues par
     * le constructeur.
     * </p>
     */
    @Mock
    private ApprovalRepository approvalRepository;

    /**
     * Contrôle des plafonds, injecté depuis l'ajout du workflow (section 2.2).
     *
     * <p>
     * Simulé comme bien d'autres : les tests existants de la section 1.8
     * n'exercent pas {@code submit}, et le laisser sans stub ferait échouer
     * le test sur un {@code null} au lieu d'un montant.
     * </p>
     */
    @Mock
    private BudgetValidationService budgetValidationService;

    /**
     * Journal d'audit des changements d'état.
     *
     * <p>
     * Simulé : les tests de la section 2.5 vérifient par ailleurs, via
     * {@code verify}, qu'une transition produit bien une ou deux entrées.
     * </p>
     */
    @Mock
    private AuditLogService auditLogService;

    /** Dépôt du journal, pour la lecture de l'historique. */
    @Mock
    private AuditLogRepository auditLogRepository;

    /**
     * Notifications déclenchées par les transitions.
     *
     * <p>
     * Simulé : chaque transition l'appelle désormais, et sans ce mock
     * {@code @InjectMocks} l'injecterait {@code null} — le {@code NPE} aurait
     * lieu dans le déclenchement de notification, loin de la cause réelle
     * (un constructeur incomplet). Les tests de déclenchement vivent dans
     * {@code NotificationServiceTest}.
     * </p>
     */
    @Mock
    private NotificationService notificationService;

    /**
     * Dépôt des utilisateurs, pour le filtrage par équipe ({@code manager_id}).
     *
     * <p>
     * Simulé : depuis l'ajout du filtrage, un appel MANAGER de
     * {@code findByStatut} résout d'abord son équipe. Sans ce mock,
     * {@code @InjectMocks} injecterait {@code null} et le NPE surviendrait
     * dans la résolution d'équipe, loin de la cause réelle.
     * </p>
     */
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ExpenseRequestService service;

    @Captor
    private ArgumentCaptor<ExpenseRequest> requestCaptor;

    // ---------------------------------------------------------------------
    // Donnees de test
    // ---------------------------------------------------------------------

    private static User user(Long id, UserRole role) {
        return User.builder()
                .id(id)
                .nom("Utilisateur " + id)
                .email("user" + id + "@expenseflow.ma")
                .role(role)
                .build();
    }

    private static Category category(Long id, String nom) {
        return Category.builder().id(id).nom(nom).build();
    }

    private static ExpenseLineRequest ligne(Long categorieId, String montant) {
        return new ExpenseLineRequest(categorieId, new BigDecimal(montant), LocalDate.of(2026, 9, 20), "note");
    }

    private static ExpenseRequestCreate request(ExpenseLineRequest... lignes) {
        return new ExpenseRequestCreate(List.of(lignes));
    }

    /**
     * Simule le {@code save} d'un agregat : Hibernate lui attribue un
     * identifiant et renvoie l'entite attachee. Sans cela, le service
     * construirait un DTO sans identifiant.
     *
     * <p>
     * L'identifiant n'est pose que s'il est absent, conformement au
     * comportement reel de JPA sur une entite nouvelle.
     * </p>
     */
    private void givenSaveAssignsId() {
        given(expenseRequestRepository.save(any(ExpenseRequest.class))).willAnswer(i -> {
            ExpenseRequest entity = i.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(42L);
            }
            return entity;
        });
    }

    // =====================================================================
    // F) Journal d'audit
    // =====================================================================

    /**
     * Construit une note existante dans un statut donne, possédée par
     * l'employé 9.
     *
     * @param statut statut courant de la note
     * @return note prête à être transitionnée
     */
    private ExpenseRequest givenNoteAuStatut(ExpenseStatus statut) {
        ExpenseRequest request = ExpenseRequest.builder()
                .id(42L)
                .employee(user(9L, UserRole.EMPLOYEE))
                .statut(statut)
                .totalMontant(new BigDecimal("150.00"))
                .build();
        given(expenseRequestRepository.findByIdWithLignes(42L)).willReturn(Optional.of(request));
        return request;
    }

    @Test
    @DisplayName("submit() journalise 2 transitions, dans l'ordre DRAFT puis PENDING_MANAGER")
    void submit_journaliseDeuxTransitionsDansLOrdre() {
        // Given
        givenNoteAuStatut(ExpenseStatus.DRAFT);
        given(budgetValidationService.validateExpense(any(), any())).willReturn(List.of());
        givenSaveAssignsId();

        // When
        service.submit(42L, user(9L, UserRole.EMPLOYEE));

        // Then : deux traces. L'ordre est vérifié via les arguments capturés —
        // une inversion signalerait que le journal est écrit après coup.
        ArgumentCaptor<ExpenseStatus> ancien = ArgumentCaptor.forClass(ExpenseStatus.class);
        ArgumentCaptor<ExpenseStatus> nouveau = ArgumentCaptor.forClass(ExpenseStatus.class);
        verify(auditLogService, times(2)).logChangementStatut(
                any(User.class), eq(42L), ancien.capture(), nouveau.capture());

        assertThat(ancien.getAllValues())
                .containsExactly(ExpenseStatus.DRAFT, ExpenseStatus.SUBMITTED);
        assertThat(nouveau.getAllValues())
                .containsExactly(ExpenseStatus.SUBMITTED, ExpenseStatus.PENDING_MANAGER);
    }

    @Test
    @DisplayName("approve() journalise 2 transitions, dans l'ordre")
    void approve_journaliseDeuxTransitionsDansLOrdre() {
        // Given
        givenNoteAuStatut(ExpenseStatus.PENDING_MANAGER);
        givenSaveAssignsId();

        // When
        service.approve(42L, user(7L, UserRole.MANAGER));

        // Then
        ArgumentCaptor<ExpenseStatus> ancien = ArgumentCaptor.forClass(ExpenseStatus.class);
        ArgumentCaptor<ExpenseStatus> nouveau = ArgumentCaptor.forClass(ExpenseStatus.class);
        verify(auditLogService, times(2)).logChangementStatut(
                any(User.class), eq(42L), ancien.capture(), nouveau.capture());

        assertThat(ancien.getAllValues())
                .containsExactly(ExpenseStatus.PENDING_MANAGER, ExpenseStatus.APPROVED_MANAGER);
        assertThat(nouveau.getAllValues())
                .containsExactly(ExpenseStatus.APPROVED_MANAGER, ExpenseStatus.PENDING_FINANCE);
    }

    @Test
    @DisplayName("reject() journalise une seule transition")
    void reject_journaliseUneTransition() {
        // Given
        givenNoteAuStatut(ExpenseStatus.PENDING_MANAGER);
        givenSaveAssignsId();

        // When
        service.reject(42L, user(7L, UserRole.MANAGER), "Justificatif manquant");

        // Then : tous les arguments sont des matchers, comme l'impose Mockito
        // dès qu'un seul argument l'est.
        verify(auditLogService, times(1)).logChangementStatut(
                any(User.class), eq(42L),
                eq(ExpenseStatus.PENDING_MANAGER), eq(ExpenseStatus.REJECTED_MANAGER));
    }

    @Test
    @DisplayName("reimburse() journalise une seule transition")
    void reimburse_journaliseUneTransition() {
        // Given
        givenNoteAuStatut(ExpenseStatus.PENDING_FINANCE);
        givenSaveAssignsId();

        // When
        service.reimburse(42L, user(8L, UserRole.FINANCE_ADMIN));

        // Then
        verify(auditLogService, times(1)).logChangementStatut(
                any(User.class), eq(42L),
                eq(ExpenseStatus.PENDING_FINANCE), eq(ExpenseStatus.REIMBURSED));
    }

    @Test
    @DisplayName("rejectFinance() journalise une seule transition")
    void rejectFinance_journaliseUneTransition() {
        // Given
        givenNoteAuStatut(ExpenseStatus.PENDING_FINANCE);
        givenSaveAssignsId();

        // When
        service.rejectFinance(42L, user(8L, UserRole.FINANCE_ADMIN), "Montant incorrect");

        // Then
        verify(auditLogService, times(1)).logChangementStatut(
                any(User.class), eq(42L),
                eq(ExpenseStatus.PENDING_FINANCE), eq(ExpenseStatus.REJECTED_FINANCE));
    }

    // =====================================================================
    // G) getAuditHistory
    // =====================================================================

    /**
     * Construit une entrée de journal déjà chargée (utilisateur résolu).
     *
     * @param id     identifiant de l'entrée
     * @param avant  statut source
     * @param apres  statut cible
     * @param auteur identifiant de l'utilisateur ayant agi
     * @return entrée de journal
     */
    private AuditLog entreeAudit(Long id, ExpenseStatus avant, ExpenseStatus apres, Long auteur) {
        return AuditLog.builder()
                .id(id)
                .entiteConcernee(AuditLogService.ENTITE_EXPENSE_REQUEST)
                .entiteId(42L)
                .utilisateur(user(auteur, auteur == 9L ? UserRole.EMPLOYEE : UserRole.MANAGER))
                .action(AuditLogService.ACTION_CHANGEMENT_STATUT)
                .details(avant + " -> " + apres)
                .build();
    }

    @Test
    @DisplayName("Une transition refusée par le workflow ne journalise rien")
    void transitionRefusee_neJournalisePas() {
        // Given : la note est déjà soumise, la soumission est donc interdite
        givenNoteAuStatut(ExpenseStatus.PENDING_MANAGER);
        given(budgetValidationService.validateExpense(any(), any())).willReturn(List.of());

        // When / Then
        assertThatThrownBy(() -> service.submit(42L, user(9L, UserRole.EMPLOYEE)))
                .isInstanceOf(WorkflowException.class);

        // Une trace décrivant un changement qui n'a pas eu lieu serait pire
        // qu'une absence de trace : l'historique mentirait sur l'état réel.
        verify(auditLogService, never()).logChangementStatut(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Un refus de plafond ne journalise aucune transition")
    void refusDePlafond_neJournalisePas() {
        // Given : un dépassement de plafond détecté
        givenNoteAuStatut(ExpenseStatus.DRAFT);
        given(budgetValidationService.validateExpense(any(), any()))
                .willReturn(List.of(new BudgetViolation(4L, "Transport",
                        UserRole.EMPLOYEE, new BigDecimal("600.00"), new BigDecimal("500.00"))));

        // When / Then
        assertThatThrownBy(() -> service.submit(42L, user(9L, UserRole.EMPLOYEE)))
                .isInstanceOf(BudgetExceededException.class);

        verify(auditLogService, never()).logChangementStatut(any(), any(), any(), any());
    }

    @Test
    @DisplayName("getAuditHistory() renvoie l'historique au propriétaire")
    void getAuditHistory_commeProprietaire() {
        // Given
        givenNoteAuStatut(ExpenseStatus.REIMBURSED);
        given(auditLogRepository.findByEntiteConcerneeAndEntiteIdOrderByDateActionAscIdAsc(
                AuditLogService.ENTITE_EXPENSE_REQUEST, 42L))
                .willReturn(List.of(entreeAudit(1L, ExpenseStatus.DRAFT, ExpenseStatus.SUBMITTED, 9L)));

        // When
        List<AuditLogResponse> historique = service.getAuditHistory(42L, user(9L, UserRole.EMPLOYEE));

        // Then
        assertThat(historique).hasSize(1);
        assertThat(historique.get(0).details()).isEqualTo("DRAFT -> SUBMITTED");
        assertThat(historique.get(0).utilisateurNom()).isEqualTo("Utilisateur 9");
    }

    @Test
    @DisplayName("getAuditHistory() est ouvert au MANAGER, même non propriétaire")
    void getAuditHistory_commeManager() {
        // Given
        givenNoteAuStatut(ExpenseStatus.PENDING_MANAGER);
        given(auditLogRepository.findByEntiteConcerneeAndEntiteIdOrderByDateActionAscIdAsc(any(), any()))
                .willReturn(List.of());

        // When / Then
        assertThat(service.getAuditHistory(42L, user(7L, UserRole.MANAGER))).isEmpty();
    }

    @Test
    @DisplayName("getAuditHistory() refuse un EMPLOYEE tiers avant toute lecture du journal")
    void getAuditHistory_refuseEmployeeTiers() {
        // Given
        givenNoteAuStatut(ExpenseStatus.PENDING_MANAGER);

        // When / Then
        assertThatThrownBy(() -> service.getAuditHistory(42L, user(99L, UserRole.EMPLOYEE)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");

        // Le refus doit survenir AVANT la lecture : si le journal était
        // consulté d'abord, un tiers aurait obtenu le nombre de transitions.
        verify(auditLogRepository, never())
                .findByEntiteConcerneeAndEntiteIdOrderByDateActionAscIdAsc(any(), any());
    }

    @Test
    @DisplayName("getAuditHistory() renvoie 404 si la note n'existe pas")
    void getAuditHistory_noteInconnue() {
        // Given
        given(expenseRequestRepository.findByIdWithLignes(404L)).willReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.getAuditHistory(404L, user(9L, UserRole.EMPLOYEE)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =====================================================================
    // A) create
    // =====================================================================

    @Test
    @DisplayName("create() calcule le total comme la somme des lignes")
    void create_withValidLignes_shouldComputeTotal() {
        // Given
        given(categoryRepository.findAllById(Set.of(4L, 5L)))
                .willReturn(List.of(category(4L, "Transport"), category(5L, "Restauration")));
        givenSaveAssignsId();

        // When
        ExpenseRequestResponse response = service.create(
                request(ligne(4L, "100.00"), ligne(5L, "50.00")), user(9L, UserRole.EMPLOYEE));

        // Then
        assertThat(response.totalMontant()).isEqualByComparingTo("150.00");
        assertThat(response.lignes()).hasSize(2);
    }

    @Test
    @DisplayName("create() impose le statut DRAFT et une date de soumission nulle")
    void create_shouldForceStatusToDraft() {
        // Given
        given(categoryRepository.findAllById(anySet())).willReturn(List.of(category(4L, "Transport")));
        givenSaveAssignsId();

        // When
        ExpenseRequestResponse response = service.create(request(ligne(4L, "10.00")), user(9L, UserRole.EMPLOYEE));

        // Then
        assertThat(response.statut()).isEqualTo(ExpenseStatus.DRAFT);
        assertThat(response.dateSoumission()).isNull();
    }

    @Test
    @DisplayName("create() utilise l'utilisateur authentifié comme auteur, jamais le payload")
    void create_shouldUseAuthenticatedUserAsEmployee() {
        // Given
        User author = user(9L, UserRole.EMPLOYEE);
        given(categoryRepository.findAllById(anySet())).willReturn(List.of(category(4L, "Transport")));
        givenSaveAssignsId();

        // When
        service.create(request(ligne(4L, "25.00")), author);

        // Then
        verify(expenseRequestRepository).save(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getEmployee()).isSameAs(author);
    }

    @Test
    @DisplayName("create() charge toutes les categories en un seul appel (pas de N+1)")
    void create_shouldLoadAllCategoriesInOneCall() {
        // Given : 3 lignes dont 2 sur la MEME categorie
        given(categoryRepository.findAllById(anySet())).willReturn(List.of(category(4L, "Transport")));
        givenSaveAssignsId();

        // When
        service.create(request(ligne(4L, "10.00"), ligne(4L, "20.00"), ligne(4L, "30.00")),
                user(9L, UserRole.EMPLOYEE));

        // Then
        verify(categoryRepository).findAllById(anySet());
        verify(categoryRepository, never()).findById(any());
    }

    @Test
    @DisplayName("create() rejette une categorie inconnue et liste TOUTES les manquantes")
    void create_withUnknownCategory_shouldThrowResourceNotFound() {
        // Given : seule la categorie 4 existe, 5 et 99 sont inconnues
        given(categoryRepository.findAllById(anySet())).willReturn(List.of(category(4L, "Transport")));

        // When / Then
        assertThatThrownBy(() -> service.create(
                request(ligne(5L, "10.00"), ligne(99L, "20.00")), user(9L, UserRole.EMPLOYEE)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Categories introuvables")
                .hasMessageContaining("5")
                .hasMessageContaining("99");

        verify(expenseRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("create() accepte plusieurs lignes sur la meme categorie")
    void create_withDuplicateCategoriesInLignes_shouldStillWork() {
        // Given
        given(categoryRepository.findAllById(anySet())).willReturn(List.of(category(4L, "Transport")));
        givenSaveAssignsId();

        // When
        ExpenseRequestResponse response = service.create(
                request(ligne(4L, "10.00"), ligne(4L, "20.00")), user(9L, UserRole.EMPLOYEE));

        // Then
        assertThat(response.lignes()).hasSize(2);
        assertThat(response.totalMontant()).isEqualByComparingTo("30.00");
    }

    // =====================================================================
    // B) findMyRequests
    // =====================================================================

    @Test
    @DisplayName("findMyRequests() interroge la base avec l'identifiant de l'appelant")
    void findMyRequests_shouldQueryWithCurrentUserId() {
        // Given
        given(expenseRequestRepository.findByEmployeeIdWithLignes(9L)).willReturn(List.of());

        // When
        service.findMyRequests(user(9L, UserRole.EMPLOYEE));

        // Then
        verify(expenseRequestRepository).findByEmployeeIdWithLignes(9L);
    }

    // =====================================================================
    // D) update - modification du brouillon
    //
    // Le PUT remplace l'integralite des lignes. Ces tests verifient donc
    // moins le nombre de lignes que le fait que l'ancien contenu a
    // disparu : c'est precisement ce que le.clear() garantit, et ce que le
    // retour du DTO permet d'observer.
    // =====================================================================

    /**
     * Note en brouillon, proprietaire 9, avec une ligne d'origine.
     *
     * @return la note simulee
     */
    private ExpenseRequest givenBrouillonAvecLigne() {
        ExpenseRequest request = ExpenseRequest.builder()
                .id(42L)
                .employee(user(9L, UserRole.EMPLOYEE))
                .statut(ExpenseStatus.DRAFT)
                .totalMontant(new BigDecimal("100.00"))
                .build();
        request.getLignes().add(ExpenseLine.builder()
                .request(request)
                .categorie(category(1L, "Transport"))
                .montant(new BigDecimal("100.00"))
                .build());
        given(expenseRequestRepository.findByIdWithLignes(42L)).willReturn(Optional.of(request));
        return request;
    }

    /**
     * Simule le depot des categories demandees.
     *
     * @param ids identifiants attendus
     */
    private void givenCategoriesConnues(Long... ids) {
        Category[] categories = new Category[ids.length];
        for (int i = 0; i < ids.length; i++) {
            categories[i] = category(ids[i], "Categorie " + ids[i]);
        }
        given(categoryRepository.findAllById(any())).willReturn(List.of(categories));
    }

    @Test
    @DisplayName("update() remplace les lignes et recalcule le total")
    void update_remplaceLesLignesEtRecalculeLeTotal() {
        // Given : une ligne 100.00, total 100.00
        givenBrouillonAvecLigne();
        givenCategoriesConnues(1L, 3L);
        givenSaveAssignsId();

        // When : le proprietaire renvoie une seule ligne de 250.00
        ExpenseRequestResponse resultat = service.update(42L, request(ligne(3L, "250.00")),
                user(9L, UserRole.EMPLOYEE));

        // Then : une seule ligne subsiste, et le total suit exactement le
        // nouveau contenu - pas l'ancien total conserve.
        assertThat(resultat.lignes()).hasSize(1);
        assertThat(resultat.lignes().getFirst().categorieNom()).isEqualTo("Categorie 3");
        assertThat(resultat.totalMontant()).isEqualByComparingTo("250.00");

        verify(expenseRequestRepository).save(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getLignes()).hasSize(1);
    }

    @Test
    @DisplayName("update() ne journalise rien : l'audit_log suit les transitions d'etat")
    void update_neJournalisePas() {
        // Given
        givenBrouillonAvecLigne();
        givenCategoriesConnues(1L);
        givenSaveAssignsId();

        // When
        service.update(42L, request(ligne(1L, "75.00")), user(9L, UserRole.EMPLOYEE));

        // Then : modifier un brouillon n'est pas une transition. Le journal
        // ne doit donc pas s'alourdir d'une entree qui n'apporte rien a
        // l'historique des decisions.
        verify(auditLogService, never()).logChangementStatut(any(), any(), any(), any());
    }

    @Test
    @DisplayName("update() par un tiers leve AccessDeniedException (403)")
    void update_parUnTiers_leveAccessDenied() {
        // Given : la note appartient a l'employe 9
        givenBrouillonAvecLigne();

        // When : l'employe 10 tente la modification
        assertThatThrownBy(() -> service.update(42L, request(ligne(1L, "75.00")),
                user(10L, UserRole.EMPLOYEE)))
                .isInstanceOf(AccessDeniedException.class);

        // Then : rien n'est enregistre
        verify(expenseRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("update() par un MANAGER leve AccessDeniedException : valider n'est pas editer")
    void update_parUnManager_leveAccessDenied() {
        // Given
        givenBrouillonAvecLigne();

        // When
        assertThatThrownBy(() -> service.update(42L, request(ligne(1L, "75.00")),
                user(7L, UserRole.MANAGER)))
                .isInstanceOf(AccessDeniedException.class);

        // Then
        verify(expenseRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("update() sur une note non DRAFT leve WorkflowException (409)")
    void update_horsBrouillon_leveWorkflowException() {
        // Given : note soumise, proprietaire 9
        givenNoteAuStatut(ExpenseStatus.PENDING_MANAGER);

        // When
        assertThatThrownBy(() -> service.update(42L, request(ligne(1L, "75.00")),
                user(9L, UserRole.EMPLOYEE)))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("n'est plus en brouillon");

        // Then
        verify(expenseRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("update() verifie le proprietaire avant le statut : un tiers obtient 403, pas 409")
    void update_verifieLeProprietaireAvantLeStatut() {
        // Given : note soumise ET appartenant a quelqu'un d'autre. L'echec
        // attendu est 403 : repondre 409 renseignerait ce tiers sur
        // l'avancement d'une note qui n'est pas la sienne.
        givenNoteAuStatut(ExpenseStatus.APPROVED_MANAGER);

        // When / Then
        assertThatThrownBy(() -> service.update(42L, request(ligne(1L, "75.00")),
                user(10L, UserRole.EMPLOYEE)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("update() avec une categorie inconnue leve ResourceNotFoundException (404)")
    void update_categorieInconnue_leveResourceNotFound() {
        // Given : le depot ne retourne que la categorie 1
        givenBrouillonAvecLigne();
        givenCategoriesConnues(1L);
        givenSaveAssignsId();

        // When : la ligne demandee pointe vers la categorie 99
        assertThatThrownBy(() -> service.update(42L, request(ligne(99L, "75.00")),
                user(9L, UserRole.EMPLOYEE)))
                .isInstanceOf(ResourceNotFoundException.class);

        // Then : la note n'est pas enregistree a moitie
        verify(expenseRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("update() d'une note inexistante leve ResourceNotFoundException (404)")
    void update_noteInconnue_leveResourceNotFound() {
        // Given
        given(expenseRequestRepository.findByIdWithLignes(9999L)).willReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.update(9999L, request(ligne(1L, "75.00")),
                user(9L, UserRole.EMPLOYEE)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =====================================================================
    // E) findByStatut - file de validation
    //
    // Le controle porte sur le couple (role, statut), pas sur le role seul :
    // un MANAGER n'a rien a faire des notes PENDING_FINANCE. C'est la
    // raison d'etre du switch dans le service, et ce que ces tests verrouillent.
    // =====================================================================

    @Test
    @DisplayName("findByStatut par MANAGER ne retourne que les notes de SON équipe")
    void findByStatut_parManagerSurPendingManager() {
        // Given : l'équipe du manager (id 7) contient l'employé (id 9). La
        // requête doit passer par findByStatutAndEmployeeIdIn — la branche
        // filtrée — et NON par findByStatutWithLignes, qui exposerait les
        // notes de toutes les équipes.
        ExpenseRequest attendue = givenNoteAuStatut(ExpenseStatus.PENDING_MANAGER);
        given(userRepository.findByManagerId(7L))
                .willReturn(List.of(user(9L, UserRole.EMPLOYEE)));
        given(expenseRequestRepository.findByStatutAndEmployeeIdIn(
                ExpenseStatus.PENDING_MANAGER, List.of(9L)))
                .willReturn(List.of(attendue));

        // When
        List<ExpenseRequestResponse> resultat = service
                .findByStatut(ExpenseStatus.PENDING_MANAGER, user(7L, UserRole.MANAGER));

        // Then
        assertThat(resultat).hasSize(1);
        assertThat(resultat.getFirst().id()).isEqualTo(42L);

        // Et surtout : la méthode « tout le monde » n'a jamais été touchée.
        verify(expenseRequestRepository, never()).findByStatutWithLignes(any());
    }

    @Test
    @DisplayName("findByStatut par FINANCE_ADMIN sur PENDING_FINANCE est accepte")
    void findByStatut_parFinanceSurPendingFinance() {
        // Given
        ExpenseRequest attendue = givenNoteAuStatut(ExpenseStatus.PENDING_FINANCE);
        given(expenseRequestRepository.findByStatutWithLignes(ExpenseStatus.PENDING_FINANCE))
                .willReturn(List.of(attendue));

        // When
        List<ExpenseRequestResponse> resultat = service
                .findByStatut(ExpenseStatus.PENDING_FINANCE, user(8L, UserRole.FINANCE_ADMIN));

        // Then
        assertThat(resultat).hasSize(1);
    }

    @Test
    @DisplayName("findByStatut par FINANCE_ADMIN sur PENDING_MANAGER : exception metier")
    void findByStatut_parFinanceSurPendingManager() {
        // Given : la finance peut approuver au niveau manager, elle doit donc
        // voir cette file. La note est preparee avant le given() : imbriquer un
        // stubbing dans un willReturn() est rejete par Mockito.
        ExpenseRequest attendue = givenNoteAuStatut(ExpenseStatus.PENDING_MANAGER);
        given(expenseRequestRepository.findByStatutWithLignes(ExpenseStatus.PENDING_MANAGER))
                .willReturn(List.of(attendue));

        // When / Then
        assertThat(service.findByStatut(ExpenseStatus.PENDING_MANAGER, user(8L, UserRole.FINANCE_ADMIN)))
                .hasSize(1);
    }

    @Test
    @DisplayName("findByStatut par EMPLOYEE leve AccessDeniedException, quel que soit le statut")
    void findByStatut_parEmploye_leveAccessDenied() {
        // Given / When / Then : un employe n'a rien a valider, meme pour sa
        // propre note. On eprouve le cas le plus tentant, ou le refus serait
        // le plus facile a oublier.
        assertThatThrownBy(() -> service.findByStatut(ExpenseStatus.PENDING_MANAGER, user(9L, UserRole.EMPLOYEE)))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> service.findByStatut(ExpenseStatus.DRAFT, user(9L, UserRole.EMPLOYEE)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("findByStatut par MANAGER sur PENDING_FINANCE leve AccessDeniedException")
    void findByStatut_managerSurPendingFinance_leveAccessDenied() {
        // Given / When / Then : le refus porte sur le couple, pas sur le role
        assertThatThrownBy(() -> service
                .findByStatut(ExpenseStatus.PENDING_FINANCE, user(7L, UserRole.MANAGER)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("findByStatut refuse ne consulte pas le depot")
    void findByStatut_refuse_neConsultePasLeDepot() {
        // When
        assertThatThrownBy(() -> service
                .findByStatut(ExpenseStatus.REIMBURSED, user(8L, UserRole.FINANCE_ADMIN)))
                .isInstanceOf(AccessDeniedException.class);

        // Then : une file terminale n'appartient a aucun validateur, et le
        // controle doit preceder la requete.
        verify(expenseRequestRepository, never()).findByStatutWithLignes(any());
    }

    // =====================================================================
    // C) findById - matrice d'acces
    // =====================================================================

    @Test
    @DisplayName("findById() autorise le proprietaire de la note")
    void findById_asOwner_shouldReturnResponse() {
        // Given
        givenAnExistingRequestOwnedBy(9L);

        // When
        ExpenseRequestResponse response = service.findById(42L, user(9L, UserRole.EMPLOYEE));

        // Then
        assertThat(response.id()).isEqualTo(42L);
    }

    @Test
    @DisplayName("findById() autorise un MANAGER sur la note d'autrui")
    void findById_asManager_shouldReturnResponse() {
        // Given
        givenAnExistingRequestOwnedBy(9L);

        // When
        ExpenseRequestResponse response = service.findById(42L, user(7L, UserRole.MANAGER));

        // Then
        assertThat(response.id()).isEqualTo(42L);
    }

    @Test
    @DisplayName("findById() autorise un FINANCE_ADMIN sur la note d'autrui")
    void findById_asFinanceAdmin_shouldReturnResponse() {
        // Given
        givenAnExistingRequestOwnedBy(9L);

        // When
        ExpenseRequestResponse response = service.findById(42L, user(8L, UserRole.FINANCE_ADMIN));

        // Then
        assertThat(response.id()).isEqualTo(42L);
    }

    @Test
    @DisplayName("findById() refuse un EMPLOYEE qui n'est pas le proprietaire")
    void findById_asOtherEmployee_shouldThrowAccessDenied() {
        // Given : la note appartient a l'employee 9
        givenAnExistingRequestOwnedBy(9L);

        // When / Then
        assertThatThrownBy(() -> service.findById(42L, user(99L, UserRole.EMPLOYEE)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    @DisplayName("findById() renvoie 404 si la note n'existe pas")
    void findById_withUnknownId_shouldThrowResourceNotFound() {
        // Given
        given(expenseRequestRepository.findByIdWithLignes(404L)).willReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.findById(404L, user(9L, UserRole.EMPLOYEE)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("404");
    }

    /**
     * Simule une note persistee, avec une ligne, appartenant a l'employee fourni.
     *
     * @param ownerId identifiant du proprietaire de la note
     */
    private void givenAnExistingRequestOwnedBy(Long ownerId) {
        User owner = user(ownerId, UserRole.EMPLOYEE);
        Category transport = category(4L, "Transport");

        ExpenseRequest entity = ExpenseRequest.builder()
                .id(42L)
                .employee(owner)
                .statut(ExpenseStatus.DRAFT)
                .totalMontant(new BigDecimal("100.00"))
                .build();

        entity.getLignes().add(ExpenseLine.builder()
                .id(1L)
                .request(entity)
                .categorie(transport)
                .montant(new BigDecimal("100.00"))
                .dateDepense(LocalDate.of(2026, 9, 20))
                .description("Train")
                .build());

        given(expenseRequestRepository.findByIdWithLignes(42L)).willReturn(Optional.of(entity));
    }
}
