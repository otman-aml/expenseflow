package com.expenseflow.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.dto.BudgetLimitRequest;
import com.expenseflow.dto.BudgetLimitResponse;
import com.expenseflow.entity.BudgetLimit;
import com.expenseflow.entity.Category;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.exception.DuplicateResourceException;
import com.expenseflow.exception.ResourceNotFoundException;
import com.expenseflow.repository.BudgetLimitRepository;
import com.expenseflow.repository.CategoryRepository;

/**
 * Logique métier des plafonds budgétaires.
 *
 * <p>
 * Un plafond est identifié par le couple (catégorie, rôle) : il matérialise le
 * montant maximal qu'un utilisateur d'un rôle donné peut déclarer sur une
 * catégorie. La contrainte d'unicité {@code uk_budget_limits_categorie_role}
 * découle directement de ce modèle.
 * </p>
 */
@Service
public class BudgetLimitService {

    private static final Logger log = LoggerFactory.getLogger(BudgetLimitService.class);

    private final BudgetLimitRepository budgetLimitRepository;

    private final CategoryRepository categoryRepository;

    /**
     * @param budgetLimitRepository dépôt des plafonds
     * @param categoryRepository    dépôt des catégories (résolution du libellé)
     */
    public BudgetLimitService(BudgetLimitRepository budgetLimitRepository,
            CategoryRepository categoryRepository) {
        this.budgetLimitRepository = budgetLimitRepository;
        this.categoryRepository = categoryRepository;
    }

    /**
     * Liste tous les plafonds, tous rôles confondus.
     *
     * <p>
     * Réservé au rôle FINANCE_ADMIN : c'est la seule vue qui expose la grille
     * complète, y compris les plafonds des autres rôles.
     * </p>
     *
     * @return tous les plafonds
     */
    @Transactional(readOnly = true)
    public List<BudgetLimitResponse> findAll() {
        return budgetLimitRepository.findAllWithCategorie().stream()
                .map(BudgetLimitResponse::from)
                .toList();
    }

    /**
     * Liste les plafonds correspondant au rôle fourni.
     *
     * <p>
     * C'est la vue destinée à {@code GET /api/budget-limits/me} : l'appelant
     * n'obtient que les plafonds de son propre rôle, ce qui évite de lui
     * révéler les montants accordés aux autres rôles.
     * </p>
     *
     * @param role rôle de l'appelant
     * @return les plafonds de ce rôle, liste vide si aucun n'est configuré
     */
    @Transactional(readOnly = true)
    public List<BudgetLimitResponse> findForCurrentUserRole(UserRole role) {
        return budgetLimitRepository.findByRoleWithCategorie(role).stream()
                .map(BudgetLimitResponse::from)
                .toList();
    }

    /**
     * Crée un plafond pour un couple (catégorie, rôle).
     *
     * @param request catégorie, rôle et montant, validés par Jakarta Validation
     * @return le plafond créé
     * @throws ResourceNotFoundException  si la catégorie n'existe pas
     * @throws DuplicateResourceException si un plafond existe déjà pour ce couple
     */
    @Transactional
    public BudgetLimitResponse create(BudgetLimitRequest request) {
        Category categorie = requireCategory(request.categorieId());
        requireNoExistingLimit(request.categorieId(), request.role(), null);

        BudgetLimit saved = budgetLimitRepository.save(BudgetLimit.builder()
                .categorie(categorie)
                .role(request.role())
                .plafond(request.plafond())
                .build());

        log.info("Plafond cree : id={} categorie={} role={} montant={}",
                saved.getId(), categorie.getId(), saved.getRole(), saved.getPlafond());

        return BudgetLimitResponse.from(saved);
    }

    /**
     * Met à jour un plafond existant.
     *
     * @param id      identifiant du plafond à modifier
     * @param request nouvelles valeurs
     * @return le plafond mis à jour
     * @throws ResourceNotFoundException  si le plafond ou la catégorie n'existe pas
     * @throws DuplicateResourceException si le couple (catégorie, rôle) est déjà occupé par un autre plafond
     */
    @Transactional
    public BudgetLimitResponse update(Long id, BudgetLimitRequest request) {
        BudgetLimit existing = requireBudgetLimit(id);
        Category categorie = requireCategory(request.categorieId());

        // Le plafond en cours de modification ne doit pas se rentrer en conflit
        // avec lui-même : on écarte donc son propre identifiant.
        requireNoExistingLimit(request.categorieId(), request.role(), id);

        existing.setCategorie(categorie);
        existing.setRole(request.role());
        existing.setPlafond(request.plafond());

        BudgetLimit saved = budgetLimitRepository.save(existing);
        log.info("Plafond mis a jour : id={} categorie={} role={} montant={}",
                saved.getId(), categorie.getId(), saved.getRole(), saved.getPlafond());

        return BudgetLimitResponse.from(saved);
    }

    /**
     * Supprime un plafond.
     *
     * @param id identifiant du plafond à supprimer
     * @throws ResourceNotFoundException si le plafond n'existe pas
     */
    @Transactional
    public void deleteById(Long id) {
        BudgetLimit existing = requireBudgetLimit(id);

        budgetLimitRepository.deleteById(id);
        log.info("Plafond supprime : id={} categorie={} role={}",
                id, existing.getCategorie().getId(), existing.getRole());
    }

    /**
     * Charge une catégorie ou échoue explicitement.
     *
     * @param id identifiant recherché
     * @return l'entité correspondante
     * @throws ResourceNotFoundException si elle n'existe pas
     */
    private Category requireCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Categorie introuvable : id=" + id));
    }

    /**
     * Charge un plafond ou échoue explicitement.
     *
     * @param id identifiant recherché
     * @return l'entité correspondante
     * @throws ResourceNotFoundException si elle n'existe pas
     */
    private BudgetLimit requireBudgetLimit(Long id) {
        return budgetLimitRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Plafond introuvable : id=" + id));
    }

    /**
     * Vérifie qu'aucun plafond n'occupe déjà le couple (catégorie, rôle).
     *
     * @param categorieId identifiant de la catégorie
     * @param role        rôle concerné
     * @param excludeId   identifiant du plafond à ignorer (modification en cours), ou {@code null}
     * @throws DuplicateResourceException si un autre plafond occupe ce couple
     */
    private void requireNoExistingLimit(Long categorieId, UserRole role, Long excludeId) {
        budgetLimitRepository.findByCategorieIdAndRole(categorieId, role)
                .filter(existing -> excludeId == null || !existing.getId().equals(excludeId))
                .ifPresent(existing -> {
                    throw new DuplicateResourceException(
                            "Un plafond existe deja pour cette categorie et ce role");
                });
    }
}
