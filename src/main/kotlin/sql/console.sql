

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



ALTER ROLE yuliyanatasheva WITH PASSWORD 'SOLGANn47@25'

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




-- Migration fix: backfill null company_id and enforce NOT NULL
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

-- 3) Now enforce the NOT NULL constraint
ALTER TABLE users
    ALTER COLUMN company_id SET NOT NULL;
END;
$$ LANGUAGE plpgsql;

-- 4) Create index on company_id if missing
CREATE INDEX IF NOT EXISTS idx_users_company
    ON users(company_id);