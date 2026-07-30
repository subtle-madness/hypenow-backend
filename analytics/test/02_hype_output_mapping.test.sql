-- 콘텐츠 출력 매핑(analytics.hype_score_output) 회귀 테스트 — 소수점 노출, 스펙
-- docs/superpowers/specs/2026-07-30-hype-score-v3-decay-after-mapping-design.md §10.
-- 순수 함수 검증 — 쓰기는 app_setting 임시 오버라이드뿐(run.sh가 BEGIN/ROLLBACK으로 격리).
DO $$
DECLARE
  lo numeric; hi numeric; mid numeric; base numeric;
  a05 numeric; a50 numeric; a90 numeric; a99 numeric;
BEGIN
  -- 1) 단조성: raw가 크면 출력도 크거나 같다(순위 불변 — 출력 매핑은 척도만 펴 바르지 순서는 안 바꾼다).
  lo  := analytics.hype_score_output(2);    -- < a05(5)
  mid := analytics.hype_score_output(23);   -- = a50
  hi  := analytics.hype_score_output(70);   -- > a99(60.8), 초과구간
  IF NOT (lo <= mid AND mid <= hi) THEN
    RAISE EXCEPTION '단조성 위반: %, %, %', lo, mid, hi;
  END IF;

  -- 2) 앵커점 매핑: 기본 앵커(p05=5·p50=23·p90=44·p99=60.8)가 각각 10·45·80·97로 정확히 잡힌다.
  a05 := analytics.hype_score_output(5);
  a50 := analytics.hype_score_output(23);
  a90 := analytics.hype_score_output(44);
  a99 := analytics.hype_score_output(60.8);
  ASSERT a05 = 10, format('p05 앵커점 불일치: %s (기대 10)', a05);
  ASSERT a50 = 45, format('p50 앵커점 불일치: %s (기대 45)', a50);
  ASSERT a90 = 80, format('p90 앵커점 불일치: %s (기대 80)', a90);
  ASSERT a99 = 97, format('p99 앵커점 불일치: %s (기대 97)', a99);

  -- 3) [0,100] 클램프: p99를 한참 넘는 입력도 100을 넘지 않고, 음수 근접 입력도 0 밑으로 안 간다.
  hi := analytics.hype_score_output(1000);
  ASSERT hi BETWEEN 0 AND 100, format('상한 클램프 위반: %s', hi);
  lo := analytics.hype_score_output(0);
  ASSERT lo = 0, format('raw=0은 출력도 0이어야 함: %s', lo);

  -- 4) NULL 규칙: 점수 산출 불가(hype_score_raw가 NULL)는 출력 매핑도 NULL.
  ASSERT analytics.hype_score_output(NULL) IS NULL, 'raw=NULL인데 출력이 NULL이 아님';

  -- 5) app_setting 오버라이드 반응 — 재배포 없는 튜닝 가능함을 확인(콘텐츠 Q 앵커·계정 앵커와 동일 관용구).
  base := analytics.hype_score_output(23);
  INSERT INTO app_setting(key,value) VALUES
    ('analytics.hype-anchor-out-p50','40'),
    ('analytics.hype-anchor-out-p90','60'),
    ('analytics.hype-anchor-out-p99','80');
  ASSERT analytics.hype_score_output(23) < base,
    format('앵커 오버라이드 무반응: base=%s, p50↑ 후=%s (더 낮아야 함)', base, analytics.hype_score_output(23));
  DELETE FROM app_setting WHERE key LIKE 'analytics.hype-anchor-out-%';

  RAISE NOTICE '02_hype_output_mapping: 모든 단언 통과';
END $$;

-- 핵심 회귀: 정수라면 동점이 됐을 두 raw 입력이 출력 매핑(소수)에서는 갈린다 — 소수점 전환의
-- 목적이 바로 이 동점 제거다. 75.1·75.4는 반올림하면 둘 다 75(구 hype_score 방식이라면 동점)지만,
-- 출력 매핑은 연속값을 그대로 받아 서로 다른 소수를 낸다.
DO $$
DECLARE out_lo numeric; out_hi numeric;
BEGIN
  ASSERT round(75.1::numeric) = round(75.4::numeric),
    '전제 붕괴: 75.1·75.4가 반올림해도 같은 정수가 아님';

  out_lo := analytics.hype_score_output(75.1);
  out_hi := analytics.hype_score_output(75.4);
  ASSERT out_lo <> out_hi,
    format('동점 제거 실패: raw 75.1·75.4(반올림하면 둘 다 75)이 출력도 같음(%s = %s)', out_lo, out_hi);
  ASSERT out_hi > out_lo,
    format('출력 순서가 입력 순서와 어긋남: raw 75.4의 출력(%s)이 75.1의 출력(%s)보다 커야 함', out_hi, out_lo);

  RAISE NOTICE '02_hype_output_mapping (동점 제거 회귀): 모든 단언 통과';
END $$;

-- hype_score_raw / hype_score 관계 회귀 — 리팩터 전과 값이 100% 동일해야 한다(§10 문서 "값·의미
-- 불변" 계약). hype_score()는 이제 round(hype_score_raw(...))::bigint일 뿐이므로 항등이어야 한다.
DO $$
DECLARE raw1 numeric; int1 bigint;
BEGIN
  raw1 := analytics.hype_score_raw('reels', 50000, 1000, 50, 10000, 3);
  int1 := analytics.hype_score('reels', 50000, 1000, 50, 10000, 3);
  ASSERT int1 = round(raw1)::bigint,
    format('hype_score != round(hype_score_raw)::bigint — 구 정수 값 불변 계약 깨짐: %s vs round(%s)', int1, raw1);

  -- NULL 규칙은 hype_score_raw도 hype_score와 동일해야 한다(같은 CASE 안에 있으므로 당연하지만 못박는다).
  ASSERT analytics.hype_score_raw('reels', NULL, 100, 10, 5000, 3) IS NULL, 'hype_score_raw: 릴스 views NULL → NULL 아님';
  ASSERT analytics.hype_score_raw('feed', NULL, 100, 10, 5000, 3) IS NOT NULL, 'hype_score_raw: 피드 views NULL인데 NULL(정상이어야 함)';

  RAISE NOTICE '02_hype_output_mapping (hype_score_raw 항등): 모든 단언 통과';
END $$;
