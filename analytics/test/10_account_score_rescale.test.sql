-- 계정 하입 스코어 매핑 함수(analytics.hype_account_score) 회귀 테스트 — 트랙 Z 후속(계정 점수 척도
-- 재교정, 스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md §9, 구현 10_account_detail.sql).
-- 순수 함수 검증 — 쓰기는 app_setting 임시 오버라이드뿐(run.sh가 BEGIN/ROLLBACK으로 격리).
-- 핵심 계약: 척도만 재교정하고 raw 평균의 **순위는 절대 깨지 않는다** — 아래 1)이 그 보장이다.
DO $$
DECLARE
  s1 bigint; s2 bigint; s3 bigint; s4 bigint; top bigint;
  base bigint; raised bigint;
BEGIN
  -- 1) 단조성: raw가 크면 매핑 점수도 크거나 같다 (순위 불변 — 이번 변경의 핵심 계약).
  --    기본 앵커(p05=1.0833·p50=12.8333·p90=31.2000·p99=44.86) 5개 구간을 고루 지나는 표본.
  s1  := analytics.hype_account_score(0.5);   -- < a05
  s2  := analytics.hype_account_score(5);     -- a05~a50
  s3  := analytics.hype_account_score(20);    -- a50~a90
  s4  := analytics.hype_account_score(40);    -- a90~a99
  top := analytics.hype_account_score(60);    -- > a99 (초과구간)
  IF NOT (s1 <= s2 AND s2 <= s3 AND s3 <= s4 AND s4 <= top) THEN
    RAISE EXCEPTION '단조성 위반: %, %, %, %, %', s1, s2, s3, s4, top;
  END IF;

  -- 2) 상한 도달: 관측 최대 raw 수준(test 스택 실측 최상위 계정 beauty_linyas2 ≈ 58.9,
  --    스펙 §9 재교정 전 계정 점수 최대값 59)이 90 이상으로 매핑된다 — 척도 재교정의 목적 자체.
  IF analytics.hype_account_score(59) < 90 THEN
    RAISE EXCEPTION '상한 도달 실패: hype_account_score(59) = % (기대 >= 90)',
      analytics.hype_account_score(59);
  END IF;

  -- 3) 0 보존: 창에 콘텐츠는 있으나 raw 평균이 0인 계정은 여전히 0점.
  IF analytics.hype_account_score(0) <> 0 THEN
    RAISE EXCEPTION 'raw=0 보존 실패: %', analytics.hype_account_score(0);
  END IF;

  -- 4) NULL 보존: 창 전체 점수 불가(raw NULL, 예: 릴스뿐인데 조회수 전무) 계정은 여전히 NULL
  --    (기존 동작 — 스펙 2026-07-29-influencer-avg-hype-score).
  IF analytics.hype_account_score(NULL) IS NOT NULL THEN
    RAISE EXCEPTION 'raw=NULL 보존 실패: %', analytics.hype_account_score(NULL);
  END IF;

  -- 5) 앵커 오버라이드 반응: app_setting으로 p50을 올리면 같은 raw가 더 낮은 점수
  --    (02_serving.test.sql의 콘텐츠 앵커 단언과 동일 관용구 — 계정 앵커도 재배포 없이 튜닝 가능함을 확인).
  base := analytics.hype_account_score(20);
  INSERT INTO app_setting(key,value) VALUES
    ('analytics.hype-anchor-acct-p50','30'),
    ('analytics.hype-anchor-acct-p90','50'),
    ('analytics.hype-anchor-acct-p99','70');
  raised := analytics.hype_account_score(20);
  IF raised >= base THEN
    RAISE EXCEPTION '앵커 오버라이드 무반응: base=%, p50↑ 후=% (더 낮아야 함)', base, raised;
  END IF;
  DELETE FROM app_setting WHERE key LIKE 'analytics.hype-anchor-acct-%';

  RAISE NOTICE '10_account_score_rescale: 모든 단언 통과';
END $$;
