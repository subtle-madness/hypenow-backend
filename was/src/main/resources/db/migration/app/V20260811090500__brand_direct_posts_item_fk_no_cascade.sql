-- brand_direct_posts.monitoring_item_id의 ON DELETE CASCADE를 제거한다(트랙 NN).
-- 이 FK가 CASCADE인 채로 두면 등록 롤백(MonitoringItemRepository.delete)이 item을 지울 때
-- 이 매핑 행이 아카이브 없이 조용히 함께 사라진다 — ArchiveCascadeReachabilityTest가
-- monitoring_items의 CASCADE 자식은 0개여야 한다고 강제한다(다른 개별 삭제 경로들과 동일 계약).
-- 이제 두 삭제 경로(등록 롤백·탈퇴) 모두 이 행을 명시적으로 먼저 아카이브·삭제한 뒤 부모를
-- 지운다(saved_contents가 users에 대해 이미 쓰는 패턴과 동일) — FK는 그 순서를 어겼을 때
-- 조용히 사라지는 대신 위반 에러로 드러내는 안전망 역할만 한다.
ALTER TABLE app.brand_direct_posts DROP CONSTRAINT brand_direct_posts_monitoring_item_id_fkey;
ALTER TABLE app.brand_direct_posts ADD CONSTRAINT brand_direct_posts_monitoring_item_id_fkey
    FOREIGN KEY (monitoring_item_id) REFERENCES app.monitoring_items(id);
