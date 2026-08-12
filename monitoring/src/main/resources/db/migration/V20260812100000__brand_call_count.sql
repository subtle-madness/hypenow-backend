-- 브랜드별 Hiker 콜 일별 집계(2026-08-12 어드민 크롤링 비용 설계) — expand 단계 신규 테이블.
-- 1행 = (브랜드, KST 달력일). CountingHikerHttp가 브랜드 컨텍스트 안의 성공 콜마다 +1 upsert한다.
-- 집계 시작 시점(이 마이그레이션 배포) 이후 누적이다 — 과거 콜(raw.fetch_payload 이력)은 소급하지
-- 않는다(게시자·댓글 콜의 브랜드 귀속을 사후 복원할 수 없음). was 어드민 크롤링 비용 카드가
-- 읽는 표면이며, was_reader SELECT는 V2의 ALTER DEFAULT PRIVILEGES가 자동 적용된다.
CREATE TABLE brand_call_count (
    brand_id  bigint NOT NULL,   -- brand_account.id (콜 시점 스냅샷 — FK 없음: 계정 CLOSED 후에도 이력 보존)
    called_on date   NOT NULL,   -- KST 달력일 — was 쪽 월초·자정 경계 계산과 같은 시간대
    calls     bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (brand_id, called_on)
);
