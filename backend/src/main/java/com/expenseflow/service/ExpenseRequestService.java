package com.expenseflow.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.dto.AuditLogResponse;
import com.expenseflow.dto.ExpenseLineRequest;
import com.expenseflow.dto.ExpenseRequestCreate;
import com.expenseflow.dto.ExpenseRequestResponse;
import com.expenseflow.dto.TransitionResponse;
import com.expenseflow.entity.Approval;
import com.expenseflow.entity.ExpenseLine;
import com.expenseflow.entity.ExpenseRequest;
import com.expenseflow.entity.User;
import com.expenseflow.entity.Category;
import com.expenseflow.entity.enums.ApprovalStatus;
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
import com.expenseflow.workflow.ExpenseWorkflow;
import com.expenseflow.workflow.WorkflowException;

/**
 * Logique métier des notes de frais.
 *
 * <p>
 * {@code ExpenseRequest} est la racine d'agrégat : les lignes de dépense lui
 * appartiennent et sont persistées avec elle, en une seule opération atomique.
 * Une note et ses lignes ne sont donc jamais visibles à moitié en base.
 * </p>
 *
 * <p>
 * Les contrôles d'accès fins sont appliqués ici plutôt que dans le
 * contrôleur : la règle « qui a le droit de voir cette note » dépend à la fois
 * de l'identité du propriétaire et du rôle du demandeur, ce qui n'a pas sa
 * place dans une annotation.
 * </p>
 */
@Service
public class ExpenseRequestService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseRequestService.class);

    private final ExpenseRequestRepository expenseRequestRepository;

    private final CategoryRepository categoryRepository;

    private final ApprovalRepository approvalRepository;

    private final BudgetValidationService budgetValidationService;

    private final AuditLogService auditLogService;

    private final AuditLogRepository auditLogRepository;

    private final NotificationService notificationService;

    private final UserRepository userRepository;

    /**
     * @param expenseRequestRepository dépôt des notes de frais
     * @param categoryRepository      dépôt des catégories
     * @param approvalRepository      dépôt des décisions d'approbation
     * @param budgetValidationService contrôle des plafonds à la soumission
     * @param auditLogService         journal d'audit des changements d'état
     * @param auditLogRepository      lecture de l'historique
     * @param notificationService     notifications aux acteurs concernés
     * @param userRepository          résolution des équipes (manager_id)
     */
    public ExpenseRequestService(ExpenseRequestRepository expenseRequestRepository,
            CategoryRepository categoryRepository,
            ApprovalRepository approvalRepository,
            BudgetValidationService budgetValidationService,
            AuditLogService auditLogService,
            AuditLogRepository auditLogRepository,
            NotificationService notificationService,
            UserRepository userRepository) {
        this.expenseRequestRepository = expenseRequestRepository;
        this.categoryRepository = categoryRepository;
        this.approvalRepository = approvalRepository;
        this.budgetValidationService = budgetValidationService;
        this.auditLogService = auditLogService;
        this.auditLogRepository = auditLogRepository;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    /**
     * Charge les catégories demandées en une seule requête.
     *
     * <p>
     * Résoudre chaque catégorie dans la boucle de création des lignes
     * produirait une requête par ligne, alors que la même opération peut charger
     * l'ensemble des catégories en un seul aller-retour.
     * </p>
     *
     * @param categorieIds identifiants demandés
     * @return les catégories, indexées par identifiant
     * @throws ResourceNotFoundException si au moins un identifiant est inconnu
     */
    private Map<Long, Category> loadCategories(Set<Long> categorieIds) {
        Map<Long, Category> categoriesById = categoryRepository.findAllById(categorieIds).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));

        Set<Long> manquantes = new HashSet<>(categorieIds);
        manquantes.removeAll(categoriesById.keySet());

        if (!manquantes.isEmpty()) {
            throw new ResourceNotFoundException("Categories introuvables : id=" + manquantes);
        }

        return categoriesById;
    }

    /**
     * Calcule la somme des montants des lignes.
     *
     * @param request lignes de la note
     * @return le total, à deux décimales
     */
    private BigDecimal computeTotal(ExpenseRequestCreate request) {
        return request.lignes().stream()
                .map(ExpenseLineRequest::montant)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Crée une note de frais avec ses lignes, en une seule transaction.
     *
     * <p>
     * Les valeurs que le client ne fournit pas — auteur, statut, total, date de
     * soumission — sont déterminées ici : l'auteur est l'utilisateur
     * authentifié, le statut démarre à {@code DRAFT}, le total est la somme des
     * lignes et la date de soumission reste nulle tant que la note n'a pas été
     * soumise. Aucune donnée de gestion n'est donc pilotable depuis le client.
     * </p>
     *
     * <p>
     * L'opération est atomique : si une ligne échoue (catégorie inconnue), la
     * note et les lignes déjà construites sont abandonnées ensemble.
     * </p>
     *
     * <p>
     * Le contrôle des plafonds n'est <strong>pas</strong> effectué ici : il
     * interviendra à la soumission, en Phase 2.
     * </p>
     *
     * @param request     lignes de la note, validées par Jakarta Validation
     * @param currentUser auteur de la note, issu du jeton
     * @return la note créée, lignes et totaux inclus
     * @throws ResourceNotFoundException si une des catégories demandées n'existe pas
     */
    @Transactional
    public ExpenseRequestResponse create(ExpenseRequestCreate request, User currentUser) {
        Set<Long> categorieIds = request.lignes().stream()
                .map(ExpenseLineRequest::categorieId)
                .collect(Collectors.toSet());
        Map<Long, Category> categoriesById = loadCategories(categorieIds);

        ExpenseRequest expenseRequest = ExpenseRequest.builder()
                .employee(currentUser)
                .statut(ExpenseStatus.DRAFT)
                .totalMontant(computeTotal(request))
                .dateSoumission(null)
                .build();

        for (ExpenseLineRequest ligneRequest : request.lignes()) {
            expenseRequest.getLignes().add(ExpenseLine.builder()
                    .request(expenseRequest)
                    .categorie(categoriesById.get(ligneRequest.categorieId()))
                    .montant(ligneRequest.montant())
                    .dateDepense(ligneRequest.dateDepense())
                    .description(ligneRequest.description())
                    .build());
        }

        // ExpenseRequest n'est pas encore managed : save() lui attribue son
        // identifiant et, grâce au cascade = ALL de l'association lignes,
        // persiste les lignes dans la même transaction.
        ExpenseRequest saved = expenseRequestRepository.save(expenseRequest);
        log.info("Note de frais creee : id={} employeeId={} lignes={} total={}",
                saved.getId(), currentUser.getId(), saved.getLignes().size(), saved.getTotalMontant());

        return ExpenseRequestResponse.from(saved);
    }

    /**
     * Liste les notes de l'utilisateur authentifié, de la plus récente à la
     * plus ancienne.
     *
     * @param currentUser utilisateur authentifié
     * @return ses notes de frais, lignes incluses
     */
    @Transactional(readOnly = true)
    public List<ExpenseRequestResponse> findMyRequests(User currentUser) {
        return expenseRequestRepository.findByEmployeeIdWithLignes(currentUser.getId()).stream()
                .map(ExpenseRequestResponse::from)
                .toList();
    }

    /**
     * Liste les notes d'un statut, pour la file de validation d'un validateur.
     *
     * <p>
     * <strong>Qui a le droit de lister quoi</strong> est une regle metier, pas
     * une consequence du statut demande. Un MANAGER ne voit que ses
     * validations ({@code PENDING_MANAGER}) ; un FINANCE_ADMIN voit les notes
     * qui l'attendent ({@code PENDING_FINANCE}) mais aussi
     * {@code PENDING_MANAGER}, la finance pouvant approuver au niveau manager.
     * Un EMPLOYEE n'a rien a valider : il est refuse, quelle que soit la
     * question posee.
     * </p>
     *
     * <p>
     * <strong>Le refus porte sur le couple (role, statut), pas sur le role
     * seul.</strong> Demander {@code PENDING_FINANCE} en tant que MANAGER est
     * aussi irrecevable que l'est un EMPLOYEE : les deux relevent du meme
     * controle, et les traiter differemment obligerait a ecrire deux branches.
     * D'ou le {@code switch} plutot qu'un simple {@code exigerRole}.
     * </p>
     *
     * <p>
     * <strong>Une valeur d'enumeration invalide est une faute de saisie, pas
     * une intrusion</strong> : elle est remontee telle quelle et traduite en 400
     * par le gestionnaire d'exceptions, qui refuse de la transformer en 403.
     * Un 403 dirait a l'appelant qu'il n'a pas le droit de poser la question,
     * alors qu'il a simplement mal orthographe le statut.
     * </p>
     *
     * @param statut      statut recherche
     * @param currentUser utilisateur authentifie
     * @return les notes correspondantes, lignes incluses
     * @throws org.springframework.security.access.AccessDeniedException si le role ne peut pas valider ce statut
     */
    @Transactional(readOnly = true)
    public List<ExpenseRequestResponse> findByStatut(ExpenseStatus statut, User currentUser) {
        boolean autorise = switch (currentUser.getRole()) {
            case MANAGER -> statut == ExpenseStatus.PENDING_MANAGER;
            case FINANCE_ADMIN -> statut == ExpenseStatus.PENDING_FINANCE
                    || statut == ExpenseStatus.PENDING_MANAGER;
            // ADMIN lit toutes les files : aucune décision n'appartient à ce
            // rôle, donc aucun couple (role, statut) ne lui est refusé. Il ne
            // valide pourtant rien — exigerRole sur les transitions le prive
            // du droit de décision, ici il ne fait que consulter.
            case ADMIN -> true;
            case EMPLOYEE -> false;
        };

        if (!autorise) {
            throw new AccessDeniedException("Access denied");
        }

        // TODO: ajouter une pagination serveur (page/size + tri paramétrable) :
        //       aujourd'hui la liste complète part en une réponse, ce qui
        //       deviendra problématique au-delà de quelques centaines de notes.

        // Filtrage par équipe : seul un MANAGER est restreint à ses propres
        // employés. FINANCE_ADMIN et ADMIN voient la file entière.
        if (currentUser.getRole() == UserRole.MANAGER) {
            List<Long> employeIds = userRepository.findByManagerId(currentUser.getId()).stream()
                    .map(User::getId)
                    .toList();

            // Manager sans équipe : aucune requête SQL, liste vide. Le cas
            // est fréquent (manager fraîchement promu) et renvoyer tout le
            // reste serait une fuite.
            if (employeIds.isEmpty()) {
                return List.of();
            }

            return expenseRequestRepository
                    .findByStatutAndEmployeeIdIn(statut, employeIds).stream()
                    .map(ExpenseRequestResponse::from)
                    .toList();
        }

        return expenseRequestRepository.findByStatutWithLignes(statut).stream()
                .map(ExpenseRequestResponse::from)
                .toList();
    }

    /**
     * Modifie le contenu d'une note de frais en brouillon.
     *
     * <p>
     * <strong>PUT = remplacement intégral.</strong> Toutes les lignes sont
     * supprimées puis recréées à partir du corps reçu, plutôt que d'appliquer un
     * diff ligne par ligne. La comparaison des identifiants de ligne n'apporterait
     * rien ici : la ligne est un détail de la note, sans existence propre pour
     * l'utilisateur, et seule la <em>liste</em> finale a un sens. Le prix à
     * payer est que les identifiants de ligne changent — sans conséquence, aucun
     * écran ni contrat d'API ne les expose.
     * </p>
     *
     * <p>
     * La suppression est prise en charge par {@code orphanRemoval = true} sur
     * l'association : retirer une ligne de la collection suffit à ce qu'Hibernate
     * la supprime. L'attacher explicitement à la nouvelle note parente est
     * indispensable — une ligne conservant son ancienne
     * {@code request_id} deviendrait orpheline en base.
     * </p>
     *
     * <p>
     * <strong>Ordre des contrôles</strong> : propriétaire d'abord, état ensuite.
     * Un tiers reçoit 403 sans apprendre l'état de la note ; le propriétaire
     * d'une note déjà soumise reçoit 409.
     * </p>
     *
     * <p>
     * <strong>Aucune trace d'audit.</strong> Le journal d'audit enregistre les
     * changements d'état, qui engagent le circuit d'approbation. Un brouillon
     * modifié n'a jamais été vu par un validateur : l'historique de la note
     * doit refléter les décisions prises, pas les brouillons successifs que son
     * auteur a pu corriger avant de soumettre.
     * </p>
     *
     * @param id           identifiant de la note
     * @param request      nouvelles lignes ; les mêmes règles de validation que
     *                     la création s'appliquent
     * @param currentUser  propriétaire de la note
     * @return la note modifiée, total recalculé
     * @throws ResourceNotFoundException si la note n'existe pas
     * @throws org.springframework.security.access.AccessDeniedException si l'appelant n'est pas le propriétaire
     * @throws WorkflowException si la note n'est plus en brouillon
     */
    @Transactional
    public ExpenseRequestResponse update(Long id, ExpenseRequestCreate request, User currentUser) {
        ExpenseRequest expenseRequest = chargerNote(id);

        // Un tiers ne modifie pas la note d'autrui, et un manager ne modifie
        // pas le contenu d'une note : il valide ou rejette, ce qui laisse le
        // brouillon sous la responsabilité de son auteur.
        if (!estProprietaire(expenseRequest, currentUser)) {
            throw new AccessDeniedException("Access denied");
        }

        if (expenseRequest.getStatut() != ExpenseStatus.DRAFT) {
            throw new WorkflowException(
                    "Modification impossible : la note " + id + " n'est plus en brouillon (statut "
                            + expenseRequest.getStatut() + ")");
        }

        // orphanRemoval supprime les lignes retirées de la collection.
        expenseRequest.getLignes().clear();

        Set<Long> categorieIds = request.lignes().stream()
                .map(ExpenseLineRequest::categorieId)
                .collect(Collectors.toSet());
        Map<Long, Category> categoriesById = loadCategories(categorieIds);

        for (ExpenseLineRequest ligneRequest : request.lignes()) {
            expenseRequest.getLignes().add(ExpenseLine.builder()
                    .request(expenseRequest)
                    .categorie(categoriesById.get(ligneRequest.categorieId()))
                    .montant(ligneRequest.montant())
                    .dateDepense(ligneRequest.dateDepense())
                    .description(ligneRequest.description())
                    .build());
        }

        // Le total est une donnée dérivée : le recalculer ici évite qu'il
        // diverge du détail affiché après une modification partielle.
        expenseRequest.setTotalMontant(computeTotal(request));

        ExpenseRequest saved = expenseRequestRepository.save(expenseRequest);

        log.info("Note modifiee : id={} lignes={} total={}", id, saved.getLignes().size(),
                saved.getTotalMontant());

        return ExpenseRequestResponse.from(saved);
    }

    // =====================================================================
    // Workflow : transitions d'état
    //
    // Règle absolue de cette section : le statut n'est jamais affecté
    // directement. Toute écriture passe par ExpenseWorkflow.assertTransition,
    // sans quoi une affectation isolée contournerait silencieusement la
    // machine à états.
    //
    // Ordre de contrôle, volontairement dans cet ordre :
    //   1. rôle insuffisant  → AccessDeniedException (403, sécurité)
    //   2. état incompatible → WorkflowException       (409, métier)
    // Inverser cet ordre renverrait 409 à un EMPLOYEE qui tente d'approuver,
    // alors que la vraie cause est un problème de droits.
    // =====================================================================

    /**
     * Vérifie qu'un acteur tient au moins un des rôles requis.
     *
     * @param currentUser  acteur
     * @param rolesRequis  rôles acceptés
     * @throws org.springframework.security.access.AccessDeniedException si aucun rôle ne correspond
     */
    private void exigerRole(User currentUser, UserRole... rolesRequis) {
        for (UserRole role : rolesRequis) {
            if (currentUser.getRole() == role) {
                return;
            }
        }

        // Message volontairement sobre : il ne divulgue pas la liste des rôles
        // valides, inutile pour l'utilisateur qui reçoit un 403.
        throw new AccessDeniedException("Access denied");
    }

    /**
     * Charge une note de frais avec ses lignes, ou lève un 404.
     *
     * @param id identifiant de la note
     * @return note chargée, lignes incluses
     * @throws ResourceNotFoundException si la note n'existe pas
     */
    private ExpenseRequest chargerNote(Long id) {
        return expenseRequestRepository.findByIdWithLignes(id)
                .orElseThrow(() -> new ResourceNotFoundException("Note de frais introuvable : id=" + id));
    }

    /**
     * Soumet une note de frais au circuit d'approbation.
     *
     * <p>
     * Un seul appel produit <strong>deux changements d'état</strong> : DRAFT
     * vers SUBMITTED, puis SUBMITTED vers PENDING_MANAGER. SUBMITTED est un état
     * transitoire, franchi dans la même transaction : il ne survit pas au
     * commit, mais il rend le cycle lisible. La date de soumission est
     * renseignée au passage.
     * </p>
     *
     * <p>
     * <strong>Aucune entrée n'est écrite dans {@code approvals} ici.</strong> La
     * table ne trace que des <em>décisions de validation</em> : son enum
     * {@code ApprovalStatus} ne contient ni {@code SUBMITTED}, ni aucun statut
     * correspondant à une soumission. La soumission n'est donc pas une
     * décision d'un validateur, et n'a rien à figurer dans l'historique des
     * approbations. Sa trace appartiendra à {@code audit_log} (section 2.3),
     * qui enregistre <em>toute</em> mutation, y compris celles qui ne sont pas
     * des décisions humaines.
     * </p>
     *
     * <p>
     * La vérification des plafonds budgétaires a lieu ici, et seulement ici :
     * un brouillon peut porter n'importe quel montant, l'employé doit pouvoir
     * saisir sa note avant de la faire contrôler.
     * </p>
     *
     * <p>
     * Un dépassement laisse la note intacte en DRAFT : l'appelant reçoit un
     * 409 listant <em>tous</em> les dépassements, corrige sa note, et soumet à
     * nouveau.
     * </p>
     *
     * @param id           identifiant de la note
     * @param currentUser  auteur de la note
     * @return nouvel état de la note
     * @throws ResourceNotFoundException                       si la note n'existe pas
     * @throws org.springframework.security.access.AccessDeniedException si l'appelant n'est pas l'auteur
     * @throws com.expenseflow.workflow.WorkflowException       si la note n'est pas en DRAFT
     */
    @Transactional
    public TransitionResponse submit(Long id, User currentUser) {
        ExpenseRequest request = chargerNote(id);

        // Un tiers ne soumet pas la note d'autrui : c'est un contrôle de
        // propriété, donc 403, pas 409.
        if (!estProprietaire(request, currentUser)) {
            throw new AccessDeniedException("Access denied");
        }

        ExpenseWorkflow.assertTransition(
                request.getStatut(), ExpenseStatus.SUBMITTED, currentUser.getRole(), true);

        // Controle des plafonds, avant toute ecriture.
        //
        // Placement deliberé : apres le controle de propriete et d'etat, mais
        // avant le moindre setStatut. Un refus de plafond doit laisser la note
        // en DRAFT, entierement intacte — l'employe corrige et soumet a nouveau.
        // Verifier apres avoir pose PENDING_MANAGER reviendrait a faire
        // depasser la note par la machine a etats avant d'etre refusee.
        List<BudgetViolation> violations = budgetValidationService.validateExpense(request, currentUser);
        if (!violations.isEmpty()) {
            throw new BudgetExceededException(BudgetExceededException.buildMessage(violations));
        }

        request.setDateSoumission(Instant.now());

        // SUBMITTED est l'état « la note a été soumise » ; PENDING_MANAGER
        // l'état opérationnel « une décision de manager est attendue ». Les
        // deux sont posés dans la même transaction. Le second passe
        // volontairement sans assertTransition : aucune requête API ne le
        // déclenche, c'est une transition système.
        //
        // Le journal est écrit ENTRE les deux affectations, jamais après :
        // le statut « depuis » de chaque entrée doit refléter l'état réel
        // précédant le changement. Écrire les deux traces en fin de méthode
        // produirait « PENDING_MANAGER -> PENDING_MANAGER » pour la seconde.
        request.setStatut(ExpenseStatus.SUBMITTED);
        auditLogService.logChangementStatut(currentUser, request.getId(),
                ExpenseStatus.DRAFT, ExpenseStatus.SUBMITTED);

        request.setStatut(ExpenseStatus.PENDING_MANAGER);
        auditLogService.logChangementStatut(currentUser, request.getId(),
                ExpenseStatus.SUBMITTED, ExpenseStatus.PENDING_MANAGER);

        ExpenseRequest saved = expenseRequestRepository.save(request);

        // Tous les managers sont prévenus : le modèle n'a pas de manager
        // attitré. L'appel est postérieur au save pour que l'identifiant
        // soit définitif dans le message.
        notificationService.notifierSoumission(saved);

        log.info("Note soumise : id={} employeeId={} -> PENDING_MANAGER", id, currentUser.getId());

        return new TransitionResponse(saved.getId(), saved.getStatut(), saved.getUpdatedAt());
    }

    /**
     * Approuve une note au niveau manager.
     *
     * <p>
     * Produit deux changements d'état : PENDING_MANAGER vers APPROVED_MANAGER,
     * puis APPROVED_MANAGER vers PENDING_FINANCE, transition système qui confie
     * la note à la finance. Une entrée {@code approvals} trace la décision du
     * manager.
     * </p>
     *
     * @param id           identifiant de la note
     * @param currentUser  manager ou finance
     * @return nouvel état de la note
     * @throws ResourceNotFoundException                 si la note n'existe pas
     * @throws org.springframework.security.access.AccessDeniedException si le rôle ne suffit pas
     * @throws com.expenseflow.workflow.WorkflowException si la note n'est pas en attente de manager
     */
    @Transactional
    public TransitionResponse approve(Long id, User currentUser) {
        // 403 d'abord : la question du droit de décider, avant celle de l'état.
        exigerRole(currentUser, UserRole.MANAGER, UserRole.FINANCE_ADMIN);

        ExpenseRequest request = chargerNote(id);
        boolean isOwner = estProprietaire(request, currentUser);

        ExpenseWorkflow.assertTransition(request.getStatut(), ExpenseStatus.APPROVED_MANAGER,
                currentUser.getRole(), isOwner);

        request.setStatut(ExpenseStatus.APPROVED_MANAGER);
        auditLogService.logChangementStatut(currentUser, request.getId(),
                ExpenseStatus.PENDING_MANAGER, ExpenseStatus.APPROVED_MANAGER);

        enregistrerDecision(request, currentUser, ApprovalStatus.APPROVED_MANAGER, null);

        // Passage automatique au circuit de la finance.
        request.setStatut(ExpenseStatus.PENDING_FINANCE);
        auditLogService.logChangementStatut(currentUser, request.getId(),
                ExpenseStatus.APPROVED_MANAGER, ExpenseStatus.PENDING_FINANCE);

        ExpenseRequest saved = expenseRequestRepository.save(request);

        notificationService.notifierApprouvee(saved);

        log.info("Note approuvee par le manager : id={} validateurId={} -> PENDING_FINANCE",
                id, currentUser.getId());

        return new TransitionResponse(saved.getId(), saved.getStatut(), saved.getUpdatedAt());
    }

    /**
     * Rejette une note au niveau manager.
     *
     * @param id           identifiant de la note
     * @param currentUser  manager ou finance
     * @param commentaire  motif du rejet, obligatoire
     * @return nouvel état de la note
     * @throws ResourceNotFoundException                 si la note n'existe pas
     * @throws org.springframework.security.access.AccessDeniedException si le rôle ne suffit pas
     * @throws com.expenseflow.workflow.WorkflowException si la note n'est pas en attente de manager
     */
    @Transactional
    public TransitionResponse reject(Long id, User currentUser, String commentaire) {
        exigerRole(currentUser, UserRole.MANAGER, UserRole.FINANCE_ADMIN);

        ExpenseRequest request = chargerNote(id);
        boolean isOwner = estProprietaire(request, currentUser);

        ExpenseWorkflow.assertTransition(request.getStatut(), ExpenseStatus.REJECTED_MANAGER,
                currentUser.getRole(), isOwner);

        request.setStatut(ExpenseStatus.REJECTED_MANAGER);
        auditLogService.logChangementStatut(currentUser, request.getId(),
                ExpenseStatus.PENDING_MANAGER, ExpenseStatus.REJECTED_MANAGER);

        enregistrerDecision(request, currentUser, ApprovalStatus.REJECTED_MANAGER, commentaire);

        ExpenseRequest saved = expenseRequestRepository.save(request);

        notificationService.notifierRejetee(saved, commentaire);

        log.info("Note rejetee par le manager : id={} validateurId={}", id, currentUser.getId());

        return new TransitionResponse(saved.getId(), saved.getStatut(), saved.getUpdatedAt());
    }

    /**
     * Marque une note comme remboursée.
     *
     * <p>
     * Transition terminale : la note quitte définitivement le circuit
     * d'approbation.
     * </p>
     *
     * @param id           identifiant de la note
     * @param currentUser  administrateur finance
     * @return nouvel état de la note
     * @throws ResourceNotFoundException                 si la note n'existe pas
     * @throws org.springframework.security.access.AccessDeniedException si le rôle ne suffit pas
     * @throws com.expenseflow.workflow.WorkflowException si la note n'est pas en attente de finance
     */
    @Transactional
    public TransitionResponse reimburse(Long id, User currentUser) {
        exigerRole(currentUser, UserRole.FINANCE_ADMIN);

        ExpenseRequest request = chargerNote(id);
        boolean isOwner = estProprietaire(request, currentUser);

        ExpenseWorkflow.assertTransition(request.getStatut(), ExpenseStatus.REIMBURSED,
                currentUser.getRole(), isOwner);

        request.setStatut(ExpenseStatus.REIMBURSED);
        auditLogService.logChangementStatut(currentUser, request.getId(),
                ExpenseStatus.PENDING_FINANCE, ExpenseStatus.REIMBURSED);

        enregistrerDecision(request, currentUser, ApprovalStatus.APPROVED_FINANCE, null);

        ExpenseRequest saved = expenseRequestRepository.save(request);

        notificationService.notifierRemboursee(saved);

        log.info("Note remboursee : id={} validateurId={}", id, currentUser.getId());

        return new TransitionResponse(saved.getId(), saved.getStatut(), saved.getUpdatedAt());
    }

    /**
     * Rejette une note au niveau finance.
     *
     * @param id           identifiant de la note
     * @param currentUser  administrateur finance
     * @param commentaire  motif du rejet, obligatoire
     * @return nouvel état de la note
     * @throws ResourceNotFoundException                 si la note n'existe pas
     * @throws org.springframework.security.access.AccessDeniedException si le rôle ne suffit pas
     * @throws com.expenseflow.workflow.WorkflowException si la note n'est pas en attente de finance
     */
    @Transactional
    public TransitionResponse rejectFinance(Long id, User currentUser, String commentaire) {
        exigerRole(currentUser, UserRole.FINANCE_ADMIN);

        ExpenseRequest request = chargerNote(id);
        boolean isOwner = estProprietaire(request, currentUser);

        ExpenseWorkflow.assertTransition(request.getStatut(), ExpenseStatus.REJECTED_FINANCE,
                currentUser.getRole(), isOwner);

        request.setStatut(ExpenseStatus.REJECTED_FINANCE);
        auditLogService.logChangementStatut(currentUser, request.getId(),
                ExpenseStatus.PENDING_FINANCE, ExpenseStatus.REJECTED_FINANCE);

        enregistrerDecision(request, currentUser, ApprovalStatus.REJECTED_FINANCE, commentaire);

        ExpenseRequest saved = expenseRequestRepository.save(request);

        notificationService.notifierRejetFinance(saved, commentaire);

        log.info("Note rejetee par la finance : id={} validateurId={}", id, currentUser.getId());

        return new TransitionResponse(saved.getId(), saved.getStatut(), saved.getUpdatedAt());
    }


    /**
     * Indique si l'acteur est l'auteur de la note.
     *
     * @param request     note concernée
     * @param currentUser acteur
     * @return true si l'acteur a créé la note
     */
    private boolean estProprietaire(ExpenseRequest request, User currentUser) {
        return request.getEmployee().getId().equals(currentUser.getId());
    }

    /**
     * Enregistre la décision d'un validateur.
     *
     * <p>
     * La colonne {@code date_action} n'est pas renseignée : elle porte
     * {@code insertable = false, updatable = false} et est alimentée par
     * {@code DEFAULT NOW()} en base. L'horodatage conservé est donc celui de la
     * validation PostgreSQL, non celui de la JVM.
     * </p>
     *
     * @param request     note concernée
     * @param validateur  acteur ayant décidé
     * @param statut      décision enregistrée
     * @param commentaire motif, ou null pour une approbation
     */
    private void enregistrerDecision(ExpenseRequest request, User validateur,
            ApprovalStatus statut, String commentaire) {
        approvalRepository.save(Approval.builder()
                .request(request)
                .validateur(validateur)
                .statut(statut)
                .commentaire(commentaire)
                .build());
    }

    /**
     * Charge une note et vérifie que l'appelant a le droit de la consulter.
     *
     * <p>
     * Trois profils sont autorisés : le propriétaire de la note, un manager et
     * l'administrateur finance. Cette règle ne peut pas s'exprimer par une
     * annotation {@code @PreAuthorize}, car elle dépend de l'identité du
     * propriétaire, connue seulement une fois la note chargée.
     * </p>
    /**
     * Historique des changements d'état d'une note de frais.
     *
     * <p>
     * <strong>Accès ouvert au propriétaire comme aux validateurs.</strong> La
     * règle reprend celle de {@link #findById} : l'employé suit sa propre note,
     * le manager et la finance suivent les notes qu'ils ont à traiter. Un
     * EMPLOYEE tiers n'en voit rien — et n'apprend même pas que la note
     * existe, puisqu'il reçoit un 403 avant toute lecture du journal.
     * </p>
     *
     * @param requestId   identifiant de la note
     * @param currentUser utilisateur authentifié
     * @return entrées du journal, de la plus ancienne à la plus récente
     * @throws ResourceNotFoundException si la note n'existe pas
     * @throws org.springframework.security.access.AccessDeniedException si l'appelant n'a pas accès à la note
     */
    @Transactional(readOnly = true)
    public List<AuditLogResponse> getAuditHistory(Long requestId, User currentUser) {
        ExpenseRequest request = chargerNote(requestId);

        boolean isOwner = estProprietaire(request, currentUser);
        boolean estValidateur = currentUser.getRole() == UserRole.MANAGER
                || currentUser.getRole() == UserRole.FINANCE_ADMIN;

        if (!isOwner && !estValidateur) {
            throw new AccessDeniedException("Access denied");
        }

        return auditLogRepository
                .findByEntiteConcerneeAndEntiteIdOrderByDateActionAscIdAsc(
                        AuditLogService.ENTITE_EXPENSE_REQUEST, requestId)
                .stream()
                .map(AuditLogResponse::from)
                .toList();
    }


    /**
     * Charge une note de frais et vérifie que l'appelant a le droit de la consulter.
     *
     * <p>
     * Trois profils sont autorisés : le propriétaire de la note, un manager et
     * l'administrateur finance. Cette règle ne peut pas s'exprimer par une
     * annotation {@code @PreAuthorize}, car elle dépend de l'identité du
     * propriétaire, connue seulement une fois la note chargée.
     * </p>
     *
     * @param id          identifiant de la note
     * @param currentUser utilisateur authentifié
     * @return la note demandée, lignes incluses
     * @throws ResourceNotFoundException si la note n'existe pas
     * @throws AccessDeniedException    si l'appelant n'est ni propriétaire, ni manager, ni finance
     */
    @Transactional(readOnly = true)
    public ExpenseRequestResponse findById(Long id, User currentUser) {
        ExpenseRequest expenseRequest = expenseRequestRepository.findByIdWithLignes(id)
                .orElseThrow(() -> new ResourceNotFoundException("Note de frais introuvable : id=" + id));

        boolean isOwner = expenseRequest.getEmployee().getId().equals(currentUser.getId());
        boolean isManager = currentUser.getRole() == UserRole.MANAGER;
        boolean isFinance = currentUser.getRole() == UserRole.FINANCE_ADMIN;

        if (!isOwner && !isManager && !isFinance) {
            log.debug("Accès refusé à la note id={} pour l'utilisateur id={}", id, currentUser.getId());
            throw new AccessDeniedException("Access denied");
        }

        return ExpenseRequestResponse.from(expenseRequest);
    }
}
