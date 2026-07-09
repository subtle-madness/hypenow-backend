# 크롤링 데이터 분석 카탈로그 설계

- 작성일: 2026-07-09
- 상태: 설계
- 관련: [crawler 설계](2026-07-07-crawler-design.md)

## 목적

crawler가 적재한 인스타그램 raw 데이터(콘텐츠·프로필·댓글·좋아요/조회수)로
**무엇을 분석할지** 지표 카탈로그를 확정하고, 각 지표를 재사용 가능한 SQL
뷰/쿼리로 박아둔다. 지금은 실데이터가 스모크 수준(콘텐츠 2건, 댓글 71건)이라
**실제 분석이 아니라 분석 항목의 정의·검증이 목표**다. 데이터가 쌓이면 같은
쿼리를 그대로 돌려 결과를 얻는다.

## 방식 (결정됨: A)

- **문서(이 스펙) + 재사용 SQL 뷰/쿼리 모음**. 지표를 SQL 뷰로 정의하고,
  실데이터 2건 + 더미데이터로 결과가 나오는지 검증한다.
- 텍스트/감성처럼 SQL로 어려운 항목은 "나중에 Python"으로 표시만 하고 이번
  범위에서 제외한다.
- BI 대시보드(Metabase 등)는 이번 범위 아님.

## 데이터 원천 요약

| 테이블 | 핵심 필드 (generated / payload) |
|---|---|
| `content` | content_type, owner_username, uploaded_at, category_id, main_group, subcategory, discovery_keyword, status, ad_marked, first_seen_at/qualified_at/aggregated_at |
| `raw_post_detail` | likes, comments_count, video_play_count (generated) / payload: videoViewCount, videoDuration, type, productType, hashtags[], mentions[], childPosts[], caption, timestamp |
| `raw_profile` | followers, username (generated) / payload: followsCount, postsCount, verified, isBusinessAccount, businessCategoryName, biography |
| `raw_comment` | writer, text, written_at (generated) / payload: likesCount, repliesCount, replies[] |
| `crawl_run` | job, status, item_count, started_at/finished_at (분석 대상 아님 — 운영지표 제외) |

### 조인 경로 (모든 콘텐츠 성과 지표의 기준)

```
content ──(content_id)── raw_post_detail          -- 성과 지표
content.owner_username ─→ account.username ─(account_id)→ raw_profile   -- 팔로워
```

- 한 계정에 `raw_profile`이 여러 개일 수 있으므로 **captured_at 최신 1건**을 사용
  (계정별 최신 프로필 뷰로 분리).
- 한 콘텐츠에 `raw_post_detail`이 여러 개일 수 있으면(재수집) 마찬가지로 최신 1건.

## 데이터 한계 (분석 설계 시 전제)

1. **저장수·공유수·도달·노출 없음** — Apify 응답에 없다. 참여는 좋아요·댓글·
   (영상) 조회수까지만.
2. **+3일 단일 스냅샷** — aggregate가 업로드 3일 후 한 번 수집. 시계열 성장곡선이
   아니라 "3일 시점 고정값"이다. 콘텐츠 간 비교는 공정(모두 3일 시점).
3. **팔로워는 qualify 시점 값** — 성과 스냅샷과 시점이 약간 다를 수 있음(무시 가능
   수준).
4. **현재 표본 극소** — 통계·분포·랭킹은 더미데이터로 쿼리를 검증하고, 실통계는
   데이터 적재 후.

## 분석 카탈로그

우선순위: **1·2·3 = 핵심(랭킹 피벗), 4·5·7 = 보조, 6 = 일부만(텍스트/감성 제외)**.

### 그룹 1 — 콘텐츠 성과 (per content) [핵심]

| 지표 | 정의 | 원천 |
|---|---|---|
| 좋아요 | likes | raw_post_detail.likes |
| 댓글수 | comments_count | raw_post_detail.comments_count |
| 조회수 | video_play_count (없으면 videoViewCount) | raw_post_detail |
| 영상길이 | videoDuration (초) | payload |
| 참여율(ER) | (likes + comments_count) / followers | detail + profile 조인 |
| 조회수대비 좋아요율 | likes / video_play_count | detail (영상만) |
| 조회수대비 댓글율 | comments_count / video_play_count | detail (영상만) |

- 0/NULL 분모 방어(NULLIF)를 뷰에서 처리.

### 그룹 2 — 분류별 집계 [핵심]

- 분류 4단계(category > main_group > subcategory > discovery_keyword) 각 레벨별:
  - 콘텐츠 수, 평균·중앙 참여율, 평균 좋아요·조회수
  - 참여율 상위 N 콘텐츠(랭킹)
- 발굴 키워드(discovery_keyword)별 성과 — "어떤 해시태그 검색이 잘 나가는
  콘텐츠를 물어오나".

### 그룹 3 — 크리에이터/계정 [핵심]

| 지표 | 정의 |
|---|---|
| 계정 프로필 | followers, followsCount, postsCount, verified, isBusinessAccount, businessCategoryName |
| 계정별 평균 참여율 | 계정이 올린 콘텐츠들의 ER 평균 |
| 계정별 콘텐츠 수 | 수집된 콘텐츠 건수 |
| 팔로워 구간별 분포 | 마이크로(<1만)/미드(1~10만)/매크로(>10만) 구간별 평균 ER·콘텐츠수 |
| 오버퍼폼 계정 | 팔로워 대비 ER이 구간 중앙값보다 높은 계정 (협업 후보) |

- 팔로워 구간 경계값은 뷰 안에서 상수로 두고 조정 가능하게.

### 그룹 4 — 콘텐츠 타입 비교 [보조]

- 릴스(video) vs 피드(image/sidecar) — type/productType/content_type 기준 평균 ER·
  좋아요·조회수 비교.
- 영상길이 구간 ↔ 조회수/ER 상관.
- 캐러셀 효과 — childPosts 개수 ↔ ER.

### 그룹 5 — 타이밍 [보조]

- 업로드 요일·시간대(uploaded_at, KST 변환)별 평균 ER·콘텐츠 수.
- 분류별 업로드 추이(주/월 단위 건수).
- 전제: +3일 스냅샷이라 "성장"이 아닌 "업로드 타이밍↔3일 성과" 분석.

### 그룹 6 — 해시태그·멘션·댓글 (텍스트/감성 제외) [보조]

- 캡션 해시태그(payload->'hashtags') 빈도 Top N, 해시태그당 평균 ER.
- 멘션(payload->'mentions') 빈도 — 협업/태그 관계.
- 댓글: 콘텐츠당 댓글 수, 고유 작성자 수(작성자 다양성), 대댓글 비율
  (repliesCount 합 / 댓글수).
- **댓글 텍스트 내용/감성 분석은 이번 범위 제외 → 나중에 Python.**

### 그룹 7 — 광고/협찬 [보조]

- ad_marked 비율 — 전체·분류별·계정별.
- 광고(ad_marked=true) vs 비광고 콘텐츠의 평균 ER 비교.

## 산출물 구조

```
crawler/analytics/
  README.md                     -- 카탈로그 요약·실행법
  views/
    00_base.sql                 -- 계정별 최신 프로필, 콘텐츠별 최신 detail, ER 기본 뷰
    01_content_performance.sql  -- 그룹 1
    02_category_rollup.sql      -- 그룹 2
    03_creators.sql             -- 그룹 3
    04_content_type.sql         -- 그룹 4
    05_timing.sql               -- 그룹 5
    06_hashtags_comments.sql    -- 그룹 6
    07_ads.sql                  -- 그룹 7
  seed/
    dummy.sql                   -- 통계 검증용 더미 콘텐츠/프로필/댓글
```

- 각 SQL은 뷰 생성 + 예시 SELECT를 포함. Flyway 마이그레이션이 아니라 분석용
  독립 스크립트(운영 스키마 불변).
- 뷰는 `analytics` 스키마에 두어 운영 테이블과 분리.

## 검증 방식

1. 실데이터 2건으로 그룹 1·2·3·4·7 뷰가 에러 없이 결과를 내는지 확인.
2. `seed/dummy.sql`로 계정 ~20개·콘텐츠 ~200개·댓글 규모를 넣어 분포·랭킹·구간
   집계가 의미 있게 나오는지 확인(더미는 별도 트랜잭션으로 롤백 가능하게).
3. 각 뷰 예시 SELECT 결과를 README에 캡처.

## 범위 밖 (YAGNI)

- 파이프라인 운영 지표(퍼널·성공률·재시도) — 모니터링 영역이라 제외.
- 댓글 텍스트/감성 분석, NLP — 나중 Python.
- BI 대시보드.
- 시계열 성장곡선(스냅샷이 1개뿐).
