-- hype_score v3 회귀 테스트: 감쇠는 앵커 매핑 **뒤에** 곱한다.
-- 순수 함수 검증 — 쓰기는 app_setting 임시 오버라이드뿐(run.sh가 BEGIN/ROLLBACK으로 격리).
-- 단언은 앵커·상수 실제값과 무관한 성질만 본다.
DO $$
DECLARE
  d0 bigint; d14 bigint; d28 bigint;
  fresh bigint; old bigint;
  r bigint; f bigint;
BEGIN
  -- 1) 매핑 후 감쇠: 같은 Q에 대해 점수는 경과일에 정확히 0.5^(경과일/halflife) 배.
  --    v2.1(감쇠를 Q에 먼저 곱하고 매핑)에서는 매핑이 비선형이라 이 비율이 성립하지 않는다.
  d0  := analytics.hype_score('reels', 100000, 3000, 200, 10000, 0);
  d14 := analytics.hype_score('reels', 100000, 3000, 200, 10000, 14);
  d28 := analytics.hype_score('reels', 100000, 3000, 200, 10000, 28);
  IF abs(d14 - round(d0 * 0.5)) > 1 THEN
    RAISE EXCEPTION '감쇠가 매핑 뒤에 곱해지지 않음: d0=%, d14=% (기대 %)', d0, d14, round(d0*0.5);
  END IF;
  IF abs(d28 - round(d0 * 0.25)) > 1 THEN
    RAISE EXCEPTION '반감기 2주기 비율 위반: d0=%, d28=% (기대 %)', d0, d28, round(d0*0.25);
  END IF;

  -- 2) 클램프는 감쇠 **전에** 적용한다 — base를 [0,100]으로 자른 뒤 곱해야
  --    "품질 백분위 × 신선도"가 성립한다. 앵커 p99를 크게 넘는 입력으로 확인:
  --    매핑값이 100을 넘으므로 d0은 100이고, 반감기 1주기 뒤는 정확히 그 절반이어야 한다.
  --    (감쇠 후 클램프라면 100보다 큰 base가 절반이 되어 50을 초과한다.)
  fresh := analytics.hype_score('reels', 1000000000, 1000000000, 0, 1000, 0);
  old   := analytics.hype_score('reels', 1000000000, 1000000000, 0, 1000, 14);
  IF fresh <> 100 THEN
    RAISE EXCEPTION '극단 입력이 상한 100에 닿지 않음: %', fresh;
  END IF;
  IF old <> round(fresh * 0.5) THEN
    RAISE EXCEPTION '클램프가 감쇠 뒤에 적용됨: fresh=%, d14=% (기대 %)', fresh, old, round(fresh*0.5);
  END IF;

  -- 3) 타입 동등성: 같은 Q면 타입과 무관하게 같은 점수.
  --    릴스 조회수를 0으로 두면 도달 항이 ln(1)=0이라 Q는 참여 항만 남는다.
  --    e0를 f0와 같게 맞추고 앵커도 두 타입 동일하게 오버라이드하면 두 Q가 완전히 같아진다.
  INSERT INTO app_setting(key,value) VALUES
    ('analytics.hype-reels-e0','0.03'),
    ('analytics.hype-anchor-q-reels-p05','0.05'), ('analytics.hype-anchor-q-feed-p05','0.05'),
    ('analytics.hype-anchor-q-reels-p50','0.60'), ('analytics.hype-anchor-q-feed-p50','0.60'),
    ('analytics.hype-anchor-q-reels-p90','1.60'), ('analytics.hype-anchor-q-feed-p90','1.60'),
    ('analytics.hype-anchor-q-reels-p99','3.00'), ('analytics.hype-anchor-q-feed-p99','3.00');
  r := analytics.hype_score('reels', 0, 1000, 50, 10000, 3);
  f := analytics.hype_score('feed', NULL, 1000, 50, 10000, 3);
  IF r <> f THEN
    RAISE EXCEPTION '타입 동등성 위반: 같은 Q인데 reels=%, feed=%', r, f;
  END IF;
  DELETE FROM app_setting WHERE key='analytics.hype-reels-e0' OR key LIKE 'analytics.hype-anchor-q-%';

  RAISE NOTICE '02_hype_v3_decay_order: 모든 단언 통과';
END $$;
