package com.expenseflow.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.dto.CategoryRequest;
import com.expenseflow.dto.CategoryResponse;
import com.expenseflow.service.CategoryService;

import jakarta.validation.Valid;

/**
 * Points d'entrée REST des catégories de dépense.
 *
 * <p>
 * Les règles d'accès sont portées par {@code @PreAuthorize}, évaluées par Spring
 * Security <em>avant</em> l'exécution de la méthode : un appel refusé n'atteint
 * donc jamais le service.
 * </p>
 *
 * <ul>
 * <li><strong>Lecture</strong> : ouverte à tout utilisateur authentifié, car les
 * catégories servent à renseigner un formulaire de note de frais — les
 * containeurs doivent pouvoir les lister.</li>
 * <li><strong>Écriture</strong> : réservée au rôle FINANCE_ADMIN, seul habilité
 * à faire évoluer le référentiel des catégories.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * @param categoryService service des catégories
     */
    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * Liste toutes les catégories.
     *
     * @return la liste des catégories
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> findAll() {
        return ResponseEntity.ok(categoryService.findAll());
    }

    /**
     * Récupère une catégorie par son identifiant.
     *
     * @param id identifiant de la catégorie
     * @return la catégorie demandée
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public ResponseEntity<CategoryResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(categoryService.findById(id));
    }

    /**
     * Crée une nouvelle catégorie.
     *
     * @param request libellé de la catégorie
     * @return 201 Created avec la catégorie créée
     */
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request));
    }

    /**
     * Met à jour une catégorie existante.
     *
     * @param id      identifiant de la catégorie à modifier
     * @param request nouveau libellé
     * @return la catégorie mise à jour
     */
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> update(@PathVariable Long id,
            @Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.ok(categoryService.update(id, request));
    }

    /**
     * Supprime une catégorie.
     *
     * @param id identifiant de la catégorie à supprimer
     * @return 204 No Content ; 409 Conflict si la catégorie est encore utilisée
     */
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteById(@PathVariable Long id) {
        categoryService.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}