-- 결정적 더미데이터 (테스트 전용). run.sh가 BEGIN/ROLLBACK으로 감싸므로 실DB 불변.
-- 신 crawler 스키마(V15) 기준 — 캡션·지표는 raw_media_page(HIKER_V2_CLIPS)·
-- raw_profile(SELF_GQL 내장 타임라인) payload 안에 있다 (스펙 §4).
--
-- 고정 fixture ID (bigserial 실 ID와 충돌하지 않는 높은 값):
--   crawl_run 99990000 / influencer 99990001~99990005 + 99990060(fb) / content 99990101~99990108
--   + 99990120(ra1) + 99990160(fb1)
--   (99990006~99990052·99990109~99990116·9999020x·9999022x는 개별 테스트 파일이 자체 픽스처로
--    쓴다 — 시드에서 쓰면 PK 충돌. 새 시드 ID는 반드시 test/*.test.sql 전체를 grep해 확인할 것)
--
-- 시나리오:
--   dummy_a(99990001)  뷰티 인플루언서 — r1(99990101, 릴스: clips 스냅샷 3 + 타임라인 1 — 핀·최신 메타 검증),
--                  r2(99990102, 타임라인 전용 릴스 — 787건 케이스), f1(99990103, 피드 — views NULL 규칙),
--                  d1(99990104, DISCOVERY 잔재 — 모수 제외·좋아요 비공개 -1 케이스), rn(99990108, 업로드 1일 전 — 숙성 가드)
--   dummy_b(99990002)  뷰티 인플루언서 — r3(99990105, 캡션 결측·유료 협찬 true),
--                  ra1(99990120, APIFY_ACTOR 액터 수집분 — 좋아요 비공개 -1·paidPartnership).
--                  프로필 HIKER_MOBILE만(소스 분기 검증)
--   dummy_co(99990003) 뷰티 회사 — r4(99990106, 모수 제외 검증)
--   dummy_x(99990004)  비뷰티 — r5(99990107, 모수 제외 검증)
--   dummy_e(99990005)  EXCLUDED — 콘텐츠·프로필 없음
--   dummy_fb(99990060) F&B 단독(뷰티 아님) — fb1(99990160, 릴스: D+4 캡처라 timely=false,
--                  계정 유일 콘텐츠라 최근창 안). 분석 후보(04)·서빙 뷰(01·02 — 08-31 서빙
--                  개방으로 모수 편입)에 들고, 20(랜딩)엔 안 드는지 검증

-- 설정 키 결정화: 실DB 오버라이드가 있으면 기대값이 흔들린다 (ROLLBACK으로 복구됨).
DELETE FROM app_setting WHERE key LIKE 'analytics.%';

INSERT INTO crawl_run(id, job, trigger_type, actor_id, status, started_at)
VALUES (99990000, 'dummy', 'MANUAL', 'dummy/actor', 'SUCCEEDED', timestamptz '2026-06-05 00:00:00+09');

INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at) VALUES
 (99990001,'dummy_a' ,'QUALIFIED', 5500, true,  false, timestamptz '2026-06-01 00:00:00+09'),
 (99990002,'dummy_b' ,'QUALIFIED',20000, true,  false, timestamptz '2026-06-01 00:00:00+09'),
 (99990003,'dummy_co','QUALIFIED', 8000, true,  true,  timestamptz '2026-06-01 00:00:00+09'),
 (99990004,'dummy_x' ,'QUALIFIED', 9000, false, false, timestamptz '2026-06-01 00:00:00+09'),
 (99990005,'dummy_e' ,'EXCLUDED',  NULL, NULL,  NULL,  NULL),
 -- F&B 단독 계정(뷰티 아님) — 04 후보·서빙(01·02) 편입 + 랜딩(20) 제외 검증 픽스처.
 (99990060,'dummy_fb','QUALIFIED', 7000, false, false, timestamptz '2026-06-01 00:00:00+09');

-- F&B 축 시드: dummy_a는 뷰티+F&B 겸임(복수 카테고리), dummy_fb는 F&B 단독,
-- 나머지는 F&B 미판정(NULL)
UPDATE influencer SET fnb = true, fnb_company = false, fnb_class = 'INFLUENCER'
WHERE username IN ('dummy_a', 'dummy_fb');

-- 홈/리빙 축 시드: dummy_a는 홈/리빙도 겸임(3축 복수 카테고리), 나머지는 미판정(NULL)
UPDATE influencer SET home_living = true, home_living_company = false, home_living_class = 'INFLUENCER'
WHERE username = 'dummy_a';

INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at, status, first_seen_at, origin, collect_attempts) VALUES
 (99990101,'dummy_r1','REELS','dummy_a' ,99990001, timestamptz '2026-06-01 09:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0),
 (99990102,'dummy_r2','REELS','dummy_a' ,99990001, timestamptz '2026-06-02 09:00:00+09','PENDING', timestamptz '2026-06-02 12:00:00+09','ENUMERATION',0),
 (99990103,'dummy_f1','FEED' ,'dummy_a' ,99990001, timestamptz '2026-06-03 09:00:00+09','PENDING', timestamptz '2026-06-03 12:00:00+09','ENUMERATION',0),
 (99990104,'dummy_d1','FEED' ,'dummy_a' ,99990001, timestamptz '2026-06-04 09:00:00+09','PENDING', timestamptz '2026-06-04 12:00:00+09','DISCOVERY'  ,0),
 (99990105,'dummy_r3','REELS','dummy_b' ,99990002, timestamptz '2026-06-01 10:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0),
 (99990106,'dummy_r4','REELS','dummy_co',99990003, timestamptz '2026-06-01 10:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0),
 (99990107,'dummy_r5','REELS','dummy_x' ,99990004, timestamptz '2026-06-01 10:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0),
 (99990108,'dummy_rn','REELS','dummy_a' ,99990001, now() - interval '1 day','PENDING', now() - interval '1 day','ENUMERATION',0),
 (99990120,'dummy_ra1','REELS','dummy_b' ,99990002, timestamptz '2026-06-05 12:00:00+09','PENDING', timestamptz '2026-06-05 12:00:00+09','ENUMERATION',0),
 -- F&B 단독 계정의 열거 콘텐츠. D+3(06-05) 스냅샷이 없어 timely=false이고, 계정의 유일한
 -- 콘텐츠라 최근 N개 윈도우 안 → 분석 후보에 in_window 경로로 든다(운영 F&B 백로그와 같은 모양).
 (99990160,'dummy_fb1','REELS','dummy_fb',99990060, timestamptz '2026-06-02 09:00:00+09','PENDING', timestamptz '2026-06-02 12:00:00+09','ENUMERATION',0);

-- 프로필. dummy_a는 HIKER_MOBILE(구) + SELF_GQL(신, 내장 타임라인 포함) 2건 — 최신 선택·소스 분기 검증.
-- dummy_b는 HIKER_MOBILE만 — user 래퍼 경로 검증. payload의 taken_at류는 뷰가 읽지 않는다(uploaded_at은 content 소유).
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at) VALUES
 (99990001,99990000,'HIKER_MOBILE','dummy_a',5000,
  '{"status":"ok","user":{"username":"dummy_a","full_name":"더미 에이 구","profile_pic_url":"https://pic/a_old.jpg","follower_count":5000,"following_count":100,"media_count":40,"biography":"bio a old","external_url":"https://link.example/a-old"}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09'),
 (99990002,99990000,'HIKER_MOBILE','dummy_b',20000,
  '{"status":"ok","user":{"username":"dummy_b","full_name":"더미 비","profile_pic_url":"https://pic/b.jpg","follower_count":20000,"following_count":200,"media_count":10,"biography":"bio b"}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09'),
 (99990003,99990000,'HIKER_MOBILE','dummy_co',8000,
  '{"status":"ok","user":{"username":"dummy_co","full_name":"더미 회사","follower_count":8000}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09'),
 (99990004,99990000,'HIKER_MOBILE','dummy_x',9000,
  '{"status":"ok","user":{"username":"dummy_x","full_name":"더미 엑스","follower_count":9000}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09'),
 -- F&B 단독 계정 프로필 — v_accounts 모수·축 컬럼 검증용 (08-31 서빙 개방)
 (99990060,99990000,'HIKER_MOBILE','dummy_fb',7000,
  '{"status":"ok","user":{"username":"dummy_fb","full_name":"더미 푸드","follower_count":7000}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09'),
 (99990001,99990000,'SELF_GQL','dummy_a',5500,
  '{"status":"ok","data":{"user":{
     "username":"dummy_a","full_name":"더미 에이",
     "profile_pic_url":"https://pic/a.jpg","profile_pic_url_hd":"https://pic/a_hd.jpg",
     "biography":"bio a","external_url":"https://link.example/a",
     "edge_followed_by":{"count":5500},"edge_follow":{"count":120},
     "edge_owner_to_timeline_media":{"count":42,"edges":[
       {"node":{"shortcode":"dummy_r1","product_type":"clips","taken_at_timestamp":1780272000,
                "video_view_count":0,
                "edge_media_preview_like":{"count":522},"edge_media_to_comment":{"count":52},
                "edge_media_to_caption":{"edges":[{"node":{"text":"cap r1 tl"}}]},
                "display_url":"https://thumb/r1_tl.jpg"}},
       {"node":{"shortcode":"dummy_r2","product_type":"clips","taken_at_timestamp":1780358400,
                "video_view_count":8000,
                "edge_media_preview_like":{"count":300},"edge_media_to_comment":{"count":30},
                "edge_media_to_caption":{"edges":[{"node":{"text":"cap r2"}}]},
                "display_url":"https://thumb/r2_tl.jpg"}},
       {"node":{"shortcode":"dummy_f1","taken_at_timestamp":1780444800,
                "video_view_count":999,
                "edge_liked_by":{"count":2000},"edge_media_to_comment":{"count":100},
                "edge_media_to_caption":{"edges":[{"node":{"text":"cap f1"}}]},
                "display_url":"https://thumb/f1_tl.jpg"}},
       {"node":{"shortcode":"dummy_d1","product_type":"feed","taken_at_timestamp":1780531200,
                "edge_media_preview_like":{"count":-1},"edge_media_to_comment":{"count":1},
                "edge_media_to_caption":{"edges":[{"node":{"text":"cap d1"}}]},
                "display_url":"https://thumb/d1_tl.jpg"}}
     ]}}}}'::jsonb,
  timestamptz '2026-06-06 12:00:00+09');

-- 릴스 페이지(HIKER_V2_CLIPS). r1은 3페이지(06-02 미성숙 / 06-05 성숙 최이른=핀 / 06-08 최신=메타).
-- rn은 play_count 없이 ig_play_count만(폴백 검증), r3는 캡션 결측 + is_paid_partnership true.
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990001,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_r1","product_type":"clips","taken_at":1780272000,"like_count":500,"comment_count":50,"play_count":10000,"video_duration":30.5,"is_paid_partnership":false,"caption":{"text":"cap r1"},"image_versions2":{"candidates":[{"url":"https://thumb/r1_v1.jpg"}]}}}]}}'::jsonb, timestamptz '2026-06-02 12:00:00+09'),
 (99990001,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_r1","product_type":"clips","taken_at":1780272000,"like_count":520,"comment_count":52,"play_count":11000,"video_duration":30.5,"is_paid_partnership":false,"caption":{"text":"cap r1 v2"},"image_versions2":{"candidates":[{"url":"https://thumb/r1_v2.jpg"}]}}}]}}'::jsonb, timestamptz '2026-06-05 12:00:00+09'),
 (99990001,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_r1","product_type":"clips","taken_at":1780272000,"like_count":530,"comment_count":53,"play_count":12000,"video_duration":30.5,"is_paid_partnership":false,"caption":{"text":"cap r1 v3"},"image_versions2":{"candidates":[{"url":"https://thumb/r1_v3.jpg"}]}}}]}}'::jsonb, timestamptz '2026-06-08 12:00:00+09'),
 (99990001,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_rn","product_type":"clips","taken_at":1781000000,"like_count":5,"comment_count":1,"ig_play_count":100,"caption":{"text":"cap rn"},"image_versions2":{"candidates":[{"url":"https://thumb/rn_v1.jpg"}]}}}]}}'::jsonb, now()),
 (99990002,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_r3","product_type":"clips","taken_at":1780275600,"like_count":1000,"comment_count":80,"play_count":40000,"video_duration":15.0,"is_paid_partnership":true,"image_versions2":{"candidates":[{"url":"https://thumb/r3_v1.jpg"}]}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09'),
 (99990003,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_r4","product_type":"clips","taken_at":1780275600,"like_count":10,"comment_count":1,"play_count":500,"caption":{"text":"cap r4"}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09'),
 (99990004,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_r5","product_type":"clips","taken_at":1780275600,"like_count":20,"comment_count":2,"play_count":600,"caption":{"text":"cap r5"}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09'),
 -- F&B 단독 계정 릴스. 캡처 06-06(D+4)이라 D+3(06-05) 창을 놓쳐 timely=false, 지표는 완비(성숙∧usable).
 (99990060,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_fb1","product_type":"clips","taken_at":1780358400,"like_count":300,"comment_count":30,"play_count":8000,"video_duration":20.0,"is_paid_partnership":false,"caption":{"text":"오늘의 밀키트 후기"},"image_versions2":{"candidates":[{"url":"https://thumb/fb1.jpg"}]}}}]}}'::jsonb, timestamptz '2026-06-06 12:00:00+09');

-- 릴스 액터(APIFY_ACTOR) 페이지 — 임시 전환 기간 수집분. crawler ReelsJob ACTOR 경로의
-- {"items":[...]} 래퍼 형태. ra1은 likesCount -1(비공개→NULL)·paidPartnership 검증용.
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990002,99990000,'APIFY_ACTOR','{"items":[{"shortCode":"dummy_ra1","productType":"clips","timestamp":"2026-06-05T03:00:00.000Z","likesCount":-1,"commentsCount":30,"videoPlayCount":7000,"caption":"액터 캡션 ra1","displayUrl":"https://thumb/ra1.jpg","videoDuration":22.5,"paidPartnership":true}]}'::jsonb, timestamptz '2026-06-06 12:00:00+09');

-- 댓글 3건 (99990101). V8부터 writer/text/written_at은 실컬럼, like_count만 payload에서 추출.
INSERT INTO raw_comment(content_id, crawl_run_id, source, writer, text, written_at, payload, captured_at) VALUES
 (99990101,99990000,'LEGACY_ENVELOPE','dummy_fan1','pretty','2026-06-04T09:10:00Z','{"likesCount":7}'::jsonb,  timestamptz '2026-06-04 09:10:00+09'),
 (99990101,99990000,'LEGACY_ENVELOPE','dummy_fan2','love it',NULL,'{}'::jsonb,                                 timestamptz '2026-06-04 09:11:00+09'),
 (99990101,99990000,'LEGACY_ENVELOPE','dummy_fan1','where to buy','2026-06-04T09:12:00Z','{"likesCount":2}'::jsonb, timestamptz '2026-06-04 09:12:00+09');

-- 실데이터 격리: 더미 외 raw 원형 제거 (ROLLBACK으로 복구). 실 content·influencer 행은 남지만
-- 원형이 없으면 스냅샷·프로필 조인(INNER)에서 자연 탈락 → 상위 뷰는 더미만 본다.
-- 2026-08-31: 하드코딩 ID 범위(구: influencer 99990001~99990005 / content 99990101~99990120)는
-- 범위 밖에 새 픽스처를 추가하면 그 원형을 조용히 지워, 상위 뷰에서 "시드했는데 안 보이는" 형태로
-- 나타난다(dummy_fb=99990060 추가 때 실제로 겪음 — 개별 테스트 파일이 이미 99990006~을 쓰고 있어
-- 범위 안 ID를 고를 수도 없었다). 더미 집합에서 유도해 같은 함정을 반복하지 않는다.
DELETE FROM raw_comment    WHERE content_id NOT IN (
  SELECT id FROM content WHERE short_code LIKE 'dummy\_%');
DELETE FROM raw_media_page WHERE influencer_id NOT IN (
  SELECT id FROM influencer WHERE username LIKE 'dummy\_%');
DELETE FROM raw_profile    WHERE influencer_id NOT IN (
  SELECT id FROM influencer WHERE username LIKE 'dummy\_%');
