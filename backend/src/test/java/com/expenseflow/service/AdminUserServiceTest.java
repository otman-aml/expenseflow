package com.expenseflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

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
 * Tests unitaires de {@link AdminUserService}.
 *
 * <p>
 * Aucun contexte Spring : dépôts et encodeur simulés. Les tests verrouillent
 * les invariants de sécurité du service — hachage systématique du mot de
 * passe, e-mail unique, interdiction de s'auto-supprimer, refus d'attacher
 * un non-manager — car chacun correspond à une ligne de code dont
 * l'absence produirait une faille, pas une erreur visible.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserService")
class AdminUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ExpenseRequestRepository expenseRequestRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AdminUserService service;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    private final User admin = User.builder()
            .id(1L).nom("Admin Test").email("admin-test@x.ma").role(UserRole.ADMIN)
            .build();

    /**
     * Requête de création valide, conforme aux contraintes backend.
     *
     * @return requête prête à être soumise
     */
    private CreateUserRequest creation() {
        return new CreateUserRequest(
                "Nouvel Employe", "Nouvel.Employee@X.MA", "MotDePasse1", UserRole.EMPLOYEE, null);
    }

    @Test
    @DisplayName("createUser() hache TOUJOURS le mot de passe — jamais de clair en base")
    void createUserHashLeMotDePasse() {
        // Given
        given(passwordEncoder.encode("MotDePasse1")).willReturn("$2a$10$hash");
        given(userRepository.findByEmail("nouvel.employee@x.ma")).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        // When
        service.createUser(creation(), admin);

        // Then : le hash servi au dépôt, jamais la saisie
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getMotDePasse()).isEqualTo("$2a$10$hash");
        assertThat(userCaptor.getValue().getMotDePasse()).isNotEqualTo("MotDePasse1");
    }

    @Test
    @DisplayName("createUser() normalise l'e-mail (minuscules, espaces retirés)")
    void createUserNormaliseEmail() {
        given(passwordEncoder.encode(anyString())).willReturn("$2a$10$hash");
        given(userRepository.findByEmail("nouvel.employee@x.ma")).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        service.createUser(creation(), admin);

        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("nouvel.employee@x.ma");
        assertThat(userCaptor.getValue().getNom()).isEqualTo("Nouvel Employe");
        assertThat(userCaptor.getValue().getRole()).isEqualTo(UserRole.EMPLOYEE);
    }

    @Test
    @DisplayName("createUser() refuse un e-mail déjà pris, avant tout hachage")
    void createUserEmailDuplique() {
        given(userRepository.findByEmail("nouvel.employee@x.ma"))
                .willReturn(Optional.of(admin));

        // When / Then : l'unicité est testée AVANT encode() — gaspiller un
        // hachage pour un refus connu n'a aucun sens.
        assertThatThrownBy(() -> service.createUser(creation(), admin))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("createUser() sans managerId laisse la hiérarchie vide")
    void createUserSansManager() {
        given(passwordEncoder.encode(anyString())).willReturn("$2a$10$hash");
        given(userRepository.findByEmail(anyString())).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        service.createUser(creation(), admin);

        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getManager()).isNull();
    }

    @Test
    @DisplayName("createUser() naît avec doitChangerMdp = true — changement force a la 1re connexion")
    void createUserDoitChangerMdp() {
        // Given
        given(passwordEncoder.encode(anyString())).willReturn("$2a$10$hash");
        given(userRepository.findByEmail(anyString())).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        // When
        service.createUser(creation(), admin);

        // Then : le compte naît à true — le mot de passe provisoire doit être
        // remplacé dès la première connexion (colonne V6).
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().isDoitChangerMdp()).isTrue();
    }

    @Test
    @DisplayName("createUser() attache un manager existant de rôle MANAGER")
    void createUserAvecManagerValide() {
        User manager = User.builder()
                .id(7L).nom("Boss").email("boss@x.ma").role(UserRole.MANAGER).build();
        given(userRepository.findById(7L)).willReturn(Optional.of(manager));
        given(passwordEncoder.encode(anyString())).willReturn("$2a$10$hash");
        given(userRepository.findByEmail(anyString())).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        CreateUserRequest avecManager = new CreateUserRequest(
                "Nouvel Employe", "nouvel.employee@x.ma", "MotDePasse1", UserRole.EMPLOYEE, 7L);
        service.createUser(avecManager, admin);

        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getManager()).isSameAs(manager);
    }

    @Test
    @DisplayName("createUser() refuse un managerId qui n'est PAS un MANAGER")
    void createUserManagerNonManagerRefuse() {
        // Given : l'identifiant pointe vers un simple employé
        User employe = User.builder()
                .id(9L).nom("Pas Un Manager").email("pasm@x.ma").role(UserRole.EMPLOYEE).build();
        given(userRepository.findById(9L)).willReturn(Optional.of(employe));

        // When / Then : rattacher à un non-manager créerait une équipe que
        // son « manager » ne pourrait pas valider — refus avant tout save.
        CreateUserRequest casse = new CreateUserRequest(
                "Nouvel Employe", "nouvel.employee@x.ma", "MotDePasse1", UserRole.EMPLOYEE, 9L);
        assertThatThrownBy(() -> service.createUser(casse, admin))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateUser() accepte de conserver son propre e-mail")
    void updateUserPropreEmailAccepte() {
        // Given : l'e-mail retourné correspond AU compte modifié
        User cible = User.builder()
                .id(5L).nom("Avant").email("cible@x.ma").role(UserRole.EMPLOYEE).build();
        given(userRepository.findById(5L)).willReturn(Optional.of(cible));
        given(userRepository.findByEmail("cible@x.ma")).willReturn(Optional.of(cible));
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        // When : e-mail identique, nom et rôle changés
        UpdateUserRequest maj = new UpdateUserRequest("Apres", "cible@x.ma",
                UserRole.MANAGER, null);
        AdminUserResponse response = service.updateUser(5L, maj, admin);

        // Then
        assertThat(response.nom()).isEqualTo("Apres");
        assertThat(response.role()).isEqualTo(UserRole.MANAGER);
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("updateUser() refuse un e-mail pris par UN AUTRE compte")
    void updateUserEmailPrisParAutreRefuse() {
        User cible = User.builder()
                .id(5L).nom("Avant").email("cible@x.ma").role(UserRole.EMPLOYEE).build();
        User autre = User.builder()
                .id(6L).nom("Autre").email("pris@x.ma").role(UserRole.EMPLOYEE).build();
        given(userRepository.findById(5L)).willReturn(Optional.of(cible));
        given(userRepository.findByEmail("pris@x.ma")).willReturn(Optional.of(autre));

        UpdateUserRequest vol = new UpdateUserRequest("Voleur", "pris@x.ma",
                UserRole.EMPLOYEE, null);
        assertThatThrownBy(() -> service.updateUser(5L, vol, admin))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("deleteUser() refuse un compte qui possède encore des notes")
    void deleteUserAvecNotesRefuse() {
        User employe = User.builder()
                .id(9L).nom("Note").email("note@x.ma").role(UserRole.EMPLOYEE).build();
        given(userRepository.findById(9L)).willReturn(Optional.of(employe));
        given(expenseRequestRepository.countByEmployeeId(9L)).willReturn(3L);

        // When / Then : 409 lisible plutôt qu'un DataIntegrityViolation (500)
        assertThatThrownBy(() -> service.deleteUser(9L, admin))
                .isInstanceOf(UserInUseException.class)
                .hasMessageContaining("3");
        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteUser() interdit à un admin de se supprimer lui-même")
    void deleteUserAutoSuppressionRefuse() {
        given(userRepository.findById(1L)).willReturn(Optional.of(admin));
        // Il existe un AUTRE admin : l'invariant « dernier ADMIN » est
        // satisfait, c'est bien l'auto-refus qui doit trancher (403).
        given(userRepository.countByRole(UserRole.ADMIN)).willReturn(2L);

        // When / Then : AccessDeniedException — le lock-out du dernier ADMIN
        // rendrait la table inaccessible pour de bon.
        assertThatThrownBy(() -> service.deleteUser(1L, admin))
                .isInstanceOf(AccessDeniedException.class);
        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteUser() supprime un compte nu sans note")
    void deleteUserCompteNuReussit() {
        User vierge = User.builder()
                .id(9L).nom("Vierge").email("vierge@x.ma").role(UserRole.EMPLOYEE).build();
        given(userRepository.findById(9L)).willReturn(Optional.of(vierge));
        given(expenseRequestRepository.countByEmployeeId(9L)).willReturn(0L);

        // When
        service.deleteUser(9L, admin);

        // Then
        verify(userRepository).delete(vierge);
    }

    @Test
    @DisplayName("listManagers() ne retourne QUE les MANAGER")
    void listManagersFiltreParRole() {
        User manager = User.builder()
                .id(7L).nom("Boss").email("boss@x.ma").role(UserRole.MANAGER).build();
        given(userRepository.findByRole(UserRole.MANAGER)).willReturn(List.of(manager));

        List<AdminUserResponse> resultat = service.listManagers();

        assertThat(resultat).hasSize(1);
        assertThat(resultat.getFirst().role()).isEqualTo(UserRole.MANAGER);
        verify(userRepository).findByRole(UserRole.MANAGER);
    }

    // ---------------------------------------------------------------------
    // Journal d'audit : chaque mutation de compte laisse une trace
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("createAuditLog_shouldBeCalled : une création journalise COMPTE_CREE avec l'appelant comme auteur")
    void createAuditLog_shouldBeCalled() {
        // Given
        given(passwordEncoder.encode(anyString())).willReturn("$2a$10$hash");
        given(userRepository.findByEmail("nouvel.employee@x.ma")).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(77L);
            return u;
        });

        // When
        service.createUser(creation(), admin);

        // Then : entiteId = compte créé, auteur = admin appelant — sans cette
        // trace, une création de compte dotée de droits ADMIN serait muette.
        verify(auditLogService).log(same(admin), eq(AuditLogService.ENTITE_USER), eq(77L),
                eq(AuditLogService.ACTION_COMPTE_CREE), contains("nouvel.employee@x.ma"));
    }

    @Test
    @DisplayName("updateAuditLog_shouldBeCalled : une édition journalise COMPTE_MODIFIE avec l'appelant comme auteur")
    void updateAuditLog_shouldBeCalled() {
        // Given
        User cible = User.builder()
                .id(5L).nom("Avant").email("cible@x.ma").role(UserRole.EMPLOYEE).build();
        given(userRepository.findById(5L)).willReturn(Optional.of(cible));
        given(userRepository.findByEmail("cible@x.ma")).willReturn(Optional.of(cible));
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        // When : promotion au rôle MANAGER
        UpdateUserRequest maj = new UpdateUserRequest("Apres", "cible@x.ma",
                UserRole.MANAGER, null);
        service.updateUser(5L, maj, admin);

        // Then
        verify(auditLogService).log(same(admin), eq(AuditLogService.ENTITE_USER), eq(5L),
                eq(AuditLogService.ACTION_COMPTE_MODIFIE), contains("MANAGER"));
    }

    @Test
    @DisplayName("deleteAuditLog_shouldBeCalled : une suppression journalise COMPTE_SUPPRIME avec l'appelant comme auteur")
    void deleteAuditLog_shouldBeCalled() {
        // Given
        User vierge = User.builder()
                .id(9L).nom("Vierge").email("vierge@x.ma").role(UserRole.EMPLOYEE).build();
        given(userRepository.findById(9L)).willReturn(Optional.of(vierge));
        given(expenseRequestRepository.countByEmployeeId(9L)).willReturn(0L);

        // When
        service.deleteUser(9L, admin);

        // Then : entiteId conserve l'identifiant sorti de la table
        verify(userRepository).delete(vierge);
        verify(auditLogService).log(same(admin), eq(AuditLogService.ENTITE_USER), eq(9L),
                eq(AuditLogService.ACTION_COMPTE_SUPPRIME), contains("vierge@x.ma"));
    }

    // ---------------------------------------------------------------------
    // Invariant « dernier ADMIN » : la table ne reste jamais sans administrateur
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("deleteDernierAdmin_shouldThrow : refuser de retirer le seul ADMIN persisté")
    void deleteDernierAdmin_shouldThrow() {
        // Given : la cible est le SEUL compte ADMIN de la table simulée —
        // l'appelant (fixture) n'y figure pas, ce qui modélise un appelant
        // dont le rôle n'est plus ADMIN en base (token périmé, futur point
        // d'appel moins strict que le contrôleur).
        User dernierAdmin = User.builder()
                .id(50L).nom("Seul Admin").email("seul.admin@x.ma").role(UserRole.ADMIN).build();
        given(userRepository.findById(50L)).willReturn(Optional.of(dernierAdmin));
        given(userRepository.countByRole(UserRole.ADMIN)).willReturn(1L);

        // When / Then : 409 lisible plutôt qu'une table verrouillée pour de bon
        assertThatThrownBy(() -> service.deleteUser(50L, admin))
                .isInstanceOf(LastAdminException.class);
        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteAdminAvecAutreAdmin_shouldSucceed : tant qu'un autre ADMIN subsiste, la suppression passe")
    void deleteAdminAvecAutreAdmin_shouldSucceed() {
        // Given : deux ADMIN en base, la cible n'est pas le dernier
        User cible = User.builder()
                .id(50L).nom("Admin B").email("admin-b@x.ma").role(UserRole.ADMIN).build();
        given(userRepository.findById(50L)).willReturn(Optional.of(cible));
        given(userRepository.countByRole(UserRole.ADMIN)).willReturn(2L);
        given(expenseRequestRepository.countByEmployeeId(50L)).willReturn(0L);

        // When
        service.deleteUser(50L, admin);

        // Then : l'invariant tient (2 - 1 = 1 restant), la suppression a lieu
        verify(userRepository).delete(cible);
        verify(auditLogService).log(same(admin), eq(AuditLogService.ENTITE_USER), eq(50L),
                eq(AuditLogService.ACTION_COMPTE_SUPPRIME), contains("admin-b@x.ma"));
    }

    // ---------------------------------------------------------------------
    // FK audit_log.utilisateur_id (RESTRICT) : le 409 symétrique aux notes
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("deleteUserAvecAuditLog_shouldThrow409 : un compte auteur d'audit est immuable")
    void deleteUserAvecAuditLog_shouldThrow409() {
        // Given : le compte est cité comme auteur d'au moins une entrée —
        // sans ce garde, la FK RESTRICT ferait échouer la transaction en 500.
        User auteur = User.builder()
                .id(10L).nom("Auteur").email("auteur@x.ma").role(UserRole.EMPLOYEE).build();
        given(userRepository.findById(10L)).willReturn(Optional.of(auteur));
        given(auditLogRepository.countByUtilisateurId(10L)).willReturn(1L);

        // When / Then : 409 lisible, aucun delete engagé
        assertThatThrownBy(() -> service.deleteUser(10L, admin))
                .isInstanceOf(UserHasAuditLogException.class)
                .hasMessageContaining("actions tracées");
        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteUserSansAuditLog_shouldSucceed : un compte sans trace peut être supprimé")
    void deleteUserSansAuditLog_shouldSucceed() {
        // Given : aucune entrée pour cet auteur, aucune note rattachée
        User vierge = User.builder()
                .id(10L).nom("Vierge").email("vierge.audit@x.ma").role(UserRole.EMPLOYEE).build();
        given(userRepository.findById(10L)).willReturn(Optional.of(vierge));
        given(auditLogRepository.countByUtilisateurId(10L)).willReturn(0L);
        given(expenseRequestRepository.countByEmployeeId(10L)).willReturn(0L);

        // When
        service.deleteUser(10L, admin);

        // Then
        verify(userRepository).delete(vierge);
    }
}