package com.expenseflow.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Émission et vérification des jetons JWT (access token uniquement).
 *
 * <p>
 * Algorithme imposé : <strong>HS256</strong> (HMAC-SHA256, clé symétrique). La
 * clé est dérivée du secret applicatif {@code jwt.secret} via
 * {@link Keys#hmacShaKeyFor(byte[])}.
 * </p>
 *
 * <p>
 * Le secret est validé au démarrage : il doit être présent et comporter au moins
 * 32 caractères, sinon une {@link IllegalStateException} interrompt le
 * démarrage. Cette validation évite deux pièges : démarrer avec un secret vide
 * (token signé avec une clé nulle), et échouer plus tard sur un
 * {@code WeakKeyException} opaque de jjwt.
 * </p>
 */
@Service
public class JwtService {

    /** Longueur minimale du secret : 32 octets = 256 bits, taille requise par HS256. */
    private static final int MIN_SECRET_LENGTH = 32;

    private final SecretKey signingKey;

    private final long expiration;

    /**
     * @param secret     secret HMAC (propriété {@code jwt.secret}, alimentée par
     *                   la variable d'environnement {@code JWT_SECRET} ou par
     *                   {@code application-local.properties})
     * @param expiration durée de vie du jeton en millisecondes (1 heure par défaut)
     */
    public JwtService(@Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration:3600000}") long expiration) {
        if (secret == null || secret.isBlank() || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("Configuration JWT invalide : 'jwt.secret' doit contenir au moins "
                    + MIN_SECRET_LENGTH + " caracteres (algorithme HS256 = 256 bits). Longueur recue : "
                    + (secret == null ? "propriete absente" : secret.length() + " caracteres")
                    + ". Definissez la variable d'environnement JWT_SECRET ou la propriete jwt.secret"
                    + " dans application-local.properties (profil 'local').");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    /**
     * Génère un access token pour l'utilisateur authentifié.
     *
     * @param userDetails utilisateur dont l'e-mail devient le {@code subject}
     * @return le jeton JWT compact signé en HS256
     */
    public String generateToken(UserDetails userDetails) {
        Date issuedAt = new Date();
        Date expirationDate = new Date(issuedAt.getTime() + expiration);
        return Jwts.builder()
                .subject(userDetails.getUsername())
                .claim("role", extractRole(userDetails))
                .issuedAt(issuedAt)
                .expiration(expirationDate)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Extrait l'adresse e-mail ({@code subject}) portée par le jeton.
     *
     * @param token jeton JWT compact
     * @return l'e-mail de l'utilisateur
     * @throws JwtException si le jeton est invalide, expiré ou mal signé
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Vérifie que le jeton appartient bien à l'utilisateur fourni et qu'il n'est
     * pas expiré.
     *
     * @param token       jeton JWT compact
     * @param userDetails utilisateur authentifié candidat
     * @return {@code true} si le jeton est valide pour cet utilisateur
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            String username = extractUsername(token);
            return username != null
                    && username.equals(userDetails.getUsername())
                    && !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * @param userDetails utilisateur authentifié
     * @return la première autorité (ex. {@code ROLE_EMPLOYEE}) ou une chaîne vide
     */
    private String extractRole(UserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse("");
    }

    /**
     * Applique une fonction de lecture sur la charge utile du jeton.
     */
    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        return claimsResolver.apply(extractAllClaims(token));
    }

    /**
     * Vérifie la signature puis retourne l'intégralité des revendications.
     *
     * @param token jeton JWT compact
     * @return les claims du jeton
     * @throws JwtException si la signature ou la structure est invalide
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * @param token jeton JWT compact
     * @return {@code true} si la date d'expiration est dépassée
     */
    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }
}
