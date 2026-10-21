package com.expenseflow.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.expenseflow.entity.enums.ExpenseStatus;
import com.expenseflow.entity.enums.UserRole;

/**
 * Tests de la machine à états.
 *
 * <p>
 * <strong>Aucun conteneur Spring, aucune base de données</strong> : la classe
 * n'exerce que des méthodes statiques. Les tests s'exécutent en quelques
 * millisecondes, ce qui autorise le parcours exhaustif des combinaisons
 * statut × statut × rôle — 8 × 8 × 3 = 192 cas. Aucun test d'intégration ne
 * produirait spontanément la totalité de ces combinaisons.
 * </p>
 */
@DisplayName("ExpenseWorkflow (machine à états)")
class ExpenseWorkflowTest {

    /**
     * Ensemble des transitions attendues comme autorisées, quel que soit le rôle.
     *
     * <p>
     * Sert de référence unique : {@link #canTransition()} refuse tout couple absent
     * de cette liste, ce qui garantit qu'une transition ajoutée par erreur dans
     * {@code TRANSITIONS} serait immédiatement signalée.
     * </p>
     */
    private static final List<String> TRANSITIONS_ATTENDUES = List.of(
            "DRAFT->SUBMITTED",
            "PENDING_MANAGER->APPROVED_MANAGER",
            "PENDING_MANAGER->REJECTED_MANAGER",
            "PENDING_FINANCE->REIMBURSED",
            "PENDING_FINANCE->REJECTED_FINANCE");

    @Nested
    @DisplayName("canTransition — transitions autorisées")
    class TransitionsAutorisees {

        /**
         * DRAFT vers SUBMITTED, pour le propriétaire quel que soit son rôle.
         *
         * @param role rôle du propriétaire
         */
        @ParameterizedTest(name = "DRAFT -> SUBMITTED par {0} (proprietaire)")
        @EnumSource(UserRole.class)
        void draftVersSubmitted_parProprietaire(UserRole role) {
            assertThat(ExpenseWorkflow.canTransition(
                    ExpenseStatus.DRAFT, ExpenseStatus.SUBMITTED, role, true)).isTrue();
        }

        /**
         * PENDING_MANAGER vers APPROVED_MANAGER, pour MANAGER et FINANCE_ADMIN.
         *
         * <p>
         * FINANCE_ADMIN est volontairement inclus : la hiérarchie est mono-niveau et
         * la finance doit pouvoir traiter une note sans attendre le manager.
         * </p>
         *
         * @param role rôle du validateur
         */
        @ParameterizedTest(name = "PENDING_MANAGER -> APPROVED_MANAGER par {0}")
        @EnumSource(value = UserRole.class, names = { "MANAGER", "FINANCE_ADMIN" })
        void pendingManagerVersApproved_parManagerOuFinance(UserRole role) {
            assertThat(ExpenseWorkflow.canTransition(
                    ExpenseStatus.PENDING_MANAGER, ExpenseStatus.APPROVED_MANAGER, role, false)).isTrue();
        }

        /**
         * PENDING_MANAGER vers REJECTED_MANAGER, pour MANAGER et FINANCE_ADMIN.
         *
         * @param role rôle du validateur
         */
        @ParameterizedTest(name = "PENDING_MANAGER -> REJECTED_MANAGER par {0}")
        @EnumSource(value = UserRole.class, names = { "MANAGER", "FINANCE_ADMIN" })
        void pendingManagerVersRejected_parManagerOuFinance(UserRole role) {
            assertThat(ExpenseWorkflow.canTransition(
                    ExpenseStatus.PENDING_MANAGER, ExpenseStatus.REJECTED_MANAGER, role, false)).isTrue();
        }

        /**
         * PENDING_FINANCE vers REIMBURSED, réservé au FINANCE_ADMIN.
         *
         * @param role rôle du valideur
         */
        @ParameterizedTest(name = "PENDING_FINANCE -> REIMBURSED par {0}")
        @EnumSource(value = UserRole.class, names = { "FINANCE_ADMIN" })
        void pendingFinanceVersRembourse_parFinance(UserRole role) {
            assertThat(ExpenseWorkflow.canTransition(
                    ExpenseStatus.PENDING_FINANCE, ExpenseStatus.REIMBURSED, role, false)).isTrue();
        }

        /**
         * PENDING_FINANCE vers REJECTED_FINANCE, réservé au FINANCE_ADMIN.
         *
         * @param role rôle du valideur
         */
        @ParameterizedTest(name = "PENDING_FINANCE -> REJECTED_FINANCE par {0}")
        @EnumSource(value = UserRole.class, names = { "FINANCE_ADMIN" })
        void pendingFinanceVersRejeteFinance_parFinance(UserRole role) {
            assertThat(ExpenseWorkflow.canTransition(
                    ExpenseStatus.PENDING_FINANCE, ExpenseStatus.REJECTED_FINANCE, role, false)).isTrue();
        }
    }

    @Nested
    @DisplayName("canTransition — transitions interdites")
    class TransitionsInterdites {

        /**
         * Parcours exhaustif : tout couple statut/statut absent de la liste de
         * référence est refusé, quels que soient le rôle et la propriété.
         *
         * <p>
         * Ce test est le contrepoids des précédents. Une transition ajoutée par
         * inadvertance dans la table de la machine à états — un raccourci
         * oublié, un statut déplacé — le ferait échouer immédiatement, sans
         * qu'aucune simulation manuelle n'ait à être imaginée.
         * </p>
         *
         * @param from statut d'origine
         * @param to   statut demandé
         * @param role rôle de l'acteur
         */
        @ParameterizedTest(name = "{0} -> {1} par {2} refuse")
        @MethodSource("couplesInterdits")
        void coupleInconnuRefusePourToutRole(ExpenseStatus from, ExpenseStatus to, UserRole role) {
            // Le rôle le plus permissif et la propriété accordée : si la
            // transition est refusée ici, elle le sera dans tous les autres cas.
            assertThat(ExpenseWorkflow.canTransition(from, to, role, true)).isFalse();
        }

        /**
         * Produit tous les couples de statuts absents de la liste de référence,
         * croisés avec les 3 rôles.
         *
         * @return couples de statuts interdits
         */
        static Stream<Arguments> couplesInterdits() {
            Set<String> autorises = Set.copyOf(TRANSITIONS_ATTENDUES);

            return Stream.of(ExpenseStatus.values())
                    .flatMap(from -> Stream.of(ExpenseStatus.values())
                            .filter(to -> !autorises.contains(from + "->" + to))
                            .flatMap(to -> Stream.of(UserRole.values())
                                    .map(role -> Arguments.of(from, to, role))));
        }

        /**
         * Un EMPLOYEE ne peut ni approuver ni rejeter.
         *
         * @param role rôle de l'acteur
         * @param from statut d'origine
         * @param to   statut demandé
         */
        @ParameterizedTest(name = "{0} refuse {1} -> {2}")
        @MethodSource("couplesDeNiveauManager")
        void validationManagerRefuseeHorsRole(UserRole role, ExpenseStatus from, ExpenseStatus to) {
            assertThat(ExpenseWorkflow.canTransition(from, to, role, false)).isFalse();
        }

        /**
         * Un MANAGER n'accède pas au circuit de la finance.
         *
         * @param role rôle de l'acteur
         * @param from statut d'origine
         * @param to   statut demandé
         */
        @ParameterizedTest(name = "{0} refuse {1} -> {2} (niveau finance)")
        @MethodSource("couplesDeNiveauFinance")
        void niveauFinanceReserve(UserRole role, ExpenseStatus from, ExpenseStatus to) {
            assertThat(ExpenseWorkflow.canTransition(from, to, role, false)).isFalse();
        }

        /**
         * Un tiers ne soumet pas la note d'autrui : la règle de propriété prime
         * sur le rôle.
         */
        @Test
        @DisplayName("DRAFT -> SUBMITTED refuse si l'acteur n'est pas le propriétaire")
        void soumissionRefuseeAuTiers() {
            assertThat(ExpenseWorkflow.canTransition(
                    ExpenseStatus.DRAFT, ExpenseStatus.SUBMITTED, UserRole.EMPLOYEE, false)).isFalse();
        }

        /**
         * Un statut nul est refusé sans lever d'exception : une valeur absente
         * ne doit pas produire un NPE qui remonterait en 500.
         */
        @Test
        @DisplayName("Un statut ou un role nul est refuse sans exception")
        void valeurNulleRefusee() {
            assertThat(ExpenseWorkflow.canTransition(null, ExpenseStatus.SUBMITTED, UserRole.EMPLOYEE, true))
                    .isFalse();
            assertThat(ExpenseWorkflow.canTransition(ExpenseStatus.DRAFT, null, UserRole.EMPLOYEE, true))
                    .isFalse();
            assertThat(ExpenseWorkflow.canTransition(ExpenseStatus.DRAFT, ExpenseStatus.SUBMITTED, null, true))
                    .isFalse();
        }

        /**
         * Combinaisons de décision réservées au manager, avec les rôles qui
         * n'y ont <em>pas</em> accès.
         *
         * <p>
         * MANAGER et FINANCE_ADMIN en sont volontairement absents : les deux
         * valident au niveau manager, la hiérarchie étant mono-niveau. Les
         * inclure ici reviendrait à nier la décision métier documentée dans
         * {@code ExpenseWorkflow}. Ils sont couverts par le bloc
         * « transitions autorisées ».
         * </p>
         *
         * @return rôle non habilité, statut d'origine, statut demandé
         */
        static Stream<Arguments> couplesDeNiveauManager() {
            return Stream.of(UserRole.EMPLOYEE)
                    .flatMap(role -> Stream.of(
                            Arguments.of(role, ExpenseStatus.PENDING_MANAGER, ExpenseStatus.APPROVED_MANAGER),
                            Arguments.of(role, ExpenseStatus.PENDING_MANAGER, ExpenseStatus.REJECTED_MANAGER)));
        }

        /**
         * Combinaisons de décision réservées à la finance, avec les rôles qui
         * n'y ont pas accès.
         *
         * @return rôle non habilité, statut d'origine, statut demandé
         */
        static Stream<Arguments> couplesDeNiveauFinance() {
            return Stream.of(UserRole.EMPLOYEE, UserRole.MANAGER)
                    .flatMap(role -> Stream.of(
                            Arguments.of(role, ExpenseStatus.PENDING_FINANCE, ExpenseStatus.REIMBURSED),
                            Arguments.of(role, ExpenseStatus.PENDING_FINANCE, ExpenseStatus.REJECTED_FINANCE)));
        }
    }

    @Nested
    @DisplayName("isTerminal — états définitifs")
    class EtatsTerminaux {

        /**
         * @param statut statut final attendu
         */
        @ParameterizedTest(name = "{0} est terminal")
        @EnumSource(value = ExpenseStatus.class, names = { "REIMBURSED", "REJECTED_MANAGER", "REJECTED_FINANCE" })
        void etatFinal(ExpenseStatus statut) {
            assertThat(ExpenseWorkflow.isTerminal(statut)).isTrue();
        }

        /**
         * @param statut statut non final attendu
         */
        @ParameterizedTest(name = "{0} n'est pas terminal")
        @EnumSource(value = ExpenseStatus.class,
                names = { "DRAFT", "SUBMITTED", "PENDING_MANAGER", "APPROVED_MANAGER", "PENDING_FINANCE" })
        void etatNonFinal(ExpenseStatus statut) {
            assertThat(ExpenseWorkflow.isTerminal(statut)).isFalse();
        }

        /**
         * Un état final n'admet aucune transition sortante, quel que soit
         * l'acteur : c'est la garantie qu'une note remboursée ne peut pas être
         * remise en circuit par un appel d'API.
         */
        @Test
        @DisplayName("Un statut terminal refuse toute transition sortante")
        void statutTerminalNeSortDeAucunEtat() {
            for (ExpenseStatus from : new ExpenseStatus[] {
                    ExpenseStatus.REIMBURSED, ExpenseStatus.REJECTED_MANAGER, ExpenseStatus.REJECTED_FINANCE }) {
                for (ExpenseStatus to : ExpenseStatus.values()) {
                    for (UserRole role : UserRole.values()) {
                        assertThat(ExpenseWorkflow.canTransition(from, to, role, true))
                                .as("%s -> %s par %s", from, to, role)
                                .isFalse();
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("assertTransition — refus explicite")
    class AssertionsWorkflow {

        /**
         * Une transition autorisée ne lève rien.
         */
        @Test
        @DisplayName("Une transition autorisée ne lève pas")
        void transitionAutoriseeNeLevePas() {
            assertThatCode(() -> ExpenseWorkflow.assertTransition(
                    ExpenseStatus.PENDING_MANAGER, ExpenseStatus.APPROVED_MANAGER,
                    UserRole.MANAGER, false)).doesNotThrowAnyException();
        }

        /**
         * Une transition inexistante lève une WorkflowException nommant les deux
         * statuts, pour que le message soit exploitable dans les journaux.
         */
        @Test
        @DisplayName("Une transition inexistante lève WorkflowException")
        void transitionInexistanteLeve() {
            assertThatThrownBy(() -> ExpenseWorkflow.assertTransition(
                    ExpenseStatus.DRAFT, ExpenseStatus.REIMBURSED, UserRole.FINANCE_ADMIN, true))
                    .isInstanceOf(WorkflowException.class)
                    .hasMessageContaining("DRAFT")
                    .hasMessageContaining("REIMBURSED")
                    .hasMessageContaining("transition inexistante");
        }

        /**
         * Un refus de rôle lève également WorkflowException, en distinguant la
         * cause : les droits font défaut, la transition existe pourtant.
         */
        @Test
        @DisplayName("Un refus de rôle lève WorkflowException en nommant la cause")
        void refusDeRoleLeveAvecMotif() {
            assertThatThrownBy(() -> ExpenseWorkflow.assertTransition(
                    ExpenseStatus.PENDING_MANAGER, ExpenseStatus.APPROVED_MANAGER,
                    UserRole.EMPLOYEE, false))
                    .isInstanceOf(WorkflowException.class)
                    .hasMessageContaining("droits insuffisants");
        }

        /**
         * Un non-propriétaire reçoit un motif explicite, distinct d'un refus de
         * rôle : la correction à apporter n'est pas la même.
         */
        @Test
        @DisplayName("La soumission par un tiers cite la règle de propriété")
        void soumissionParTiersCiteLaPropriete() {
            assertThatThrownBy(() -> ExpenseWorkflow.assertTransition(
                    ExpenseStatus.DRAFT, ExpenseStatus.SUBMITTED, UserRole.EMPLOYEE, false))
                    .isInstanceOf(WorkflowException.class)
                    .hasMessageContaining("proprietaire");
        }
    }
}
