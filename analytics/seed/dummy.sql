-- 결정적 더미데이터 (테스트 전용). run.sh가 BEGIN/ROLLBACK으로 감싸므로 실DB 불변.
--
-- 테스트가 의존하는 고정 fixture ID:
--   category    999
--   crawl_run   9990
--   account     9001~9004
--   content     9101~9105
-- 이 높은 고정 ID들은 bigserial로 생성되는 실 ID와 당분간 충돌하지 않도록 선택했다.
--
-- 카테고리/실행
INSERT INTO category(id, name, enabled) VALUES (999, 'dummy_cat', true);
INSERT INTO crawl_run(id, job, trigger_type, actor_id, status, started_at)
VALUES (9990, 'dummy', 'MANUAL', 'dummy/actor', 'SUCCEEDED', timestamptz '2026-06-05 00:00:00+09');

-- 계정 + 최신 프로필
INSERT INTO account(id, username) VALUES
 (9001,'dummy_micro'), (9002,'dummy_mid'), (9003,'dummy_macro'), (9004,'dummy_over');
INSERT INTO raw_profile(account_id, crawl_run_id, payload, captured_at) VALUES
 (9001,9990,'{"username":"dummy_micro","followersCount":5000,"followsCount":300,"postsCount":120,"verified":false,"isBusinessAccount":true,"businessCategoryName":"Health/Beauty"}'::jsonb, timestamptz '2026-06-05 00:00:00+09'),
 (9002,9990,'{"username":"dummy_mid","followersCount":50000,"followsCount":800,"postsCount":400,"verified":true,"isBusinessAccount":true,"businessCategoryName":"Beauty"}'::jsonb, timestamptz '2026-06-05 00:00:00+09'),
 (9003,9990,'{"username":"dummy_macro","followersCount":500000,"followsCount":200,"postsCount":900,"verified":true,"isBusinessAccount":true,"businessCategoryName":"Public Figure"}'::jsonb, timestamptz '2026-06-05 00:00:00+09'),
 (9004,9990,'{"username":"dummy_over","followersCount":8000,"followsCount":500,"postsCount":60,"verified":false,"isBusinessAccount":false,"businessCategoryName":null}'::jsonb, timestamptz '2026-06-05 00:00:00+09');

-- 콘텐츠
INSERT INTO content(id, short_code, content_type, owner_username, uploaded_at, category_id, discovery_keyword, status, first_seen_at, subcategory, main_group, ad_marked) VALUES
 (9101,'dummy_c1','REELS','dummy_micro', timestamptz '2026-06-01 09:00:00+09',999,'makeup','AGGREGATED', timestamptz '2026-06-01 00:00:00+09','makeup_sub','A', false),
 (9102,'dummy_c2','FEED', 'dummy_mid',   timestamptz '2026-06-01 14:00:00+09',999,'makeup','AGGREGATED', timestamptz '2026-06-01 00:00:00+09','makeup_sub','A', true),
 (9103,'dummy_c3','REELS','dummy_macro', timestamptz '2026-06-02 09:00:00+09',999,'glow','AGGREGATED',  timestamptz '2026-06-02 00:00:00+09','glow_sub','B', false),
 (9104,'dummy_c4','REELS','dummy_over',  timestamptz '2026-06-03 20:00:00+09',999,'glow','AGGREGATED',  timestamptz '2026-06-03 00:00:00+09','glow_sub','B', false),
 (9105,'dummy_c5','REELS','dummy_micro', timestamptz '2026-06-01 09:30:00+09',999,'kbeauty','AGGREGATED',timestamptz '2026-06-01 00:00:00+09','kbeauty_sub','B', false);

-- 콘텐츠 상세 (payload의 likesCount/commentsCount/videoPlayCount는 generated 컬럼으로 노출됨)
INSERT INTO raw_post_detail(content_id, crawl_run_id, payload, captured_at) VALUES
 (9101,9990,'{"shortCode":"dummy_c1","type":"Video","likesCount":500,"commentsCount":50,"videoPlayCount":10000,"videoDuration":30,"hashtags":["makeup","kbeauty"],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-06-04 09:00:00+09'),
 (9102,9990,'{"shortCode":"dummy_c2","type":"Image","likesCount":2000,"commentsCount":100,"hashtags":[],"mentions":["brand_x"],"productType":"feed"}'::jsonb, timestamptz '2026-06-04 14:00:00+09'),
 (9103,9990,'{"shortCode":"dummy_c3","type":"Video","likesCount":20000,"commentsCount":500,"videoPlayCount":400000,"videoDuration":45,"hashtags":["makeup"],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-06-05 09:00:00+09'),
 (9104,9990,'{"shortCode":"dummy_c4","type":"Video","likesCount":1600,"commentsCount":200,"videoPlayCount":30000,"videoDuration":15,"hashtags":["makeup","glow"],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-06-06 20:00:00+09'),
 (9105,9990,'{"shortCode":"dummy_c5","type":"Video","likesCount":300,"commentsCount":30,"videoPlayCount":8000,"videoDuration":20,"hashtags":["kbeauty"],"mentions":[],"productType":"clips"}'::jsonb, timestamptz '2026-06-04 09:30:00+09');

-- 댓글 (dummy_c1 = content_id 9101): 3건, 작성자 2명, 대댓글 합 3
INSERT INTO raw_comment(content_id, crawl_run_id, payload, captured_at) VALUES
 (9101,9990,'{"ownerUsername":"dummy_fan1","text":"pretty","repliesCount":1,"timestamp":"2026-06-04T09:10:00Z"}'::jsonb, timestamptz '2026-06-04 09:10:00+09'),
 (9101,9990,'{"ownerUsername":"dummy_fan2","text":"love it","repliesCount":0,"timestamp":"2026-06-04T09:11:00Z"}'::jsonb, timestamptz '2026-06-04 09:11:00+09'),
 (9101,9990,'{"ownerUsername":"dummy_fan1","text":"where to buy","repliesCount":2,"timestamp":"2026-06-04T09:12:00Z"}'::jsonb, timestamptz '2026-06-04 09:12:00+09');

-- 실데이터 격리: 더미 외 상세/댓글/프로필 제거 (트랜잭션 안이라 ROLLBACK으로 복구됨).
-- 콘텐츠 기반 전역 집계 뷰는 더미 콘텐츠 5건만, 프로필 기반 집계 뷰는 더미 계정 4개만 보게 된다.
DELETE FROM raw_comment     WHERE content_id NOT IN (SELECT id FROM content WHERE category_id = 999);
DELETE FROM raw_post_detail WHERE content_id NOT IN (SELECT id FROM content WHERE category_id = 999);
DELETE FROM raw_profile     WHERE account_id NOT IN (SELECT id FROM account WHERE username LIKE 'dummy_%');
