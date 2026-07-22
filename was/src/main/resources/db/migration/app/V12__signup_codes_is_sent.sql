-- 발송 여부(설계 2026-07-22) — 어드민이 코드를 대상자에게 전달했는지 표시.
-- 소진(used_at)과 별개 축: 보냈지만 미가입, 안 보냈는데 소진(직접 전달) 모두 가능.
ALTER TABLE app.signup_codes ADD COLUMN is_sent boolean NOT NULL DEFAULT false;
