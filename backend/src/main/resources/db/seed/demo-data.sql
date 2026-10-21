-- ============================================================
-- ExpenseFlow - jeu de donnees de demonstration (MAROC)
--
-- A executer MANUELLEMENT, jamais automatiquement au demarrage :
--
--   docker exec -i expenseflow-postgres psql -U expenseflow -d expenseflow \
--     < backend/src/main/resources/db/seed/demo-data.sql
--
-- Pourquoi pas une migration Flyway ? Ces donnees sont un jeu d'essai
-- destine a la demonstration. Une migration s'execute sur toutes les
-- installations, y compris une base de production : elle y laisserait
-- des notes fictives. Le jeu est donc versionne comme script.
--
-- ---- IDEMPOTENCE -------------------------------------------
-- Ce script peut etre rejoue : le resultat final est identique.
-- Aucune contrainte d'unicite n'existe sur expense_requests, donc
-- ON CONFLICT n'y serait pas possible. On supprime les notes de
-- demonstration precedentes - reperees par le prefixe de leur
-- description - puis on reinsere. Les categories utilisent ON
-- CONFLICT sur leur nom, qui est UNIQUE en base.
--
-- ---- PREREQUIS ----------------------------------------------
-- Les trois comptes de demonstration doivent deja exister. Ils sont
-- resolus PAR EMAIL, jamais par un identifiant fige : un id numerique
-- depend de l'ordre d'insertion et change d'une base a l'autre.
-- Le script echoue avec un message explicite si un compte manque.
--
-- ---- MONTANTS -----------------------------------------------
-- Expreses en dirhams (MAD). Chaque note porte UNE seule ligne :
-- total_montant vaut donc exactement le montant de cette ligne.
-- ============================================================

\set ON_ERROR_STOP on

-- ------------------------------------------------------------
-- 0 bis. Comptes de demonstration
--
-- Crees ICI, et non a la main sur le serveur : c'est la seule facon
-- de garantir qu'un hachage BCrypt soit reellement compatible avec
-- le BCryptPasswordEncoder de l'application. Un hachage fabrique ou
-- recopie ailleurs donne un compte existant qui refuse TOUTE connexion,
-- sans message distinct.
--
-- Le mot de passe est Password123! pour les cinq comptes. Ce n'est
-- acceptable QUE parce que ces comptes n'existent que sur une base de
-- demonstration. En production, ces lignes ne doivent pas etre jouees.
--
-- ON CONFLICT (email) : le meme hachage pour tous, et la colonne email
-- est UNIQUE en base — l'insertion reste donc sans effet sur une base
-- ou les comptes existent deja, et ne les ecrase jamais.
--
-- Le rattachement manager (UPDATE plus bas) est separate de l'INSERT :
-- manager@ peut etre insere APRES employee@ dans le meme statement, et
-- une reference croisee dans les memes valeurs ne serait pas resolue.
--
-- ⚠ Le hachage ci-dessous est un SECRET connu de toute personne lisant
-- le depot. Il est acceptable ici parce que les comptes sont fictifs
-- et la base de demonstration publique. Il ne doit JAMAIS etre
-- reutilise sur un environnement reel.
-- ------------------------------------------------------------
INSERT INTO users (nom, email, mot_de_passe, role) VALUES
  ('Employe Demo', 'employee@expenseflow.ma',
   '$2a$10$uZ7ZXSJ.oE/h08BkkyCsXOqtn3iOeJfl2zoQ2wzNxA2VilYEZ8D2u', 'EMPLOYEE'),
  ('Manager Demo',  'manager@expenseflow.ma',
   '$2a$10$uZ7ZXSJ.oE/h08BkkyCsXOqtn3iOeJfl2zoQ2wzNxA2VilYEZ8D2u', 'MANAGER'),
  ('Finance Demo',  'finance@expenseflow.ma',
   '$2a$10$uZ7ZXSJ.oE/h08BkkyCsXOqtn3iOeJfl2zoQ2wzNxA2VilYEZ8D2u', 'FINANCE_ADMIN'),
  ('Admin Demo',  'admin@expenseflow.ma',
   '$2a$10$uZ7ZXSJ.oE/h08BkkyCsXOqtn3iOeJfl2zoQ2wzNxA2VilYEZ8D2u', 'ADMIN'),
  -- Second compte ADMIN : de secours si le mot de passe du premier est perdu.
  ('Admin Demo 2', 'admin2@expenseflow.ma',
   '$2a$10$uZ7ZXSJ.oE/h08BkkyCsXOqtn3iOeJfl2zoQ2wzNxA2VilYEZ8D2u', 'ADMIN')
ON CONFLICT (email) DO NOTHING;

-- Rattachement de l'employe de demonstration a son manager.
--
-- Indispensable depuis le filtrage par equipe : un manager ne voit que
-- les notes de SES employes, et sans ce lien la file de validation de
-- manager@expenseflow.ma serait vide — la demonstration du circuit
-- echouerait sans aucune erreur visible.
--
-- Idempotent : seul un compte encore non rattache est modifie. finance@
-- reste sans manager (la finance n'a pas de superieur dans la chaine
-- mono-niveau, elle termine le circuit).
UPDATE users SET manager_id = (SELECT id FROM users WHERE email = 'manager@expenseflow.ma')
WHERE email = 'employee@expenseflow.ma'
  AND manager_id IS NULL;

-- ------------------------------------------------------------
-- 0. Controle prealable : les trois comptes doivent exister
-- ------------------------------------------------------------
DO $$
DECLARE
  manquants TEXT;
BEGIN
  SELECT string_agg(e.email, ', ')
  INTO manquants
  FROM unnest(ARRAY[
         'employee@expenseflow.ma',
         'manager@expenseflow.ma',
         'finance@expenseflow.ma'
       ]) AS e(email)
  WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.email = e.email);

  IF manquants IS NOT NULL THEN
    RAISE EXCEPTION
      'Comptes de demonstration manquants : %. Voir README.',
      manquants;
  END IF;
END $$;

-- ------------------------------------------------------------
-- 1. Categories marocaines
--
-- Les categories deja presentes sont conservees : une note creee
-- avant ce script y reste rattachee. Les accents sont conserves :
-- ces noms s'affichent dans l'interface, et « Hotel » sans accent
-- se voit immediatement dans une application francophone.
-- ------------------------------------------------------------
INSERT INTO categories (nom) VALUES
  ('Taxi'),
  ('Restaurant'),
  ('Hôtel'),
  ('Carburant'),
  ('Fournitures de bureau'),
  ('Téléphone'),
  ('Formation'),
  ('Train'),
  ('Parking')
ON CONFLICT (nom) DO NOTHING;

-- ------------------------------------------------------------
-- 2. Purge du precedent jeu de demonstration
--
-- Le SELECT DISTINCT n'est pas cosmetique : plusieurs lignes
-- peuvent partager le meme request_id. ON DELETE CASCADE emporte
-- lignes, approvals et audit_log rattaches.
-- ------------------------------------------------------------
DELETE FROM expense_requests
WHERE id IN (
  SELECT DISTINCT l.request_id
  FROM expense_lines l
  WHERE l.description LIKE 'Démo : %'
);

-- ------------------------------------------------------------
-- 3. Notes
--
-- Une seule instruction pour les 15 notes : la liste se lit d'un
-- regard, et il n'existe pas 15 occasions de se tromper sur le
-- total. Les trois mois sont balances pour que le tableau de bord
-- affiche des chiffres non nuls.
--
-- Chaque note est ensuite identifiee par le couple unique
-- (employe, created_at) : c'est lui qui la relie a sa ligne.
-- ------------------------------------------------------------
WITH d(statut, date_soumission, total, categorie, montant, date_depense, description, cree) AS (
  VALUES
    -- ---- Octobre 2026 : le mois courant ----
    ('DRAFT',           NULL::timestamptz,        250.00, 'Taxi',                  250.00, DATE '2026-10-02', 'Démo : Taxi à l''aéroport de Casablanca', '2026-10-02 09:15:00+01'),
    ('PENDING_MANAGER',  '2026-10-04 08:30:00+01', 180.00, 'Restaurant',            180.00, DATE '2026-10-03', 'Démo : Déjeuner client à Marrakech',         '2026-10-03 19:20:00+01'),
    ('PENDING_FINANCE',  '2026-10-06 10:00:00+01', 850.00, 'Hôtel',                 850.00, DATE '2026-10-05', 'Démo : Hôtel à Rabat, deux nuits',            '2026-10-05 16:45:00+01'),
    ('REIMBURSED',       '2026-10-08 09:00:00+01', 120.00, 'Fournitures de bureau', 120.00, DATE '2026-10-07', 'Démo : Fournitures de bureau',                '2026-10-07 14:30:00+01'),
    ('PENDING_MANAGER',  '2026-10-10 11:00:00+01', 450.00, 'Carburant',             450.00, DATE '2026-10-09', 'Démo : Carburant, plein Casablanca',          '2026-10-09 18:05:00+01'),

    -- ---- Septembre 2026 ----
    ('REIMBURSED',       '2026-09-12 09:00:00+01', 200.00, 'Taxi',                  200.00, DATE '2026-09-10', 'Démo : Taxi chez le client Casa Tech',        '2026-09-10 09:00:00+01'),
    ('REIMBURSED',       '2026-09-18 10:30:00+01', 150.00, 'Restaurant',            150.00, DATE '2026-09-16', 'Démo : Déjeuner d''affaires',                 '2026-09-16 20:00:00+01'),
    ('REIMBURSED',       '2026-09-22 15:00:00+01', 700.00, 'Hôtel',                 700.00, DATE '2026-09-20', 'Démo : Hôtel à Tanger, une nuit',             '2026-09-20 10:00:00+01'),
    ('REJECTED_MANAGER', '2026-09-24 09:30:00+01', 400.00, 'Carburant',             400.00, DATE '2026-09-22', 'Démo : Carburant, trajet non professionnel',  '2026-09-22 17:30:00+01'),
    ('REIMBURSED',       '2026-09-28 16:00:00+01', 1500.00, 'Formation',            1500.00, DATE '2026-09-26', 'Démo : Formation React Native',               '2026-09-26 09:30:00+01'),

    -- ---- Août 2026 ----
    ('REIMBURSED',       '2026-08-05 10:00:00+01', 180.00, 'Taxi',                  180.00, DATE '2026-08-03', 'Démo : Taxi gare Casa Voyageurs',             '2026-08-03 08:45:00+01'),
    ('REIMBURSED',       '2026-08-11 11:30:00+01', 220.00, 'Restaurant',            220.00, DATE '2026-08-09', 'Démo : Déjeuner client Foire Tech',           '2026-08-09 19:00:00+01'),
    ('REIMBURSED',       '2026-08-15 14:00:00+01', 300.00, 'Téléphone',               300.00, DATE '2026-08-13', 'Démo : Forfait téléphonique mensuel',         '2026-08-13 12:00:00+01'),
    ('REIMBURSED',       '2026-08-20 09:00:00+01',  90.00, 'Fournitures de bureau',  90.00, DATE '2026-08-18', 'Démo : Papeterie',                           '2026-08-18 15:20:00+01'),
    ('REIMBURSED',       '2026-08-26 17:00:00+01', 380.00, 'Carburant',             380.00, DATE '2026-08-24', 'Démo : Carburant, véhicule de service',       '2026-08-24 11:00:00+01')
)
INSERT INTO expense_requests
       (employee_id, statut, date_soumission, total_montant, created_at, updated_at)
SELECT u.id, d.statut::expense_status, d.date_soumission, d.total,
       d.cree::timestamptz, d.cree::timestamptz
FROM d
CROSS JOIN users u
WHERE u.email = 'employee@expenseflow.ma';

-- ------------------------------------------------------------
-- 4. Lignes de depense
--
-- La jointure se fait sur (employee_id, created_at) plutot que sur
-- un identifiant : ce couple vient d'etre insere et il est unique
-- par note.
-- ------------------------------------------------------------
WITH d(categorie, montant, date_depense, description, cree) AS (
  VALUES
    ('Taxi',                  250.00, DATE '2026-10-02', 'Démo : Taxi à l''aéroport de Casablanca', '2026-10-02 09:15:00+01'),
    ('Restaurant',            180.00, DATE '2026-10-03', 'Démo : Déjeuner client à Marrakech',         '2026-10-03 19:20:00+01'),
    ('Hôtel',                 850.00, DATE '2026-10-05', 'Démo : Hôtel à Rabat, deux nuits',            '2026-10-05 16:45:00+01'),
    ('Fournitures de bureau', 120.00, DATE '2026-10-07', 'Démo : Fournitures de bureau',                '2026-10-07 14:30:00+01'),
    ('Carburant',             450.00, DATE '2026-10-09', 'Démo : Carburant, plein Casablanca',          '2026-10-09 18:05:00+01'),
    ('Taxi',                  200.00, DATE '2026-09-10', 'Démo : Taxi chez le client Casa Tech',        '2026-09-10 09:00:00+01'),
    ('Restaurant',            150.00, DATE '2026-09-16', 'Démo : Déjeuner d''affaires',                 '2026-09-16 20:00:00+01'),
    ('Hôtel',                 700.00, DATE '2026-09-20', 'Démo : Hôtel à Tanger, une nuit',             '2026-09-20 10:00:00+01'),
    ('Carburant',             400.00, DATE '2026-09-22', 'Démo : Carburant, trajet non professionnel',  '2026-09-22 17:30:00+01'),
    ('Formation',            1500.00, DATE '2026-09-26', 'Démo : Formation React Native',               '2026-09-26 09:30:00+01'),
    ('Taxi',                  180.00, DATE '2026-08-03', 'Démo : Taxi gare Casa Voyageurs',             '2026-08-03 08:45:00+01'),
    ('Restaurant',            220.00, DATE '2026-08-09', 'Démo : Déjeuner client Foire Tech',           '2026-08-09 19:00:00+01'),
    ('Téléphone',               300.00, DATE '2026-08-13', 'Démo : Forfait téléphonique mensuel',         '2026-08-13 12:00:00+01'),
    ('Fournitures de bureau',  90.00, DATE '2026-08-18', 'Démo : Papeterie',                           '2026-08-18 15:20:00+01'),
    ('Carburant',             380.00, DATE '2026-08-24', 'Démo : Carburant, véhicule de service',       '2026-08-24 11:00:00+01')
)
INSERT INTO expense_lines (request_id, categorie_id, montant, date_depense, description)
SELECT er.id, c.id, d.montant, d.date_depense, d.description
FROM d
JOIN users u             ON u.email = 'employee@expenseflow.ma'
JOIN expense_requests er ON er.employee_id = u.id
                         AND er.created_at    = d.cree::timestamptz
JOIN categories c        ON c.nom = d.categorie;
-- ------------------------------------------------------------
-- 3 bis. Notes des roles de validation
--
-- Le bloc precedent ne cree des notes que pour l'EMPLOYE. C'est
-- logique — un manager ou un administrateur finance valident, ils ne
-- declarent pas de depenses — mais cela rendrait leur tableau de bord
-- vide en demonstration : les quatre cartes afficheraient 0, ce qui
-- donne l'impression d'une application cassee plutot que d'un role
-- sans depense a declarer.
--
-- D'ou ces notes supplementaires. Elles sont toutes REIMBURSED, et
-- c'est une contrainte metier et non un choix cosmetique : ni un
-- MANAGER ni un FINANCE_ADMIN ne valide ses propres notes (cf.
-- ExpenseWorkflow, ou la propriete conditionne la soumission).
--
-- Les dates evitent deliberement les creneaux horaires des notes
-- de l'employe : la jointure qui relie une ligne a sa note passe par
-- le couple (employe, created_at), et deux notes creees a la meme
-- minute par deux proprietaires distincts resteraient ambigues.
-- ------------------------------------------------------------
WITH d(prop, statut, date_soumission, total, categorie, montant, date_depense, description, cree) AS (
  VALUES
    -- ---- MANAGER ----
    ('manager@expenseflow.ma', 'REIMBURSED', '2026-10-03 10:00:00+01', 180.00, 'Taxi',                  180.00, DATE '2026-10-02', 'Démo : manager - Taxi a Casa Tech',              '2026-10-02 07:20:00+01'),
    ('manager@expenseflow.ma', 'REIMBURSED', '2026-10-12 10:00:00+01', 220.00, 'Restaurant',            220.00, DATE '2026-10-10', 'Démo : manager - Dejeuner client Casa Tech',       '2026-10-10 07:25:00+01'),
    ('manager@expenseflow.ma', 'REIMBURSED', '2026-09-19 10:00:00+01', 650.00, 'Hôtel',                 650.00, DATE '2026-09-17', 'Démo : manager - Hôtel a Rabat, déplacement',    '2026-09-17 07:30:00+01'),
    ('manager@expenseflow.ma', 'REIMBURSED', '2026-09-09 10:00:00+01', 320.00, 'Carburant',             320.00, DATE '2026-09-07', 'Démo : manager - Carburant, mission Casa Tech',   '2026-09-07 07:35:00+01'),
    ('manager@expenseflow.ma', 'REIMBURSED', '2026-08-14 10:00:00+01', 150.00, 'Restaurant',            150.00, DATE '2026-08-12', 'Démo : manager - Dejeuner équipe',                '2026-08-12 07:40:00+01'),

    -- ---- FINANCE_ADMIN ----
    ('finance@expenseflow.ma', 'REIMBURSED', '2026-10-06 11:00:00+01', 200.00, 'Taxi',                  200.00, DATE '2026-10-05', 'Démo : finance - Taxi a la banque',               '2026-10-05 06:15:00+01'),
    ('finance@expenseflow.ma', 'REIMBURSED', '2026-10-15 11:00:00+01', 180.00, 'Restaurant',            180.00, DATE '2026-10-13', 'Démo : finance - Dejeuner de direction',         '2026-10-13 06:20:00+01'),
    ('finance@expenseflow.ma', 'REIMBURSED', '2026-09-23 11:00:00+01', 250.00, 'Fournitures de bureau', 250.00, DATE '2026-09-21', 'Démo : finance - Fournitures du service',         '2026-09-21 06:25:00+01'),
    ('finance@expenseflow.ma', 'REIMBURSED', '2026-09-11 11:00:00+01', 350.00, 'Téléphone',               350.00, DATE '2026-09-09', 'Démo : finance - Forfait téléphonique',           '2026-09-09 06:30:00+01'),
    ('finance@expenseflow.ma', 'REIMBURSED', '2026-08-20 11:00:00+01', 1200.00,'Formation',           1200.00, DATE '2026-08-18', 'Démo : finance - Formation certification',        '2026-08-18 06:35:00+01')
)
INSERT INTO expense_requests
       (employee_id, statut, date_soumission, total_montant, created_at, updated_at)
SELECT u.id, d.statut::expense_status, d.date_soumission::timestamptz, d.total,
       d.cree::timestamptz, d.cree::timestamptz
FROM d
JOIN users u ON u.email = d.prop;

-- Lignes de ces notes : meme jointure, proprietaire compris.
WITH d(prop, categorie, montant, date_depense, description, cree) AS (
  VALUES
    ('manager@expenseflow.ma', 'Taxi',                  180.00, DATE '2026-10-02', 'Démo : manager - Taxi a Casa Tech',            '2026-10-02 07:20:00+01'),
    ('manager@expenseflow.ma', 'Restaurant',            220.00, DATE '2026-10-10', 'Démo : manager - Dejeuner client Casa Tech',     '2026-10-10 07:25:00+01'),
    ('manager@expenseflow.ma', 'Hôtel',                 650.00, DATE '2026-09-17', 'Démo : manager - Hôtel a Rabat, déplacement',  '2026-09-17 07:30:00+01'),
    ('manager@expenseflow.ma', 'Carburant',             320.00, DATE '2026-09-07', 'Démo : manager - Carburant, mission Casa Tech', '2026-09-07 07:35:00+01'),
    ('manager@expenseflow.ma', 'Restaurant',            150.00, DATE '2026-08-12', 'Démo : manager - Dejeuner équipe',              '2026-08-12 07:40:00+01'),
    ('finance@expenseflow.ma', 'Taxi',                  200.00, DATE '2026-10-05', 'Démo : finance - Taxi a la banque',             '2026-10-05 06:15:00+01'),
    ('finance@expenseflow.ma', 'Restaurant',            180.00, DATE '2026-10-13', 'Démo : finance - Dejeuner de direction',       '2026-10-13 06:20:00+01'),
    ('finance@expenseflow.ma', 'Fournitures de bureau', 250.00, DATE '2026-09-21', 'Démo : finance - Fournitures du service',       '2026-09-21 06:25:00+01'),
    ('finance@expenseflow.ma', 'Téléphone',               350.00, DATE '2026-09-09', 'Démo : finance - Forfait téléphonique',         '2026-09-09 06:30:00+01'),
    ('finance@expenseflow.ma', 'Formation',            1200.00, DATE '2026-08-18', 'Démo : finance - Formation certification',      '2026-08-18 06:35:00+01')
)
INSERT INTO expense_lines (request_id, categorie_id, montant, date_depense, description)
SELECT er.id, c.id, d.montant, d.date_depense, d.description
FROM d
JOIN users u             ON u.email = d.prop
JOIN expense_requests er ON er.employee_id = u.id
                         AND er.created_at    = d.cree::timestamptz
JOIN categories c        ON c.nom = d.categorie;


-- ------------------------------------------------------------
-- 5. Journal d'audit
--
-- Sans ces entrees, le detail d'une note deja remboursee affiche
-- un historique vide alors que son statut l'explique : la page
-- contredirait l'etat affiche.
--
-- Seules les notes d'octobre en recoivent un : c'est le seul mois
-- dont l'historique est consulte dans une demonstration.
-- ------------------------------------------------------------
WITH d(note_creee, action, detail, auteur_email, action_le) AS (
  VALUES
    ('2026-10-03 19:20:00+01', 'SOUMISSION',         'Note soumise pour validation', 'employee@expenseflow.ma', '2026-10-04 08:30:00+01'),
    ('2026-10-05 16:45:00+01', 'SOUMISSION',         'Note soumise pour validation', 'employee@expenseflow.ma', '2026-10-06 10:00:00+01'),
    ('2026-10-05 16:45:00+01', 'VALIDATION_MANAGER', 'Note validée par le manager',  'manager@expenseflow.ma',  '2026-10-06 10:00:00+01'),
    ('2026-10-07 14:30:00+01', 'SOUMISSION',         'Note soumise pour validation', 'employee@expenseflow.ma', '2026-10-08 09:00:00+01'),
    ('2026-10-07 14:30:00+01', 'VALIDATION_MANAGER', 'Note validée par le manager',  'manager@expenseflow.ma',  '2026-10-08 09:00:00+01'),
    ('2026-10-07 14:30:00+01', 'REMBOURSEMENT',      'Remboursement effectué',       'finance@expenseflow.ma',  '2026-10-09 15:00:00+01'),
    ('2026-10-09 18:05:00+01', 'SOUMISSION',         'Note soumise pour validation', 'employee@expenseflow.ma', '2026-10-10 11:00:00+01')
)
INSERT INTO audit_log
       (entite_concernee, entite_id, action, utilisateur_id, date_action, details)
SELECT 'NOTE', er.id, d.action, u.id, d.action_le::timestamptz, d.detail
FROM d
JOIN users u             ON u.email = d.auteur_email
JOIN expense_requests er ON er.created_at = d.note_creee::timestamptz
JOIN users proprietaire  ON proprietaire.id = er.employee_id
                        AND proprietaire.email = 'employee@expenseflow.ma';

-- ------------------------------------------------------------
-- 6. Verification finale
--
-- Le total de chaque note doit valoir la somme de ses lignes. Un
-- desaccord signalerait une note dont le montant saisi et la ligne
-- ne correspondent pas - exactement l'ecart qu'affiche le
-- chatbot d'extraction quand il se trompe.
-- ------------------------------------------------------------
DO $$
DECLARE
  incoherentes INT;
BEGIN
  SELECT count(*) INTO incoherentes
  FROM (
    SELECT er.id
    FROM expense_requests er
    JOIN expense_lines l ON l.request_id = er.id
    WHERE l.description LIKE 'Démo : %'
    GROUP BY er.id, er.total_montant
    HAVING er.total_montant <> sum(l.montant)
  ) fautives;

  IF incoherentes > 0 THEN
    RAISE EXCEPTION
      '% note(s) de demonstration incoherentes : total != somme des lignes',
      incoherentes;
  END IF;
END $$;

-- ------------------------------------------------------------
-- 3. Notes
--
-- Une seule instruction pour les 15 notes : la liste se lit d'un
-- regard, et il n'existe pas 15 occasions de se tromper sur le
-- total. Les trois mois sont balances pour que le tableau de bord
-- affiche des chiffres non nuls.
--
-- Chaque note est ensuite identifiee par le couple unique
-- (employe, created_at) : c'est lui qui la relie a sa ligne.
-- ------------------------------------------------------------
WITH d(statut, date_soumission, total, categorie, montant, date_depense, description, cree) AS (
  VALUES
    -- ---- Octobre 2026 : le mois courant ----
    ('DRAFT',           NULL::timestamptz,        250.00, 'Taxi',                  250.00, DATE '2026-10-02', 'Démo : Taxi à l''aéroport de Casablanca', '2026-10-02 09:15:00+01'),
    ('PENDING_MANAGER',  '2026-10-04 08:30:00+01', 180.00, 'Restaurant',            180.00, DATE '2026-10-03', 'Démo : Déjeuner client à Marrakech',         '2026-10-03 19:20:00+01'),
    ('PENDING_FINANCE',  '2026-10-06 10:00:00+01', 850.00, 'Hôtel',                 850.00, DATE '2026-10-05', 'Démo : Hôtel à Rabat, deux nuits',            '2026-10-05 16:45:00+01'),
    ('REIMBURSED',       '2026-10-08 09:00:00+01', 120.00, 'Fournitures de bureau', 120.00, DATE '2026-10-07', 'Démo : Fournitures de bureau',                '2026-10-07 14:30:00+01'),
    ('PENDING_MANAGER',  '2026-10-10 11:00:00+01', 450.00, 'Carburant',             450.00, DATE '2026-10-09', 'Démo : Carburant, plein Casablanca',          '2026-10-09 18:05:00+01'),

    -- ---- Septembre 2026 ----
    ('REIMBURSED',       '2026-09-12 09:00:00+01', 200.00, 'Taxi',                  200.00, DATE '2026-09-10', 'Démo : Taxi chez le client Casa Tech',        '2026-09-10 09:00:00+01'),
    ('REIMBURSED',       '2026-09-18 10:30:00+01', 150.00, 'Restaurant',            150.00, DATE '2026-09-16', 'Démo : Déjeuner d''affaires',                 '2026-09-16 20:00:00+01'),
    ('REIMBURSED',       '2026-09-22 15:00:00+01', 700.00, 'Hôtel',                 700.00, DATE '2026-09-20', 'Démo : Hôtel à Tanger, une nuit',             '2026-09-20 10:00:00+01'),
    ('REJECTED_MANAGER', '2026-09-24 09:30:00+01', 400.00, 'Carburant',             400.00, DATE '2026-09-22', 'Démo : Carburant, trajet non professionnel',  '2026-09-22 17:30:00+01'),
    ('REIMBURSED',       '2026-09-28 16:00:00+01', 1500.00, 'Formation',            1500.00, DATE '2026-09-26', 'Démo : Formation React Native',               '2026-09-26 09:30:00+01'),

    -- ---- Août 2026 ----
    ('REIMBURSED',       '2026-08-05 10:00:00+01', 180.00, 'Taxi',                  180.00, DATE '2026-08-03', 'Démo : Taxi gare Casa Voyageurs',             '2026-08-03 08:45:00+01'),
    ('REIMBURSED',       '2026-08-11 11:30:00+01', 220.00, 'Restaurant',            220.00, DATE '2026-08-09', 'Démo : Déjeuner client Foire Tech',           '2026-08-09 19:00:00+01'),
    ('REIMBURSED',       '2026-08-15 14:00:00+01', 300.00, 'Téléphone',               300.00, DATE '2026-08-13', 'Démo : Forfait téléphonique mensuel',         '2026-08-13 12:00:00+01'),
    ('REIMBURSED',       '2026-08-20 09:00:00+01',  90.00, 'Fournitures de bureau',  90.00, DATE '2026-08-18', 'Démo : Papeterie',                           '2026-08-18 15:20:00+01'),
    ('REIMBURSED',       '2026-08-26 17:00:00+01', 380.00, 'Carburant',             380.00, DATE '2026-08-24', 'Démo : Carburant, véhicule de service',       '2026-08-24 11:00:00+01')
)
INSERT INTO expense_requests
       (employee_id, statut, date_soumission, total_montant, created_at, updated_at)
SELECT u.id, d.statut::expense_status, d.date_soumission, d.total,
       d.cree::timestamptz, d.cree::timestamptz
FROM d
CROSS JOIN users u
WHERE u.email = 'employee@expenseflow.ma';
