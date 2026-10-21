package com.expenseflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.dto.BudgetLimitRequest;
import com.expenseflow.dto.CategoryRequest;
import com.expenseflow.dto.ExpenseLineRequest;
import com.expenseflow.dto.ExpenseRequestCreate;
import com.expenseflow.entity.Category;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.CategoryRepository;
import com.expenseflow.repository.UserRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
// Jackson 3 : depuis Spring Boot 4, le package est tools.jackson.databind et non
// com.fasterxml.jackson.databind. La serialisation des DTOs de test doit donc
// utiliser le meme moteur que celui employe par les controleurs, sous peine de
// diverger des regles de nommage de proprietes.
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests d'integration de la couche securite : authentification JWT, controle
 * d'acces par role et controle d'acces fin sur le proprietaire.
 *
 * <p>
 * Ces tests traversent la <em>chaine complete</em> â€” filtre
 * {@code JwtAuthenticationFilter}, chaine de filtres, annotations
 * {@code @PreAuthorize}, controleurs, services et base PostgreSQL reelle â€” via
 * {@link MockMvc}. Aucun composant n'est simule : c'est ce qui les distingue des
 * tests unitaires de la Section 1.8, ou le jeton lui-meme etait une chaine de
 * caracteres fabriquee par un mock.
 * </p>
 *
 * <p>
 * <strong>Point d'attention sur l'ordre des controles</strong> : une annotation
 * {@code @PreAuthorize} est evaluee par le proxy AOP au moment de l'appel de la
 * methode, donc <em>apres</em> la resolution des arguments par le
 * {@code DispatcherServlet}, et donc apres {@code @Valid}. Un corps de requete
 * invalide adresse a un EMPLOYEE renverrait ainsi 400 et non 403. Tous les tests
 * de refus de role ci-dessous envoient donc un corps <em>valide</em> : le 403
 * observe provient alors bien du controle de role, et non d'un effet de bord de
 * la validation.
 * </p>
 *
 * <p>
 * L'annotation {@code @Transactional} assure le rollback de chaque methode de
 * test : le conteneur PostgreSQL etant partage avec les autres classes
 * d'integration, des donnees residuelles rendraient les assertions de comptage
 * dependantes de l'ordre d'execution.
 * </p>
 */
@Transactional
@AutoConfigureMockMvc
@DisplayName("Securite (integration)")
class SecurityIntegrationTest extends AbstractIntegrationTest {

    /**
     * Secret employe par {@link JwtService} dans le profil {@code test}.
     * Lu depuis la configuration plutot que recopie en dur, afin que ce test
     * signe reellement avec la meme cle que l'application.
     */
    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * Autre secret, de longueur suffisante pour HS256, servant a fabriquer un
     * jeton dont la signature ne correspond pas. Sert uniquement au test
     * {@code wrongSignature_shouldReturn401}.
     */
    private static final String AUTRE_SECRET = "autre-secret-de-test-64-caracteres-minimum-pour-hs256-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    // ---------------------------------------------------------------------
    // A) Authentification JWT
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Sans en-tete Authorization, l'API renvoie 401 Unauthorized")
    void noToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/expense-requests"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    @DisplayName("Un jeton expire est rejete en 401, meme si l'utilisateur existe")
    void expiredToken_shouldReturn401() throws Exception {
        // Given : un utilisateur bien reel, et un jeton a son nom dont seule
        // l'expiration est fautive.
        User employe = createUser("Employe Jeton Expire", UserRole.EMPLOYEE);
        String jetonExpire = genererJetonExpire(employe.getEmail());

        // When / Then
        mockMvc.perform(get("/api/expense-requests").header("Authorization", bearer(jetonExpire)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Un jeton malforme est rejete en 401 sans faire tomber l'API")
    void malformedToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/expense-requests").header("Authorization", bearer("not.a.jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Un jeton signe avec une autre cle est rejete en 401")
    void wrongSignature_shouldReturn401() throws Exception {
        // Given : un utilisateur reel, mais un jeton signe avec une cle inconnue
        // de l'application. Sans ce test, une defaillance de la verification de
        // signature passerait inapercue.
        User employe = createUser("Employe Mauvaise Cle", UserRole.EMPLOYEE);
        String jetonFalsifie = genererJetonSigneAvecAutreCle(employe.getEmail());

        // When / Then
        mockMvc.perform(get("/api/expense-requests").header("Authorization", bearer(jetonFalsifie)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Un jeton valide donne acces a l'endpoint protege /api/auth/me")
    void validToken_shouldAccessProtectedEndpoint() throws Exception {
        // Given : un EMPLOYEE enregistre et son jeton
        User employe = createUser("Employe Valide", UserRole.EMPLOYEE);

        // When / Then
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(tokenFor(employe))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(employe.getId()))
                .andExpect(jsonPath("$.email").value(employe.getEmail()))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"));
    }

    // ---------------------------------------------------------------------
    // B) Controle d'acces par role (@PreAuthorize)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Un EMPLOYEE ne peut pas creer une categorie : 403 Forbidden")
    void employeeAccessingFinanceEndpoint_shouldReturn403() throws Exception {
        // Given : un corps valide, pour que le 403 ne puisse venir que du role.
        User employe = createUser("Employe Refus Categorie", UserRole.EMPLOYEE);
        String corps = json(new CategoryRequest("Transport " + unique()));

        // When / Then
        mockMvc.perform(post("/api/categories")
                        .header("Authorization", bearer(tokenFor(employe)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    @DisplayName("Un MANAGER ne peut pas creer de plafond budgetaire : 403 Forbidden")
    void managerAccessingFinanceEndpoint_shouldReturn403() throws Exception {
        // Given : une categorie reelle et un plafond valide. Le MANAGER est
        // volontairement plus puissant que l'EMPLOYEE, mais la finance reste
        // la seule habilitee a ecrire dans la grille des plafonds.
        User manager = createUser("Manager Refus Plafond", UserRole.MANAGER);
        Category categorie = createCategory("Hebergement " + unique());
        String corps = json(new BudgetLimitRequest(categorie.getId(), UserRole.EMPLOYEE, new BigDecimal("500.00")));

        // When / Then
        mockMvc.perform(post("/api/budget-limits")
                        .header("Authorization", bearer(tokenFor(manager)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    @DisplayName("Un FINANCE_ADMIN peut creer une categorie : 201 Created")
    void financeAdminAccessingFinanceEndpoint_shouldReturn201() throws Exception {
        // Given
        User finance = createUser("Admin Finance Categorie", UserRole.FINANCE_ADMIN);
        String nom = "Categories " + unique();

        // When / Then
        mockMvc.perform(post("/api/categories")
                        .header("Authorization", bearer(tokenFor(finance)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CategoryRequest(nom))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nom").value(nom));
    }

    // ---------------------------------------------------------------------
    // C) Controle d'acces fin (proprietaire de la note)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Un EMPLOYEE ne peut pas consulter la note d'un autre EMPLOYEE : 403")
    void employeeAccessingOtherEmployeeRequest_shouldReturn403() throws Exception {
        // Given : deux employes, la note appartient au premier.
        User proprietaire = createUser("Employe Proprietaire", UserRole.EMPLOYEE);
        User intrus = createUser("Employe Intrus", UserRole.EMPLOYEE);
        Long noteId = creerNotePour(proprietaire, "Hote " + unique());

        // When : l'intrus tente de lire la note de l'autre
        MvcResult resultat = mockMvc.perform(get("/api/expense-requests/" + noteId)
                        .header("Authorization", bearer(tokenFor(intrus))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andReturn();

        // Then : la reponse ne laisse fuiter aucune donnee de la note
        assertThat(resultat.getResponse().getContentAsString()).doesNotContain("montant");
    }

    @Test
    @DisplayName("Un EMPLOYEE peut consulter sa propre note : 200 OK")
    void employeeAccessingOwnRequest_shouldReturn200() throws Exception {
        // Given : une note dont l'appelant est le proprietaire
        User employe = createUser("Employe Proprietaire Ok", UserRole.EMPLOYEE);
        Long noteId = creerNotePour(employe, "Train " + unique());

        // When / Then
        mockMvc.perform(get("/api/expense-requests/" + noteId)
                        .header("Authorization", bearer(tokenFor(employe))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(noteId))
                .andExpect(jsonPath("$.lignes").isArray())
                .andExpect(jsonPath("$.lignes[0].description").exists());
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Enregistre un utilisateur de test, avec un mot de passe reellement hache
     * en BCrypt par le bean de production.
     *
     * <p>
     * L'adresse e-mail est suffixe par {@link System#nanoTime()} : la colonne
     * {@code users.email} porte une contrainte d'unicite, et deux methodes de
     * test distinctes ne doivent pas pouvoir se chevaucher.
     * </p>
     *
     * @param nom  nom affiche
     * @param role role applicatif
     * @return l'utilisateur enregistre, identifiant renseigne
     */
    private User createUser(String nom, UserRole role) {
        return userRepository.save(User.builder()
                .nom(nom)
                .email(nom.toLowerCase().replace(' ', '-') + "-" + unique() + "@expenseflow.ma")
                .motDePasse(passwordEncoder.encode("MotDePasse1!"))
                .role(role)
                .build());
    }

    /**
     * Enregistre une categorie de test.
     *
     * @param nom libelle unique
     * @return la categorie enregistree
     */
    private Category createCategory(String nom) {
        return categoryRepository.save(Category.builder().nom(nom).build());
    }

    /**
     * Fait creer une note de frais par {@code proprietaire} en passant par
     * l'API elle-meme, puis en extrait l'identifiant.
     *
     * <p>
     * La creation passe volontairement par HTTP et non par le repository : la
     * note est alors produite par le meme chemin qu'en production, l'auteur
     * etant deduit du jeton et non d'un champ du corps de la requete.
     * </p>
     *
     * @param proprietaire EMPLOYEE auteur de la note
     * @param description libelle de la ligne de depense
     * @return identifiant de la note creee
     */
    private Long creerNotePour(User proprietaire, String description) throws Exception {
        Category categorie = createCategory("Restaurant " + unique());
        ExpenseRequestCreate corps = new ExpenseRequestCreate(List.of(
                new ExpenseLineRequest(categorie.getId(), new BigDecimal("120.00"),
                        LocalDate.of(2026, 9, 20), description)));

        MvcResult resultat = mockMvc.perform(post("/api/expense-requests")
                        .header("Authorization", bearer(tokenFor(proprietaire)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(corps)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode arbre = objectMapper.readTree(resultat.getResponse().getContentAsString());
        return arbre.get("id").asLong();
    }

    /**
     * Genere un jeton valide pour un utilisateur, via le {@link JwtService} de
     * l'application : le test utilise donc exactement le meme code de signature
     * que la production, et non une reconstitution approximative.
     *
     * @param user utilisateur destinataire du jeton
     * @return jeton compact, sans le prefixe {@code Bearer}
     */
    private String tokenFor(User user) {
        return jwtService.generateToken(new CustomUserDetails(user));
    }

    /**
     * Fabrique un jeton signe avec la vraie cle, mais dont l'expiration est
     * deja depassee.
     *
     * @param email adresse portee en {@code subject}
     * @return jeton compact expire
     */
    private String genererJetonExpire(String email) {
        Instant maintenant = Instant.now();
        return Jwts.builder()
                .subject(email)
                .claim("role", "ROLE_EMPLOYEE")
                .issuedAt(Date.from(maintenant.minusSeconds(7200)))
                .expiration(Date.from(maintenant.minusSeconds(3600)))
                .signWith(cle(jwtSecret), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Fabrique un jeton structurellement valide et non expire, mais signe avec
     * une cle qui n'est pas celle de l'application.
     *
     * @param email adresse portee en {@code subject}
     * @return jeton compact a signature invalide
     */
    private String genererJetonSigneAvecAutreCle(String email) {
        return Jwts.builder()
                .subject(email)
                .claim("role", "ROLE_EMPLOYEE")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(cle(AUTRE_SECRET), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Derive une cle HMAC a partir d'un secret, comme le fait {@link JwtService}.
     *
     * @param secret secret en clair
     * @return cle de signature HS256
     */
    private SecretKey cle(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param jeton jeton compact
     * @return valeur prete pour l'en-tete {@code Authorization}
     */
    private String bearer(String jeton) {
        return "Bearer " + jeton;
    }

    /**
     * Serialise un DTO en corps de requete JSON.
     *
     * <p>
     * Aucune exception controlee n'est declaree : en Jackson 3,
     * {@code writeValueAsString} leve une {@code JacksonException} non
     * controlee.
     * </p>
     *
     * @param valeur DTO a serialiser
     * @return corps JSON
     */
    private String json(Object valeur) {
        return objectMapper.writeValueAsString(valeur);
    }

    /**
     * @return suffixe d'unicite pour les contraintes UNIQUE de la base
     */
    private long unique() {
        return System.nanoTime();
    }
}
