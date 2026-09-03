-- 브랜드 해시태그 자동 시드 기록(2026-09-03 자동 시드 재설계 §4-1).
-- 계산은 브랜드당 1회(이 테이블), 사용자 장부 삽입은 사용자당 1회(brand_monitorings.hashtag_seeded_at).
-- path = FREQ|AI|FALLBACK|SKIP. SKIP은 "이미 사용자 관리 태그가 있어 자동 태그를 얹지 않았다"이고
-- 그때만 tag가 NULL이다. brand_id는 monitoring brand_account.id 논리 참조(크로스 DB FK 없음).
-- 전부 additive — 롤링 중 구 코드는 이 테이블·컬럼을 모른 채 그대로 돈다.
CREATE TABLE app.brand_hashtag_seed (
    brand_id  bigint PRIMARY KEY,
    path      text NOT NULL,
    tag       text,
    seeded_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE app.brand_monitorings ADD COLUMN hashtag_seeded_at timestamptz;
