-- ============================================================
-- Projet     : ExpenseFlow
-- Fichier    : V1__init_schema.sql
-- Date       : 2026-09-27
-- Description: Migration initiale Flyway — création du schéma
--              de base (enums + 7 tables) pour la gestion des
--              notes de frais (users, categories, expense_requests,
--              expense_lines, approvals, budget_limits, audit_log).
-- ============================================================

-- ============================================================
-- ENUMS
-- ============================================================

DROP TYPE IF EXISTS user_role CASCADE;
CREATE TYPE user_role AS ENUM (
    'EMPLOYEE',
    'MANAGER',
    'FINANCE_ADMIN'
);

DROP TYPE IF EXISTS expense_status CASCADE;
CREATE TYPE expense_status AS ENUM (
    'DRAFT',
    'SUBMITTED',
    'PENDING_MANAGER',
    'APPROVED_MANAGER',
    'REJECTED_MANAGER',
    'PENDING_FINANCE',
    'REIMBURSED',
    'REJECTED_FINANCE'
);

DROP TYPE IF EXISTS approval_status CASCADE;
CREATE TYPE approval_status AS ENUM (
    'APPROVED_MANAGER',
    'REJECTED_MANAGER',
    'APPROVED_FINANCE',
    'REJECTED_FINANCE'
);

-- ============================================================
-- TABLE : users
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    nom           VARCHAR(150) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    mot_de_passe  VARCHAR(255) NOT NULL,
    role          user_role NOT NULL,
    manager_id    BIGINT NULL REFERENCES users(id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- TABLE : categories
-- ============================================================

CREATE TABLE IF NOT EXISTS categories (
    id          BIGSERIAL PRIMARY KEY,
    nom         VARCHAR(100) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- TABLE : expense_requests
-- ============================================================

CREATE TABLE IF NOT EXISTS expense_requests (
    id              BIGSERIAL PRIMARY KEY,
    employee_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    statut          expense_status NOT NULL DEFAULT 'DRAFT',
    date_soumission TIMESTAMPTZ NULL,
    total_montant   NUMERIC(10,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- TABLE : expense_lines
-- ============================================================

CREATE TABLE IF NOT EXISTS expense_lines (
    id                BIGSERIAL PRIMARY KEY,
    request_id        BIGINT NOT NULL REFERENCES expense_requests(id) ON DELETE CASCADE,
    categorie_id      BIGINT NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    montant           NUMERIC(10,2) NOT NULL CHECK (montant > 0),
    date_depense      DATE NOT NULL,
    description       TEXT NULL,
    justificatif_url  TEXT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- TABLE : approvals
-- ============================================================

CREATE TABLE IF NOT EXISTS approvals (
    id             BIGSERIAL PRIMARY KEY,
    request_id     BIGINT NOT NULL REFERENCES expense_requests(id) ON DELETE CASCADE,
    validateur_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    statut         approval_status NOT NULL,
    commentaire    TEXT NULL,
    date_action    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- TABLE : budget_limits
-- ============================================================

CREATE TABLE IF NOT EXISTS budget_limits (
    id            BIGSERIAL PRIMARY KEY,
    categorie_id  BIGINT NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    role          user_role NOT NULL,
    plafond       NUMERIC(10,2) NOT NULL CHECK (plafond > 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (categorie_id, role)
);

-- ============================================================
-- TABLE : audit_log
-- ============================================================

CREATE TABLE IF NOT EXISTS audit_log (
    id                BIGSERIAL PRIMARY KEY,
    entite_concernee  VARCHAR(100) NOT NULL,
    action            VARCHAR(100) NOT NULL,
    utilisateur_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    date_action       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    details           TEXT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- INDEX
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_expense_requests_employee_id ON expense_requests(employee_id);
CREATE INDEX IF NOT EXISTS idx_expense_requests_statut      ON expense_requests(statut);
CREATE INDEX IF NOT EXISTS idx_expense_lines_request_id     ON expense_lines(request_id);
CREATE INDEX IF NOT EXISTS idx_approvals_request_id         ON approvals(request_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_utilisateur_id     ON audit_log(utilisateur_id);

-- ============================================================
-- Fin de la migration V1 — schéma initial ExpenseFlow
-- ============================================================
