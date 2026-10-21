-- ============================================================
-- V6 : mot de passe a changer a la premiere connexion.
--
-- Un compte cree par un administrateur porte un mot de passe
-- provisoire que l'administrateur communique hors ligne. La
-- colonne doit_changer_mdp marque qu'il doit etre remplace des
-- la premiere connexion : AdminUserService.createUser naissant
-- a true, UserProfileService.changePassword le repasse a false.
--
-- DEFAULT FALSE : les comptes preexistants (et ceux du seed) sont
-- censes avoir deja un mot de passe definitif. Seuls les comptes
-- crees via l'interface d'administration naissent a true.
-- ============================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS doit_changer_mdp BOOLEAN NOT NULL DEFAULT FALSE;
