package com.expenseflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.CustomUserDetailsService;
import com.expenseflow.security.JwtService;

/**
 * Tests d'integration du profil de l'utilisateur connecte
 * {@code /api/users/**}.
 *
 * <p>
 * Chaque test cree <strong>ses propres comptes de test</strong> — jamais les
 * comptes de demo — via {@code createUser}, et s'appuie sur
 * {@code @Transactional} pour que ses donnees disparaissent apres coup. La
 * base reelle des tests est un conteneur jetable declare dans
 * {@link AbstractIntegrationTest}.
 * </p>
 *
 * <p>
 * <strong>Le sixieme test verifie la precondition</strong> du « pas de
 * deconnexion » cote frontend : un ancien mot de passe errone renvoie
 * 400 (pas 401), donc {@code client.js} — qui ne reagit qu'aux 401 — laisse
 * la session ouverte. Le comportement Axios lui-meme est hors perimetre
 * backend.
 * </p>
 */
@Transactional
@AutoConfigureMockMvc
@DisplayName("Profil utilisateur (integration)")
class UserControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    // ---------------------------------------------------------------------
    // 1. Lecture du profil (GET /api/users/me)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("GET /me avec un jeton valide renvoie le profil")
    void getMe_withValidToken_shouldReturnProfile() throws Exception {
        // Given
        User user = createUser("Test Profile", UserRole.EMPLOYEE);
        String token = bearer(user);

        // When / Then
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nom").value("Test Profile"))
                .andExpect(jsonPath("$.email").value(startsWith("test-profile-")));
    }

    @Test
    @DisplayName("GET /me sans jeton renvoie 401")
    void getMe_withoutToken_shouldReturn401() throws Exception {
        // When / Then
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------
    // 2. Modification du nom et de l'e-mail (PUT /api/users/me)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("PUT /me avec un corps valide met à jour nom et e-mail")
    void putMe_withValidPayload_shouldUpdateProfile() throws Exception {
        // Given
        User user = createUser("Old Name", UserRole.EMPLOYEE);
        String token = bearer(user);
        String newEmail = "new-" + System.nanoTime() + "@expenseflow.ma";
        String body = """
                {"nom": "New Name", "email": "%s"}
                """.formatted(newEmail);

        // When / Then : la réponse reflète la modification
        mockMvc.perform(put("/api/users/me")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nom").value("New Name"))
                .andExpect(jsonPath("$.email").value(newEmail));

        // Then : la base est vraiment modifiée
        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.getNom()).isEqualTo("New Name");
        assertThat(updated.getEmail()).isEqualTo(newEmail);
    }

    @Test
    @DisplayName("PUT /me avec un e-mail déjà pris renvoie 409")
    void putMe_withDuplicateEmail_shouldReturn409() throws Exception {
        // Given : un second compte possède déjà l'adresse convoitée
        User user1 = createUser("User One", UserRole.EMPLOYEE);
        User user2 = createUser("User Two", UserRole.EMPLOYEE);
        String token = bearer(user1);
        String body = """
                {"nom": "Changed", "email": "%s"}
                """.formatted(user2.getEmail());

        // When / Then
        mockMvc.perform(put("/api/users/me")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    @DisplayName("PUT /me/password avec l'ancien correct renvoie 204 et change le hash")
    void putMePassword_withValidOldPassword_shouldReturn204() throws Exception {
        // Given
        User user = createUser("Password User", UserRole.EMPLOYEE);
        String token = bearer(user);
        String body = """
                {"ancienMotDePasse": "MotDePasse1!",
                 "nouveauMotDePasse": "NewPassword456!",
                 "confirmationMotDePasse": "NewPassword456!"}
                """;

        // When / Then
        mockMvc.perform(put("/api/users/me/password")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        // Then : le nouveau hash valide, l'ancien ne l'est plus
        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("NewPassword456!", updated.getMotDePasse())).isTrue();
        assertThat(passwordEncoder.matches("MotDePasse1!", updated.getMotDePasse())).isFalse();
    }

    @Test
    @DisplayName("PUT /me/password avec l'ancien faux renvoie 400 — jamais 401 — et ne change rien")
    void putMePassword_withInvalidOldPassword_shouldReturn400() throws Exception {
        // Given
        User user = createUser("Password User 2", UserRole.EMPLOYEE);
        String token = bearer(user);
        String oldHash = user.getMotDePasse();
        String body = """
                {"ancienMotDePasse": "WrongPassword!",
                 "nouveauMotDePasse": "NewPassword456!",
                 "confirmationMotDePasse": "NewPassword456!"}
                """;

        // When / Then : 400 (pas 401), donc le client Axios ne déconnecte pas
        mockMvc.perform(put("/api/users/me/password")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));

        // Then : rien n'a été écrit en base
        User unchanged = userRepository.findById(user.getId()).orElseThrow();
        assertThat(unchanged.getMotDePasse()).isEqualTo(oldHash);
    }

    // =====================================================================
    // Helpers — convention copiée sur les autres tests d'intégration
    // =====================================================================

    private User createUser(String nom, UserRole role) {
        return userRepository.save(User.builder()
                .nom(nom)
                .email(nom.toLowerCase().replace(' ', '-') + "-" + System.nanoTime() + "@expenseflow.ma")
                .motDePasse(passwordEncoder.encode("MotDePasse1!"))
                .role(role)
                .build());
    }

    private String bearer(User utilisateur) {
        return "Bearer " + jwtService.generateToken(
                customUserDetailsService.loadUserByUsername(utilisateur.getEmail()));
    }
}