package com.expenseflow.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.entity.User;
import com.expenseflow.repository.UserRepository;

/**
 * Chargement des utilisateurs depuis la base PostgreSQL pour Spring Security.
 *
 * <p>
 * L'identifiant de connexion est l'adresse e-mail (colonne unique
 * {@code users.email}). En cas d'absence, une {@link UsernameNotFoundException}
 * est levée : Spring Security la convertit en échec d'authentification sans
 * divulguer d'information sur l'existence du compte.
 * </p>
 *
 * <p>
 * La lecture est transactionnelle et en lecture seule : cela stabilise le
 * chargement paresseux des associations (ex. {@code manager}) alors que
 * {@code spring.jpa.open-in-view=false} dans cette application.
 * </p>
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * @param userRepository dépôt des utilisateurs
     */
    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Charge un utilisateur par son adresse e-mail.
     *
     * @param email adresse e-mail saisie lors de la connexion
     * @return l'utilisateur enveloppé dans un {@link CustomUserDetails}
     * @throws UsernameNotFoundException si aucune adresse ne correspond
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Aucun utilisateur trouve pour l'e-mail : " + email));
        return new CustomUserDetails(user);
    }
}
