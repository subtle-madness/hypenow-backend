-- 브랜드 was 계약 필드(2026-08-07 스펙 §3-2) — expand 단계, 전부 nullable ADD.
-- 대상은 08-06 브랜드 전용 테이블만 — 기존 캠페인 테이블 무접촉.
ALTER TABLE brand_account
    ADD COLUMN full_name             text,        -- 프로필 콜 관측 최신값(BrandProfile.fullName)
    ADD COLUMN profile_pic_url       text,
    ADD COLUMN is_verified           boolean,
    ADD COLUMN external_url          text,
    ADD COLUMN following             bigint,      -- 최신값(추이는 brand_profile_snapshot)
    ADD COLUMN media_count           bigint,
    ADD COLUMN backfill_error        text,        -- 초기 백필 실패 기록 — 스윕 성공 시 클리어(§5-2)
    ADD COLUMN backfill_completed_at timestamptz, -- 최초 완주 시각(collectionCompletedAt)
    ADD COLUMN last_swept_at         timestamptz; -- lastDetectedAt·lastTrackedAt 공급(시각 — last_swept_on은 날짜)

ALTER TABLE brand_post_meta
    ADD COLUMN video_url           text,
    ADD COLUMN video_duration      double precision,
    ADD COLUMN is_paid_partnership boolean;      -- null=키 부재(판정 unknown 근거)

ALTER TABLE author_profile
    ADD COLUMN is_verified boolean;
