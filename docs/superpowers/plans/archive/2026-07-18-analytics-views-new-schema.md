# 분석 뷰 신 스키마 재구축 구현 계획

> 상태: ✅ 구현/실행/반영됨 (2026-07-18, PR #36 머지 · 트랙 A2 완료)
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** analytics/views의 분석 뷰 전체(00~20 + 04 신설)를 신 crawler 스키마(V15) 기준으로 재구축하고, SQL 하니스를 신 스키마 시드로 재작성한다.

**Architecture:** 스펙 [2026-07-17-analytics-views-new-schema-design.md](../../specs/archive/2026-07-17-analytics-views-new-schema-design.md)의 A안(플랫 뷰 체인) — base 층에 평탄화 뷰 2종(clips 아이템·SELF_GQL 타임라인)을 신설해 구 `v_base_detail`/`v_base_detail_history` 인터페이스를 재현하고, 상위 뷰(01~20)는 소스 교체만 한다. 서빙 모수는 뷰티 인플루언서(QUALIFIED ∧ beauty ∧ ¬beauty_company), 미러 계약은 형태 유지.

**Tech Stack:** PostgreSQL 뷰 SQL(raw DB `analytics` 스키마), bash SQL 하니스(BEGIN/ROLLBACK), 미러는 기존 Java(MirrorJob) 무변경.

**전제 (2026-07-18 확인 완료):**
- PR #30(feat/beauty-captions)은 **이미 develop에 머지됨**(07-17 10:23) — Task 1은 develop 최신화만 하면 된다.
- 로컬 `crawler-postgres-1`의 crawler DB는 Flyway V15, 팀 덤프(07-17) 복원 상태. `analytics` 스키마 없음(신규 생성).
- app_setting에 `analytics.*` 키 없음 — 뷰의 COALESCE 기본값이 적용된다.
- 미러 실행 = `./gradlew :analytics:bootRun` (`analytics.mirror-on-startup=true`가 기본).

**작업 디렉토리:** 이 워크트리(`.claude/worktrees/reverent-kirch-f93a40`) 그대로. 커맨드의 상대 경로는 리포 루트 기준.

---

### Task 1: 브랜치 준비 + develop 최신화

**Files:** 없음 (git 조작만)

- [ ] **Step 1: feat 브랜치 생성**

```bash
git fetch origin
git checkout -b feat/analytics-views-new-schema
```

- [ ] **Step 2: origin/develop 머지 (V15 크롤러 개편 유입)**

```bash
git merge origin/develop
```

충돌 예상 지점: `ARCHITECTURE.md` §5·§7 (이 브랜치의 A2 행·결정 기록 vs develop의 beauty-captions 반영). 해소 원칙: **양쪽 행을 모두 보존**(A2 행과 develop 신규 행 공존, §7은 날짜 역순 정렬 유지). 해소 후:

```bash
git add ARCHITECTURE.md && git commit --no-edit
```

- [ ] **Step 3: 스키마 전제 확인**

```bash
docker exec crawler-postgres-1 psql -U crawler -d crawler -tc \
  "SELECT max(version::int) FROM flyway_schema_history;"
```

Expected: `15`

---

### Task 2: 하니스 리셋 — 구 뷰·테스트 삭제 + 신 스키마 시드

**Files:**
- Delete: `analytics/views/00_base.sql`, `01_recent_window.sql`, `02_serving.sql`, `03_analysis_baseline.sql`, `10_account_detail.sql`, `20_landing_stats.sql`
- Delete: `analytics/test/00_base.test.sql`, `01_recent_window.test.sql`, `02_serving.test.sql`, `03_analysis_baseline.test.sql`, `10_account_detail.test.sql`, `20_landing_stats.test.sql`
- Rewrite: `analytics/seed/dummy.sql`
- 유지: `analytics/test/run.sh` (무변경)

구 뷰 파일은 구 스키마(`account_id`·`category_id`·`raw_post_detail`)를 참조해 V15 DB에 적용 자체가 실패한다 — run.sh가 views/*.sql 전체를 적용하므로 먼저 비워야 이후 태스크의 TDD 사이클이 돈다.

- [ ] **Step 1: 구 뷰·테스트 삭제**

```bash
git rm analytics/views/00_base.sql analytics/views/01_recent_window.sql \
  analytics/views/02_serving.sql analytics/views/03_analysis_baseline.sql \
  analytics/views/10_account_detail.sql analytics/views/20_landing_stats.sql
git rm analytics/test/00_base.test.sql analytics/test/01_recent_window.test.sql \
  analytics/test/02_serving.test.sql analytics/test/03_analysis_baseline.test.sql \
  analytics/test/10_account_detail.test.sql analytics/test/20_landing_stats.test.sql
```

- [ ] **Step 2: seed/dummy.sql 전면 재작성**

`analytics/seed/dummy.sql` 전체를 다음 내용으로 교체:

```sql
-- 결정적 더미데이터 (테스트 전용). run.sh가 BEGIN/ROLLBACK으로 감싸므로 실DB 불변.
-- 신 crawler 스키마(V15) 기준 — 캡션·지표는 raw_media_page(HIKER_V2_CLIPS)·
-- raw_profile(SELF_GQL 내장 타임라인) payload 안에 있다 (스펙 §4).
--
-- 고정 fixture ID (bigserial 실 ID와 충돌하지 않는 높은 값):
--   crawl_run 99990000 / influencer 99990001~99990005 / content 99990101~99990108
--
-- 시나리오:
--   dummy_a(99990001)  뷰티 인플루언서 — r1(99990101, 릴스: clips 스냅샷 3 + 타임라인 1 — 핀·최신 메타 검증),
--                  r2(99990102, 타임라인 전용 릴스 — 787건 케이스), f1(99990103, 피드 — views NULL 규칙),
--                  d1(99990104, DISCOVERY 잔재 — 모수 제외·좋아요 비공개 -1 케이스), rn(99990108, 업로드 1일 전 — 숙성 가드)
--   dummy_b(99990002)  뷰티 인플루언서 — r3(99990105, 캡션 결측·유료 협찬 true). 프로필 HIKER_MOBILE만(소스 분기 검증)
--   dummy_co(99990003) 뷰티 회사 — r4(99990106, 모수 제외 검증)
--   dummy_x(99990004)  비뷰티 — r5(99990107, 모수 제외 검증)
--   dummy_e(99990005)  EXCLUDED — 콘텐츠·프로필 없음

-- 설정 키 결정화: 실DB 오버라이드가 있으면 기대값이 흔들린다 (ROLLBACK으로 복구됨).
DELETE FROM app_setting WHERE key LIKE 'analytics.%';

INSERT INTO crawl_run(id, job, trigger_type, actor_id, status, started_at)
VALUES (99990000, 'dummy', 'MANUAL', 'dummy/actor', 'SUCCEEDED', timestamptz '2026-06-05 00:00:00+09');

INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at) VALUES
 (99990001,'dummy_a' ,'QUALIFIED', 5500, true,  false, timestamptz '2026-06-01 00:00:00+09'),
 (99990002,'dummy_b' ,'QUALIFIED',20000, true,  false, timestamptz '2026-06-01 00:00:00+09'),
 (99990003,'dummy_co','QUALIFIED', 8000, true,  true,  timestamptz '2026-06-01 00:00:00+09'),
 (99990004,'dummy_x' ,'QUALIFIED', 9000, false, false, timestamptz '2026-06-01 00:00:00+09'),
 (99990005,'dummy_e' ,'EXCLUDED',  NULL, NULL,  NULL,  NULL);

INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at, status, first_seen_at, origin, collect_attempts) VALUES
 (99990101,'dummy_r1','REELS','dummy_a' ,99990001, timestamptz '2026-06-01 09:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0),
 (99990102,'dummy_r2','REELS','dummy_a' ,99990001, timestamptz '2026-06-02 09:00:00+09','PENDING', timestamptz '2026-06-02 12:00:00+09','ENUMERATION',0),
 (99990103,'dummy_f1','FEED' ,'dummy_a' ,99990001, timestamptz '2026-06-03 09:00:00+09','PENDING', timestamptz '2026-06-03 12:00:00+09','ENUMERATION',0),
 (99990104,'dummy_d1','FEED' ,'dummy_a' ,99990001, timestamptz '2026-06-04 09:00:00+09','PENDING', timestamptz '2026-06-04 12:00:00+09','DISCOVERY'  ,0),
 (99990105,'dummy_r3','REELS','dummy_b' ,99990002, timestamptz '2026-06-01 10:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0),
 (99990106,'dummy_r4','REELS','dummy_co',99990003, timestamptz '2026-06-01 10:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0),
 (99990107,'dummy_r5','REELS','dummy_x' ,99990004, timestamptz '2026-06-01 10:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0),
 (99990108,'dummy_rn','REELS','dummy_a' ,99990001, now() - interval '1 day','PENDING', now() - interval '1 day','ENUMERATION',0);

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
 (99990004,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_r5","product_type":"clips","taken_at":1780275600,"like_count":20,"comment_count":2,"play_count":600,"caption":{"text":"cap r5"}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09');

-- 댓글 3건 (99990101). V8부터 writer/text/written_at은 실컬럼, like_count만 payload에서 추출.
INSERT INTO raw_comment(content_id, crawl_run_id, source, writer, text, written_at, payload, captured_at) VALUES
 (99990101,99990000,'LEGACY_ENVELOPE','dummy_fan1','pretty','2026-06-04T09:10:00Z','{"likesCount":7}'::jsonb,  timestamptz '2026-06-04 09:10:00+09'),
 (99990101,99990000,'LEGACY_ENVELOPE','dummy_fan2','love it',NULL,'{}'::jsonb,                                 timestamptz '2026-06-04 09:11:00+09'),
 (99990101,99990000,'LEGACY_ENVELOPE','dummy_fan1','where to buy','2026-06-04T09:12:00Z','{"likesCount":2}'::jsonb, timestamptz '2026-06-04 09:12:00+09');

-- 실데이터 격리: 더미 외 raw 원형 제거 (ROLLBACK으로 복구). 실 content·influencer 행은 남지만
-- 원형이 없으면 스냅샷·프로필 조인(INNER)에서 자연 탈락 → 상위 뷰는 더미만 본다.
DELETE FROM raw_comment    WHERE content_id NOT BETWEEN 99990101 AND 99990108;
DELETE FROM raw_media_page WHERE influencer_id NOT BETWEEN 99990001 AND 99990005;
DELETE FROM raw_profile    WHERE influencer_id NOT BETWEEN 99990001 AND 99990005;
```

- [ ] **Step 3: run.sh가 "no tests found"로 끝나는지 확인 (뷰·테스트 0개 상태 정상)**

```bash
analytics/test/run.sh
```

Expected: `no tests found` 출력 후 exit 1 (테스트가 아직 없으므로 정상)

- [ ] **Step 4: Commit**

```bash
git add -A analytics/views analytics/test analytics/seed
git commit -m "chore(analytics): 구 스키마 뷰·테스트 초기화 + 신 스키마 더미 시드"
```

---

### Task 3: 00_base.sql — base 층

**Files:**
- Create: `analytics/views/00_base.sql`
- Test: `analytics/test/00_base.test.sql`

> **일회성 선행 처치 (실행 완료 — 07-18)**: 로컬 실DB에 구 시대 `analytics.*` 뷰 17개 + hype_score
> 함수가 잔존해 `CREATE OR REPLACE VIEW`가 컬럼 변경을 거부했다("복원 덤프에 analytics 스키마
> 없음" 전제가 실DB와 달랐음). 재구축이 스키마 전체를 대체하므로
> `DROP SCHEMA analytics CASCADE;` 1회 실행으로 정리(raw 불변). 프레시 DB(CI)에서는 불필요.

- [ ] **Step 1: 실패하는 테스트 작성**

`analytics/test/00_base.test.sql`:

```sql
-- base 뷰 기대값. 시드 근거: seed/dummy.sql 시나리오 주석.
DO $$
BEGIN
  -- v_base_influencer: 판정 컬럼 노출
  ASSERT (SELECT count(*) FROM analytics.v_base_influencer WHERE username LIKE 'dummy_%') = 5,
    'v_base_influencer dummy rows != 5';
  ASSERT (SELECT beauty FROM analytics.v_base_influencer WHERE username = 'dummy_a') = true,
    'v_base_influencer dummy_a beauty != true';
  ASSERT (SELECT beauty_company FROM analytics.v_base_influencer WHERE username = 'dummy_co') = true,
    'v_base_influencer dummy_co beauty_company != true';

  -- v_base_profile: 계정별 최신 1건 + 소스 분기
  ASSERT (SELECT count(*) FROM analytics.v_base_profile WHERE username LIKE 'dummy_%') = 4,
    'v_base_profile dummy rows != 4 (e는 프로필 없음)';
  ASSERT (SELECT followers FROM analytics.v_base_profile WHERE username = 'dummy_a') = 5500,
    'v_base_profile dummy_a followers != 5500 (최신 SELF_GQL 실컬럼)';
  ASSERT (SELECT display_name FROM analytics.v_base_profile WHERE username = 'dummy_a') = '더미 에이',
    'v_base_profile dummy_a display_name != 더미 에이 (data.user 경로)';
  ASSERT (SELECT profile_image_url FROM analytics.v_base_profile WHERE username = 'dummy_a') = 'https://pic/a_hd.jpg',
    'v_base_profile dummy_a image != a_hd.jpg (profile_pic_url_hd 우선)';
  ASSERT (SELECT follows_count FROM analytics.v_base_profile WHERE username = 'dummy_a') = 120,
    'v_base_profile dummy_a follows_count != 120 (edge_follow.count)';
  ASSERT (SELECT posts_count FROM analytics.v_base_profile WHERE username = 'dummy_a') = 42,
    'v_base_profile dummy_a posts_count != 42 (edge_owner_to_timeline_media.count)';
  ASSERT (SELECT biography FROM analytics.v_base_profile WHERE username = 'dummy_a') = 'bio a',
    'v_base_profile dummy_a biography != bio a';
  ASSERT (SELECT external_link FROM analytics.v_base_profile WHERE username = 'dummy_a') = 'https://link.example/a',
    'v_base_profile dummy_a external_link mismatch';
  ASSERT (SELECT display_name FROM analytics.v_base_profile WHERE username = 'dummy_b') = '더미 비',
    'v_base_profile dummy_b display_name != 더미 비 (HIKER_MOBILE user 래퍼 경로)';
  ASSERT (SELECT follows_count FROM analytics.v_base_profile WHERE username = 'dummy_b') = 200,
    'v_base_profile dummy_b follows_count != 200 (following_count)';
  ASSERT (SELECT posts_count FROM analytics.v_base_profile WHERE username = 'dummy_b') = 10,
    'v_base_profile dummy_b posts_count != 10 (media_count)';
  ASSERT (SELECT external_link FROM analytics.v_base_profile WHERE username = 'dummy_b') IS NULL,
    'v_base_profile dummy_b external_link not null (키 없음)';

  -- v_base_reel_item: clips 아이템 평탄화
  ASSERT (SELECT count(*) FROM analytics.v_base_reel_item WHERE short_code LIKE 'dummy_%') = 7,
    'v_base_reel_item dummy rows != 7 (r1x3 + rn + r3 + r4 + r5)';
  ASSERT (SELECT views FROM analytics.v_base_reel_item WHERE short_code = 'dummy_rn') = 100,
    'v_base_reel_item rn views != 100 (ig_play_count 폴백)';
  ASSERT (SELECT paid_partnership FROM analytics.v_base_reel_item WHERE short_code = 'dummy_r3') = true,
    'v_base_reel_item r3 paid_partnership != true';
  ASSERT (SELECT caption FROM analytics.v_base_reel_item WHERE short_code = 'dummy_r3') IS NULL,
    'v_base_reel_item r3 caption not null (캡션 결측)';

  -- v_base_timeline_item: 타임라인 노드 평탄화
  ASSERT (SELECT count(*) FROM analytics.v_base_timeline_item WHERE short_code LIKE 'dummy_%') = 4,
    'v_base_timeline_item dummy rows != 4';
  ASSERT (SELECT views FROM analytics.v_base_timeline_item WHERE short_code = 'dummy_r1') IS NULL,
    'v_base_timeline_item r1 views not null (video_view_count 0 → NULL)';
  ASSERT (SELECT views FROM analytics.v_base_timeline_item WHERE short_code = 'dummy_r2') = 8000,
    'v_base_timeline_item r2 views != 8000';
  ASSERT (SELECT likes FROM analytics.v_base_timeline_item WHERE short_code = 'dummy_f1') = 2000,
    'v_base_timeline_item f1 likes != 2000 (edge_liked_by 폴백)';
  ASSERT (SELECT views FROM analytics.v_base_timeline_item WHERE short_code = 'dummy_f1') = 999,
    'v_base_timeline_item f1 views != 999 (아이템 층은 원값 — FEED 게이트는 스냅샷 층)';
  ASSERT (SELECT likes FROM analytics.v_base_timeline_item WHERE short_code = 'dummy_d1') IS NULL,
    'v_base_timeline_item d1 likes not null (좋아요 비공개 -1 → NULL)';

  -- v_base_content_snapshot: UNION + content_type 게이트 + 합성 id 유일성
  ASSERT (SELECT count(*) FROM analytics.v_base_content_snapshot WHERE content_id BETWEEN 99990101 AND 99990108) = 11,
    'v_base_content_snapshot dummy rows != 11 (r1:4, r2·f1·d1·rn·r3·r4·r5:1)';
  ASSERT (SELECT views FROM analytics.v_base_content_snapshot WHERE content_id = 99990103) IS NULL,
    'v_base_content_snapshot f1 views not null (FEED → 무조건 NULL)';
  ASSERT (SELECT count(*) = count(DISTINCT id) FROM analytics.v_base_content_snapshot),
    'v_base_content_snapshot 합성 id 중복';

  -- v_base_detail: 콘텐츠별 최신 1건
  ASSERT (SELECT likes FROM analytics.v_base_detail WHERE content_id = 99990101) = 530,
    'v_base_detail r1 likes != 530 (최신 06-08 스냅샷)';
  ASSERT (SELECT views FROM analytics.v_base_detail WHERE content_id = 99990101) = 12000,
    'v_base_detail r1 views != 12000';
  ASSERT (SELECT caption FROM analytics.v_base_detail WHERE content_id = 99990101) = 'cap r1 v3',
    'v_base_detail r1 caption != cap r1 v3';
  ASSERT (SELECT thumbnail_url FROM analytics.v_base_detail WHERE content_id = 99990101) = 'https://thumb/r1_v3.jpg',
    'v_base_detail r1 thumbnail mismatch';
  ASSERT (SELECT views FROM analytics.v_base_detail WHERE content_id = 99990102) = 8000,
    'v_base_detail r2 views != 8000 (타임라인 전용 릴스)';

  -- v_base_comment
  ASSERT (SELECT count(*) FROM analytics.v_base_comment WHERE content_id = 99990101) = 3,
    'v_base_comment 99990101 rows != 3';
  ASSERT (SELECT max(like_count) FROM analytics.v_base_comment WHERE content_id = 99990101) = 7,
    'v_base_comment 99990101 max like_count != 7';
END $$;
```

- [ ] **Step 2: 실패 확인**

```bash
analytics/test/run.sh test/00_base.test.sql
```

Expected: FAIL — `relation "analytics.v_base_influencer" does not exist`

- [ ] **Step 3: 뷰 작성**

`analytics/views/00_base.sql`:

```sql
-- base 뷰: raw 테이블·payload를 직접 만지는 유일한 SQL (ARCHITECTURE.md §4-4).
-- 신 크롤러(V15)는 상세 수집 없이 열거만 한다 — 캡션·지표는 raw_media_page(HIKER_V2_CLIPS
-- 릴스 페이지)·raw_profile(SELF_GQL 내장 타임라인) payload 안. 추출 경로는 crawler
-- MediaItemExtractor·ProfileExtractor와 정합 (스펙 2026-07-17 §4 — 계약은 crawler가 정의).
-- raw_post_detail(LEGACY 전용)·HIKER_GQL_MEDIAS(유휴 경로)·reel_parse(로컬 실험)는 제외.
CREATE SCHEMA IF NOT EXISTS analytics;

-- influencer 노출 — 서빙 모수(뷰티 인플루언서) 필터 재료. 필터 자체는 상위 뷰(01·02·20) 몫.
CREATE OR REPLACE VIEW analytics.v_base_influencer AS
SELECT
  id AS influencer_id,
  username,
  status,
  followers,
  beauty,
  beauty_company,
  beauty_judged_at
FROM influencer;

-- 계정별 최신 프로필 1건. 실컬럼(username·followers) 우선, payload 폴백.
-- 파생 필드는 source별 경로 분기 — SELF_GQL은 data.user, HIKER_MOBILE·DATALIKERS는
-- user 래퍼(없으면 최상위 — ProfileExtractor.user()와 동일), LEGACY_ENVELOPE·기타는 flat 키.
CREATE OR REPLACE VIEW analytics.v_base_profile AS
SELECT DISTINCT ON (influencer_id)
  influencer_id,
  COALESCE(username, payload#>>'{data,user,username}',
           payload#>>'{user,username}', payload->>'username')          AS username,
  COALESCE(followers,
           (payload#>>'{data,user,edge_followed_by,count}')::bigint,
           (payload#>>'{user,follower_count}')::bigint,
           (payload->>'follower_count')::bigint,
           (payload->>'followersCount')::bigint)                       AS followers,
  captured_at,
  CASE
    WHEN source = 'SELF_GQL' THEN payload#>>'{data,user,full_name}'
    WHEN source IN ('HIKER_MOBILE','DATALIKERS')
      THEN COALESCE(payload#>>'{user,full_name}', payload->>'full_name')
    ELSE payload->>'fullName'
  END AS display_name,
  CASE
    WHEN source = 'SELF_GQL' THEN COALESCE(payload#>>'{data,user,profile_pic_url_hd}',
                                           payload#>>'{data,user,profile_pic_url}')
    WHEN source IN ('HIKER_MOBILE','DATALIKERS')
      THEN COALESCE(payload#>>'{user,profile_pic_url}', payload->>'profile_pic_url')
    ELSE payload->>'profilePicUrl'
  END AS profile_image_url,
  CASE
    WHEN source = 'SELF_GQL' THEN (payload#>>'{data,user,edge_follow,count}')::bigint
    WHEN source IN ('HIKER_MOBILE','DATALIKERS')
      THEN COALESCE(payload#>>'{user,following_count}', payload->>'following_count')::bigint
    ELSE (payload->>'followsCount')::bigint
  END AS follows_count,
  CASE
    WHEN source = 'SELF_GQL' THEN (payload#>>'{data,user,edge_owner_to_timeline_media,count}')::bigint
    WHEN source IN ('HIKER_MOBILE','DATALIKERS')
      THEN COALESCE(payload#>>'{user,media_count}', payload->>'media_count')::bigint
    ELSE (payload->>'postsCount')::bigint
  END AS posts_count,
  CASE
    WHEN source = 'SELF_GQL' THEN payload#>>'{data,user,biography}'
    WHEN source IN ('HIKER_MOBILE','DATALIKERS')
      THEN COALESCE(payload#>>'{user,biography}', payload->>'biography')
    ELSE payload->>'biography'
  END AS biography,
  CASE
    WHEN source = 'SELF_GQL' THEN payload#>>'{data,user,external_url}'
    WHEN source IN ('HIKER_MOBILE','DATALIKERS')
      THEN COALESCE(payload#>>'{user,external_url}', payload->>'external_url')
    ELSE payload->>'externalUrl'
  END AS external_link
FROM raw_profile
ORDER BY influencer_id, captured_at DESC, id DESC;

-- 릴스 페이지 아이템 평탄화 (HIKER_V2_CLIPS). item_ordinal = 페이지 내 위치(원형 불변 → 안정)
-- — 합성 스냅샷 id 재료. 실DB 전수에서 flat 접두사(1l/1f) 0건 확인 — 평문 키만 파싱.
-- 좋아요 비공개는 -1 센티널로 온다 → NULL(미상)로 정규화 — hype·평균 오염을 base에서 차단.
CREATE OR REPLACE VIEW analytics.v_base_reel_item AS
SELECT
  p.id            AS page_id,
  it.ord          AS item_ordinal,
  p.influencer_id,
  p.captured_at,
  m.media->>'code'                                        AS short_code,
  NULLIF((m.media->>'like_count')::bigint, -1)            AS likes,
  (m.media->>'comment_count')::bigint                     AS comments_count,
  COALESCE((m.media->>'play_count')::bigint,
           (m.media->>'ig_play_count')::bigint)           AS views,
  m.media->'caption'->>'text'                             AS caption,
  m.media#>>'{image_versions2,candidates,0,url}'          AS thumbnail_url,
  (m.media->>'video_duration')::numeric                   AS video_duration,
  COALESCE((m.media->>'is_paid_partnership')::boolean, false) AS paid_partnership
FROM (SELECT * FROM raw_media_page
      WHERE source = 'HIKER_V2_CLIPS'
        AND jsonb_typeof(payload#>'{response,items}') = 'array') p
CROSS JOIN LATERAL jsonb_array_elements(p.payload#>'{response,items}')
  WITH ORDINALITY AS it(item, ord)
CROSS JOIN LATERAL (SELECT it.item->'media' AS media) m;

-- SELF_GQL 내장 타임라인 노드 평탄화. 타임라인은 피드 전용이 아니다 — product_type='clips'
-- 노드(릴스)가 다수라 릴스 스냅샷 폴백 소스로도 쓴다. video_view_count 0은 미공개 표기 → NULL.
-- 좋아요 비공개 -1도 동일하게 NULL.
CREATE OR REPLACE VIEW analytics.v_base_timeline_item AS
SELECT
  p.id            AS profile_id,
  it.ord          AS item_ordinal,
  p.influencer_id,
  p.captured_at,
  n.node->>'shortcode'                                    AS short_code,
  NULLIF(COALESCE((n.node#>>'{edge_media_preview_like,count}')::bigint,
                  (n.node#>>'{edge_liked_by,count}')::bigint), -1) AS likes,
  (n.node#>>'{edge_media_to_comment,count}')::bigint      AS comments_count,
  NULLIF((n.node->>'video_view_count')::bigint, 0)        AS views,
  n.node#>>'{edge_media_to_caption,edges,0,node,text}'    AS caption,
  n.node->>'display_url'                                  AS thumbnail_url,
  n.node->>'product_type'                                 AS product_type
FROM (SELECT * FROM raw_profile
      WHERE source = 'SELF_GQL'
        AND jsonb_typeof(payload#>'{data,user,edge_owner_to_timeline_media,edges}') = 'array') p
CROSS JOIN LATERAL jsonb_array_elements(p.payload#>'{data,user,edge_owner_to_timeline_media,edges}')
  WITH ORDINALITY AS it(edge, ord)
CROSS JOIN LATERAL (SELECT it.edge->'node' AS node) n;

-- 콘텐츠 메타 (content 테이블 노출 — origin·status·influencer_id 포함)
CREATE OR REPLACE VIEW analytics.v_base_content AS
SELECT
  id AS content_id,
  short_code,
  content_type,
  owner_username,
  influencer_id,
  uploaded_at,
  origin,
  status
FROM content;

-- 지표 스냅샷 이력 (구 v_base_detail_history 후계) — 열거 원형 1건 = 스냅샷 1행,
-- captured_at = 원형 수집 시각(재방문마다 누적 → +3일 고정 규칙 성립).
-- 합성 id = (원본행 id × 1000 + 아이템 서수) × 2 + 소스태그(reel=0, timeline=1) — 유일·안정 bigint.
-- 서수 < 1000 전제(현 크롤러는 원형 1행당 아이템 ~12개) — 초과 시 이웃 id 대역과 겹치지만,
-- 겹침은 content_metric_snapshots 미러 PK 위반으로 시끄럽게 드러난다(무언 오염 아님).
-- views NULL 규칙(§6): FEED는 무조건 NULL(타임라인에 값이 있어도 게이트), 릴스는 소스 값.
-- content_type은 content 테이블이 정본(crawler 판정 우선), 조인 키는 short_code.
CREATE OR REPLACE VIEW analytics.v_base_content_snapshot AS
SELECT
  (r.page_id * 1000 + r.item_ordinal) * 2 AS id,
  c.content_id,
  r.captured_at,
  r.likes,
  r.comments_count,
  CASE WHEN c.content_type = 'REELS' THEN r.views END AS views,
  r.caption,
  r.thumbnail_url,
  r.video_duration,
  r.paid_partnership
FROM analytics.v_base_reel_item r
JOIN analytics.v_base_content c USING (short_code)
UNION ALL
SELECT
  (t.profile_id * 1000 + t.item_ordinal) * 2 + 1 AS id,
  c.content_id,
  t.captured_at,
  t.likes,
  t.comments_count,
  CASE WHEN c.content_type = 'REELS' THEN t.views END AS views,
  t.caption,
  t.thumbnail_url,
  NULL::numeric AS video_duration,
  false AS paid_partnership
FROM analytics.v_base_timeline_item t
JOIN analytics.v_base_content c USING (short_code);

-- 콘텐츠별 최신 스냅샷 1건 (구 v_base_detail 후계). 메타(캡션·썸네일)는 최신 수집분
-- — 인스타 CDN 서명 URL ~4일 만료 대응(§6). 지표 고정(+3일)은 02 서빙 층 몫.
CREATE OR REPLACE VIEW analytics.v_base_detail AS
SELECT DISTINCT ON (content_id)
  content_id,
  caption,
  likes,
  comments_count,
  views,
  video_duration,
  thumbnail_url,
  paid_partnership,
  captured_at
FROM analytics.v_base_content_snapshot
ORDER BY content_id, captured_at DESC, id DESC;

-- 댓글 평탄화 (V8부터 writer/text/written_at 실컬럼, like_count만 payload 추출)
CREATE OR REPLACE VIEW analytics.v_base_comment AS
SELECT
  id AS comment_id,
  content_id,
  writer,
  text,
  (payload->>'likesCount')::bigint AS like_count,
  written_at
FROM raw_comment;
```

- [ ] **Step 4: 통과 확인**

```bash
analytics/test/run.sh test/00_base.test.sql
```

Expected: `PASS: test/00_base.test.sql` → `ALL GREEN`

- [ ] **Step 5: Commit**

```bash
git add analytics/views/00_base.sql analytics/test/00_base.test.sql
git commit -m "feat(analytics): base 뷰 신 스키마 재작성 — clips·타임라인 평탄화 + 스냅샷 UNION"
```

---

### Task 4: 01_recent_window.sql — 최근 N개 윈도우 + 모수 필터

**Files:**
- Create: `analytics/views/01_recent_window.sql`
- Test: `analytics/test/01_recent_window.test.sql`

- [ ] **Step 1: 실패하는 테스트 작성**

`analytics/test/01_recent_window.test.sql`:

```sql
-- 최근 N개 윈도우 + 서빙 모수(뷰티 인플루언서 ∩ ENUMERATION) 기대값.
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_recent_content WHERE owner_username LIKE 'dummy_%') = 5,
    'v_recent_content dummy rows != 5 (a:r1·r2·f1·rn + b:r3)';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_recent_content
                     WHERE short_code IN ('dummy_d1','dummy_r4','dummy_r5')),
    'v_recent_content에 제외 대상 존재 (DISCOVERY·회사·비뷰티)';
  ASSERT (SELECT recency_rank FROM analytics.v_recent_content WHERE short_code = 'dummy_rn') = 1,
    'v_recent_content rn recency_rank != 1 (최신 업로드)';
  ASSERT (SELECT recency_rank FROM analytics.v_recent_content WHERE short_code = 'dummy_r1') = 4,
    'v_recent_content r1 recency_rank != 4';
  ASSERT (SELECT ad_marked FROM analytics.v_recent_content WHERE short_code = 'dummy_r3') = true,
    'v_recent_content r3 ad_marked != true (is_paid_partnership)';
  ASSERT (SELECT ad_marked FROM analytics.v_recent_content WHERE short_code = 'dummy_r1') = false,
    'v_recent_content r1 ad_marked != false';
END $$;

-- 윈도우 컷: N=2로 줄이면 dummy_a는 최신 2개(rn·f1)만 남는다.
INSERT INTO app_setting(key, value) VALUES ('analytics.recent-window', '2');
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_recent_content WHERE owner_username = 'dummy_a') = 2,
    'v_recent_content 윈도우 N=2 미적용';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_recent_content WHERE short_code = 'dummy_r1'),
    'v_recent_content N=2인데 r1(rank 4) 잔존';
END $$;
DELETE FROM app_setting WHERE key = 'analytics.recent-window';
```

- [ ] **Step 2: 실패 확인**

```bash
analytics/test/run.sh test/01_recent_window.test.sql
```

Expected: FAIL — `relation "analytics.v_recent_content" does not exist`

- [ ] **Step 3: 뷰 작성**

`analytics/views/01_recent_window.sql`:

```sql
-- 최근 N개 윈도우 (ARCHITECTURE.md §4-1): 모든 계정 단위 지표의 공통 밑판.
-- N은 app_setting 'analytics.recent-window' (기본 12) — 재배포 없이 런타임 조정.
-- 서빙 모수 필터의 진입점: 뷰티 인플루언서(QUALIFIED ∧ beauty ∧ ¬beauty_company)의
-- ENUMERATION 콘텐츠 중 스냅샷 있는 것만 (INNER JOIN 의도 — 스펙 2026-07-17 §5).
-- 같은 필터가 02(v_serving_content)·20(micro_account)에도 있다(뷰 적용 순서상 공유 불가) —
-- 모수를 바꿀 땐 세 곳을 같이 고친다.
-- 구 버전 대비: category_id·main_group 소멸(raw에서 제거 — B4 캡션 분류가 대체 소스),
-- ad_marked는 이름 유지 + 소스만 최신 스냅샷의 is_paid_partnership(릴스 전용, 피드 false).
CREATE OR REPLACE VIEW analytics.v_recent_content AS
WITH ranked AS (
  SELECT
    c.content_id,
    c.short_code,
    c.owner_username,
    c.uploaded_at,
    c.content_type,
    d.paid_partnership AS ad_marked,
    d.likes,
    d.comments_count,
    d.views,
    d.video_duration,
    row_number() OVER (PARTITION BY c.owner_username
                       ORDER BY c.uploaded_at DESC, c.content_id DESC) AS recency_rank
  FROM analytics.v_base_content c
  JOIN analytics.v_base_influencer i ON i.influencer_id = c.influencer_id
  JOIN analytics.v_base_detail d USING (content_id)
  WHERE c.origin = 'ENUMERATION'
    AND i.status = 'QUALIFIED' AND i.beauty AND NOT i.beauty_company
)
SELECT *
FROM ranked
WHERE recency_rank <= COALESCE(
  (SELECT value::int FROM app_setting WHERE key = 'analytics.recent-window'), 12);
```

- [ ] **Step 4: 통과 확인**

```bash
analytics/test/run.sh test/01_recent_window.test.sql
```

Expected: `PASS` → `ALL GREEN`

- [ ] **Step 5: Commit**

```bash
git add analytics/views/01_recent_window.sql analytics/test/01_recent_window.test.sql
git commit -m "feat(analytics): 최근 N개 윈도우 신 스키마 이식 — 뷰티 모수 필터 진입점"
```

---

### Task 5: 02_serving.sql — 서빙 4종 + hype_score

**Files:**
- Create: `analytics/views/02_serving.sql`
- Test: `analytics/test/02_serving.test.sql`

- [ ] **Step 1: 실패하는 테스트 작성**

`analytics/test/02_serving.test.sql`:

```sql
-- 서빙 뷰 기대값 — 미러 계약 형태(컬럼 이름·순서)는 구 버전과 동일해야 한다.
DO $$
BEGIN
  -- v_accounts: 뷰티 인플루언서 ∩ 프로필 보유
  ASSERT (SELECT count(*) FROM analytics.v_accounts WHERE handle LIKE 'dummy_%') = 2,
    'v_accounts dummy rows != 2 (a·b만)';
  ASSERT EXISTS (SELECT 1 FROM analytics.v_accounts WHERE handle = 'dummy_a' AND followers = 5500),
    'v_accounts dummy_a followers != 5500';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_accounts WHERE handle IN ('dummy_co','dummy_x','dummy_e')),
    'v_accounts에 모수 제외 대상 존재';

  -- v_contents: +3일 고정(성숙 최이른) + 최신 메타 + 피드 NULL
  ASSERT (SELECT count(*) FROM analytics.v_contents WHERE account_handle LIKE 'dummy_%') = 5,
    'v_contents dummy rows != 5';
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 11000,
    'v_contents r1 views != 11000 (06-05 성숙 최이른 스냅샷 고정)';
  ASSERT (SELECT likes FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 520,
    'v_contents r1 likes != 520 (고정 스냅샷)';
  ASSERT (SELECT caption FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 'cap r1 v3',
    'v_contents r1 caption != cap r1 v3 (메타는 최신)';
  ASSERT (SELECT thumbnail_url FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 'https://thumb/r1_v3.jpg',
    'v_contents r1 thumbnail != 최신 스냅샷';
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_rn') = 100,
    'v_contents rn views != 100 (성숙 스냅샷 없음 → 최신 폴백)';
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_f1') IS NULL,
    'v_contents f1 views not null (피드)';
  ASSERT (SELECT original_url FROM analytics.v_contents WHERE short_code = 'dummy_r1')
         = 'https://www.instagram.com/p/dummy_r1/',
    'v_contents r1 original_url mismatch (short_code 합성)';
  ASSERT (SELECT content_type FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 'reels',
    'v_contents r1 content_type != reels (lower)';
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_r1') IS NOT NULL,
    'v_contents r1 hype_score is null';

  -- v_content_metric_snapshots: 이력 + 합성 id + 모수
  ASSERT (SELECT count(*) FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_r1') = 4,
    'v_content_metric_snapshots r1 rows != 4';
  ASSERT (SELECT count(*) = count(DISTINCT id) FROM analytics.v_content_metric_snapshots
          WHERE short_code LIKE 'dummy_%'),
    'v_content_metric_snapshots 합성 id 중복';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_content_metric_snapshots
                     WHERE short_code IN ('dummy_d1','dummy_r4','dummy_r5')),
    'v_content_metric_snapshots에 모수 제외 대상 존재';

  -- v_content_comments: 마스킹
  ASSERT (SELECT count(*) FROM analytics.v_content_comments WHERE short_code = 'dummy_r1') = 3,
    'v_content_comments r1 rows != 3';
  ASSERT (SELECT count(*) FROM analytics.v_content_comments
          WHERE short_code = 'dummy_r1' AND author_masked = 'dum***') = 3,
    'v_content_comments author_masked != dum***';
END $$;
```

- [ ] **Step 2: 실패 확인**

```bash
analytics/test/run.sh test/02_serving.test.sql
```

Expected: FAIL — `relation "analytics.v_accounts" does not exist`

- [ ] **Step 3: 뷰 작성**

`analytics/views/02_serving.sql` — **hype_score 함수는 구 버전 그대로**(무변경 복사), 서빙 뷰 4종은 컬럼 이름·순서 유지 + 소스 교체:

```sql
-- 서빙 형태 뷰: 미러 테이블과 1:1 (컬럼 이름·순서 = Flyway DDL = contract-analysis record).
-- 컬럼을 바꾸면 세 곳을 같은 PR에서 바꾼다 (ARCHITECTURE.md §4-5).

-- hype_score 산식 (스펙 5.4, 2026-07-15 API 스펙 정렬 — 구 원값 방식(릴스=조회수, 피드=좋아요+댓글) 폐기).
-- 결과 0~100. 두 서빙 뷰(v_contents·v_content_metric_snapshots)가 공유 — 신선도 기준 시각만 호출부가 정한다.
--   릴스: score = round(cbrt(reach × engage × fresh) × 100)
--     reach  = LEAST(ln(1 + views/(followers+1000)) / ln(31), 1)
--     engage = LEAST(LEAST((likes + comments×3)/views, 0.5) / 0.12, 1)
--   피드(views 항상 NULL): er = (likes + comments×3)/(followers+1000), axis = LEAST(LEAST(er, 0.3)/0.10, 1)
--     score = round(cbrt(axis² × fresh) × 100) — 축 제곱으로 릴스(3축 곱)와 스케일 균형.
--   fresh = 0.5 ^ (경과일/7) — elapsed_days는 호출부가 계산해 넘기고, 음수 클램프는 함수 안(GREATEST 0).
-- NULL 규칙: 릴스인데 views NULL → NULL, likes·comments 중 NULL → NULL (피드 조회수 항상 NULL — CLAUDE.md 함정).
--   LEAST/GREATEST는 NULL 인자를 무시해 NULL이 전파되지 않으므로 명시 가드가 필수다.
CREATE OR REPLACE FUNCTION analytics.hype_score(
  content_type text, views bigint, likes bigint, comments bigint,
  followers bigint, elapsed_days numeric
) RETURNS bigint
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
    WHEN likes IS NULL OR comments IS NULL
         OR (content_type = 'reels' AND views IS NULL) THEN NULL
    WHEN content_type = 'reels' THEN
      round(power(
        LEAST(ln(1 + views::numeric / (COALESCE(followers, 0) + 1000)) / ln(31), 1)
        * LEAST(LEAST((likes + comments * 3)::numeric / NULLIF(views, 0), 0.5) / 0.12, 1)
        * power(0.5, GREATEST(elapsed_days, 0) / 7.0),
        1.0 / 3.0) * 100)::bigint
    ELSE
      round(power(
        power(LEAST(LEAST((likes + comments * 3)::numeric
                          / (COALESCE(followers, 0) + 1000), 0.3) / 0.10, 1), 2)
        * power(0.5, GREATEST(elapsed_days, 0) / 7.0),
        1.0 / 3.0) * 100)::bigint
  END
$$;

-- 서빙 모수: 뷰티 인플루언서(QUALIFIED ∧ beauty ∧ ¬beauty_company)의 ENUMERATION 콘텐츠
-- (스펙 2026-07-17 §2 결정 2). 아래 뷰들이 공유하는 필터 밑판 — 미러 안 함.
-- 같은 필터가 01(v_recent_content)·20(micro_account)에도 있다 — 모수를 바꿀 땐 세 곳을 같이.
CREATE OR REPLACE VIEW analytics.v_serving_content AS
SELECT c.content_id, c.short_code, c.owner_username, c.uploaded_at, c.content_type
FROM analytics.v_base_content c
JOIN analytics.v_base_influencer i ON i.influencer_id = c.influencer_id
WHERE c.origin = 'ENUMERATION'
  AND i.status = 'QUALIFIED' AND i.beauty AND NOT i.beauty_company;

-- 계정 (자연키 handle = 인스타 username). 뷰티 모수 ∩ 프로필 보유 (INNER JOIN 의도).
CREATE OR REPLACE VIEW analytics.v_accounts AS
SELECT
  p.username AS handle,
  p.display_name,
  p.profile_image_url,
  p.followers,
  p.external_link
FROM analytics.v_base_profile p
JOIN analytics.v_base_influencer i USING (influencer_id)
WHERE i.status = 'QUALIFIED' AND i.beauty AND NOT i.beauty_company;

-- 콘텐츠 팩트. 지표(views·likes·comments·hype_score)는 **업로드 +N일 이후 가장 이른 스냅샷으로
-- 고정**(07-14 정정 ③ — 열거 재방문으로 스냅샷이 누적돼도 서빙 지표는 3일 시점 값 유지).
-- N은 app_setting 'analytics.metric-pin-days' (기본 3). 고정 후보가 없으면(업로드 3일 안 수집분만
-- 있는 신선 게시물) 최신 스냅샷 폴백. 메타(썸네일·캡션)는 최신 스냅샷(v_base_detail) —
-- 썸네일 서명 URL 만료(~4일) 대응. original_url은 short_code로 합성(신 payload에 url 필드 없음).
-- hype_score 신선도는 now() 기준 — 미러 갱신 시점이 랭킹 신선도의 기준 시각이다.
CREATE OR REPLACE VIEW analytics.v_contents AS
WITH snap AS (
  SELECT h.id, h.content_id, h.views, h.likes, h.comments_count, h.captured_at,
         h.captured_at >= e.uploaded_at + make_interval(days => COALESCE(
           (SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)) AS matured
  FROM analytics.v_base_content_snapshot h
  JOIN analytics.v_serving_content e USING (content_id)
),
pinned AS (
  -- 성숙(matured) 스냅샷이 있으면 그중 가장 이른 것, 없으면 최신 것.
  SELECT DISTINCT ON (content_id) content_id, views, likes, comments_count, captured_at
  FROM snap
  ORDER BY content_id, matured DESC,
           CASE WHEN matured THEN captured_at END ASC,
           CASE WHEN matured THEN id END ASC,
           captured_at DESC, id DESC
)
SELECT
  e.short_code,
  e.owner_username AS account_handle,
  d.thumbnail_url,
  d.caption,
  e.uploaded_at AS posted_at,
  lower(e.content_type) AS content_type,
  d.video_duration,
  'https://www.instagram.com/p/' || e.short_code || '/' AS original_url,
  p.views,
  p.likes,
  p.comments_count AS comments,
  analytics.hype_score(lower(e.content_type), p.views, p.likes, p.comments_count, pr.followers,
                       extract(epoch FROM (now() - e.uploaded_at)) / 86400.0) AS hype_score,
  p.captured_at AS metric_captured_at
FROM analytics.v_serving_content e
JOIN analytics.v_base_detail d USING (content_id)
JOIN pinned p USING (content_id)
LEFT JOIN analytics.v_base_profile pr ON pr.username = e.owner_username;

-- 댓글 (작성자는 마스킹해 서빙 — 원문 계정명은 raw에만 둔다). 형태 구 버전 동일.
-- 댓글 수집 게이트 off 동안 신규 유입 없음 — 구 시대 잔존 행 서빙은 무해(고아 short_code는 was가 안 씀).
CREATE OR REPLACE VIEW analytics.v_content_comments AS
SELECT
  m.comment_id AS id,
  c.short_code,
  left(m.writer, 3) || '***' AS author_masked,
  m.text AS body,
  m.like_count
FROM analytics.v_base_comment m
JOIN analytics.v_base_content c USING (content_id);

-- 지표 스냅샷 이력 (게시물 × 수집 시점 1행). contents는 이 중 고정 스냅샷 1건을 편 것 —
-- 랭킹 기본 경로는 contents, as-of 조회·추이만 이 뷰를 쓴다.
-- id = 합성 스냅샷 id (00_base 참조 — 구 시대의 raw_post_detail.id 자연키 대체).
-- hype 산식은 v_contents와 동일 함수 — 신선도만 captured_at 기준(as-of 화면은 "그 시점의 신선도").
CREATE OR REPLACE VIEW analytics.v_content_metric_snapshots AS
SELECT
  h.id,
  e.short_code,
  h.captured_at,
  h.views,
  h.likes,
  h.comments_count AS comments,
  analytics.hype_score(lower(e.content_type), h.views, h.likes, h.comments_count, pr.followers,
                       extract(epoch FROM (h.captured_at - e.uploaded_at)) / 86400.0) AS hype_score
FROM analytics.v_base_content_snapshot h
JOIN analytics.v_serving_content e USING (content_id)
LEFT JOIN analytics.v_base_profile pr ON pr.username = e.owner_username;
```

- [ ] **Step 4: 통과 확인**

```bash
analytics/test/run.sh test/02_serving.test.sql
```

Expected: `PASS` → `ALL GREEN`

- [ ] **Step 5: Commit**

```bash
git add analytics/views/02_serving.sql analytics/test/02_serving.test.sql
git commit -m "feat(analytics): 서빙 뷰 신 스키마 이식 — 계약 형태 유지, 소스만 교체"
```

---

### Task 6: 03_analysis_baseline.sql — 분석 기준선

**Files:**
- Create: `analytics/views/03_analysis_baseline.sql`
- Test: `analytics/test/03_analysis_baseline.test.sql`

- [ ] **Step 1: 실패하는 테스트 작성**

`analytics/test/03_analysis_baseline.test.sql`:

```sql
-- 기준선 기대값. dummy_a 윈도우 = r1(12000)·r2(8000)·f1(NULL)·rn(100) — 최신 스냅샷 기준(v_recent_content).
DO $$
BEGIN
  ASSERT (SELECT recent_contents_count FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 4,
    'baseline r1 recent_contents_count != 4';
  ASSERT (SELECT recent_reels_count FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 3,
    'baseline r1 recent_reels_count != 3 (views 있는 릴스: r1·r2·rn)';
  ASSERT (SELECT recent_reels_avg_views FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 6700,
    'baseline r1 recent_reels_avg_views != 6700 (avg(12000,8000,100))';
  ASSERT (SELECT rank_in_recent_reels FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 1,
    'baseline r1 rank != 1';
  ASSERT (SELECT rank_in_recent_reels FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r2') = 2,
    'baseline r2 rank != 2';
  ASSERT (SELECT recent12_avg_like_count FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 709,
    'baseline r1 avg_like != 709 (avg(530,300,2000,5)=708.75)';
  ASSERT (SELECT category_top_percentile FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') IS NULL,
    'baseline category_top_percentile not null (소스 소멸 — NULL 상수)';
  ASSERT (SELECT category_avg_views FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') IS NULL,
    'baseline category_avg_views not null';
  ASSERT (SELECT category_sample_size FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') IS NULL,
    'baseline category_sample_size not null';
  ASSERT (SELECT captured_at FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1')
         = timestamptz '2026-06-08 12:00:00+09',
    'baseline r1 captured_at != 최신 스냅샷 시각';
END $$;
```

- [ ] **Step 2: 실패 확인**

```bash
analytics/test/run.sh test/03_analysis_baseline.test.sql
```

Expected: FAIL — `relation "analytics.v_analysis_baseline" does not exist`

- [ ] **Step 3: 뷰 작성**

`analytics/views/03_analysis_baseline.sql`:

```sql
-- 콘텐츠별 기준선 (분석 잡 전용 — 미러 안 함, 분석 시점에 content_analyses로 고정 저장).
-- ER = (likes+comments)/views. views NULL(피드)은 ER NULL → 평균에서 제외.
-- 컬럼 형태는 구 버전 유지(기존 분석 Java 무접촉) — category_* 3컬럼은 main_group 소멸로
-- NULL 상수 (B4 캡션 분류 산출물(analysis DB)이 대체 예정 — 스펙 2026-07-17 §5).
CREATE OR REPLACE VIEW analytics.v_analysis_baseline AS
WITH windowed AS (
  -- captured_at은 최신 수집분 우선 정렬용 (썸네일 서명 URL 만료 대응).
  SELECT w.*,
         round((w.likes + w.comments_count)::numeric / NULLIF(w.views, 0), 4) AS er,
         d.captured_at
  FROM analytics.v_recent_content w
  JOIN analytics.v_base_detail d USING (content_id)
),
account_agg AS (
  SELECT owner_username,
         count(*)                                            AS recent_contents_count,
         round(avg(er), 4)                                   AS recent12_avg_engagement_rate,
         round(avg(likes), 0)                                AS recent12_avg_like_count,
         round(avg(comments_count), 0)                       AS recent12_avg_comment_count,
         count(*) FILTER (WHERE lower(content_type) = 'reels' AND views IS NOT NULL) AS recent_reels_count,
         round(avg(views) FILTER (WHERE lower(content_type) = 'reels'), 0)           AS recent_reels_avg_views
  FROM windowed
  GROUP BY owner_username
),
reels_rank AS (
  SELECT content_id,
         rank() OVER (PARTITION BY owner_username ORDER BY views DESC NULLS LAST) AS rank_in_recent_reels
  FROM windowed
  WHERE lower(content_type) = 'reels' AND views IS NOT NULL
)
SELECT
  w.short_code,
  a.recent_reels_avg_views,
  r.rank_in_recent_reels,
  a.recent_reels_count,
  a.recent_contents_count,
  a.recent12_avg_engagement_rate,
  a.recent12_avg_like_count,
  a.recent12_avg_comment_count,
  NULL::smallint AS category_top_percentile,
  NULL::numeric  AS category_avg_views,
  NULL::bigint   AS category_sample_size,
  w.captured_at
FROM windowed w
JOIN account_agg a USING (owner_username)
LEFT JOIN reels_rank r USING (content_id);
```

- [ ] **Step 4: 통과 확인**

```bash
analytics/test/run.sh test/03_analysis_baseline.test.sql
```

Expected: `PASS` → `ALL GREEN`

- [ ] **Step 5: Commit**

```bash
git add analytics/views/03_analysis_baseline.sql analytics/test/03_analysis_baseline.test.sql
git commit -m "feat(analytics): 분석 기준선 신 스키마 이식 — 카테고리 맥락은 NULL 상수"
```

---

### Task 7: 04_analysis_candidates.sql — LLM 캡션 선분석 후보 (신설)

**Files:**
- Create: `analytics/views/04_analysis_candidates.sql`
- Test: `analytics/test/04_analysis_candidates.test.sql`

- [ ] **Step 1: 실패하는 테스트 작성**

`analytics/test/04_analysis_candidates.test.sql`:

```sql
-- LLM 후보 자격: 뷰티 모수 ∩ ENUMERATION ∩ 캡션 존재 ∩ 숙성(3일).
-- 기대: r1·r2·f1 포함 / rn(1일 전 업로드 — 미숙성)·r3(캡션 결측) 제외.
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_analysis_candidates WHERE account_handle LIKE 'dummy_%') = 3,
    'candidates dummy rows != 3 (r1·r2·f1)';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_rn'),
    'candidates에 미숙성(rn) 존재';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r3'),
    'candidates에 캡션 결측(r3) 존재';
  ASSERT (SELECT caption FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r1') = 'cap r1 v3',
    'candidates r1 caption != cap r1 v3 (최신 메타)';
  ASSERT (SELECT followers FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r1') = 5500,
    'candidates r1 followers != 5500';
  ASSERT (SELECT views FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r1') = 11000,
    'candidates r1 views != 11000 (고정 지표 승계)';
END $$;
```

- [ ] **Step 2: 실패 확인**

```bash
analytics/test/run.sh test/04_analysis_candidates.test.sql
```

Expected: FAIL — `relation "analytics.v_analysis_candidates" does not exist`

- [ ] **Step 3: 뷰 작성**

`analytics/views/04_analysis_candidates.sql`:

```sql
-- LLM 캡션 선분석 후보 (분석 잡 전용 — 미러 안 함). 스펙 2026-07-17 §5.
-- raw만 보고 판단 가능한 자격까지만 뷰가 담당: 뷰티 모수 ∩ ENUMERATION ∩ 캡션 존재 ∩
-- 숙성(uploaded_at + 'analytics.analyze-maturity-days'(기본 3)일 경과 — B4 가드 키 승계).
-- '이미 분석됨' 제외(analysis DB content_analyses 대조)·배치 상한·정렬 정책은 Java 몫 —
-- Haiku+Batch 파이프라인(별도 설계)이 이 뷰를 입구로 배치를 구성한다.
-- v_contents 위에 얹는다: 모수·+3일 고정 지표·최신 메타(캡션·썸네일) 규칙을 그대로 승계.
CREATE OR REPLACE VIEW analytics.v_analysis_candidates AS
SELECT
  v.short_code,
  v.content_type,
  v.account_handle,
  v.posted_at AS uploaded_at,
  v.caption,
  v.thumbnail_url,
  pr.followers,
  v.views,
  v.likes,
  v.comments,
  v.metric_captured_at
FROM analytics.v_contents v
LEFT JOIN analytics.v_base_profile pr ON pr.username = v.account_handle
WHERE v.caption IS NOT NULL AND btrim(v.caption) <> ''
  AND v.posted_at + make_interval(days => COALESCE(
        (SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-maturity-days'), 3)) <= now();
```

- [ ] **Step 4: 통과 확인**

```bash
analytics/test/run.sh test/04_analysis_candidates.test.sql
```

Expected: `PASS` → `ALL GREEN`

- [ ] **Step 5: Commit**

```bash
git add analytics/views/04_analysis_candidates.sql analytics/test/04_analysis_candidates.test.sql
git commit -m "feat(analytics): LLM 캡션 선분석 후보 뷰 신설 — 숙성 가드·캡션 필수"
```

---

### Task 8: 10_account_detail.sql — 인플루언서 상세 3종

**Files:**
- Create: `analytics/views/10_account_detail.sql`
- Test: `analytics/test/10_account_detail.test.sql`

- [ ] **Step 1: 실패하는 테스트 작성**

`analytics/test/10_account_detail.test.sql`:

```sql
-- 인플루언서 상세 기대값. dummy_a 윈도우(업로드순): r1(12000)·r2(8000)·f1(NULL)·rn(100).
-- metric='views'(views>0 3개 ≥ max(3, 4/2)), trend: older avg(12000,8000)=10000 / newer 100(f1 NULL 제외)
-- → -99% down. avg_er_pct = avg(583,330,2100,6 각각 /5500)*100 = 13.7.
-- dummy_b: 표본 1 → metric='likes'(views_count 1 < 3), r3가 광고라 organic 표본 0 → ad_drop_pct NULL.
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_recent WHERE owner_username LIKE 'dummy_%') = 5,
    'v_account_recent dummy rows != 5';

  ASSERT (SELECT analyzed_count FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 4,
    'summaries a analyzed_count != 4';
  ASSERT (SELECT followers FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 5500,
    'summaries a followers != 5500';
  ASSERT (SELECT views_count FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 3,
    'summaries a views_count != 3';
  ASSERT (SELECT avg_views FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 6700,
    'summaries a avg_views != 6700';
  ASSERT (SELECT metric FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 'views',
    'summaries a metric != views';
  ASSERT (SELECT avg_er_pct FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 13.7,
    'summaries a avg_er_pct != 13.7';
  ASSERT (SELECT trend_direction FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 'down',
    'summaries a trend != down';
  ASSERT (SELECT trend_change_pct FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = -99,
    'summaries a trend_change_pct != -99';
  ASSERT (SELECT metric FROM analytics.v_account_summaries WHERE handle = 'dummy_b') = 'likes',
    'summaries b metric != likes (views 표본 1 < 3)';
  ASSERT (SELECT sponsored_count FROM analytics.v_account_summaries WHERE handle = 'dummy_b') = 1,
    'summaries b sponsored_count != 1';
  ASSERT (SELECT ad_avg FROM analytics.v_account_summaries WHERE handle = 'dummy_b') = 1000,
    'summaries b ad_avg != 1000 (metric=likes)';
  ASSERT (SELECT ad_drop_pct FROM analytics.v_account_summaries WHERE handle = 'dummy_b') IS NULL,
    'summaries b ad_drop_pct not null (유기 표본 0)';

  -- 카테고리 믹스: 소스 소멸 — 형태 유지 + 항상 0행
  ASSERT (SELECT count(*) FROM analytics.v_account_category_stats) = 0,
    'v_account_category_stats not empty';

  ASSERT (SELECT count(*) FROM analytics.v_account_content_series WHERE account_handle LIKE 'dummy_%') = 5,
    'v_account_content_series dummy rows != 5';
  ASSERT (SELECT sponsored FROM analytics.v_account_content_series WHERE short_code = 'dummy_r3') = true,
    'v_account_content_series r3 sponsored != true';
END $$;
```

- [ ] **Step 2: 실패 확인**

```bash
analytics/test/run.sh test/10_account_detail.test.sql
```

Expected: FAIL — `relation "analytics.v_account_recent" does not exist`

- [ ] **Step 3: 뷰 작성**

`analytics/views/10_account_detail.sql` — `v_account_summaries`·`v_account_content_series`는 구 버전 로직 그대로(소스만 신형 밑판), `v_account_category_stats`만 0행 상수로 교체:

```sql
-- 그룹 10: 인플루언서 상세 (비LLM) — celfit-front AccountReport의 결정 지표.
-- 산식 정본: celfit-front scripts/real-data-pipeline/parse_accounts_recent.py
-- (스펙: docs/superpowers/specs/2026-07-13-c1-account-detail-design.md §3).
-- 서빙 뷰 3종은 미러 1:1 — 컬럼 이름·순서 = V10 DDL = contract record.
-- 신 스키마 이식(2026-07-17 스펙): 밑판 소스만 교체, ad_marked는 릴스 is_paid_partnership
-- 기반이라 sponsored 지표는 릴스 유료 협찬만 잡힌다(피드 광고는 B4 캡션 분류가 대체 소스).

-- 밑판 (미러 안 함): 윈도우 행 + 팔로워. 프로필 없는 계정은 서빙에서 제외 (INNER JOIN 의도 — 프론트가 팔로워를 요구).
CREATE OR REPLACE VIEW analytics.v_account_recent AS
SELECT r.*, p.followers AS profile_followers
FROM analytics.v_recent_content r
JOIN analytics.v_base_profile p ON p.username = r.owner_username;

-- 계정 1행 요약.
-- 기준 지표(metric) 폴백: 조회수 있는 게시물이 max(3, n/2) 미만이면 좋아요 기준 (프론트 상수 — 키로 빼지 않음).
-- 트렌드/광고 비교는 metric 값 > 0인 게시물만 (피드 views NULL은 자연 제외).
CREATE OR REPLACE VIEW analytics.v_account_summaries AS
WITH cfg AS (
  SELECT COALESCE((SELECT value::numeric FROM app_setting
                   WHERE key = 'analytics.trend-threshold'), 0.15) AS trend_threshold
),
win AS (
  SELECT owner_username, content_id, uploaded_at, likes, comments_count, views, ad_marked,
         profile_followers AS followers,
         row_number() OVER (PARTITION BY owner_username ORDER BY uploaded_at ASC, content_id ASC) AS seq,
         count(*)     OVER (PARTITION BY owner_username)                                          AS n
  FROM analytics.v_account_recent
),
base AS (
  SELECT owner_username,
         max(followers)                                     AS followers,
         count(*)                                           AS analyzed_count,
         count(*) FILTER (WHERE views > 0)                  AS views_count,
         round(avg(views) FILTER (WHERE views > 0))::bigint AS avg_views,
         round(avg((likes + comments_count)::numeric / NULLIF(followers, 0)) * 100, 1) AS avg_er_pct,
         round(avg(likes))::bigint                          AS avg_likes,
         round(avg(comments_count))::bigint                 AS avg_comments,
         min(uploaded_at)                                   AS first_posted_at,
         max(uploaded_at)                                   AS last_posted_at
  FROM win
  GROUP BY owner_username
),
metric AS (
  SELECT owner_username,
         CASE WHEN views_count >= GREATEST(3, analyzed_count / 2) THEN 'views' ELSE 'likes' END AS metric
  FROM base
),
mrow AS (
  SELECT w.*, CASE WHEN m.metric = 'views' THEN w.views ELSE w.likes END AS mval
  FROM win w
  JOIN metric m USING (owner_username)
),
-- 올린 순 앞 절반(floor(n/2)) vs 뒤 절반(나머지 — 홀수 중앙은 뒤에 포함). 절반 판정 후 metric>0만 평균.
trend AS (
  SELECT owner_username,
         avg(mval) FILTER (WHERE seq <= n / 2 AND mval > 0) AS older_raw,
         avg(mval) FILTER (WHERE seq >  n / 2 AND mval > 0) AS newer_raw
  FROM mrow
  GROUP BY owner_username
),
ads AS (
  SELECT owner_username,
         count(*) FILTER (WHERE ad_marked)                   AS sponsored_count,
         avg(mval)  FILTER (WHERE NOT ad_marked AND mval > 0) AS organic_raw,
         avg(mval)  FILTER (WHERE ad_marked AND mval > 0)     AS ad_raw,
         count(*)   FILTER (WHERE NOT ad_marked AND mval > 0) AS comparison_organic_count,
         count(*)   FILTER (WHERE ad_marked AND mval > 0)     AS comparison_ad_count,
         max(uploaded_at) FILTER (WHERE ad_marked)            AS last_ad_posted_at
  FROM mrow
  GROUP BY owner_username
)
SELECT
  b.owner_username AS handle,
  b.followers,
  p.follows_count,
  p.posts_count,
  p.biography,
  b.analyzed_count,
  b.views_count,
  m.metric,
  b.avg_views,
  round(b.avg_views::numeric / NULLIF(b.followers, 0), 1) AS views_per_follower,
  b.avg_er_pct,
  b.avg_likes,
  b.avg_comments,
  CASE
    WHEN t.older_raw > 0 AND t.newer_raw > 0 THEN
      CASE WHEN t.newer_raw / t.older_raw - 1 >  cfg.trend_threshold THEN 'up'
           WHEN t.newer_raw / t.older_raw - 1 < -cfg.trend_threshold THEN 'down'
           ELSE 'flat' END
    ELSE 'flat'
  END AS trend_direction,
  CASE WHEN t.older_raw > 0 AND t.newer_raw > 0
       THEN round((t.newer_raw / t.older_raw - 1) * 100)::int
       ELSE 0 END AS trend_change_pct,
  round(t.older_raw)::bigint AS trend_older_avg,
  round(t.newer_raw)::bigint AS trend_newer_avg,
  a.sponsored_count,
  round(a.organic_raw)::bigint AS organic_avg,
  round(a.ad_raw)::bigint      AS ad_avg,
  CASE WHEN a.organic_raw > 0 AND a.ad_raw IS NOT NULL
       THEN round((1 - a.ad_raw / a.organic_raw) * 100)::int
  END AS ad_drop_pct,
  a.comparison_organic_count,
  a.comparison_ad_count,
  a.last_ad_posted_at,
  b.last_posted_at,
  -- 스팬/(n-1): 연속 간격 평균의 절사 없는 정의 (스펙 §3 — 프론트 절사 평균과 소수점만 다를 수 있음)
  CASE WHEN b.analyzed_count > 1
       THEN round((EXTRACT(EPOCH FROM (b.last_posted_at - b.first_posted_at)) / 86400.0
                   / (b.analyzed_count - 1))::numeric, 1)
  END AS avg_interval_days
FROM base b
JOIN metric m USING (owner_username)
JOIN trend  t USING (owner_username)
JOIN ads    a USING (owner_username)
JOIN analytics.v_base_profile p ON p.username = b.owner_username
CROSS JOIN cfg;

-- 카테고리 믹스 — main_group 소멸(V8)로 항상 0행. 형태 유지(미러·record·was 무접촉),
-- B4 캡션 분류 연계 시 Java 미러 단계에서 되살린다 (스펙 2026-07-17 §5·§10).
CREATE OR REPLACE VIEW analytics.v_account_category_stats AS
SELECT NULL::text AS account_handle, NULL::text AS main_group, NULL::bigint AS content_count
WHERE false;

-- 게시물 시계열 (차트 막대·광고 스트립·최근 콘텐츠 탭 재료. 올린 순 정렬은 was 몫)
-- views NULL(피드) 보존 — "0 = 미공개"는 프론트 표현 규약이라 여기서 변환하지 않는다.
CREATE OR REPLACE VIEW analytics.v_account_content_series AS
SELECT short_code,
       owner_username AS account_handle,
       uploaded_at AS posted_at,
       lower(content_type) AS content_type,
       views,
       likes,
       comments_count AS comments,
       ad_marked AS sponsored
FROM analytics.v_account_recent;
```

- [ ] **Step 4: 통과 확인**

```bash
analytics/test/run.sh test/10_account_detail.test.sql
```

Expected: `PASS` → `ALL GREEN`

- [ ] **Step 5: Commit**

```bash
git add analytics/views/10_account_detail.sql analytics/test/10_account_detail.test.sql
git commit -m "feat(analytics): 인플루언서 상세 뷰 신 스키마 이식 — 카테고리 믹스는 0행 상수"
```

---

### Task 9: 20_landing_stats.sql — 랜딩 통계

**Files:**
- Create: `analytics/views/20_landing_stats.sql`
- Test: `analytics/test/20_landing_stats.test.sql`

- [ ] **Step 1: 실패하는 테스트 작성**

`analytics/test/20_landing_stats.test.sql`:

```sql
-- 랜딩 통계 기대값. 모수 = 뷰티 인플루언서 ∩ 마이크로(3천~5만): a(5500)·b(20000).
-- co(8000)는 구간 안이지만 회사, x(9000)는 비뷰티 — 제외 검증.
-- 콘텐츠 = a·b의 ENUMERATION(스냅샷 보유): r1·r2·f1·rn·r3 = 5. d1(DISCOVERY) 제외.
-- 조회수(릴스만, 최신 스냅샷): 12000+8000+100+40000 = 60100, avg = 15025.
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_landing_stats) = 1, 'landing_stats != 1행';
  ASSERT (SELECT influencers_count FROM analytics.v_landing_stats) = 2, 'influencers_count != 2';
  ASSERT (SELECT followers3k10k FROM analytics.v_landing_stats) = 1, 'followers3k10k != 1';
  ASSERT (SELECT followers10k30k FROM analytics.v_landing_stats) = 1, 'followers10k30k != 1';
  ASSERT (SELECT followers30k50k FROM analytics.v_landing_stats) = 0, 'followers30k50k != 0';
  ASSERT (SELECT contents_count FROM analytics.v_landing_stats) = 5, 'contents_count != 5';
  ASSERT (SELECT total_views FROM analytics.v_landing_stats) = 60100, 'total_views != 60100';
  ASSERT (SELECT avg_views FROM analytics.v_landing_stats) = 15025, 'avg_views != 15025';
END $$;
```

- [ ] **Step 2: 실패 확인**

```bash
analytics/test/run.sh test/20_landing_stats.test.sql
```

Expected: FAIL — `relation "analytics.v_landing_stats" does not exist`

- [ ] **Step 3: 뷰 작성**

`analytics/views/20_landing_stats.sql`:

```sql
-- 랜딩 데이터 투명성 통계 (스펙 6.20) — 항상 정확히 1행 (계정 0명이어도 0으로 채운 1행).
-- 모수 = **뷰티 인플루언서 ∩ 마이크로 구간**(팔로워 3,000 이상 50,000 미만)과 그 계정들의
-- ENUMERATION 콘텐츠 (2026-07-17 신 스키마 스펙 §5 — 랜딩 카피 "뷰티 마이크로 인플루언서"와 정합).
-- 뷰티 필터는 01·02(v_serving_content)와 동일 문구 — 모수를 바꿀 땐 세 곳을 같이.
-- 조회수 집계는 릴스만 (피드는 조회수 미공개 — base 층이 이미 NULL 게이트).
-- 분포는 구간별 '계정 수'까지만 내고 %·합계 100 보정은 was 표현 계층 몫 (§4-2).
-- updated_at: 뷰 실행 = 미러 실행 시각 → "매주 갱신 중" 표기의 근거.
--
-- 컬럼명 주의: followers3k10k 등은 언더스코어 없는 이름이 정본 —
-- MirrorJob.toSnakeCase는 대문자 앞에만 '_'를 넣으므로 record 컴포넌트 followers3k10k가
-- 그대로 컬럼명이 된다 (§4-3: 뷰·DDL을 record 변환 결과에 맞춘다).
-- 타입 주의: sum()/round(avg())는 numeric을 돌려준다 — record가 Long이라 ::bigint 캐스트 필수.
CREATE OR REPLACE VIEW analytics.v_landing_stats AS
WITH micro_account AS (
  SELECT p.username, p.followers
  FROM analytics.v_base_profile p
  JOIN analytics.v_base_influencer i USING (influencer_id)
  WHERE i.status = 'QUALIFIED' AND i.beauty AND NOT i.beauty_company
    AND p.followers >= 3000 AND p.followers < 50000
),
micro_content AS (
  -- CTE 이름이 raw의 실테이블 content를 가리지 않도록 micro_content로 둔다.
  SELECT c.content_id, lower(c.content_type) AS content_type, d.views
  FROM analytics.v_base_content c
  JOIN analytics.v_base_detail d USING (content_id)
  JOIN micro_account m ON m.username = c.owner_username
  WHERE c.origin = 'ENUMERATION'
),
content_agg AS (
  SELECT count(*) AS contents_count,
         COALESCE(sum(views) FILTER (WHERE content_type = 'reels'), 0)::bigint AS total_views,
         COALESCE(round(avg(views) FILTER (WHERE content_type = 'reels')), 0)::bigint AS avg_views
  FROM micro_content
),
account_agg AS (
  -- 구간별 '계정 수'까지만 — %·합계 100 보정은 was 몫. 구간 합 = influencers_count.
  SELECT count(*) AS influencers_count,
         count(*) FILTER (WHERE followers < 10000) AS followers3k10k,
         count(*) FILTER (WHERE followers >= 10000 AND followers < 30000) AS followers10k30k,
         count(*) FILTER (WHERE followers >= 30000) AS followers30k50k
  FROM micro_account
)
-- 집계는 GROUP BY가 없으면 모수가 비어도 항상 1행 → CROSS JOIN 결과도 항상 1행.
SELECT contents_count, influencers_count, total_views, avg_views,
       followers3k10k, followers10k30k, followers30k50k, now() AS updated_at
FROM content_agg CROSS JOIN account_agg;
```

- [ ] **Step 4: 전체 하니스 통과 확인**

```bash
analytics/test/run.sh
```

Expected: 7개 테스트 전부 `PASS` → `ALL GREEN`

- [ ] **Step 5: Commit**

```bash
git add analytics/views/20_landing_stats.sql analytics/test/20_landing_stats.test.sql
git commit -m "feat(analytics): 랜딩 통계 신 스키마 이식 — 모수 뷰티∩마이크로"
```

---

### Task 10: 실데이터 스모크

**Files:** 없음 (검증만 — 수치는 PR 본문에 기록)

- [ ] **Step 1: 모수·커버리지 수치 확인**

```bash
docker exec crawler-postgres-1 psql -U crawler -d crawler -c "
SELECT (SELECT count(*) FROM analytics.v_accounts)             AS accounts,
       (SELECT count(*) FROM analytics.v_contents)             AS contents,
       (SELECT count(*) FROM analytics.v_analysis_candidates)  AS candidates,
       (SELECT count(*) FROM analytics.v_content_metric_snapshots) AS snapshots;"
```

Expected: accounts ≈ 1,4xx (뷰티 1,496 중 프로필 보유분), contents·candidates·snapshots 모두 > 0. 실측값을 기록해 둔다.

- [ ] **Step 2: 불변식 확인**

```bash
docker exec crawler-postgres-1 psql -U crawler -d crawler -c "
SELECT count(*) FILTER (WHERE content_type = 'feed' AND views IS NOT NULL) AS feed_views_violation,
       count(*) - count(DISTINCT short_code) AS dup_short_code
FROM analytics.v_contents;" -c "
SELECT count(*) - count(DISTINCT id) AS dup_snapshot_id FROM analytics.v_base_content_snapshot;"
```

Expected: `feed_views_violation = 0`, `dup_short_code = 0`, `dup_snapshot_id = 0`

- [ ] **Step 3: 미러 감내 성능 확인**

```bash
docker exec crawler-postgres-1 psql -U crawler -d crawler -c "\timing" -c "
SELECT count(*) FROM analytics.v_contents;" -c "
SELECT count(*) FROM analytics.v_account_summaries;"
```

Expected: 각 수 초 이내(미러는 수동 배치라 10초 수준도 허용). 비정상적으로 길면(분 단위) MATERIALIZED VIEW 전환(스펙 §2 B안)을 후속 이슈로 기록.

---

### Task 11: 미러 스모크 (MirrorJob 무변경 검증)

> **스모크 발견 수정(07-18)**: 타임라인 likes의 비공개 -1 센티널이 hype cbrt 도메인 오류를 유발 — base 층 NULL 정규화로 수정(00_base·시드·테스트 갱신).

**Files:** 없음 (실행 검증만)

기존 MirrorJob이 무변경으로 도는 것이 "계약 형태 유지"의 검증이다. 로컬 analysis DB의 구 시대 미러 데이터(contents 137 등)가 신 데이터로 대체된다 — 계보 단절 상태였으므로 의도된 결과.

- [ ] **Step 1: 미러 1회 실행**

```bash
./gradlew :analytics:bootRun
```

Expected: MirrorJob 로그에 8개 spec(accounts, contents, content_comments, content_metric_snapshots, account_summaries, account_category_stats, account_content_series, landing_stats) 각각 "N행" 성공 로그, **컬럼 대조 가드 통과**(불일치 예외 없음), 정상 종료(exit 0).

- [ ] **Step 2: analysis DB 반영 확인**

```bash
docker exec crawler-postgres-1 psql -U crawler -d analysis -c "
SELECT (SELECT count(*) FROM accounts)               AS accounts,
       (SELECT count(*) FROM contents)               AS contents,
       (SELECT count(*) FROM account_category_stats) AS category_stats,
       (SELECT count(*) FROM landing_stats)          AS landing;"
```

Expected: accounts·contents는 Task 10 Step 1의 뷰 수치와 일치, `category_stats = 0`, `landing = 1`

- [ ] **Step 3: 전체 테스트 회귀 확인 (Java 무변경 검증)**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL (Java는 건드리지 않았으므로 기존 테스트 전부 통과)

---

### Task 12: 문서 갱신

**Files:**
- Modify: `ARCHITECTURE.md` (§3 raw 테이블 표, §5 A2 행, §8 CI 블로커 행)
- Modify: `docs/superpowers/specs/2026-07-17-analytics-views-new-schema-design.md` (상태 헤더)
- Modify: `analytics/README.md` (뷰 목록이 구 스키마 기준이면 갱신)

- [ ] **Step 1: ARCHITECTURE.md §3 raw 테이블 표 교체**

"### raw DB (crawler 소유 — 분석 작업에서 불변)" 아래 표를 다음으로 교체:

```markdown
| 테이블 | 내용 |
|---|---|
| `influencer` | 계정 (username, status, followers, 뷰티 판정 beauty/beauty_company/beauty_judged_at) |
| `content` | 게시물 제어 (short_code, content_type, owner, uploaded_at, origin DISCOVERY/ENUMERATION, status) — 캡션·지표 없음 |
| `raw_media_page` | 릴스 페이지 원형(HIKER_V2_CLIPS jsonb) — 릴스 캡션·지표·썸네일의 소스 |
| `raw_profile` | 프로필 원형(SELF_GQL·HIKER_MOBILE 등 source별 jsonb) — SELF_GQL엔 내장 타임라인 12개(피드 캡션·지표의 소스) |
| `raw_post_detail` | 구 시대 상세 payload — 신 파이프라인 미사용(LEGACY, 크롤러 대시보드 전용) |
| `raw_comment` | 댓글 원문 (writer/text/written_at 실컬럼) — 수집 게이트 off, 신규 유입 없음 |
| `app_setting` | 런타임 설정 key-value (분석 뷰도 여기서 임계값을 읽음) |
```

바로 아래 "### 분석 뷰" 절의 "기존 소스(00~08)는 2026-07-12 초기화" 문장을 다음으로 교체:

```markdown
`analytics/views/NN_*.sql` 번호순 적용 컨벤션. 2026-07-18 신 crawler 스키마(V15) 기준으로
전면 재구축 — base 층(00)이 raw 접촉을 격리하고, 서빙 모수는 뷰티 인플루언서
(QUALIFIED ∧ beauty ∧ ¬beauty_company). 04는 LLM 캡션 선분석 후보 뷰(미러 안 함).
```

- [ ] **Step 2: §5 A2 행 상태 갱신**

A2 행의 `⬜`를 `✅`로, 내용 끝 "설계 확정(07-17), 구현은 PR #30 머지 후"를 "07-18 구현 완료"로 교체.

- [ ] **Step 3: §8 CI 블로커 행 갱신**

"계약 테스트 CI 연결" 행의 상태 설명을 다음으로 교체:

```markdown
raw 변경 PR에서 `analytics/test/run.sh` 자동 실행. 블로커였던 구 스키마 전제는 07-18 뷰
재구축으로 해소 — 하니스 시드가 신 스키마(V15)에 직접 INSERT하므로 프레시 DB + V1~V15 +
run.sh 구조가 성립. CI 워크플로에 Postgres 서비스 + Flyway 적용 + run.sh 연결만 남음
```

- [ ] **Step 4: 스펙 상태 헤더 갱신**

`docs/superpowers/specs/2026-07-17-analytics-views-new-schema-design.md` 첫머리 `> 상태: 🟢 활성`을 `> 상태: ✅ 구현/실행/반영됨 (2026-07-18)`으로 교체.

- [ ] **Step 5: analytics/README.md 확인·갱신**

README가 구 뷰 구성(00~08, raw_post_detail 등)을 언급하면 신 구성(00~04·10·20, 소스 = raw_media_page·SELF_GQL 타임라인)으로 갱신. 언급 없으면 무변경.

- [ ] **Step 6: Commit**

```bash
git add ARCHITECTURE.md docs/superpowers/specs/2026-07-17-analytics-views-new-schema-design.md analytics/README.md
git commit -m "docs: 분석 뷰 신 스키마 재구축 반영 — §3 raw 표·A2 완료·CI 블로커 해소"
```

---

### Task 13: 브랜치 마무리 (PR)

- [ ] **Step 1: superpowers:finishing-a-development-branch 스킬 호출**

Skill 도구로 `superpowers:finishing-a-development-branch`를 호출해 develop 대상 PR 생성 절차를 따른다.

PR 본문에 포함할 것: 스펙 링크, Task 10의 실측 수치(accounts/contents/candidates), 미러 스모크 결과(8 spec 통과·category_stats 0행), "우아한 공백" 명시(카테고리 믹스 빈 서빙·피드 광고 false — B4 연계 후속).

---

## Self-Review 체크 결과 (계획 작성 시 수행)

- **스펙 커버리지**: §3 파일 구성 → Task 2~9, §4 base 규칙 → Task 3, §5 서빙 변경점 → Task 4~9, §8 하니스 → Task 2~9의 테스트 스텝, §9 적용 절차 → Task 1·10·11, 문서 갱신 → Task 12. 누락 없음.
- **플레이스홀더**: 없음 — 모든 뷰·테스트·시드가 완전한 코드로 수록됨.
- **타입 일관성**: `v_serving_content`(02에서 정의)는 02 내부에서만 사용. `v_base_*` 이름은 Task 3 정의와 Task 4~9 참조가 일치. 합성 id 산식은 00 주석·02 사용처 동일. 테스트 기대값은 시드 수치로부터 재계산해 검증(709 = avg(530,300,2000,5), 13.7 = avg_er_pct, 60100 = 릴스 views 합).
