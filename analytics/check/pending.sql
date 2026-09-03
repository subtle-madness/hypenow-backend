-- 미분석 콘텐츠 "왜" 분해 — 세션·운영 공용 정본 (2026-07-28).
-- "분석 잔여 몇 건?" / "이 게시물들은 왜 분석이 없나?"에 답할 때는 이 스크립트를 쓴다.
-- content_analyses 단독 카운트 금지: 미분석 콘텐츠의 timely 여부는 분석 완료 전엔 어디에도
-- 영속화되지 않고(v_analysis_candidates.timely가 유일한 판정 지점), 즉석 쿼리는
-- "제때창(3일) 놓쳐서 분석 안 함"과 "분석 대상인데 대기"를 뭉개기 쉽다(07-21·07-28 오답 전력).
--
-- 실행: ./check/pending.sh  — crawler DB 세션에서 돌며, analysis DB 셋은 임시 테이블로 주입된다.
-- 전제 임시 테이블(pending.sh가 만든다):
--   _analyzed(short_code, metric_timeliness) — content_analyses 전량
--   _comment_gate(short_code)                — 댓글 미러됨 ∧ 분류 미완 (잡 보류 게이트)
--   _mirrored(short_code)                    — 미러 contents (라이브 뷰-미러 간극 스킵 판정)
-- 상태 정의는 /ui 콘텐츠 보드(PipelineStatsService)와 동일 — 항등식 대조 출력으로 검증한다.
-- ※ 2026-09-03 2단계 분리: metric_timeliness='pending'은 "파트 A(사실)만 채워짐"이다.
--   행 존재 = 기분석이 아니므로 상태 11 / 24 / 34 / 42로 따로 센다.

ANALYZE _analyzed;
ANALYZE _comment_gate;
ANALYZE _mirrored;

-- 분류 본체 — 모수는 v_contents 현 스냅샷 전체(캡션 없는 것 포함: 자격 밖도 이유를 보인다).
-- 성숙 가드 수식은 04 뷰·PipelineStatsService.POOL_SQL과 동일해야 한다(어긋나면 항등식이 깨진다).
CREATE TEMP TABLE _cls AS
SELECT
  p.short_code,
  CASE
    WHEN NOT p.has_caption THEN '50'
    WHEN NOT p.mature THEN CASE WHEN s.facts_only THEN '11' ELSE '10' END
    WHEN c.timely IS TRUE THEN
      CASE WHEN s.facts_only THEN '24'
           WHEN s.analyzed THEN '20'
           WHEN s.gated THEN '22'
           WHEN NOT s.mirrored THEN '23'
           ELSE '21' END
    WHEN c.timely IS FALSE THEN
      CASE WHEN s.facts_only THEN '34'
           WHEN s.analyzed THEN '30'
           WHEN s.gated THEN '32'
           WHEN NOT s.mirrored THEN '33'
           ELSE '31' END
    WHEN s.facts_only THEN '42'
    WHEN s.analyzed THEN '41'
    ELSE '40'
  END AS k
FROM (
  SELECT v.short_code,
         (v.caption IS NOT NULL AND btrim(v.caption) <> '') AS has_caption,
         (v.posted_at AT TIME ZONE 'Asia/Seoul')::date
           + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)
           + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-timely-slack-days'), 1)
           <= (now() AT TIME ZONE 'Asia/Seoul')::date AS mature
  FROM analytics.v_contents v
) p
LEFT JOIN (SELECT short_code, timely FROM analytics.v_analysis_candidates) c USING (short_code)
CROSS JOIN LATERAL (
  SELECT EXISTS (SELECT 1 FROM _analyzed a WHERE a.short_code = p.short_code) AS analyzed,
         -- 2026-09-03 2단계 분리: 행이 있어도 파트 A만이면 '기분석'이 아니다(해석·기준선이 없어
         -- 랭킹·드로어 비교 블록에 못 뜬다). '사실만'으로 따로 센다.
         EXISTS (SELECT 1 FROM _analyzed a WHERE a.short_code = p.short_code
                 AND a.metric_timeliness = 'pending') AS facts_only,
         EXISTS (SELECT 1 FROM _comment_gate g WHERE g.short_code = p.short_code) AS gated,
         EXISTS (SELECT 1 FROM _mirrored m WHERE m.short_code = p.short_code) AS mirrored
) s;

\echo ''
\echo '== 상태 분해 (모수: 뷰티 서빙 v_contents 현 스냅샷) =='
SELECT st.label AS "상태", COALESCE(c.n, 0) AS "건수", st.note AS "해석"
FROM (VALUES
  ('10', '미성숙 — 제때창 안 닫힘',     '판정 대기 — 창 닫히면 랭킹/상세/영구 제외로 갈린다. 분석 실패 아님'),
  ('11', '미성숙 · 사실만',             '파트 A 완료(광고 판정·카테고리 표시됨) - 해석은 창 닫힌 뒤'),
  ('20', '랭킹 트랙 · 기분석',          '제때창(3일) 안 크롤 성공 + 분석 완료 → 랭킹 노출'),
  ('21', '랭킹 트랙 · 분석 대기',       '분석 대상 — 다음 새벽 잡이 처리(진짜 잔여)'),
  ('22', '랭킹 트랙 · 댓글 게이트 보류', '후보지만 댓글 분류 미완으로 잡이 스킵 — 분류 완료 후 자연 재대상'),
  ('23', '랭킹 트랙 · 미러 갭',         '후보지만 미러에 아직 없어 잡이 스킵 — 다음 미러 후 자연 해소'),
  ('24', '랭킹 트랙 · 사실만',          '파트 A 완료 · 파트 B 대기 - 광고/카테고리는 뜨지만 랭킹엔 아직 안 나온다'),
  ('30', '상세 트랙 · 기분석',          '제때창 놓침·최근 윈도우 안 + 분석 완료 → 인플루언서 상세만(랭킹 제외)'),
  ('31', '상세 트랙 · 분석 대기',       '분석 대상(백필 잡) — 분석돼도 랭킹엔 안 나온다'),
  ('32', '상세 트랙 · 댓글 게이트 보류', '후보지만 댓글 분류 미완으로 잡이 스킵'),
  ('33', '상세 트랙 · 미러 갭',         '후보지만 미러에 아직 없어 잡이 스킵'),
  ('34', '상세 트랙 · 사실만',          '파트 A 완료 · 파트 B 대기 - 인플루언서 상세엔 이미 반영'),
  ('40', '영구 제외 · 미분석',          '제때창(3일) 놓침 ∧ 최근 윈도우 밖 — 분석 대상이 아니다(실패·지연 아님)'),
  ('41', '영구 제외 · 과거 분석 보유',   '윈도우에 있던 시절 분석됨 — 저장분 노출은 유지, 신규 분석 없음'),
  ('42', '영구 제외 · 사실만',          '제때창 놓침 ∧ 윈도우 밖이지만 파트 A는 채워짐 - 해석은 영영 안 만든다'),
  ('50', '캡션 없음 — 자격 밖',         'LLM 입력 불가 — 분석 대상이 아니다')
) st(k, label, note)
LEFT JOIN (SELECT k, count(*) AS n FROM _cls GROUP BY k) c USING (k)
ORDER BY st.k;

\echo ''
\echo '== /ui 콘텐츠 보드 대조 (항등식: 자격 = 랭킹 + 상세 · 각 트랙 = 기분석 + 미분석) =='
SELECT
  count(*) FILTER (WHERE k IN ('20','21','22','23','24','30','31','32','33','34')) AS "분석 자격",
  count(*) FILTER (WHERE k IN ('20','21','22','23','24'))                          AS "랭킹 트랙",
  count(*) FILTER (WHERE k = '20')                                                 AS "랭킹 기분석",
  count(*) FILTER (WHERE k IN ('21','22','23','24'))                               AS "랭킹 미분석",
  count(*) FILTER (WHERE k = '24')                                                 AS "랭킹 사실만",
  count(*) FILTER (WHERE k IN ('30','31','32','33','34'))                          AS "상세 트랙",
  count(*) FILTER (WHERE k = '30')                                                 AS "상세 기분석",
  count(*) FILTER (WHERE k IN ('31','32','33','34'))                               AS "상세 미분석",
  count(*) FILTER (WHERE k IN ('10','11'))                                         AS "미성숙",
  count(*) FILTER (WHERE k IN ('40','41','42'))                                    AS "영구 제외"
FROM _cls;

-- 마킹·뷰 불일치 — 캘린더일 정합(07-28) 이후에도 남은 과거 마킹 표류(소급 런북 대상:
-- plans/2026-07-28-timely-calendar-alignment.md §Task 4). 0·0이면 소급 완료 상태.
\echo ''
\echo '== 마킹·뷰 불일치 (0이 아니면 timely 소급 런북 미실행분) =='
SELECT
  count(*) FILTER (WHERE c.timely AND a.metric_timeliness = 'late_backfill')
    AS "뷰=timely인데 late_backfill 마킹",
  count(*) FILTER (WHERE NOT c.timely AND a.metric_timeliness = 'timely')
    AS "뷰=늦크롤인데 timely 마킹"
FROM analytics.v_analysis_candidates c
JOIN _analyzed a USING (short_code);
