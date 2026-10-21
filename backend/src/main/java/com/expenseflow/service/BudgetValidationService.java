package com.expenseflow.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.entity.BudgetLimit;
import com.expenseflow.entity.ExpenseRequest;
import com.expenseflow.entity.User;
import com.expenseflow.entity.enums.UserRole;
import com.expenseflow.repository.BudgetLimitRepository;
import com.expenseflow.workflow.BudgetViolation;

/**
 * Vérifie le respect des plafonds budgétaires d'une note de frais.
 *
 * <p>
 * <strong>Règle appliquée : comparaison par ligne, et non sur le total par
 * catégorie.</strong> Un plafond s'interprète comme un maximum par dépense —
 * « 200 MAD par repas », « 500 MAD par trajet » — et non comme un budget
 * mensuel. Cumuler deux repas de 150 MAD sous un plafond de 200 donnerait un
 * total de 300, et l'empêché serait bloqué alors qu'aucune dépense isolée ne
 * dépasse rien. Le cumul refute par ailleurs l'utilisateur sans l'expliquer :
 * il verrait « 300 > 200 » pour deux lignes qu'il jugeait chacune conforme.
 * </p>
 *
 * <p>
 * <strong>Comparaison stricte.</strong> Un montant exactement égal au plafond
 * est accepté : le plafond est un maximum, pas un seuil à franchir. Le test
 * correspondant verrouille ce cas, car une comparaison {@code >=} passerait
 * presque tous les tests fonctionnels et bloquerait à tort les notes dont le
 * total tombe juste.
 * </p>
 *
 * <p>
 * <strong>Ouvert par défaut.</strong> Une catégorie dépourvue de plafond pour le
 * rôle de l'employé ne bloque pas la soumission. La règle retenue est
 * « l'absence de limite vaut absence de restriction » : bloquer une dépense
 * faute de configuration rendrait la finance incapable d'utiliser le
 * formulaire sans configurer au préalable chaque catégorie, alors que la
 * sécurisation par défaut du workflow reste assurée par les rôles.
 * </p>
 *
 * <p>
 * <strong>Une seule requête.</strong> Tous les plafonds du rôle sont chargés
 * d'un coup puis indexés en mémoire. Une recherche par ligne déclencherait un
 * problème N+1 sur une note de dix lignes.
 * </p>
 */
@Service
public class BudgetValidationService {

    private final BudgetLimitRepository budgetLimitRepository;

    /**
     * @param budgetLimitRepository dépôt des plafonds budgétaires
     */
    public BudgetValidationService(BudgetLimitRepository budgetLimitRepository) {
        this.budgetLimitRepository = budgetLimitRepository;
    }

    /**
     * Contrôle les lignes d'une note face aux plafonds du rôle de l'employé.
     *
     * <p>
     * La méthode ne lève pas : elle renvoie la liste des violations, éventuellement
     * vide. Séparer la détection de la décision permet au service appelant de
     * composer son message et de choisir le code HTTP, et rend cette classe
     * testable sans exception à anticipation.
     * </p>
     *
     * @param request  note à contrôler, lignes déjà chargées
     * @param employee auteur de la note, dont le rôle détermine les plafonds
     * @return violations constatées, vide si la note est conforme
     */
    @Transactional(readOnly = true)
    public List<BudgetViolation> validateExpense(ExpenseRequest request, User employee) {
        UserRole role = employee.getRole();

        // Index par identifiant de catégorie : les plafonds du rôle sont peu
        // nombreux, on les charge une fois et on les relit par ligne.
        Map<Long, BudgetLimit> plafondsParCategorie = new HashMap<>();
        for (BudgetLimit plafond : budgetLimitRepository.findByRoleWithCategorie(role)) {
            plafondsParCategorie.put(plafond.getCategorie().getId(), plafond);
        }

        List<BudgetViolation> violations = new ArrayList<>();

        for (var ligne : request.getLignes()) {
            Long categorieId = ligne.getCategorie().getId();

            // Pas de plafond pour cette categorie : aucune restriction.
            BudgetLimit plafond = plafondsParCategorie.get(categorieId);
            if (plafond == null) {
                continue;
            }

            // Comparaison stricte : egalite au plafond reste acceptable.
            // TODO: prévoir une notion de plafond MENSUEL (cumul par catégorie)
            //       : la règle actuelle est « par dépense », donc dix repas de
            //       150 MAD passent tous sous un plafond de 200 sans rien
            //       déclencher, alors que le total (1 500) contredit
            //       probablement l'esprit de la politique.
            if (ligne.getMontant().compareTo(plafond.getPlafond()) > 0) {
                violations.add(new BudgetViolation(
                        categorieId,
                        ligne.getCategorie().getNom(),
                        role,
                        ligne.getMontant(),
                        plafond.getPlafond()));
            }
        }

        return violations;
    }
}