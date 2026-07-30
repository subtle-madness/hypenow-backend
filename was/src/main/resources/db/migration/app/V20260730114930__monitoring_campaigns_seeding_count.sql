-- 캠페인 시딩 인원 수(프론트 요청 A-1) — 이행률(발송 대비 등록)의 모수. 등록된 추적 행 수로는
-- 절대 구할 수 없다("시딩은 했지만 계정을 등록하지 않은 사람"은 추적 행이 없다) — 유저가 캠페인에
-- 직접 선언하는 입력값이라 집계값이 아니다(계약 6.25 "서버가 하지 않는 것"의 캠페인 집계와는 별개).
-- nullable 확장 — 이 마이그레이션 이전에 생성된 행은 null(미설정)로 남는다.
ALTER TABLE app.monitoring_campaigns
    ADD COLUMN seeding_count integer CHECK (seeding_count >= 0);
