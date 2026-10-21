package com.expenseflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.expenseflow.dto.AuthResponse;
import com.expenseflow.dto.LoginRequest;
import com.expenseflow.dto.RegisterRequest;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.exception.EmailAlreadyExistsException;
import com.expenseflow.exception.ResourceNotFoundException;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.CustomUserDetails;
import com.expenseflow.security.JwtService;

/**
 * Tests unitaires de {@link AuthService}.
 *
 * <p>
 * Le Hachage et l'emetion du jeton sont verifies par interaction avec les
 * collaborateurs simules ({@code verify}) plutot que par inspection du seul
 * resultat : l'interet est de prouver que le mot de passe en clair ne part
 * jamais vers la base.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthService")
class AuthServiceTest {

    private static final String HASH = "$2a$10$3eqYHaI52xan46seTvrU6O.xhPHlySgiZsIkX3izupFO9ALFnj10G";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private Authentication authentication;

    /**
     * Service instancie explicitement plutot que par {@code @InjectMocks}.
     *
     * <p>
     * Le constructeur de {@link AuthService} comporte un {@code long expiration}
     * injecte par {@code @Value} : Mockito ne sait pas le resoudre et bascule sur
     * une tentative d'instanciation sans argument, qui n'existe pas. Construire
     * le service a la main evite cette ambiguite et rend explicite la duree de
     * vie des jetons attendue par les tests.
     * </p>
     */
    private AuthService service;

    @BeforeEach
    void buildService() {
        service = new AuthService(userRepository, passwordEncoder, jwtService, authenticationManager, 3_600_000L);
    }

    @Captor
    private ArgumentCaptor<User> userCaptor;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static User user(Long id, UserRole role) {
        return User.builder()
                .id(id)
                .nom("Demo")
                .email("demo@expenseflow.ma")
                .motDePasse(HASH)
                .role(role)
                .build();
    }

    /** Simule le {@code save} d'un utilisateur, en lui attribuant un identifiant. */
    private void givenSaveAssignsId() {
        given(userRepository.save(any(User.class))).willAnswer(i -> {
            User entity = i.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(12L);
            }
            return entity;
        });
    }

    // =====================================================================
    // A) register
    // =====================================================================

    @Test
    @DisplayName("register() cree l'utilisateur et renvoie un jeton")
    void register_withUniqueEmail_shouldSucceed() {
        // Given
        given(userRepository.findByEmail("nouveau@expenseflow.ma")).willReturn(Optional.empty());
        given(passwordEncoder.encode("MotDePasse123")).willReturn(HASH);
        given(jwtService.generateToken(any(CustomUserDetails.class))).willReturn("jeton.signature");
        givenSaveAssignsId();

        // When
        AuthResponse result = service.register(
                new RegisterRequest("Nouveau", "nouveau@expenseflow.ma", "MotDePasse123"));

        // Then
        assertThat(result.token()).isEqualTo("jeton.signature");
        assertThat(result.user().email()).isEqualTo("nouveau@expenseflow.ma");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("register() hache le mot de passe et ne persiste JAMAIS la valeur en clair")
    void register_shouldHashPasswordBeforeSaving() {
        // Given
        given(userRepository.findByEmail(any())).willReturn(Optional.empty());
        given(passwordEncoder.encode("MotDePasse123")).willReturn(HASH);
        given(jwtService.generateToken(any(CustomUserDetails.class))).willReturn("jeton.signature");
        givenSaveAssignsId();

        // When
        service.register(new RegisterRequest("Nouveau", "nouveau@expenseflow.ma", "MotDePasse123"));

        // Then
        verify(passwordEncoder).encode("MotDePasse123");
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getMotDePasse()).isEqualTo(HASH);
        assertThat(userCaptor.getValue().getMotDePasse()).isNotEqualTo("MotDePasse123");
    }

    @Test
    @DisplayName("register() impose le rôle EMPLOYEE, non lisible depuis le payload")
    void register_shouldForceEmployeeRole() {
        // Given
        given(userRepository.findByEmail(any())).willReturn(Optional.empty());
        given(passwordEncoder.encode(any())).willReturn(HASH);
        given(jwtService.generateToken(any(CustomUserDetails.class))).willReturn("jeton.signature");
        givenSaveAssignsId();

        // When
        AuthResponse result = service.register(
                new RegisterRequest("Nouveau", "nouveau@expenseflow.ma", "MotDePasse123"));

        // Then
        assertThat(result.user().role()).isEqualTo(UserRole.EMPLOYEE);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo(UserRole.EMPLOYEE);
    }

    @Test
    @DisplayName("register() refuse un e-mail deja utilise")
    void register_withDuplicateEmail_shouldThrowEmailAlreadyExists() {
        // Given
        given(userRepository.findByEmail("existe@expenseflow.ma"))
                .willReturn(Optional.of(user(1L, UserRole.EMPLOYEE)));

        // When / Then
        assertThatThrownBy(() -> service.register(
                new RegisterRequest("Autre", "existe@expenseflow.ma", "MotDePasse123")))
                .isInstanceOf(EmailAlreadyExistsException.class)
                .hasMessageContaining("existe@expenseflow.ma");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("register() normalise l'e-mail en minuscules et sans espaces")
    void register_shouldNormalizeEmail() {
        // Given
        given(userRepository.findByEmail("test@expenseflow.ma")).willReturn(Optional.empty());
        given(passwordEncoder.encode(any())).willReturn(HASH);
        given(jwtService.generateToken(any(CustomUserDetails.class))).willReturn("jeton.signature");
        givenSaveAssignsId();

        // When
        AuthResponse result = service.register(
                new RegisterRequest("Test", "  TEST@ExpenseFlow.MA  ", "MotDePasse123"));

        // Then
        assertThat(result.user().email()).isEqualTo("test@expenseflow.ma");
        verify(userRepository).findByEmail("test@expenseflow.ma");
    }

    // =====================================================================
    // B) login
    // =====================================================================

    @Test
    @DisplayName("login() emet un jeton pour des identifiants valides")
    void login_withValidCredentials_shouldReturnToken() {
        // Given : le gestionnaire d'authentification renvoie un principal reel
        CustomUserDetails principal = new CustomUserDetails(user(7L, UserRole.MANAGER));
        given(authenticationManager.authenticate(any())).willReturn(authentication);
        given(authentication.getPrincipal()).willReturn(principal);
        given(jwtService.generateToken(any(CustomUserDetails.class))).willReturn("jeton.valide");

        // When
        AuthResponse result = service.login(
                new LoginRequest("manager@expenseflow.ma", "MotDePasse123"));

        // Then
        assertThat(result.token()).isEqualTo("jeton.valide");
        assertThat(result.user().role()).isEqualTo(UserRole.MANAGER);
        verify(jwtService).generateToken(any(CustomUserDetails.class));
    }

    @Test
    @DisplayName("login() renvoie le schéma Bearer et la durée de validité configurée")
    void login_shouldReturnBearerTypeAndExpiration() {
        // Given
        given(authenticationManager.authenticate(any()))
                .willReturn(authentication);
        given(authentication.getPrincipal()).willReturn(new CustomUserDetails(user(7L, UserRole.MANAGER)));
        given(jwtService.generateToken(any(CustomUserDetails.class))).willReturn("jeton.valide");

        // When
        AuthResponse result = service.login(new LoginRequest("manager@expenseflow.ma", "MotDePasse123"));

        // Then
        assertThat(result.type()).isEqualTo("Bearer");
        assertThat(result.expiresIn()).isEqualTo(3_600_000L);
    }

    @Test
    @DisplayName("login() propage l'echec d'authentification de Spring Security")
    void login_withInvalidCredentials_shouldThrowBadCredentials() {
        // Given
        given(authenticationManager.authenticate(any()))
                .willThrow(new BadCredentialsException("Bad credentials"));

        // When / Then : aucune verification de mot de passe n'est faite par le service
        assertThatThrownBy(() -> service.login(
                new LoginRequest("manager@expenseflow.ma", "MauvaisMotDePasse")))
                .isInstanceOf(BadCredentialsException.class);

        verify(jwtService, never()).generateToken(any());
    }

    @Test
    @DisplayName("login() peuple le SecurityContext avec l'authentification obtenue")
    void login_shouldSetSecurityContext() {
        // Given
        given(authenticationManager.authenticate(any())).willReturn(authentication);
        given(authentication.getPrincipal()).willReturn(new CustomUserDetails(user(7L, UserRole.MANAGER)));
        given(jwtService.generateToken(any(CustomUserDetails.class))).willReturn("jeton.valide");

        // When
        service.login(new LoginRequest("manager@expenseflow.ma", "MotDePasse123"));

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
    }

    // =====================================================================
    // C) me
    // =====================================================================

    @Test
    @DisplayName("me() renvoie le profil de l'utilisateur authentifié")
    void me_withExistingUser_shouldReturnUserResponse() {
        // Given
        given(userRepository.findById(7L)).willReturn(Optional.of(user(7L, UserRole.MANAGER)));

        // When
        var result = service.me(new CustomUserDetails(user(7L, UserRole.MANAGER)));

        // Then
        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.role()).isEqualTo(UserRole.MANAGER);
    }

    @Test
    @DisplayName("me() renvoie 404 si l'utilisateur n'existe plus en base")
    void me_withUnknownUser_shouldThrowResourceNotFound() {
        // Given : le jeton est valide mais l'utilisateur a ete supprime
        given(userRepository.findById(99L)).willReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.me(new CustomUserDetails(user(99L, UserRole.EMPLOYEE))))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}