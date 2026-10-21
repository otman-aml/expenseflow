-- ============================================================
-- Projet     : ExpenseFlow
-- Fichier    : V2__add_entite_id_to_audit_log.sql
-- Date       : 2026-09-29
-- Description: Ajout de la colonne entite_id à audit_log, afin de
--              permettre de retrouver les entrées d'une entité précise
--              (historique des transitions d'une note de frais).
--
-- Contexte    : V1 a créé audit_log sans clé d'identification de
--              l'entité concernée — seulement un nom logique
--              (entite_concernee). Conséquence : le journal pouvait
--              être écrit, mais pas relu. "Quelles sont les
--              transitions de la note 7 ?" était sans réponse, la
--              seule recherche possible étant un LIKE sur une
--              colonne texte, non indexable et ambigu
--              ("ExpenseRequest#7" matcherait "#17").
--
-- Choix      : entite_id reste NULLABLE, et non NOT NULL.
--              Le journal est générique : toutes les entités ne
--              sont pas identifiées par un identifiant numérique
--              (une catégorie de référentiel, par exemple, peut
--              l'être par son nom). Rendre la colonne obligatoire
--              contraindrait les usages futurs. L'indexation
--              ci-dessous porte sur le couple (entite_concernee,
--              entite_id), qui est exactement le critère de
--              recherche de l'historique.
--
-- Rétrocompatibilité : V1 n'est pas modifiée. La migration est
--              additive et sans backfill — la table était vide
--              lors de son introduction en section 2.3.
-- ============================================================

ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS entite_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_audit_log_entite
    ON audit_log(entite_concernee, entite_id);

-- ============================================================
-- Fin de la migration V2
-- ============================================================
