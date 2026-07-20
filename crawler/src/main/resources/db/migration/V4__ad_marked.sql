-- 광고·협찬 표기 판별 결과 — aggregate가 상세 캡션·파트너십 필드로 채운다 (AdSignals).
ALTER TABLE content ADD COLUMN ad_marked boolean NOT NULL DEFAULT false;
