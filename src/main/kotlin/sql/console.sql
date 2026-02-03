-- Enable pgcrypto extension for UUID generation
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- (NEW) 0. Компании: многоарендность
CREATE TABLE IF NOT EXISTS companies (
                                         id          SERIAL PRIMARY KEY,
                                         name        TEXT   NOT NULL UNIQUE,
                                         invite_code VARCHAR(16) NOT NULL UNIQUE DEFAULT substr(md5(random()::text),1,16),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
    );

-- 1. Таблица пользователей: хранит данные сотрудников
CREATE TABLE users (
                       id               SERIAL PRIMARY KEY,                         -- Уникальный идентификатор (PK)
                       employee_number  UUID       NOT NULL DEFAULT gen_random_uuid(), -- Уникальный служебный номер сотрудника
                       first_name       VARCHAR(100) NOT NULL,                      -- Имя сотрудника
                       last_name        VARCHAR(100) NOT NULL,                      -- Фамилия сотрудника
                       birth_date       DATE       NOT NULL,                        -- Дата рождения сотрудника
                       email            VARCHAR(255) NOT NULL UNIQUE,               -- Логин (email), должен быть уникальным
                       password         VARCHAR(60)  NOT NULL,                      -- Хэш пароля (bcrypt)
                       created_at       TIMESTAMP WITH TIME ZONE DEFAULT now()      -- Время создания записи
);

ALTER TABLE users
    ADD COLUMN company_id       INTEGER NULL REFERENCES companies(id) ON DELETE RESTRICT,
  ADD COLUMN is_company_admin BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN is_global_admin  BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_users_company
    ON users(company_id);

-- (NEW) Триггер: первый пользователь компании становится админом
CREATE OR REPLACE FUNCTION assign_first_admin() RETURNS trigger AS $$
BEGIN
  IF (SELECT COUNT(*) FROM users
      WHERE company_id = NEW.company_id
        AND is_company_admin) = 0
  THEN
    NEW.is_company_admin := TRUE;
END IF;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_assign_first_admin
    BEFORE INSERT ON users
    FOR EACH ROW
    EXECUTE FUNCTION assign_first_admin();

-- 2. Таблица одноразовых nonces: временные токены терминала
CREATE TABLE nonces (
                        nonce      VARCHAR(64) PRIMARY KEY,                          -- Значение токена (одноразовый nonce)
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()   -- Время генерации (для контроля срока жизни)
);

-- 3. Таблица логов прихода/ухода: фиксирует события
CREATE TABLE logs (
                      id             SERIAL PRIMARY KEY,                          -- Уникальный идентификатор записи лога
                      user_id        INTEGER NOT NULL
                          REFERENCES users(id) ON DELETE CASCADE,        -- Ссылка на сотрудника (FK)
                      terminal_nonce VARCHAR(64) NOT NULL,                        -- Отсканированный одноразовый токен
                      action         VARCHAR(10) NOT NULL
                          CHECK(action IN ('in','out')),                -- Тип события: 'in' или 'out'
  timestamp      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now() -- Время фиксации события
);

-- Индекс для ускорения выборок логов по сотруднику и времени
CREATE INDEX idx_logs_user_ts ON logs(user_id, timestamp);

-- 4. Добавляем контактный телефон сотрудника
ALTER TABLE users
    ADD COLUMN phone VARCHAR(20);  -- телефонный номер сотрудника





ALTER TABLE users
    ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT FALSE;


ALTER TABLE users
    ALTER COLUMN first_name DROP NOT NULL,
ALTER COLUMN last_name  DROP NOT NULL,
    ALTER COLUMN email      DROP NOT NULL,
    ALTER COLUMN password   DROP NOT NULL,
    ALTER COLUMN birth_date DROP NOT NULL;


ALTER TABLE users

    ADD COLUMN avatar_url VARCHAR(255);

ALTER TABLE users
    ALTER COLUMN  avatar_url DROP  NOT NULL;



ALTER TABLE nonces
    ADD COLUMN used BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE logs
    ADD CONSTRAINT fk_nonce
        FOREIGN KEY (terminal_nonce)
            REFERENCES nonces(nonce)
            ON DELETE RESTRICT;


ALTER TABLE logs
-- сохраняем обе версии: координаты и текстовый адрес
    ADD COLUMN latitude  DOUBLE PRECISION,
    ADD COLUMN longitude DOUBLE PRECISION,
    ADD COLUMN location_description TEXT;
ALTER TABLE nonces
    ADD COLUMN user_id   INTEGER NOT NULL REFERENCES users(id);

ALTER TABLE nonces
    ADD COLUMN work_date DATE    NOT NULL DEFAULT CURRENT_DATE;



ALTER TABLE nonces
    ADD COLUMN action VARCHAR(10) NOT NULL DEFAULT 'in';

ALTER TABLE nonces
DROP COLUMN action,
    ADD COLUMN action VARCHAR(10) NULL;


CREATE TABLE pause_sessions (
                                id           SERIAL PRIMARY KEY,
                                user_id      INTEGER NOT NULL
                                    REFERENCES users(id) ON DELETE CASCADE,
                                started_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                                ended_at     TIMESTAMP WITH TIME ZONE,
                                is_active    BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_pause_sessions_user_active
    ON pause_sessions(user_id, is_active);



CREATE TABLE proofs (
                        id            SERIAL PRIMARY KEY,
                        user_id       INTEGER NOT NULL
                            REFERENCES users(id) ON DELETE CASCADE,
                        latitude      DOUBLE PRECISION NOT NULL,
                        longitude     DOUBLE PRECISION NOT NULL,
                        radius        INTEGER NOT NULL DEFAULT 100,        -- метрическая зона проверки
                        date          DATE    NOT NULL DEFAULT CURRENT_DATE,
                        slot          SMALLINT NOT NULL CHECK (slot IN (1,2)),
                        sent_at       TIMESTAMP WITH TIME ZONE,
                        responded     BOOLEAN NOT NULL DEFAULT FALSE,
                        responded_at  TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_proofs_user_date_slot
    ON proofs(user_id, date, slot);




CREATE TABLE device_tokens (
                               id SERIAL PRIMARY KEY,
                               user_id INTEGER NOT NULL
                                   REFERENCES users(id) ON DELETE CASCADE,
                               platform VARCHAR(16) NOT NULL CHECK (platform IN ('ios','android')),
                               token TEXT NOT NULL,
                               created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                               UNIQUE(user_id, platform)
);

-- Если ещё не включили расширение pgcrypto для UUID:
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Таблица для хранения SMS/email-кодов сброса пароля
CREATE TABLE IF NOT EXISTS password_reset_tokens (
                                                     id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone VARCHAR(20)    NOT NULL,
    code VARCHAR(6)      NOT NULL,
    expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
    );

-- (NEW) Таблица кодов-приглашений для регистрации пользователей и админов
CREATE TABLE IF NOT EXISTS invitation_codes (
                                                id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id   INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    code         VARCHAR(16) NOT NULL UNIQUE,
    role         VARCHAR(20) NOT NULL CHECK(role IN ('user','admin')),
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
                                                               usage_limit  INTEGER NOT NULL DEFAULT 1,
                                                               used_count   INTEGER NOT NULL DEFAULT 0
                                                               );
CREATE INDEX IF NOT EXISTS idx_invitation_codes_company
    ON invitation_codes(company_id);

-- 1) Переименовать старое поле phone в destination
ALTER TABLE password_reset_tokens
    RENAME COLUMN phone TO destination;

-- 2) Добавить поле user_id (изначально nullable, чтобы не рушить старые записи)
ALTER TABLE password_reset_tokens
    ADD COLUMN user_id INTEGER;

-- 3) Добавить поле channel для указания способа доставки
ALTER TABLE password_reset_tokens
    ADD COLUMN channel VARCHAR(10);

-- 4) Привязать user_id к users(id)
ALTER TABLE password_reset_tokens
    ADD CONSTRAINT fk_prt_user
        FOREIGN KEY (user_id)
            REFERENCES users(id)
            ON DELETE CASCADE;

-- 5) Задать NOT NULL и дефолты (если в новых записях вы уже будете их заполнять)
ALTER TABLE password_reset_tokens
    ALTER COLUMN user_id    SET NOT NULL,
ALTER COLUMN channel    SET NOT NULL,
    ALTER COLUMN channel    SET DEFAULT 'sms';

-- 6) По желанию, если хотите, чтобы destination был NOT NULL
ALTER TABLE password_reset_tokens
    ALTER COLUMN destination SET NOT NULL;


SELECT now();
SHOW timezone;
SELECT current_database() AS db;

ALTER DATABASE attendance
    SET timezone = 'Europe/Berlin';

SELECT current_database() AS db;


SELECT
    name,
    setting,
    source,
    context
FROM pg_settings
WHERE name = 'TimeZone';

SELECT current_user;

ALTER ROLE yuliyanatasheva
SET TimeZone = 'Europe/Berlin';

SHOW TimeZone;  -- должно вернуть Europe/Berlin
SELECT now();   -- должно показать время с +02:00

SELECT sent_at AT TIME ZONE 'Europe/Berlin'
FROM proofs
         LIMIT 5;




DO $$
DECLARE
comp_id INTEGER;
BEGIN
  -- 1) Ensure a default company exists
SELECT id INTO comp_id
FROM companies
WHERE name = 'Default Company';
IF NOT FOUND THEN
    INSERT INTO companies(name)
    VALUES('Default Company')
    RETURNING id INTO comp_id;
END IF;

  -- 2) Backfill existing users with the default company
UPDATE users
SET company_id = comp_id
WHERE company_id IS NULL;

-- 3) Now allow company_id to be nullable
ALTER TABLE users
    ALTER COLUMN company_id DROP NOT NULL;
END;
$$ LANGUAGE plpgsql;

-- 4) Create index on company_id if missing
CREATE INDEX IF NOT EXISTS idx_users_company
    ON users(company_id);

ALTER TABLE users
    ADD COLUMN is_company_admin BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN is_global_admin  BOOLEAN NOT NULL DEFAULT FALSE;


ALTER TABLE users
    ALTER COLUMN company_id DROP NOT NULL;

-- === Hardening: unique roles & identities ===
-- Ensure at most one admin per company
CREATE UNIQUE INDEX IF NOT EXISTS uq_company_admin
    ON users(company_id)
    WHERE is_company_admin = TRUE;

-- Ensure employee_number is globally unique
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_employee_number
    ON users(employee_number);

-- Case-insensitive uniqueness for emails (if CITEXT is not used)
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower
    ON users (lower(email));

-- === Projects & Membership (v1) =============================================

-- 1) Projects table per company
CREATE TABLE IF NOT EXISTS projects (
                                        id          SERIAL PRIMARY KEY,
                                        company_id  INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    title       VARCHAR(120) NOT NULL,
    description TEXT,
    location    TEXT,
    lat         DOUBLE PRECISION,
    lng         DOUBLE PRECISION,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT projects_title_unique_per_company UNIQUE (company_id, title),
    CONSTRAINT projects_lat_check CHECK (lat IS NULL OR (lat >= -90 AND lat <= 90)),
    CONSTRAINT projects_lng_check CHECK (lng IS NULL OR (lng >= -180 AND lng <= 180))
    );

CREATE INDEX IF NOT EXISTS idx_projects_company_id ON projects(company_id);

-- Keep updated_at in sync
CREATE OR REPLACE FUNCTION projects_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_trigger WHERE tgname = 'trg_projects_updated_at'
  ) THEN
CREATE TRIGGER trg_projects_updated_at
    BEFORE UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION projects_set_updated_at();
END IF;
END;
$$;

-- 2) Project members (many-to-many users↔projects), with simple role
CREATE TABLE IF NOT EXISTS project_members (
                                               project_id  INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id     INTEGER NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    role        SMALLINT NOT NULL DEFAULT 0,   -- 0=member, 1=manager (на будущее)
    joined_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
    );

CREATE INDEX IF NOT EXISTS idx_project_members_user_id ON project_members(user_id);

-- (Опционально, но очень полезно) Гарантия: пользователь и проект одной компании
CREATE OR REPLACE FUNCTION ensure_same_company_project_members()
RETURNS TRIGGER AS $$
DECLARE
proj_company  INTEGER;
  user_company  INTEGER;
BEGIN
SELECT company_id INTO proj_company FROM projects WHERE id = NEW.project_id;
SELECT company_id INTO user_company FROM users    WHERE id = NEW.user_id;
IF proj_company IS NULL OR user_company IS NULL OR proj_company <> user_company THEN
    RAISE EXCEPTION 'User and project must belong to the same company';
END IF;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_trigger WHERE tgname = 'trg_project_members_same_company'
  ) THEN
CREATE TRIGGER trg_project_members_same_company
    BEFORE INSERT OR UPDATE ON project_members
                         FOR EACH ROW EXECUTE FUNCTION ensure_same_company_project_members();
END IF;
END;
$$;

-- 3) Link logs with projects for reporting (nullable)
ALTER TABLE logs
    ADD COLUMN IF NOT EXISTS project_id INTEGER;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.table_constraints
    WHERE table_name = 'logs' AND constraint_name = 'fk_logs_project'
  ) THEN
ALTER TABLE logs
    ADD CONSTRAINT fk_logs_project
        FOREIGN KEY (project_id)
            REFERENCES projects(id)
            ON DELETE SET NULL;
END IF;
END;
$$;

CREATE INDEX IF NOT EXISTS idx_logs_project_id ON logs(project_id);
-- === End Projects & Membership ==============================================



UPDATE logs
SET project_id = 8
WHERE project_id IS NULL;



-- === Work Photos ============================================================
-- Фото ДО/ПОСЛЕ, загруженные сотрудниками для проектов компании

CREATE TABLE IF NOT EXISTS work_photos (
                                           id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    company_id   INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    project_id   INTEGER NOT NULL REFERENCES projects(id)  ON DELETE CASCADE,
    user_id      INTEGER NOT NULL REFERENCES users(id)     ON DELETE CASCADE,

    -- Маркеры и метаданные
    type         VARCHAR(10) NOT NULL CHECK (type IN ('BEFORE','AFTER')),  -- ДО/ПОСЛЕ
    caption      TEXT,
    status       SMALLINT NOT NULL DEFAULT 0 CHECK (status IN (0,1,2)),    -- 0=pending, 1=approved, 2=rejected

-- Где лежит файл (ключ в S3/MinIO или путь на диске) и, при необходимости, отдаваемый URL
    storage_key  TEXT NOT NULL,           -- напр. "projects/42/8dbf...c6.jpg"
    url          TEXT,                    -- публичный/подписанный URL (может генериться на лету — поле опционально)

-- Технические поля
    width        INTEGER,
    height       INTEGER,
    size_bytes   BIGINT,
    checksum_md5 BYTEA,                   -- опционально: md5/sha256 содержимого

    taken_at     TIMESTAMPTZ,             -- когда сделано фото (клиент может прислать)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),  -- когда запись создана на сервере

    approved_by  INTEGER REFERENCES users(id) ON DELETE SET NULL,
    approved_at  TIMESTAMPTZ,
    rejected_reason TEXT
    );

-- Индексы под частые выборки
CREATE INDEX IF NOT EXISTS idx_work_photos_company   ON work_photos(company_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_work_photos_project   ON work_photos(project_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_work_photos_user      ON work_photos(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_work_photos_type      ON work_photos(project_id, type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_work_photos_status    ON work_photos(status);

-- Триггер: подтягиваем company_id из проекта и проверяем, что user и project из одной компании
CREATE OR REPLACE FUNCTION work_photos_set_company_and_validate()
    RETURNS TRIGGER AS $$
DECLARE
proj_company  INTEGER;
    user_company  INTEGER;
BEGIN
SELECT company_id INTO proj_company FROM projects WHERE id = NEW.project_id;
IF proj_company IS NULL THEN
        RAISE EXCEPTION 'Project % not found or has no company', NEW.project_id;
END IF;

SELECT company_id INTO user_company FROM users WHERE id = NEW.user_id;
IF user_company IS NULL OR user_company <> proj_company THEN
        RAISE EXCEPTION 'User % and project % must belong to the same company', NEW.user_id, NEW.project_id;
END IF;

    -- Если кто-то не передал company_id — выставим автоматически.
    NEW.company_id := proj_company;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
        IF NOT EXISTS (
            SELECT 1 FROM pg_trigger WHERE tgname = 'trg_work_photos_company_validate'
        ) THEN
CREATE TRIGGER trg_work_photos_company_validate
    BEFORE INSERT OR UPDATE ON work_photos
                         FOR EACH ROW EXECUTE FUNCTION work_photos_set_company_and_validate();
END IF;
END;
$$;
-- === End Work Photos ========================================================


SELECT datname FROM pg_database WHERE datistemplate = false;



-- 0) На всякий: расширение для UUID/crypto
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1) Компания: лимит мест + режим учёта мест
ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS max_seats INTEGER NOT NULL DEFAULT 5,
    ADD COLUMN IF NOT EXISTS seat_mode  TEXT    NOT NULL DEFAULT 'auto'; -- 'auto' или 'manual'

-- 2) Пользователь: активность (чтоб считать только активных)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;

-- Индекс под частые выборки активных сотрудников компании
CREATE INDEX IF NOT EXISTS idx_users_company_active
    ON users(company_id)
    WHERE is_active;

-- 3) Подписка компании (текущее состояние)
--    Если хочешь хранить историю позже — сделаем отдельную таблицу событий.
CREATE TABLE IF NOT EXISTS subscriptions (
                                             id                       SERIAL PRIMARY KEY,
                                             company_id               INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    stripe_customer_id       TEXT,
    stripe_subscription_id   TEXT,
    price_id                 TEXT,
    status                   TEXT,                -- raw из Stripe: active, trialing, past_due, canceled, incomplete, unpaid...
    current_period_end       TIMESTAMPTZ,         -- конец оплаченного периода
    unpaid_since             TIMESTAMPTZ,         -- когда впервые стали "не оплачены" для grace-политики
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_subscriptions_company UNIQUE (company_id)
    );

-- Обновляем updated_at автоматически
CREATE OR REPLACE FUNCTION subscriptions_set_updated_at()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_subscriptions_updated_at') THEN
CREATE TRIGGER trg_subscriptions_updated_at
    BEFORE UPDATE ON subscriptions
    FOR EACH ROW EXECUTE FUNCTION subscriptions_set_updated_at();
END IF;
END;
$$;

-- Ускорим поиски по статусу/компании
CREATE INDEX IF NOT EXISTS idx_subscriptions_company_status
    ON subscriptions(company_id, status);

-- 4) Вьюха: сводка по местам в компании (использовано/лимит)
CREATE OR REPLACE VIEW v_company_seats AS
SELECT
    c.id           AS company_id,
    c.name         AS company_name,
    c.max_seats    AS seats_limit,
    COUNT(u.*) FILTER (WHERE u.is_active) AS seats_used
FROM companies c
         LEFT JOIN users u ON u.company_id = c.id
GROUP BY c.id, c.name, c.max_seats;

-- 5) Вьюха: биллинг + расчет paid/unpaid (с 72ч grace)
-- paid_effective = TRUE, если статус оплаченный ИЛИ мы в "grace" окне.
CREATE OR REPLACE VIEW v_company_billing AS
WITH sub AS (
    SELECT
        s.company_id,
        s.status,
        s.price_id,
        s.current_period_end,
        s.unpaid_since,
        CASE
            WHEN s.status IN ('active','trialing')
                AND s.current_period_end IS NOT NULL
                AND now() <= s.current_period_end
                THEN TRUE
            ELSE FALSE
            END AS is_paid_now,
        CASE
            WHEN (s.status IS NULL OR s.status NOT IN ('active','trialing'))
                AND s.unpaid_since IS NOT NULL
                THEN s.unpaid_since + INTERVAL '72 hours'
            ELSE NULL
            END AS grace_until
    FROM subscriptions s
)
SELECT
    c.id   AS company_id,
    c.name AS company_name,
    sub.status,
    sub.price_id,
    sub.current_period_end,
    sub.unpaid_since,
    sub.grace_until,
    -- эффективный доступ: оплачен сейчас или ещё действует grace
    (COALESCE(sub.is_paid_now, FALSE)
        OR (sub.grace_until IS NOT NULL AND now() <= sub.grace_until)) AS paid_effective,
    -- человекочитаемая причина, если доступ закрыт
    CASE
        WHEN (COALESCE(sub.is_paid_now, FALSE)
            OR (sub.grace_until IS NOT NULL AND now() <= sub.grace_until))
            THEN NULL
        ELSE COALESCE(sub.status, 'no_subscription')
        END AS unpaid_reason
FROM companies c
         LEFT JOIN sub ON sub.company_id = c.id;

-- 6) Сводная вьюха: права + места
CREATE OR REPLACE VIEW v_company_entitlements AS
SELECT
    b.company_id,
    b.company_name,
    b.paid_effective AS paid,
    b.unpaid_reason  AS reason,
    b.grace_until,
    b.price_id,
    s.seats_used,
    s.seats_limit
FROM v_company_billing b
         JOIN v_company_seats   s ON s.company_id = b.company_id;

-- 7) (Опционально) Жёсткое ограничение мест на уровне БД
--    Блокирует включение/перевод пользователя в active, если лимит исчерпан.
--    Если пока не нужно — этот блок можно не выполнять.
CREATE OR REPLACE FUNCTION enforce_seat_limit()
    RETURNS TRIGGER AS $$
DECLARE
limit_i  INTEGER;
    used_i   INTEGER;
    comp_i   INTEGER;
BEGIN
    comp_i := COALESCE(NEW.company_id, OLD.company_id);
    IF comp_i IS NULL THEN
        RETURN NEW;
END IF;

    -- интересует только включение/перевод в active
    IF TG_OP IN ('INSERT','UPDATE') AND NEW.is_active = TRUE THEN
SELECT max_seats INTO limit_i FROM companies WHERE id = comp_i;
SELECT COUNT(*) INTO used_i FROM users WHERE company_id = comp_i AND is_active = TRUE;

-- Если это INSERT, used_i уже учитывает NEW; если UPDATE с FALSE->TRUE, то тоже.
-- Разрешим, если хватает мест.
IF used_i > limit_i THEN
            RAISE EXCEPTION 'Seat limit exceeded for company %: used %, limit %', comp_i, used_i, limit_i
                USING ERRCODE = 'check_violation';
END IF;
END IF;

RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_enforce_seat_limit_insert') THEN
CREATE TRIGGER trg_enforce_seat_limit_insert
    BEFORE INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION enforce_seat_limit();
END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_enforce_seat_limit_update') THEN
CREATE TRIGGER trg_enforce_seat_limit_update
    BEFORE UPDATE OF company_id, is_active ON users
    FOR EACH ROW EXECUTE FUNCTION enforce_seat_limit();
END IF;
END;
$$;

SELECT * FROM v_company_entitlements ORDER BY company_id;


-- === Real-time Tracking (Admin ↔ User) =====================================
-- Goal:
-- 1) User (employee) starts/stops a tracking session during work.
-- 2) User app sends location points to backend.
-- 3) Admin app subscribes to a session (WS) and sees points in real time.
-- 4) (Optional) Store Live Activity push token if you later decide to update Live Activity remotely.

-- Ensure UUID support
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1) Tracking sessions (one active session per user is recommended)
CREATE TABLE IF NOT EXISTS tracking_sessions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    company_id   INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at     TIMESTAMPTZ,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,

    -- Optional metadata
    started_by_admin_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    stop_reason         TEXT
);

-- Fast lookups
CREATE INDEX IF NOT EXISTS idx_tracking_sessions_company_active
    ON tracking_sessions(company_id, is_active, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_tracking_sessions_user_active
    ON tracking_sessions(user_id, is_active);

-- Enforce: at most one active session per user
CREATE UNIQUE INDEX IF NOT EXISTS uq_tracking_sessions_one_active_per_user
    ON tracking_sessions(user_id)
    WHERE is_active = TRUE;

-- Enforce: user must belong to the same company as the session
CREATE OR REPLACE FUNCTION ensure_same_company_tracking_session()
RETURNS TRIGGER AS $$
DECLARE
    user_company INTEGER;
BEGIN
    SELECT company_id INTO user_company FROM users WHERE id = NEW.user_id;
    IF user_company IS NULL OR user_company <> NEW.company_id THEN
        RAISE EXCEPTION 'User % must belong to the same company %', NEW.user_id, NEW.company_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'trg_tracking_sessions_same_company'
    ) THEN
        CREATE TRIGGER trg_tracking_sessions_same_company
            BEFORE INSERT OR UPDATE ON tracking_sessions
            FOR EACH ROW EXECUTE FUNCTION ensure_same_company_tracking_session();
    END IF;
END;
$$;

-- 2) Location points streamed from user app
-- Keep it lean: you can store only recent history if desired.
CREATE TABLE IF NOT EXISTS tracking_points (
    id          BIGSERIAL PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES tracking_sessions(id) ON DELETE CASCADE,

    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    speed_mps   REAL,
    heading_deg REAL,

    -- Timestamp from device (preferred) or server time fallback
    ts          TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT tracking_points_lat_check CHECK (latitude >= -90 AND latitude <= 90),
    CONSTRAINT tracking_points_lng_check CHECK (longitude >= -180 AND longitude <= 180)
);

CREATE INDEX IF NOT EXISTS idx_tracking_points_session_ts
    ON tracking_points(session_id, ts DESC);

-- 3) Admin watch/audit log (who watched whom and when)
CREATE TABLE IF NOT EXISTS admin_watch_logs (
    id            BIGSERIAL PRIMARY KEY,
    company_id    INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,

    admin_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id    UUID NOT NULL REFERENCES tracking_sessions(id) ON DELETE CASCADE,

    opened_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at     TIMESTAMPTZ,
    reason        TEXT
);

CREATE INDEX IF NOT EXISTS idx_admin_watch_logs_company_opened
    ON admin_watch_logs(company_id, opened_at DESC);

CREATE INDEX IF NOT EXISTS idx_admin_watch_logs_session
    ON admin_watch_logs(session_id, opened_at DESC);

-- Enforce: admin must belong to the same company
CREATE OR REPLACE FUNCTION ensure_same_company_admin_watch()
RETURNS TRIGGER AS $$
DECLARE
    admin_company INTEGER;
    sess_company  INTEGER;
BEGIN
    SELECT company_id INTO admin_company FROM users WHERE id = NEW.admin_user_id;
    SELECT company_id INTO sess_company  FROM tracking_sessions WHERE id = NEW.session_id;

    IF admin_company IS NULL OR sess_company IS NULL OR admin_company <> sess_company THEN
        RAISE EXCEPTION 'Admin % and session % must belong to the same company', NEW.admin_user_id, NEW.session_id;
    END IF;

    NEW.company_id := sess_company;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'trg_admin_watch_logs_same_company'
    ) THEN
        CREATE TRIGGER trg_admin_watch_logs_same_company
            BEFORE INSERT OR UPDATE ON admin_watch_logs
            FOR EACH ROW EXECUTE FUNCTION ensure_same_company_admin_watch();
    END IF;
END;
$$;

-- 4) (Optional) Live Activity push tokens (only if you later want server-driven updates)
-- For your current plan (local updates on the same device), you may NOT need this.
CREATE TABLE IF NOT EXISTS live_activity_tokens (
    id           BIGSERIAL PRIMARY KEY,
    company_id   INTEGER NOT NULL REFERENCES companies(id) ON DELETE CASCADE,

    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id   UUID NOT NULL REFERENCES tracking_sessions(id) ON DELETE CASCADE,

    activity_id  TEXT NOT NULL,
    token_hex    TEXT NOT NULL,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_live_activity_token UNIQUE (activity_id)
);

CREATE INDEX IF NOT EXISTS idx_live_activity_tokens_session
    ON live_activity_tokens(session_id, updated_at DESC);

-- Keep updated_at in sync for token refreshes
CREATE OR REPLACE FUNCTION live_activity_tokens_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'trg_live_activity_tokens_updated_at'
    ) THEN
        CREATE TRIGGER trg_live_activity_tokens_updated_at
            BEFORE UPDATE ON live_activity_tokens
            FOR EACH ROW EXECUTE FUNCTION live_activity_tokens_set_updated_at();
    END IF;
END;
$$;

-- Enforce: token owner must belong to the same company as the session
CREATE OR REPLACE FUNCTION ensure_same_company_live_activity_token()
RETURNS TRIGGER AS $$
DECLARE
    user_company INTEGER;
    sess_company INTEGER;
BEGIN
    SELECT company_id INTO user_company FROM users WHERE id = NEW.user_id;
    SELECT company_id INTO sess_company FROM tracking_sessions WHERE id = NEW.session_id;

    IF user_company IS NULL OR sess_company IS NULL OR user_company <> sess_company THEN
        RAISE EXCEPTION 'User % and session % must belong to the same company', NEW.user_id, NEW.session_id;
    END IF;

    NEW.company_id := sess_company;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'trg_live_activity_tokens_same_company'
    ) THEN
        CREATE TRIGGER trg_live_activity_tokens_same_company
            BEFORE INSERT OR UPDATE ON live_activity_tokens
            FOR EACH ROW EXECUTE FUNCTION ensure_same_company_live_activity_token();
    END IF;
END;
$$;

-- === End Real-time Tracking ================================================
