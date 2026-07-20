-- collect가 기간 백필(개월)·추적 윈도우(일) 컷오프를 버리고 "방문당 최근 피드 12개(프로필
-- 내장) + 릴스 1페이지"로 바뀌면서 두 설정 키가 사라졌다 — 남은 오버라이드 행은 어디서도
-- 읽히지 않으므로 정리한다. (구 파이프라인의 aggregate.* 오버라이드도 함께 정리)
DELETE FROM app_setting
WHERE key IN ('collect.backfill-months', 'collect.track-window-days',
              'aggregate.delay-days', 'aggregate.batch-limit', 'aggregate.comments-per-post');
