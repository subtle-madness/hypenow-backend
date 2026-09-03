-- 지표 시점 어휘에 'pending' 추가 (2026-09-03 콘텐츠 분석 2단계 분리 설계 §4-2).
--   pending = 파트 A(사실)만 채워진 행. 파트 B(해석 5필드 + 기준선 스냅샷)가 아직 없어
--             "지표 시점 미확정"이며, 파트 B 수거가 timely / late_backfill로 확정한다.
-- 왜 신규 어휘인가:
--   NULL       : 랭킹 6.1·카테고리 벤치마크 6.3이 `= 'timely' OR IS NULL`로 레거시 timely 취급 →
--                미성숙 지표 행이 랭킹에 들어가 하향 편향. V33이 막으려던 바로 그 사고다.
--   'immature' : V33 정의는 "가드 도입 전 영구 고정 누수"라는 종결 상태다. 전이 상태로 재사용하면
--                어드민 퍼널 immaturePool·check/pending.sh의 레거시 집계가 오염된다.
-- expand 단계: 구 코드는 이 값을 쓰지 않고, 읽어도 제외 분기로 떨어진다.
-- 파괴 패턴(DROP TABLE/COLUMN·RENAME·타입 변경·SET NOT NULL) 아님 - allow-destructive 불요.
-- 제약 이름을 하드코딩하지 않는 이유: V33이 ADD COLUMN 인라인 CHECK로 만들어 이름이 자동
-- 생성됐다. 이름이 다르면 DROP IF EXISTS가 조용히 통과하고 구 CHECK가 살아남아 'pending'이
-- 계속 거부되는데, 마이그레이션은 성공으로 기록돼 원인 추적이 어려워진다. 못 찾으면 실패시킨다.
DO $$
DECLARE constraint_name text;
BEGIN
  SELECT conname INTO constraint_name
  FROM pg_constraint
  WHERE conrelid = 'content_analyses'::regclass
    AND contype = 'c'
    AND pg_get_constraintdef(oid) LIKE '%metric_timeliness%';
  IF constraint_name IS NULL THEN
    RAISE EXCEPTION 'metric_timeliness CHECK 제약을 찾지 못했다 - V33 형상 확인 필요';
  END IF;
  EXECUTE format('ALTER TABLE content_analyses DROP CONSTRAINT %I', constraint_name);
END $$;

ALTER TABLE content_analyses
    ADD CONSTRAINT content_analyses_metric_timeliness_check
    CHECK (metric_timeliness IN ('timely', 'late_backfill', 'immature', 'pending'));

-- 파트 B 후보 좁히기 전용 부분 인덱스 - 잡이 "후보 ∩ pending" 포함 집합을 매 실행 읽는다.
-- V38의 idx_content_analyses_synthesis_stale과 같은 관용구(부분 인덱스로 대상만 좁게).
CREATE INDEX idx_content_analyses_timeliness_pending
    ON content_analyses (short_code) WHERE metric_timeliness = 'pending';
