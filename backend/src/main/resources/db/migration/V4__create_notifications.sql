-- ============================================================
-- Projet     : ExpenseFlow
-- Fichier    : V4__create_notifications.sql
-- Date       : 2026-10-09
-- Description: Notifications applicatives, déclenchées par les
--              transitions d'une note de frais.
--
-- Contexte    : jusqu'ici, le destinataire d'une transition ne
--              l'apprenait qu'en ouvrant l'application (liste,
--              validations, tableau de bord). La table permet
--              d'informer sans que l'utilisateur ait à chercher :
--              « votre note a été approuvée » arrive à l'employé,
--              « une note vous attend » arrive au manager.
--
-- Choix      : utilisateur_id est NOT NULL et ON DELETE CASCADE.
--              Contrairement à ai_call_log (un journal
--              d'exploitation qui doit survivre au compte), une
--              notification est un message ADRESSÉ : sans
--              destinataire elle n'a plus aucun sens, et la
--              conserver ne ferait que conserver du bruit. CASCADE
--              ici, SET NULL là-bas : le lien est essentiel d'un
--              côté, accessoire de l'autre.
--
-- Choix      : le type est stocké en VARCHAR sans contrainte
--              d'enum SQL. PostgreSQL sait créer des enums, mais
--              en ajouter un au projet suivant impose une
--              migration supplémentaire, alors qu'une simple
--              insertion de ligne suffit ici. L'enum Java
--              (NotificationType) fait foi côté application ; la
--              base reste un transport. Aucun écran ne fait de
--              GROUP BY sur cette colonne.
--
-- Choix      : « notifiers le manager » n'a pas de cible unique
--              dans ExpenseFlow : le modèle ne comporte AUCUNE
--              table d'équipe ni manager attitré — tout manager
--              voit toutes les notes soumises. Le service créera
--              donc une notification par utilisateur au rôle
--              MANAGER. La table reste agnostique : elle stocke un
--              destinataire par ligne, quelle que soit la règle
--              qui l'a choisi.
--
-- Rétrocompatibilité : table nouvelle, sans impact sur les
--              données existantes.
-- ============================================================

CREATE TABLE IF NOT EXISTS notifications (
    id             BIGSERIAL PRIMARY KEY,
    utilisateur_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type           VARCHAR(40) NOT NULL,
    message        TEXT NOT NULL,
    -- Cible interne de l'application (ex. /notes/177). NULLABLE :
    -- une notification peut exister sans destination cliquable
    -- (message d'information pure), et imposer NOT NULL obligerait
    -- à inventer un lien factice pour ce cas.
    lien           VARCHAR(200),
    lue            BOOLEAN NOT NULL DEFAULT FALSE,
    -- Alimentée par la base (DEFAULT NOW()), comme created_at des
    -- autres tables : l'horodatage est celui de PostgreSQL, pas
    -- celui de la JVM.
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index COMPOSITE sur (utilisateur_id, lue) : la requête courante
-- est « les notifications non lues d'un utilisateur », filtrée sur
-- les deux colonnes. Deux index séparés obligeraient le moteur à
-- combiner leurs résultats ; un seul composite sert les deux
-- sélectivités, la plus discriminante étant la première colonne.
CREATE INDEX IF NOT EXISTS idx_notifications_utilisateur_lue
    ON notifications(utilisateur_id, lue);

-- Index de tri sur la date, décroissante : la liste affiche les
-- plus récentes en premier, et un index croissant imposerait un
-- parcours inverse.
CREATE INDEX IF NOT EXISTS idx_notifications_created
    ON notifications(created_at DESC);

-- ============================================================
-- Fin de la migration V4
-- ============================================================