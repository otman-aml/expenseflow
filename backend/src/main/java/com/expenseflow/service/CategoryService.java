package com.expenseflow.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.dto.CategoryRequest;
import com.expenseflow.dto.CategoryResponse;
import com.expenseflow.entity.Category;
import com.expenseflow.exception.CategoryInUseException;
import com.expenseflow.exception.DuplicateResourceException;
import com.expenseflow.exception.ResourceNotFoundException;
import com.expenseflow.repository.BudgetLimitRepository;
import com.expenseflow.repository.CategoryRepository;
import com.expenseflow.repository.ExpenseLineRepository;

/**
 * Logique métier des catégories de dépense.
 *
 * <p>
 * Les contrôles d'accès ne sont pas inscrits ici : ils sont portés par les
 * annotations {@code @PreAuthorize} du contrôleur, qui s'exécutent avant
 * l'appel au service. Ce dernier n'est responsable que des règles métier et de
 * l'intégrité des données.
 * </p>
 */
@Service
public class CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    private final CategoryRepository categoryRepository;

    private final BudgetLimitRepository budgetLimitRepository;

    private final ExpenseLineRepository expenseLineRepository;

    /**
     * @param categoryRepository    dépôt des catégories
     * @param budgetLimitRepository dépôt des plafonds (contrôle d'usage)
     * @param expenseLineRepository dépôt des lignes de dépense (contrôle d'usage)
     */
    public CategoryService(CategoryRepository categoryRepository,
            BudgetLimitRepository budgetLimitRepository,
            ExpenseLineRepository expenseLineRepository) {
        this.categoryRepository = categoryRepository;
        this.budgetLimitRepository = budgetLimitRepository;
        this.expenseLineRepository = expenseLineRepository;
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
     * Vérifie qu'un nom de catégorie est libre pour l'entité concernée.
     *
     * @param nom       libellé souhaité
     * @param excludeId identifiant de l'entité en cours de modification, ou {@code null}
     * @throws DuplicateResourceException si une autre catégorie porte déjà ce nom
     */
    private void requireNomLibre(String nom, Long excludeId) {
        categoryRepository.findByNom(nom)
                .filter(existing -> excludeId == null || !existing.getId().equals(excludeId))
                .ifPresent(existing -> {
                    throw new DuplicateResourceException("Une categorie portant ce nom existe deja : " + nom);
                });
    }

    /**
     * @return toutes les catégories
     */
    @Transactional(readOnly = true)
    public List<CategoryResponse> findAll() {
        return categoryRepository.findAll().stream().map(CategoryResponse::from).toList();
    }

    /**
     * @param id identifiant de la catégorie
     * @return la catégorie correspondante
     * @throws ResourceNotFoundException si aucune catégorie ne porte cet identifiant
     */
    @Transactional(readOnly = true)
    public CategoryResponse findById(Long id) {
        return CategoryResponse.from(requireCategory(id));
    }

    /**
     * Crée une nouvelle catégorie.
     *
     * @param request libellé validé par Jakarta Validation
     * @return la catégorie créée
     * @throws DuplicateResourceException si le nom est déjà utilisé
     */
    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        String nom = request.nom().trim();
        requireNomLibre(nom, null);

        Category saved = categoryRepository.save(Category.builder().nom(nom).build());
        log.info("Categorie creee : id={} nom={}", saved.getId(), saved.getNom());
        return CategoryResponse.from(saved);
    }

    /**
     * Met à jour le libellé d'une catégorie existante.
     *
     * @param id      identifiant de la catégorie à modifier
     * @param request nouveau libellé
     * @return la catégorie mise à jour
     * @throws ResourceNotFoundException  si la catégorie n'existe pas
     * @throws DuplicateResourceException si le nom est déjà porté par une autre catégorie
     */
    @Transactional
    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = requireCategory(id);
        String nom = request.nom().trim();
        requireNomLibre(nom, id);

        category.setNom(nom);
        Category saved = categoryRepository.save(category);
        log.info("Categorie mise a jour : id={} nom={}", saved.getId(), saved.getNom());
        return CategoryResponse.from(saved);
    }

    /**
     * Supprime une catégorie si et seulement si rien ne la référence.
     *
     * <p>
     * Les deux clés étrangères pointant vers {@code categories} sont en
     * {@code ON DELETE RESTRICT} dans le schéma V1. Le contrôle est donc
     * effectué en amont : sans lui, PostgreSQL rejetterait l'opération et la
     * remontée en {@code DataIntegrityViolationException} se traduirait par un
     * 500 trompeur au lieu d'un 409 décrivant la cause réelle.
     * </p>
     *
     * @param id identifiant de la catégorie à supprimer
     * @throws ResourceNotFoundException si la catégorie n'existe pas
     * @throws CategoryInUseException    si des plafonds ou des lignes la référencent
     */
    @Transactional
    public void deleteById(Long id) {
        Category category = requireCategory(id);

        long plafondCount = budgetLimitRepository.countByCategorieId(id);
        long ligneCount = expenseLineRepository.countByCategorieId(id);

        if (plafondCount > 0 || ligneCount > 0) {
            log.debug("Suppression refusee id={} : {} plafond(s), {} ligne(s)",
                    id, plafondCount, ligneCount);
            throw new CategoryInUseException(category.getNom());
        }

        categoryRepository.deleteById(id);
        log.info("Categorie supprimee : id={} nom={}", id, category.getNom());
    }
}
