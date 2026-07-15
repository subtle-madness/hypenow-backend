-- 결정적 더미데이터 (테스트 전용). run.sh가 BEGIN/ROLLBACK으로 감싸므로 실DB 불변.
--
-- 고정 fixture ID (bigserial 실 ID와 충돌하지 않는 높은 값):
--   category 999 / crawl_run 9990 / account 9001~9002 / content 9101~9104

INSERT INTO category(id, name, enabled) VALUES (999, 'dummy_cat', true);
INSERT INTO crawl_run(id, job, trigger_type, actor_id, status, started_at)
VALUES (9990, 'dummy', 'MANUAL', 'dummy/actor', 'SUCCEEDED', timestamptz '2026-06-05 00:00:00+09');

-- 계정 2개. dummy_a는 프로필 스냅샷 2개(최신 선택 검증용).
INSERT INTO account(id, username) VALUES (9001,'dummy_a'), (9002,'dummy_b');
INSERT INTO raw_profile(account_id, crawl_run_id, payload, captured_at) VALUES
 (9001,9990,'{"username":"dummy_a","fullName":"더미 에이","profilePicUrl":"https://pic/a_old.jpg","followersCount":5000}'::jsonb,  timestamptz '2026-06-01 00:00:00+09'),
 (9001,9990,'{"username":"dummy_a","fullName":"더미 에이","profilePicUrl":"https://pic/a.jpg","followersCount":5500}'::jsonb,  timestamptz '2026-06-05 00:00:00+09'),
 (9002,9990,'{"username":"dummy_b","fullName":"더미 비","profilePicUrl":"https://pic/b.jpg","followersCount":20000}'::jsonb, timestamptz '2026-06-01 00:00:00+09');

-- 콘텐츠 4개: dummy_a 릴스2+피드1, dummy_b 릴스1
INSERT INTO content(id, short_code, content_type, owner_username, uploaded_at, category_id, discovery_keyword, status, first_seen_at, subcategory, main_group, ad_marked) VALUES
 (9101,'dummy_r1','REELS','dummy_a', timestamptz '2026-06-01 09:00:00+09',999,'makeup','AGGREGATED', timestamptz '2026-06-01 00:00:00+09','makeup_sub','A', false),
 (9102,'dummy_r2','REELS','dummy_a', timestamptz '2026-06-02 09:00:00+09',999,'makeup','AGGREGATED', timestamptz '2026-06-02 00:00:00+09','makeup_sub','A', false),
 (9103,'dummy_f1','FEED', 'dummy_a', timestamptz '2026-06-03 09:00:00+09',999,'glow','AGGREGATED',   timestamptz '2026-06-03 00:00:00+09','glow_sub','B', true),
 (9104,'dummy_r3','REELS','dummy_b', timestamptz '2026-06-04 09:00:00+09',999,'glow','AGGREGATED',   timestamptz '2026-06-04 00:00:00+09','glow_sub','B', false);

-- 상세. 9101은 스냅샷 3개(최신 선택 + 신형 payload 썸네일 폴백 검증),
-- 9102는 videoPlayCount 없이 videoViewCount만(폴백 검증), 9103은 피드(조회수 필드 없음 → views NULL 검증).
-- 9101 최신(06-06)은 2026-07-11 crawler 신형 payload 재현: 최상위 displayUrl 없이
-- _rawDetail...display_uri에만 썸네일. 지표는 직전 스냅샷과 동일해 지표 기대값 불변.
INSERT INTO raw_post_detail(content_id, crawl_run_id, payload, captured_at) VALUES
 (9101,9990,'{"shortCode":"dummy_r1","type":"Video","caption":"cap r1 old","likesCount":500,"commentsCount":50,"videoPlayCount":10000,"videoDuration":30,"displayUrl":"https://thumb/dummy_r1_old.jpg","url":"https://www.instagram.com/p/dummy_r1/"}'::jsonb, timestamptz '2026-06-04 09:00:00+09'),
 (9101,9990,'{"shortCode":"dummy_r1","type":"Video","caption":"cap r1","likesCount":520,"commentsCount":52,"videoPlayCount":11000,"videoDuration":30,"displayUrl":"https://thumb/dummy_r1.jpg","url":"https://www.instagram.com/p/dummy_r1/"}'::jsonb,     timestamptz '2026-06-05 09:00:00+09'),
 (9101,9990,'{"shortCode":"dummy_r1","type":"Video","caption":"cap r1","likesCount":520,"commentsCount":52,"videoPlayCount":11000,"videoDuration":30,"url":"https://www.instagram.com/p/dummy_r1/","_rawDetail":{"data":{"xig_polaris_media":{"if_not_gated_logged_out":{"display_uri":"https://thumb/dummy_r1_new.jpg"}}}}}'::jsonb, timestamptz '2026-06-06 09:00:00+09'),
 (9102,9990,'{"shortCode":"dummy_r2","type":"Video","caption":"cap r2","likesCount":300,"commentsCount":30,"videoViewCount":7000,"videoDuration":20,"displayUrl":"https://thumb/dummy_r2.jpg","url":"https://www.instagram.com/p/dummy_r2/"}'::jsonb,      timestamptz '2026-06-05 09:00:00+09'),
 (9103,9990,'{"shortCode":"dummy_f1","type":"Image","caption":"cap f1","likesCount":2000,"commentsCount":100,"displayUrl":"https://thumb/dummy_f1.jpg","url":"https://www.instagram.com/p/dummy_f1/"}'::jsonb,                                             timestamptz '2026-06-06 09:00:00+09'),
 (9104,9990,'{"shortCode":"dummy_r3","type":"Video","caption":"cap r3","likesCount":1000,"commentsCount":80,"videoPlayCount":40000,"videoDuration":15,"displayUrl":"https://thumb/dummy_r3.jpg","url":"https://www.instagram.com/p/dummy_r3/"}'::jsonb,    timestamptz '2026-06-07 09:00:00+09');

-- 댓글 3건 (9101). like_count 7 / NULL / 2.
INSERT INTO raw_comment(content_id, crawl_run_id, payload, captured_at) VALUES
 (9101,9990,'{"ownerUsername":"dummy_fan1","text":"pretty","likesCount":7,"timestamp":"2026-06-04T09:10:00Z"}'::jsonb,       timestamptz '2026-06-04 09:10:00+09'),
 (9101,9990,'{"ownerUsername":"dummy_fan2","text":"love it","timestamp":"2026-06-04T09:11:00Z"}'::jsonb,                     timestamptz '2026-06-04 09:11:00+09'),
 (9101,9990,'{"ownerUsername":"dummy_fan1","text":"where to buy","likesCount":2,"timestamp":"2026-06-04T09:12:00Z"}'::jsonb, timestamptz '2026-06-04 09:12:00+09');

-- 실데이터 격리: 더미 외 상세/댓글/프로필 제거 (트랜잭션 안이라 ROLLBACK으로 복구됨).
-- base 뷰를 조인하는 상위 뷰는 더미 데이터만 보게 된다.
DELETE FROM raw_comment     WHERE content_id NOT IN (SELECT id FROM content WHERE category_id = 999);
DELETE FROM raw_post_detail WHERE content_id NOT IN (SELECT id FROM content WHERE category_id = 999);
DELETE FROM raw_profile     WHERE account_id NOT IN (SELECT id FROM account WHERE username LIKE 'dummy_%');
