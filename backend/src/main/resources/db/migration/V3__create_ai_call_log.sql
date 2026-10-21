-- ============================================================
-- Projet     : ExpenseFlow
-- Fichier    : V3__create_ai_call_log.sql
-- Date       : 2026-09-30
-- Description: Journal des appels à l'API Mistral, pour mesurer l'usage
--              de la brique IA (volume, latence, taux d'échec, tokens).
--
-- Contexte    : la brique d'extraction appelle un service externe, ce qui
--              introduit trois grandeurs absentes du reste de l'application :
--              une latence réseau, un coût, et un taux d'échec. Le journal
--              d'audit des transitions ne couvre aucun des trois — il ne
--              trace que les changements d'état métier. Sans ce suivi, une
--              dégradation de l'extraction (modèle plus lent, quota atteint,
--              clé expirée) serait invisible.
--
-- Choix      : utilisateur_id est NULLABLE et ON DELETE SET NULL.
--              Un journal d'exploitation ne doit jamais empêcher une
--              suppression de compte. Effacer le lien conserve l'appel —
--              utile pour le diagnostic — tout en respectant la
--              suppression : le compte n'est plus identifiable, mais la
--              mesure reste. ON DELETE CASCADE aurait été plus simple
--              et aurait perdu l'historique d'usage.
--
-- Choix      : duree_ms et succes sont NOT NULL. Ce sont les deux seules
--              colonnes réellement universelles : un appel a toujours une
--              durée, et toujours un résultat binaire. Les compteurs de
--              tokens restent NULLABLES car tous les modèles et toutes
--              les versions d'API ne les fournissent pas ; un NULL
--              significa « non communiqué », pas « zéro ».
--
-- Rétrocompatibilité : V1 et V2 ne sont pas modifiées. Table nouvelle,
--              sans impact sur les données existantes.
-- ============================================================

CREATE TABLE IF NOT EXISTS ai_call_log (
    id                 BIGSERIAL PRIMARY KEY,
    date_appel         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    utilisateur_id     BIGINT REFERENCES users(id) ON DELETE SET NULL,
    duree_ms           BIGINT NOT NULL,
    succes             BOOLEAN NOT NULL,
    type_erreur        VARCHAR(50),
    message_erreur     TEXT,
    tokens_prompt      INTEGER,
    tokens_completion  INTEGER,
    tokens_total       INTEGER,
    modele             VARCHAR(50) NOT NULL,
    -- Alimente par la base. Distinct de date_appel : celui-ci est l'instant
    -- de l'appel, celui-la le moment de son enregistrement dans la table.
    -- Les deux peuvent differer d'une seconde si l'echange s'est prolonge
    -- apres la fin de l'appel.
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index sur la date, décroissante : la lecture courante est « les derniers
-- appels », et un index croissant imposerait un parcours inverse.
CREATE INDEX IF NOT EXISTS idx_ai_call_log_date
    ON ai_call_log(date_appel DESC);

CREATE INDEX IF NOT EXISTS idx_ai_call_log_utilisateur
    ON ai_call_log(utilisateur_id);

CREATE INDEX IF NOT EXISTS idx_ai_call_log_succes
    ON ai_call_log(succes);

-- ============================================================
-- Fin de la migration V3
-- ============================================================