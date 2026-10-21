package com.expenseflow.workflow;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.expenseflow.entity.enums.ExpenseStatus;
import com.expenseflow.entity.enums.UserRole;

/**
 * Machine à états du cycle de vie d'une note de frais.
 *
 * <p>
 * <strong>Logique pure, sans dépendance à Spring.</strong> Cette classe ne
 * contient aucun {@code @Service}, aucune injection et aucun accès à la base :
 * elle ne fait que répondre par {@code true} ou {@code false} à une question
 * sur deux statuts et un rôle. Conséquence directe : elle se teste sans
 * conteneur, en quelques dizaines de millisecondes, ce qui permet de couvrir
 * <em>toutes</em> les combinaisons de transitions — y compris celles qu'aucun
 * test d'intégration ne provoquerait spontanément.
 * </p>
 *
 * <h2>Cycle de vie</h2>
 * <pre>
 *   DRAFT ──submit──▶ SUBMITTED ──▶ PENDING_MANAGER
 *                                             │
 *                          ┌──────────────────┴──────────────────┐
 *                       approve                               reject
 *                          ▼                                     ▼
 *                 APPROVED_MANAGER                     REJECTED_MANAGER (terminal)
 *                          │
 *                          ▼
 *                 PENDING_FINANCE
 *                          │
 *              ┌───────────┴───────────┐
 *           reimburse               reject
 *              ▼                       ▼
 *         REIMBURSED            REJECTED_FINANCE (terminal)
 *         (terminal)
 * </pre>
 *
 * <p>
 * <strong>Pourquoi deux états intermédiaires par niveau</strong> :
 * {@code SUBMITTED} et {@code APPROVED_MANAGER} sont franchis dans la même
 * transaction que l'étape précédente, mais restent distincts en base. Ils
 * rendent la trace lisible : la date de soumission est conservée, et l'on sait
 * que le manager a validé avant que la finance ne prenne le relais.
 * </p>
 *
 * <p>
 * <strong>Règle de propriété.</strong> Seul le propriétaire peut soumettre sa
 * note. Les validations, elles, sont ouvertes au rôle concerné sans
 * vérification de propriété : un manager valide les notes de son équipe, et la
 * finance valide l'ensemble. C'est pourquoi
 * {@link #canTransition(ExpenseStatus, ExpenseStatus, UserRole, boolean)} reçoit
 * un indicateur de propriété plutôt que l'utilisateur entier : la machine n'a
 * pas besoin de connaître l'identité, seulement le rôle et le fait que l'acteur
 * est ou non l'auteur.
 * </p>
 */
public final class ExpenseWorkflow {

    /**
     * Transitions autorisées, par statut d'origine.
     *
     * <p>
     * Chaque entrée associe à un statut la liste des statuts atteignables. Une
     * clé absente de la map signifie qu'aucune transition n'est possible : les
     * états terminaux sont donc terminaux de fait, sans liste vide explicite.
     * </p>
     *
     * <p>
     * Les transitions <em>automatiques</em> (SUBMITTED vers PENDING_MANAGER,
     * APPROVED_MANAGER vers PENDING_FINANCE) sont délibérément absentes : ces
     * sauts sont réalisés par le service dans la même transaction, sans contrôle
     * de rôle. Les modéliser ici ferait croire qu'un MANAGER peut les déclencher
     * par un appel d'API, ce qui n'est pas le cas.
     * </p>
     */
    private static final Map<ExpenseStatus, Set<ExpenseStatus>> TRANSITIONS = new EnumMap<>(ExpenseStatus.class);

    static {
        // Soumission : reservee au proprietaire de la note.
        TRANSITIONS.put(ExpenseStatus.DRAFT, EnumSet.of(ExpenseStatus.SUBMITTED));

        // Niveau 1 : decision du manager.
        // FINANCE_ADMIN est egalement autorise ici : decision metier assumee.
        // La hierarchie d'approbation est mono-niveau, et la finance doit
        // pouvoir rejeter une note sans attendre un aller-retour par le
        // manager. Sans cela, une note que le manager ne traite jamais resterait
        // bloquee indefiniment a PENDING_MANAGER, la seule voie de sortie etant
        // l'intervention du manager. Ce n'est pas un contournement : le role
        // reste requis, seule l'etendue de ce role est elargie.
        TRANSITIONS.put(ExpenseStatus.PENDING_MANAGER, EnumSet.of(
                ExpenseStatus.APPROVED_MANAGER,
                ExpenseStatus.REJECTED_MANAGER));

        // Niveau 2 : decision de la finance.
        TRANSITIONS.put(ExpenseStatus.PENDING_FINANCE, EnumSet.of(
                ExpenseStatus.REIMBURSED,
                ExpenseStatus.REJECTED_FINANCE));
    }

    /**
     * Statuts à partir desquels aucune transition n'est possible.
     */
    private static final Set<ExpenseStatus> TERMINAUX = EnumSet.of(
            ExpenseStatus.REIMBURSED,
            ExpenseStatus.REJECTED_MANAGER,
            ExpenseStatus.REJECTED_FINANCE);

    /**
     * Constructeur privé : la classe ne porte que des méthodes statiques et ne
     * doit pas être instanciée.
     */
    private ExpenseWorkflow() {
    }

    /**
     * Indique si une transition est autorisée.
     *
     * @param from     statut actuel de la note
     * @param to       statut demandé
     * @param userRole rôle de l'acteur
     * @param isOwner  true si l'acteur est l'auteur de la note
     * @return true si la transition est autorisée
     */
    public static boolean canTransition(ExpenseStatus from, ExpenseStatus to, UserRole userRole, boolean isOwner) {
        // Rôle ou statut inconnu : aucune transition n'est accordée. Le rôle ne
        // peut pas être nul en base (contrainte NOT NULL), mais une valeur nulle
        // ferait echouer silencieusement une comparaison par egalite.
        if (userRole == null || from == null || to == null) {
            return false;
        }

        // Un statut terminal ne possede aucune transition sortante.
        if (TERMINAUX.contains(from)) {
            return false;
        }

        Set<ExpenseStatus> cibles = TRANSITIONS.get(from);

        // Statut d'origine inconnu de la table : on refuse par prudence.
        if (cibles == null || !cibles.contains(to)) {
            return false;
        }

        // La regle de propriete ne concerne que la soumission. Les decisions de
        // validation s'appuient uniquement sur le role.
        return switch (to) {
            case SUBMITTED -> isOwner;
            case APPROVED_MANAGER, REJECTED_MANAGER ->
                    userRole == UserRole.MANAGER || userRole == UserRole.FINANCE_ADMIN;
            case REIMBURSED, REJECTED_FINANCE -> userRole == UserRole.FINANCE_ADMIN;
            default -> false;
        };
    }

    /**
     * Vérifie une transition et lève une exception si elle est interdite.
     *
     * <p>
     * Point d'entrée unique pour le service : aucune transition ne doit être
     * écrite ailleurs. Une affectation directe de {@code request.setStatut(...)}
     * ailleurs dans le code contournerait silencieusement la machine à états.
     * </p>
     *
     * @param from     statut actuel de la note
     * @param to       statut demandé
     * @param userRole rôle de l'acteur
     * @param isOwner  true si l'acteur est l'auteur de la note
     * @throws WorkflowException si la transition est interdite
     */
    public static void assertTransition(ExpenseStatus from, ExpenseStatus to, UserRole userRole, boolean isOwner) {
        if (canTransition(from, to, userRole, isOwner)) {
            return;
        }

        throw new WorkflowException("Transition non autorisee : " + from + " vers " + to
                + " (" + motifRefus(from, to, userRole, isOwner) + ")");
    }

    /**
     * Détermine la cause d'un refus, pour rendre le message exploitable.
     *
     * <p>
     * Un refus de rôle et une transition inexistante n'appellent pas la même
     * correction : le premier relève des droits, le second de l'état de la note.
     * Le message produit est destiné à la journalisation serveur ; la réponse
     * HTTP reste générique.
     * </p>
     *
     * @param from     statut actuel
     * @param to       statut demandé
     * @param userRole rôle de l'acteur
     * @param isOwner  true si l'acteur est l'auteur
     * @return libellé du motif de refus
     */
    private static String motifRefus(ExpenseStatus from, ExpenseStatus to, UserRole userRole, boolean isOwner) {
        Set<ExpenseStatus> cibles = TRANSITIONS.get(from);

        // Le lien lui-meme n'existe pas : la machine ne definit pas ce couple.
        if (cibles == null || !cibles.contains(to)) {
            return "transition inexistante";
        }

        // Le lien existe mais l'acteur n'a pas le role requis.
        if (to == ExpenseStatus.SUBMITTED && !isOwner) {
            return "seul le proprietaire peut soumettre sa note";
        }

        return "droits insuffisants pour le role " + userRole;
    }

    /**
     * Indique si un statut est final, c'est-à-dire sans transition sortante.
     *
     * @param statut statut à tester
     * @return true si aucune transition n'est possible depuis ce statut
     */
    public static boolean isTerminal(ExpenseStatus statut) {
        return TERMINAUX.contains(statut);
    }
}
