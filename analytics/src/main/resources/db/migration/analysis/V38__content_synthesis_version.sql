-- 해석 문구 부분 갱신 지원 (07-21).
-- content_analyses 한 행에는 성격이 다른 두 종류가 섞여 있다:
--   ① 사실 추출 — detected_brands·main_category·ad_type·detected_products 등. 캡션·썸네일에만 의존.
--   ② 해석 문구 — ai_content_summary·contents_pattern·ai_comment_insight + 기준선 스냅샷.
--      기준선 수치를 인용하므로 기준선 정의가 바뀌면 낡는다.
-- 07-21 하루에만 기준선 참여율 분모(조회수→팔로워)와 contentsPattern 의미가 바뀌어 ②가 전량 낡았는데,
-- 통합 1콜 + 불변 행 구조라 멀쩡한 ①까지 버리고 재추출해야 했다(썸네일 다운로드·분류표 72행 재전송).
-- synthesis_version으로 "②가 어느 계약으로 생성됐는지"를 기록해, 낡은 행만 골라 ②만 UPDATE한다.
-- NULL = 이 컬럼 이전에 생성된 행 = 갱신 대상.
ALTER TABLE content_analyses ADD COLUMN synthesis_version smallint;
ALTER TABLE content_analyses ADD COLUMN synthesized_at    timestamptz;

-- 낡은 행 스캔용 — 부분 인덱스로 갱신 대상만 좁게 잡는다.
CREATE INDEX idx_content_analyses_synthesis_stale
    ON content_analyses (short_code) WHERE synthesis_version IS NULL;
