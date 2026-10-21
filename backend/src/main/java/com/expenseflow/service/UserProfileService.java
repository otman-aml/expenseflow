package com.expenseflow.service;

import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.dto.ChangePasswordRequest;
import com.expenseflow.dto.UpdateProfileRequest;
import com.expenseflow.dto.UserResponse;
import com.expenseflow.entity.User;
import com.expenseflow.exception.EmailAlreadyExistsException;
import com.expenseflow.exception.InvalidPasswordException;
import com.expenseflow.exception.PasswordMismatchException;
import com.expenseflow.exception.ResourceNotFoundException;
import com.expenseflow.repository.UserRepository;

/**
 * Gestion du profil de l'utilisateur connecté.
 *
 * <p>
 * <strong>Le rôle n'est jamais modifiable ici.</strong> Ni le service ni les
 * DTO ne l'exposent : le rôle décide de ce qu'un compte peut faire, il ne se
 * gère pas depuis le compte lui-même.
 * </p>
 *
 * <p>
 * <strong>Un utilisateur absent est un 404, pas un 401.</strong> Son jeton
 * est valide et la requête porte bien sur un compte : l'identifiant ne
 * correspond simplement à plus rien. Le contrôle d'accès a déjà eu lieu.
 * </p>
 */
@Service
public class UserProfileService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * @param userRepository   accès aux comptes
     * @param passwordEncoder encodeur BCrypt, même instance qu'à l'inscription
     */
    public UserProfileService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Profil de l'utilisateur connecté.
     *
     * @param userId identifiant tiré du jeton
     * @return nom, e-mail et rôle
     * @throws ResourceNotFoundException si le compte n'existe plus
     */
    @Transactional(readOnly = true)
    public UserResponse getProfile(Long userId) {
        return UserResponse.from(requireUser(userId));
    }

    /**
     * Modifie le nom et l'e-mail.
     *
     * <p>
     * <strong>L'e-mail est normalisé avant tout contrôle</strong> — espaces
     * retirés, casse abaissée. Sans cela, «  Alice@x.ma » et
     * « alice@x.ma » passeraient tous deux l'unicité puis resteraient en
     * base comme deux comptes distincts, la contrainte UNIQUE ne portant que
     * sur la chaîne exacte.
     * </p>
     *
     * @param userId  identifiant tiré du jeton
     * @param request nouveau nom et nouvel e-mail
     * @return le profil enregistré
     * @throws EmailAlreadyExistsException si l'e-mail appartient à un autre compte
     * @throws ResourceNotFoundException si le compte n'existe plus
     */
    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = requireUser(userId);

        String email = normaliser(request.email());
        requireEmailLibre(email, userId);

        user.setNom(request.nom().trim());
        user.setEmail(email);

        return UserResponse.from(userRepository.save(user));
    }

    /**
     * Change le mot de passe de l'utilisateur connecté.
     *
     * <p>
     * <strong>L'ordre des vérifications est significatif.</strong> L'ancien
     * mot de passe est contrôlé en premier : sans cela, un jeton volé
     * suffirait à prendre définitivement le compte. Les deux autres
     * contrôles ne servent que si l'utilisateur est légitime.
     * </p>
     *
     * <p>
     * <strong>La comparaison nouveau / ancien passe par le hachage</strong>,
     * et non par une égalité de chaînes : « Password123! » et
     * « password123 » ne sont pas le même mot de passe pour BCrypt, et le
     * laisser passer produirait un changement d'apparence sans aucun effet.
     * </p>
     *
     * @param userId  identifiant tiré du jeton
     * @param request ancien, nouveau et confirmation
     * @throws InvalidPasswordException si l'ancien mot de passe est faux
     * @throws PasswordMismatchException si la confirmation diffère, ou si le
     *                                 nouveau égale l'ancien
     * @throws ResourceNotFoundException si le compte n'existe plus
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = requireUser(userId);

        if (!passwordEncoder.matches(request.ancienMotDePasse(), user.getMotDePasse())) {
            throw new InvalidPasswordException("L'ancien mot de passe est incorrect");
        }

        if (!request.nouveauMotDePasse().equals(request.confirmationMotDePasse())) {
            throw new PasswordMismatchException(
                    "Le nouveau mot de passe et la confirmation ne correspondent pas");
        }

        if (passwordEncoder.matches(request.nouveauMotDePasse(), user.getMotDePasse())) {
            throw new PasswordMismatchException(
                    "Le nouveau mot de passe doit être différent de l'ancien");
        }

        user.setMotDePasse(passwordEncoder.encode(request.nouveauMotDePasse()));
        // Le mot de passe provisoire est remplace : le drapeau retombe a false
        // et libere la navigation (changement force a la premiere connexion).
        user.setDoitChangerMdp(false);
        userRepository.save(user);
    }

    /**
     * Charge un compte ou échoue.
     *
     * @param userId identifiant recherché
     * @return l'entité trouvée
     * @throws ResourceNotFoundException si aucun compte ne porte cet identifiant
     */
    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Utilisateur introuvable : id=" + userId));
    }

    /**
     * Vérifie qu'un e-mail n'est pas déjà pris par un autre compte.
     *
     * @param email         e-mail déjà normalisé
     * @param excludeUserId compte à ignorer : l'utilisateur lui-même, qui peut
     *                     ainsi réenregistrer son profil sans changer d'adresse
     * @throws EmailAlreadyExistsException si un autre compte porte cet e-mail
     */
    private void requireEmailLibre(String email, Long excludeUserId) {
        userRepository.findByEmail(email)
                .filter(existant -> !existant.getId().equals(excludeUserId))
                .ifPresent(existant -> {
                    throw new EmailAlreadyExistsException(email);
                });
    }

    /**
     * Normalise un e-mail : espaces retirés, casse abaissée.
     *
     * @param email e-mail saisi
     * @return la forme canonique stockée en base
     */
    private String normaliser(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}