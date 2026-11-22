CREATE TABLE reminders (
  id CHAR(36) NOT NULL PRIMARY KEY, owner_id CHAR(36) NOT NULL, pet_id CHAR(36) NOT NULL, version BIGINT NOT NULL DEFAULT 1,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), deleted_at TIMESTAMP(3) NULL,
  title VARCHAR(200) NOT NULL, due_date DATE NOT NULL, due_time CHAR(5) NOT NULL, done BOOLEAN NOT NULL DEFAULT FALSE, record_id VARCHAR(36) NOT NULL DEFAULT '',
  INDEX ix_reminders_owner (owner_id, updated_at), INDEX ix_reminders_due (done, due_date, due_time)
);
