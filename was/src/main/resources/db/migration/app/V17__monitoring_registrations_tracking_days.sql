-- share 링크 등록 항목(6.27)은 접수 시점엔 target 종류를 특정할 수 없어 monitoring_items 행을
-- 만들지 못한다(resolve 성공 후 실행기가 뒤늦게 생성) — 그때 필요한 tracking_days·campaign_id는
-- 원래 등록 요청 시점 값이라 항목 자체가 아니라 요청 1건(monitoring_registrations)에 있어야 한다.
-- nullable 확장 — 이 마이그레이션 이전에 생성된 행(있다면)은 null로 남고, was 코드는 등록 시점에 항상 채워 넣는다.
ALTER TABLE app.monitoring_registrations
    ADD COLUMN tracking_days int,
    ADD COLUMN campaign_id   bigint REFERENCES app.monitoring_campaigns(id) ON DELETE SET NULL;

-- RegistrationRepository.findEntryByItemId는 item_id당 entry가 최대 1개라고 가정한다(실행기가
-- item_id로 소속 entry를 역조회 — 복구·완료 마킹 경로). 지금까지는 애플리케이션 코드가 이 불변식을
-- 지켰을 뿐 DB가 강제하지 않았다 — 부분 유니크 인덱스로 승격(null은 여러 개 허용해야 하므로 WHERE로 제한).
CREATE UNIQUE INDEX monitoring_registration_entries_item_id_uidx
    ON app.monitoring_registration_entries (item_id) WHERE item_id IS NOT NULL;
