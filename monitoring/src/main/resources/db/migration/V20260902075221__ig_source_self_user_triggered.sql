-- 사용자 트리거 비동기 흐름의 self 도입 시점 토글 — 새벽 스케줄 트리거(self 1순위, 이미 개통)와
-- 등록 백필·보강·해시태그 스윕·메트릭 백필 같은 사용자 트리거 비동기 흐름을 분리한다.
-- 시드는 false — 사용자 트리거 비동기는 계속 Hiker 1순위(HikerFirstInstagramSource)로 라우팅되고,
-- self-enabled를 켜도 이 흐름에는 영향이 없다(행동 변화 0). 검증 뒤 전환은 SQL 한 줄:
--   UPDATE app_setting SET value = 'true' WHERE key = 'ig-source.self-user-triggered';
-- (재배포 불필요 — IgSourceSettings TTL 5초 이내 반영)
INSERT INTO app_setting (key, value) VALUES
    ('ig-source.self-user-triggered', 'false')
ON CONFLICT (key) DO NOTHING;
