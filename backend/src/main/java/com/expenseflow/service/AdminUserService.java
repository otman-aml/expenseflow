package com.expenseflow.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.dto.AdminUserResponse;
import com.expenseflow.dto.CreateUserRequest;
import com.expenseflow.dto.UpdateUserRequest;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.exception.EmailAlreadyExistsException;
import com.expenseflow.exception.LastAdminException;
import com.expenseflow.exception.ResourceNotFoundException;
import com.expenseflow.exception.UserHasAuditLogException;
import com.expenseflow.exception.UserInUseException;
import com.expenseflow.repository.AuditLogRepository;
import com.expenseflow.repository.ExpenseRequestRepository;
import com.expenseflow.repository.UserRepository;

/**
 * Gestion des comptes, réservée au rôle {@code ADMIN}.
 *
 * <p>
 * <strong>Le service ne s'autorise jamais lui-même</strong> : il ne vérifie
 * pas le rôle de l'appelant, c'est {@code @PreAuthorize("hasRole('ADMIN')")}
 * sur le contrôleur qui tranche avant d'atteindre cette classe. Une telle
 * séparation évite qu'une règle de sécurité glisse un jour dans une
 * méthode interne, contournée par un appel du même bean.
 * </p>
 *
 * <p>
 * <strong>Les opérations destructives sont contrôlées, pas subies.</strong>
 * Supprimer un compte qui possède des notes violerait la clé
 * {@code expense_requests.employee_id} ({@code ON DELETE RESTRICT}) : le
 * service compte d'abord et renvoie un 409 lisible plutôt que de laisser
 * PostgreSQL échouer en 500. Un admin ne peut pas non plus se supprimer
 * lui-même — le lock-out d'un unique administrateur rendrait la table
 * inaccessible à quiconque.
 * </p>
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final UserRepository userRepository;

    private final ExpenseRequestRepository expenseRequestRepository;

    private final PasswordEncoder passwordEncoder;

    private final AuditLogService auditLogService;

    private final AuditLogRepository auditLogRepository;

    /**
     * @param userRepository            dépôt des comptes
     * @param expenseRequestRepository  dépôt des notes, pour le contrôle de suppression
     * @param passwordEncoder           encodeur BCrypt du contexte
     * @param auditLogService           journal d'audit, alimenté à chaque mutation
     * @param auditLogRepository        journal d'audit, lu pour le contrôle de suppression
     */
    public AdminUserService(UserRepository userRepository,
            ExpenseRequestRepository expenseRequestRepository,
            PasswordEncoder passwordEncoder,
            AuditLogService auditLogService,
            AuditLogRepository auditLogRepository) {
        this.userRepository = userRepository;
        this.expenseRequestRepository = expenseRequestRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Crée un compte avec le rôle demandé.
     *
     * @param request     nom, e-mail, mot de passe en clair, rôle et manager éventuel
     * @param currentUser administrateur appelant — auteur de l'action journalisée
     * @return le compte créé
     * @throws EmailAlreadyExistsException si l'e-mail est déjà pris
     * @throws ResourceNotFoundException   si {@code managerId} ne désigne personne de rôle MANAGER
     */
    @Transactional
    public AdminUserResponse createUser(CreateUserRequest request, User currentUser) {
        String email = request.email().trim().toLowerCase();

        if (userRepository.findByEmail(email).isPresent()) {
            throw new EmailAlreadyExistsException(email);
        }

        User user = User.builder()
                .nom(request.nom().trim())
                .email(email)
                // Haché ici, jamais stocké en clair : le DTO reçoit le mot de
                // passe du formulaire, il ne doit pas survivre au service.
                .motDePasse(passwordEncoder.encode(request.motDePasse()))
                .role(request.role())
                .manager(resolverManager(request.managerId()))
                // Mot de passe provisoire communique hors ligne : le
                // destinataire doit le remplacer a sa premiere connexion
                // (colonne doit_changer_mdp, migration V6).
                .doitChangerMdp(true)
                .build();

        User saved = userRepository.save(user);
        log.info("Compte cree : id={} email={} role={} managerId={}",
                saved.getId(), saved.getEmail(), saved.getRole(),
                saved.getManager() != null ? saved.getManager().getId() : null);

        // Tracé de bout en bout : sans cette ligne, la création d'un compte
        // (dont ses droits) ne laisserait aucune trace interrogeable après coup.
        auditLogService.log(currentUser, AuditLogService.ENTITE_USER, saved.getId(),
                AuditLogService.ACTION_COMPTE_CREE,
                "Compte créé : " + saved.getEmail() + " (" + saved.getRole() + ")");

        return AdminUserResponse.from(saved);
    }

    /**
     * Liste tous les comptes, manager résolu en une seule requête.
     *
     * @return tous les comptes, triés par identifiant
     */
    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers() {
        return userRepository.findAllWithManager().stream()
                .map(AdminUserResponse::from)
                .toList();
    }

    /**
     * Modifie nom, e-mail, rôle et rattachement d'un compte.
     *
     * @param id          identifiant du compte
     * @param request     nouvelles valeurs
     * @param currentUser administrateur appelant — auteur de l'action journalisée
     * @return le compte modifié
     * @throws ResourceNotFoundException   si le compte ou le manager n'existe pas, ou si le manager n'a pas le rôle MANAGER
     * @throws EmailAlreadyExistsException si l'e-mail est pris par un autre compte
     */
    @Transactional
    public AdminUserResponse updateUser(Long id, UpdateUserRequest request, User currentUser) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur " + id + " introuvable"));

        String email = request.email().trim().toLowerCase();
        requireEmailLibre(email, id);

        user.setNom(request.nom().trim());
        user.setEmail(email);
        user.setRole(request.role());
        user.setManager(resolverManager(request.managerId()));

        User saved = userRepository.save(user);
        log.info("Compte modifie : id={} role={} managerId={}",
                saved.getId(), saved.getRole(),
                saved.getManager() != null ? saved.getManager().getId() : null);

        // Un changement de rôle ou d'e-mail échappe à la note elle-même : sans
        // trace, impossible de dire QUI a promu un compte ADMIN et QUAND.
        auditLogService.log(currentUser, AuditLogService.ENTITE_USER, saved.getId(),
                AuditLogService.ACTION_COMPTE_MODIFIE,
                "Compte modifié : " + saved.getEmail() + " (" + saved.getRole() + ")");

        return AdminUserResponse.from(saved);
    }

    /**
     * Supprime un compte, sauf s'il possède des notes ou s'il s'agit de
     * l'administrateur appelant.
     *
     * @param id          identifiant du compte
     * @param currentUser administrateur authentifié (pour l'auto-refus)
     * @throws ResourceNotFoundException   si le compte n'existe pas
     * @throws LastAdminException          si la cible est le seul compte ADMIN de la table
     * @throws UserHasAuditLogException    si le compte est déjà auteur d'entrées d'audit
     * @throws UserInUseException          si le compte possède encore des notes
     * @throws AccessDeniedException       si l'admin se supprime lui-même
     */
    @Transactional
    public void deleteUser(Long id, User currentUser) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur " + id + " introuvable"));

        // Invariant de données, placé AVANT toute autre règle : la table doit
        // toujours compter au moins un ADMIN. Il décrit l'état de la table, pas
        // le lien appelant/cible — un appelant non-ADMIN ou un futur point
        // d'appel moins strict que le contrôleur se heurterait au même mur.
        // L'auto-refus ci-dessous ne couvre que le cas « je me supprime moi »,
        // pas « je retire le dernier administrateur par un autre compte ».
        if (user.getRole() == UserRole.ADMIN
                && userRepository.countByRole(UserRole.ADMIN) <= 1) {
            throw new LastAdminException();
        }

        // Auto-suppression refusée : le dernier ADMIN partirait avec la cle
        // de la table, et aucun compte ne pourrait la rouvrir.
        if (user.getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("Access denied");
        }

        // audit_log.utilisateur_id est en ON DELETE RESTRICT : supprimer un
        // compte déjà cité comme auteur ferait échouer la transaction en 500.
        // AVANT les notes, car ce refus est définitif — l'admin doit le savoir
        // avant de songer à détacher quoi que ce soit.
        long traces = auditLogRepository.countByUtilisateurId(user.getId());
        if (traces > 0) {
            throw new UserHasAuditLogException();
        }

        long notes = expenseRequestRepository.countByEmployeeId(id);
        if (notes > 0) {
            throw new UserInUseException(user.getNom(), notes);
        }

        // TODO: si le compte supprimé est un MANAGER, traiter explicitement le
        //       sort de ses employés (users.manager_id orphelin) : aujourd'hui
        //       la contrainte FK décide seule, et l'admin n'est pas prévenu
        //       que des comptes se retrouveront sans supérieur.
        userRepository.delete(user);
        log.info("Compte supprime : id={} email={}", user.getId(), user.getEmail());

        // Journalisé APRÈS le delete : entiteId reste l'identifiant sorti de la
        // table, et l'auteur (l'admin appelant) n'est jamais lui-même supprimé —
        // l'auto-refus ci-dessus garantit que la FK audit reste satisfaisable.
        auditLogService.log(currentUser, AuditLogService.ENTITE_USER, user.getId(),
                AuditLogService.ACTION_COMPTE_SUPPRIME,
                "Compte supprimé : " + user.getEmail() + " (" + user.getRole() + ")");
    }

    /**
     * Comptes éligibles au rôle de manager d'un nouveau compte.
     *
     * @return tous les MANAGER, pour le sélecteur du formulaire
     */
    @Transactional(readOnly = true)
    public List<AdminUserResponse> listManagers() {
        return userRepository.findByRole(UserRole.MANAGER).stream()
                .map(AdminUserResponse::from)
                .toList();
    }

    /**
     * Résout le manager d'un compte, ou {@code null} s'il n'y en a pas.
     *
     * <p>
     * Le manager doit exister <em>et</em> porter le rôle MANAGER : rattaché à
     * un non-manager, le filtrage d'équipe assignerait des notes à quelqu'un
     * qui ne pourrait pas les valider — l'incohérence ne se révèlerait qu'au
     * moment de la première file de validation.
     * </p>
     *
     * @param managerId identifiant fourni, ou {@code null}
     * @return le manager résolu
     * @throws ResourceNotFoundException si l'identifiant ne désigne pas un MANAGER
     */
    private User resolverManager(Long managerId) {
        if (managerId == null) {
            return null;
        }

        User manager = userRepository.findById(managerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Manager " + managerId + " introuvable"));

        if (manager.getRole() != UserRole.MANAGER) {
            throw new ResourceNotFoundException(
                    "Utilisateur " + managerId + " n'est pas un manager");
        }

        return manager;
    }

    /**
     * Vérifie qu'un e-mail n'est pas déjà pris par un AUTRE compte.
     *
     * @param email          e-mail normalisé
     * @param excludeUserId  identifiant du compte en cours de modification
     * @throws EmailAlreadyExistsException si un autre compte porte déjà cet e-mail
     */
    private void requireEmailLibre(String email, Long excludeUserId) {
        userRepository.findByEmail(email)
                .filter(existant -> !existant.getId().equals(excludeUserId))
                .ifPresent(existant -> {
                    throw new EmailAlreadyExistsException(email);
                });
    }
}
