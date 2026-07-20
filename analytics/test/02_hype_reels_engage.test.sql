-- 릴스 참여 축 팔로워 정규화(v2.1) 회귀 테스트.
-- 순수 함수 검증 — 쓰기 없음(트랜잭션 래핑 불필요). 단언은 매핑 단조성에 의존해 튜닝값과 무관.
DO $$
DECLARE
  c bigint; d bigint;   -- 조회수 단조성
  a bigint; b bigint;   -- 저조회수 뭉침 vs 도달
  n bigint;             -- NULL 규칙
BEGIN
  -- 1) 참여·팔로워 동일, 조회수만 다름 → 조회수 많은 쪽 점수가 낮으면 안 됨.
  --    (현재 ÷조회수 식은 조회수가 늘면 engage가 줄어 이 성질이 깨진다.)
  c := analytics.hype_score('reels', 1000,  500, 50, 5000, 0);
  d := analytics.hype_score('reels', 50000, 500, 50, 5000, 0);
  IF d < c THEN
    RAISE EXCEPTION '조회수 단조성 위반: views=1000 -> %, views=50000 -> %', c, d;
  END IF;

  -- 2) 저조회수 릴스가 잘 퍼진 릴스를 이기면 안 됨.
  a := analytics.hype_score('reels', 200,    300,  13,  10000, 0);  -- 저조회수 뭉침 케이스
  b := analytics.hype_score('reels', 100000, 3000, 200, 10000, 0);  -- 도달·참여 모두 큼
  IF a >= b THEN
    RAISE EXCEPTION '저조회수 뭉침: 200뷰 릴스(%)가 10만뷰 릴스(%)를 이김', a, b;
  END IF;

  -- 3) 릴스인데 조회수 NULL → 여전히 NULL(도달 축이 views를 쓰므로). 회귀 가드.
  n := analytics.hype_score('reels', NULL, 500, 50, 5000, 0);
  IF n IS NOT NULL THEN
    RAISE EXCEPTION '릴스 views NULL 규칙 위반: NULL이어야 하는데 %', n;
  END IF;

  RAISE NOTICE '02_hype_reels_engage: 모든 단언 통과';
END $$;
