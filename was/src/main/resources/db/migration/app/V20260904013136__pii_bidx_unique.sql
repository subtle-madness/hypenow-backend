-- 블라인드 인덱스 UNIQUE(트랙 A 스펙 §전환 — 백필 완료 후 적용, 운영·스테이징 09-04 완료 확인).
-- NULL 다중은 UNIQUE가 허용하므로 미백필 행이 있어도 실패하지 않지만, 읽기 전환은 백필 0 NULL이 전제.
CREATE UNIQUE INDEX users_email_bidx_key ON app.users (email_bidx);
CREATE UNIQUE INDEX password_resets_email_bidx_key ON app.password_resets (email_bidx);
CREATE INDEX signup_events_bidx_ix ON app.signup_events (email_bidx, created_at);
