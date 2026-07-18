-- 결정적 더미데이터 (테스트 전용). run.sh가 BEGIN/ROLLBACK으로 감싸므로 실DB 불변.
-- 개편 크롤러 신스키마(V8 influencer_pipeline + V9 origin) 기준 —
--   influencer / raw_profile(influencer_id·source·실컬럼 username·followers) /
--   content(influencer_id·origin, 카테고리·광고 컬럼 없음) / raw_media_page({response,items[].media}).
-- 스키마 정본: crawler V8__influencer_pipeline.sql · 소스별 프로필 경로 정본: ProfileExtractor.java
--
-- 고정 fixture ID (bigserial 실 ID와 충돌하지 않는 높은 값):
--   crawl_run 9909990 / influencer 9909001~9909002 / content 9909101~9909104 / raw_media_page 9909401~9909404

INSERT INTO crawl_run(id, job, trigger_type, actor_id, status, started_at)
VALUES (9909990, 'dummy', 'MANUAL', 'dummy/actor', 'SUCCEEDED', timestamptz '2026-06-05 00:00:00+09');

-- 인플루언서 2명. dummy_a는 프로필 스냅샷 2개(최신 선택 검증용).
INSERT INTO influencer(id, username) VALUES (9909001,'dummy_a'), (9909002,'dummy_b');

-- 프로필: 소스 3형태를 전부 시드에 포함 (v_base_profile의 3경로 COALESCE 검증 재료).
--   HIKER_MOBILE {user,*} — dummy_a 구스냅샷 / SELF_GQL {data,user,*} — dummy_a 최신 /
--   DATALIKERS 평탄 — dummy_b. username·followers는 crawler가 추출해 채우는 실컬럼이라
--   시드도 컬럼과 payload를 함께 채운다 (뷰는 컬럼을 읽고, 나머지 필드만 payload 3경로).
-- dummy_a 최신(SELF_GQL)엔 카운트·biography 키를 일부러 뺀다 (없는 키 → NULL 검증, 00 테스트).
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at) VALUES
 (9909001,9909990,'HIKER_MOBILE','dummy_a',5000,
  '{"user":{"username":"dummy_a","full_name":"더미 에이","profile_pic_url":"https://pic/a_old.jpg","follower_count":5000}}'::jsonb,
  timestamptz '2026-06-01 00:00:00+09'),
 (9909001,9909990,'SELF_GQL','dummy_a',5500,
  '{"data":{"user":{"username":"dummy_a","full_name":"더미 에이","profile_pic_url":"https://pic/a.jpg","external_url":"https://link.example/a","edge_followed_by":{"count":5500}}}}'::jsonb,
  timestamptz '2026-06-05 00:00:00+09'),
 (9909002,9909990,'DATALIKERS','dummy_b',20000,
  '{"username":"dummy_b","full_name":"더미 비","profile_pic_url":"https://pic/b.jpg","follower_count":20000}'::jsonb,
  timestamptz '2026-06-01 00:00:00+09');

-- 콘텐츠 4개: dummy_a 릴스2+피드1, dummy_b 릴스1.
-- 카테고리·광고 컬럼은 개편으로 소멸 — base 뷰가 NULL/false 상수로 계약만 유지한다.
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at, status, origin, first_seen_at) VALUES
 (9909101,'dummy_r1','REELS','dummy_a',9909001, timestamptz '2026-06-01 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-06-01 00:00:00+09'),
 (9909102,'dummy_r2','REELS','dummy_a',9909001, timestamptz '2026-06-02 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-06-02 00:00:00+09'),
 (9909103,'dummy_f1','FEED', 'dummy_a',9909001, timestamptz '2026-06-03 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-06-03 00:00:00+09'),
 (9909104,'dummy_r3','REELS','dummy_b',9909002, timestamptz '2026-06-04 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-06-04 00:00:00+09');

-- 열거 페이지 원형: {response,items[].media} (HIKER_V2_CLIPS 형태 — 신 base 뷰가 읽는 유일한 payload 경로).
-- 스냅샷 단위가 게시물이 아니라 "계정 페이지"라, 한 페이지에 여러 게시물 아이템이 실린다.
--   9909401 (06-04): r1 구지표 (likes 500, views 10000, 썸네일 dummy_r1_old.jpg)
--   9909402 (06-05): r1 중간 지표 (520/11000, 썸네일 dummy_r1.jpg) + r2 (ig_play_count만 → 폴백 검증)
--   9909403 (06-06): r1 최신 — 지표는 9909402와 동일하나 image_versions2 누락
--                 (실측 DZjhKALAgx1 재현 — 썸네일 non-null 폴백: 최신값이 아니라 9909402의 값이 살아남아야 함)
--                 + f1 (media_type 1 피드, 조회수 필드 없음 → views NULL 검증)
--   9909404 (06-07): dummy_b 페이지 — r3
INSERT INTO raw_media_page(id, influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (9909401,9909001,9909990,'HIKER_V2_CLIPS',
  '{"response":{"items":[
     {"media":{"code":"dummy_r1","media_type":2,"caption":{"text":"cap r1 old"},"like_count":500,"comment_count":50,"play_count":10000,"video_duration":30,"image_versions2":{"candidates":[{"url":"https://thumb/dummy_r1_old.jpg"}]}}}
   ]}}'::jsonb, timestamptz '2026-06-04 09:00:00+09'),
 (9909402,9909001,9909990,'HIKER_V2_CLIPS',
  '{"response":{"items":[
     {"media":{"code":"dummy_r1","media_type":2,"caption":{"text":"cap r1"},"like_count":520,"comment_count":52,"play_count":11000,"video_duration":30,"image_versions2":{"candidates":[{"url":"https://thumb/dummy_r1.jpg"}]}}},
     {"media":{"code":"dummy_r2","media_type":2,"caption":{"text":"cap r2"},"like_count":300,"comment_count":30,"ig_play_count":7000,"video_duration":20,"image_versions2":{"candidates":[{"url":"https://thumb/dummy_r2.jpg"}]}}}
   ]}}'::jsonb, timestamptz '2026-06-05 09:00:00+09'),
 (9909403,9909001,9909990,'HIKER_V2_CLIPS',
  '{"response":{"items":[
     {"media":{"code":"dummy_r1","media_type":2,"caption":{"text":"cap r1"},"like_count":520,"comment_count":52,"play_count":11000,"video_duration":30}},
     {"media":{"code":"dummy_f1","media_type":1,"caption":{"text":"cap f1"},"like_count":2000,"comment_count":100,"image_versions2":{"candidates":[{"url":"https://thumb/dummy_f1.jpg"}]}}}
   ]}}'::jsonb, timestamptz '2026-06-06 09:00:00+09'),
 (9909404,9909002,9909990,'HIKER_V2_CLIPS',
  '{"response":{"items":[
     {"media":{"code":"dummy_r3","media_type":2,"caption":{"text":"cap r3"},"like_count":1000,"comment_count":80,"play_count":40000,"video_duration":15,"image_versions2":{"candidates":[{"url":"https://thumb/dummy_r3.jpg"}]}}}
   ]}}'::jsonb, timestamptz '2026-06-07 09:00:00+09');

-- 댓글 3건 (9909101). like_count 7 / NULL / 2. 개편 크롤러는 댓글 미수집(MVP 제외) —
-- 구 수집분만 남으므로 LEGACY_ENVELOPE. writer·text·written_at은 이제 실컬럼(추출 저장).
INSERT INTO raw_comment(content_id, crawl_run_id, source, writer, text, written_at, payload, captured_at) VALUES
 (9909101,9909990,'LEGACY_ENVELOPE','dummy_fan1','pretty','2026-06-04T09:10:00Z',
  '{"ownerUsername":"dummy_fan1","text":"pretty","likesCount":7,"timestamp":"2026-06-04T09:10:00Z"}'::jsonb,       timestamptz '2026-06-04 09:10:00+09'),
 (9909101,9909990,'LEGACY_ENVELOPE','dummy_fan2','love it','2026-06-04T09:11:00Z',
  '{"ownerUsername":"dummy_fan2","text":"love it","timestamp":"2026-06-04T09:11:00Z"}'::jsonb,                     timestamptz '2026-06-04 09:11:00+09'),
 (9909101,9909990,'LEGACY_ENVELOPE','dummy_fan1','where to buy','2026-06-04T09:12:00Z',
  '{"ownerUsername":"dummy_fan1","text":"where to buy","likesCount":2,"timestamp":"2026-06-04T09:12:00Z"}'::jsonb, timestamptz '2026-06-04 09:12:00+09');

-- 실데이터 격리: 더미 외 원형(raw) 제거 (트랜잭션 안이라 ROLLBACK으로 복구됨).
-- base 뷰를 조인하는 상위 뷰는 더미 데이터만 보게 된다. content 실행은 남지만
-- 상세(raw_media_page)가 없어 INNER JOIN에서 자연 탈락한다.
DELETE FROM raw_comment     WHERE content_id NOT IN (SELECT id FROM content WHERE short_code LIKE 'dummy_%');
DELETE FROM raw_media_page  WHERE influencer_id NOT IN (SELECT id FROM influencer WHERE username LIKE 'dummy_%');
DELETE FROM raw_profile     WHERE influencer_id NOT IN (SELECT id FROM influencer WHERE username LIKE 'dummy_%');
