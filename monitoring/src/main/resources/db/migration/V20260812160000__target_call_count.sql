-- 캠페인·콘텐츠 모니터링 콜의 유저별 일별 집계(2026-08-12 어드민 크롤링 비용 범위 확장) —
-- expand 단계 신규 테이블. 브랜드(brand_call_count)와 달리 키가 유저다: target.user_id가 콜 시점에
-- 이미 확정돼 있어(등록 필수값) 귀속을 monitoring이 콜 시점에 끝낼 수 있고, was는 기간 계산 없이
-- 유저 행만 SELECT하면 된다. 한 콜이 여러 유저의 캠페인을 서빙하면(계정 열거 공유) 유저마다 +1 —
-- brand_call_count의 "공유 브랜드는 양쪽 모두 계상"과 같은 비용 상한 관점이다.
-- was_reader SELECT는 V2의 ALTER DEFAULT PRIVILEGES가 자동 적용된다.
CREATE TABLE target_call_count (
    user_id   bigint NOT NULL,   -- was 유저 논리 참조(target.user_id와 동일 공간 — FK 없음)
    called_on date   NOT NULL,   -- KST 달력일 — brand_call_count·was 월초/자정 경계와 같은 시간대
    calls     bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, called_on)
);
