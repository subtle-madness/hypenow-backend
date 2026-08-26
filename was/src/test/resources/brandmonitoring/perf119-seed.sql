-- BrandPostAssemblyBenchmarkTest 시드(2026-08-25 9초 지연 분석 하니스) — perf119 DB에 적용.
-- 운영 브랜드 119와 동일 규모의 합성 데이터: 게시물 4,995 · 메타(캡션 ~1.5KB) · 스냅샷 9,990 ·
-- 댓글 54,324 · 작성자 3,173. 실데이터가 아니라 md5 난수 텍스트라 PII 없음.
-- 테이블 정의는 운영 monitoring DB에서 벤치가 읽는 컬럼만 옮긴 사본이다(마이그레이션 정본 아님).

CREATE TABLE brand_account (
  id bigint PRIMARY KEY, username text NOT NULL, ig_user_id text NOT NULL DEFAULT 'x',
  followers bigint, following bigint, media_count bigint, biography text, full_name text,
  profile_pic_url text, is_verified boolean, external_url text,
  status text NOT NULL DEFAULT 'ACTIVE', registered_at timestamptz NOT NULL DEFAULT now(),
  closed_at timestamptz, last_swept_on date, last_swept_at timestamptz,
  backfill_completed_at timestamptz, backfill_error text, image_object_path text,
  collection_months int NOT NULL DEFAULT 12, collection_started_at timestamptz,
  collection_capped boolean NOT NULL DEFAULT false, covered_until timestamptz
);
CREATE TABLE brand_tagged_post (
  brand_id bigint NOT NULL, short_code text NOT NULL, author_username text NOT NULL,
  author_ig_user_id text, taken_at timestamptz NOT NULL, first_seen_at timestamptz NOT NULL DEFAULT now(),
  comments_collected_count bigint NOT NULL DEFAULT 0, last_crawled_at timestamptz,
  tag_detected_at timestamptz, direct_registered_at timestamptz, unavailable_at timestamptz,
  enriched_at timestamptz, PRIMARY KEY (brand_id, short_code)
);
CREATE TABLE brand_post_meta (
  short_code text PRIMARY KEY, username text NOT NULL, content_type text, uploaded_at date NOT NULL,
  caption text NOT NULL, thumbnail_url text, video_url text, video_duration double precision,
  is_paid_partnership boolean, image_object_path text, first_seen_at timestamptz DEFAULT now(),
  ad_verdict text, ad_violations jsonb, ad_evidence jsonb
);
CREATE TABLE brand_post_snapshot (
  username text NOT NULL, short_code text NOT NULL, captured_on date NOT NULL, content_type text,
  likes bigint, likes_hidden boolean NOT NULL DEFAULT false, comments bigint, views bigint,
  fb_plays bigint, saves bigint, shares bigint, shares_hidden boolean NOT NULL DEFAULT false,
  reposts bigint, PRIMARY KEY (short_code, captured_on)
);
CREATE TABLE brand_post_comment (
  short_code text NOT NULL, id text NOT NULL, author text NOT NULL, body text NOT NULL,
  like_count bigint NOT NULL, commented_at timestamptz NOT NULL, owner_reply_text text,
  PRIMARY KEY (short_code, id)
);
CREATE TABLE author_profile (
  ig_user_id text PRIMARY KEY, username text NOT NULL, full_name text, followers bigint,
  following bigint, media_count bigint, biography text, profile_pic_url text, is_private boolean,
  fetched_at timestamptz NOT NULL, is_verified boolean, image_object_path text
);

INSERT INTO brand_account (id, username, followers, media_count, last_swept_on, last_swept_at, registered_at, backfill_completed_at, collection_months)
VALUES (119, 'bench_brand', 119392, 1043, current_date, now(), now()-interval '6 days', now()-interval '6 days', 12);

INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id, taken_at, first_seen_at, comments_collected_count, last_crawled_at, tag_detected_at, enriched_at)
SELECT 119, 'SC'||lpad(g::text,9,'0'), 'user_'||(g % 3173), CASE WHEN g % 10 = 0 THEN NULL ELSE 'ig_'||(g % 3173) END,
       now() - (g % 365) * interval '1 day', now() - (g % 365) * interval '1 day', 10, now(), now() - (g % 365) * interval '1 day', now()
FROM generate_series(1,4995) g;

INSERT INTO brand_post_meta (short_code, username, content_type, uploaded_at, caption, thumbnail_url, video_url, video_duration, is_paid_partnership, image_object_path)
SELECT 'SC'||lpad(g::text,9,'0'), 'user_'||(g % 3173), CASE WHEN g % 5 < 3 THEN 'REELS' ELSE 'FEED' END,
       (now() - (g % 365) * interval '1 day')::date,
       (SELECT string_agg(md5((g*100+i)::text), ' ') FROM generate_series(1,48) i),
       'https://scontent-cdn.example.com/v/t51.2885-15/'||md5(g::text)||'_n.jpg?stp=dst-jpg&efg='||md5((g+7)::text)||'&_nc_ht=scontent&_nc_cat=108&oh='||md5((g+13)::text)||'&oe=66F0A1B2',
       CASE WHEN g % 5 < 3 THEN 'https://scontent-cdn.example.com/o1/v/t16/f2/m86/'||md5((g+3)::text)||'.mp4?efg='||md5((g+9)::text) END,
       CASE WHEN g % 5 < 3 THEN 12.5 END,
       CASE WHEN g % 20 = 0 THEN true WHEN g % 3 = 0 THEN false END,
       CASE WHEN g % 2 = 0 THEN 'brand-posts/'||md5(g::text)||'.jpg' END
FROM generate_series(1,4995) g;

INSERT INTO brand_post_snapshot (username, short_code, captured_on, content_type, likes, comments, views, saves, shares, reposts)
SELECT 'user_'||(g % 3173), 'SC'||lpad(g::text,9,'0'), current_date - s, CASE WHEN g % 5 < 3 THEN 'REELS' ELSE 'FEED' END,
       (g*7) % 10000, (g*3) % 500, CASE WHEN g % 5 < 3 THEN (g*97) % 1000000 END, g % 300, g % 100, g % 50
FROM generate_series(1,4995) g, generate_series(0,1) s
WHERE g*2+s <= 10365;

INSERT INTO brand_post_comment (short_code, id, author, body, like_count, commented_at)
SELECT 'SC'||lpad(((c*7919) % 4995 + 1)::text,9,'0'), 'cm_'||c, 'commenter_'||(c % 9999),
       substr(md5(c::text)||' '||md5((c+1)::text), 1, 40 + c % 40), c % 100, now() - (c % 300) * interval '1 hour'
FROM generate_series(1,54324) c;

INSERT INTO author_profile (ig_user_id, username, full_name, followers, following, media_count, biography, profile_pic_url, fetched_at, is_verified, image_object_path)
SELECT 'ig_'||a, 'user_'||a, 'Full Name '||a, (a*37) % 500000, a % 5000, a % 2000,
       (SELECT string_agg(md5((a*10+i)::text), ' ') FROM generate_series(1,4) i),
       'https://scontent-cdn.example.com/v/t51.2885-19/'||md5(a::text)||'_s150x150.jpg?oh='||md5((a+5)::text),
       now(), a % 50 = 0, CASE WHEN a % 2 = 0 THEN 'author-profiles/'||md5(a::text)||'.jpg' END
FROM generate_series(0,3172) a;

ANALYZE;
