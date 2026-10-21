package com.expenseflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

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
import org.springframework.security.crypto.password.PasswordEncoder;

import com.expenseflow.dto.ChangePasswordRequest;
import com.expenseflow.dto.UpdateProfileRequest;
import com.expenseflow.dto.UserResponse;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.exception.EmailAlreadyExistsException;
import com.expenseflow.exception.InvalidPasswordException;
import com.expenseflow.exception.PasswordMismatchException;
import com.expenseflow.exception.ResourceNotFoundException;
import com.expenseflow.repository.UserRepository;

/**
 * Tests unitaires de {@link UserProfileService}.
 *
 * <p>
 * L'attention porte sur trois points où le service peut se tromper
 * silencieusement : la normalisation de l'e-mail, l'exclusion de
 * l'utilisateur lui-même du contrôle d'unicite, et la comparaison du
 * nouveau mot de passe a l'ancien par hachage plutot que par egalite de
 * chaines.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserProfileService")
class UserProfileServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserProfileService service;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    // === Tests getProfile ===

    @Test
    @DisplayName("getProfile retourne le profil de l'utilisateur existant")
    void getProfile_withExistingUser_shouldReturn() {
        // Given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(utilisateur()));

        // When
        UserResponse reponse = service.getProfile(USER_ID);

        // Then
        assertThat(reponse.nom()).isEqualTo("Alice Martin");
        assertThat(reponse.email()).isEqualTo("alice.martin@expenseflow.ma");
        assertThat(reponse.role()).isEqualTo(UserRole.EMPLOYEE);
    }

    @Test
    @DisplayName("getProfile lève 404 si l'utilisateur est inconnu")
    void getProfile_withUnknownUser_shouldThrow404() {
        // Given
        given(userRepository.findById(99L)).willReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.getProfile(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    // === Tests updateProfile ===

    @Test
    @DisplayName("updateProfile enregistre le nouveau nom et l'e-mail")
    void updateProfile_shouldUpdateNomAndEmail() {
        // Given
        User user = utilisateur();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userRepository.findByEmail("bob.dupont@expenseflow.ma")).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willReturn(user);

        // When
        UserResponse reponse = service.updateProfile(USER_ID,
                new UpdateProfileRequest("Bob Dupont", "bob.dupont@expenseflow.ma"));

        // Then
        assertThat(reponse.nom()).isEqualTo("Bob Dupont");
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getNom()).isEqualTo("Bob Dupont");
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("bob.dupont@expenseflow.ma");
    }

    @Test
    @DisplayName("updateProfile normalise l'e-mail (trim + casse)")
    void updateProfile_shouldNormalizeEmail() {
        // Given : espaces autour et casse haute
        User user = utilisateur();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userRepository.findByEmail("bob.dupont@expenseflow.ma")).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willReturn(user);

        // When
        UserResponse reponse = service.updateProfile(USER_ID,
                new UpdateProfileRequest("  Bob Dupont  ", "  Bob.Dupont@ExpenseFlow.MA  "));

        // Then : la normalisation est visible aussi dans la recherche d'unicite
        assertThat(reponse.email()).isEqualTo("bob.dupont@expenseflow.ma");
        assertThat(reponse.nom()).isEqualTo("Bob Dupont");
        verify(userRepository).findByEmail("bob.dupont@expenseflow.ma");
    }

    @Test
    @DisplayName("updateProfile lève 409 si l'e-mail appartient à un autre compte")
    void updateProfile_withDuplicateEmail_shouldThrow409() {
        // Given : un AUTRE compte porte déjà l'adresse
        User autre = utilisateur();
        autre.setId(2L);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(utilisateur()));
        given(userRepository.findByEmail("alice.martin@expenseflow.ma")).willReturn(Optional.of(autre));

        // When / Then
        assertThatThrownBy(() -> service.updateProfile(USER_ID,
                new UpdateProfileRequest("Alice Martin", "alice.martin@expenseflow.ma")))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("updateProfile accepte de conserver son propre e-mail")
    void updateProfile_withSameEmail_shouldSucceed() {
        // Given : findByEmail renvoie le MÊME compte — il doit s'exclure lui-même
        User user = utilisateur();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userRepository.findByEmail("alice.martin@expenseflow.ma")).willReturn(Optional.of(user));
        given(userRepository.save(any(User.class))).willReturn(user);

        // When
        UserResponse reponse = service.updateProfile(USER_ID,
                new UpdateProfileRequest("Alice M. Martin", "alice.martin@expenseflow.ma"));

        // Then : pas de conflit, le simple renommage passe
        assertThat(reponse.nom()).isEqualTo("Alice M. Martin");
        assertThat(reponse.email()).isEqualTo("alice.martin@expenseflow.ma");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("changePassword accepte un ancien mot de passe correct")
    void changePassword_withValidOldPassword_shouldSucceed() {
        // Given : le compte porte encore le mot de passe provisoire (V6)
        User user = utilisateur();
        user.setDoitChangerMdp(true);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(passwordEncoder.matches("Ancien123!", "hashAncien")).willReturn(true);
        given(passwordEncoder.encode("Nouveau456!")).willReturn("hashNouveau");

        // When
        service.changePassword(USER_ID, new ChangePasswordRequest(
                "Ancien123!", "Nouveau456!", "Nouveau456!"));

        // Then : le HACHAGE est stocké, jamais le mot de passe en clair
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getMotDePasse()).isEqualTo("hashNouveau");
        assertThat(userCaptor.getValue().getMotDePasse()).isNotEqualTo("Nouveau456!");
        // Le provisoire est remplacé : le drapeau retombe et libère la navigation.
        assertThat(userCaptor.getValue().isDoitChangerMdp()).isFalse();
    }

    @Test
    @DisplayName("changePassword lève 400 si l'ancien mot de passe est incorrect")
    void changePassword_withInvalidOldPassword_shouldThrow400() {
        // Given
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(utilisateur()));
        given(passwordEncoder.matches("Faux123!", "hashAncien")).willReturn(false);

        // When / Then
        assertThatThrownBy(() -> service.changePassword(USER_ID, new ChangePasswordRequest(
                "Faux123!", "Nouveau456!", "Nouveau456!")))
                .isInstanceOf(InvalidPasswordException.class)
                .hasMessageContaining("incorrect");

        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("changePassword lève 400 si la confirmation ne correspond pas")
    void changePassword_withMismatchedConfirmation_shouldThrow400() {
        // Given : l'ancien est bon, mais les deux saisies diffèrent
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(utilisateur()));
        given(passwordEncoder.matches("Ancien123!", "hashAncien")).willReturn(true);

        // When / Then
        assertThatThrownBy(() -> service.changePassword(USER_ID, new ChangePasswordRequest(
                "Ancien123!", "Nouveau456!", "Nouveau789!")))
                .isInstanceOf(PasswordMismatchException.class)
                .hasMessageContaining("confirmation");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("changePassword refuse un nouveau mot de passe équivalent à l'ancien")
    void changePassword_withSamePassword_shouldThrow400() {
        // Given : chaîne DIFFÉRENTE, mais BCrypt la reconnaît comme identique.
        // Une comparaison par égalité de chaînes laisserait passer ce cas et
        // produirait un changement d'apparence sans aucun effet.
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(utilisateur()));
        given(passwordEncoder.matches("Ancien123!", "hashAncien")).willReturn(true);
        given(passwordEncoder.matches("ancien123!", "hashAncien")).willReturn(true);

        // When / Then
        assertThatThrownBy(() -> service.changePassword(USER_ID, new ChangePasswordRequest(
                "Ancien123!", "ancien123!", "ancien123!")))
                .isInstanceOf(PasswordMismatchException.class)
                .hasMessageContaining("différent");

        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Utilisateur de référence : mot de passe deja hache en "hashAncien".
     *
     * @return une entité complete et valide
     */
    private User utilisateur() {
        User user = new User();
        user.setId(USER_ID);
        user.setNom("Alice Martin");
        user.setEmail("alice.martin@expenseflow.ma");
        user.setMotDePasse("hashAncien");
        user.setRole(UserRole.EMPLOYEE);
        return user;
    }
}