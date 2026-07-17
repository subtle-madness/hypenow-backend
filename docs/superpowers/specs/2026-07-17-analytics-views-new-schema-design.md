# 분석 뷰 신 crawler 스키마 재구축 설계

> 상태: 🟢 활성
>
> 2026-07-17. analytics/views의 분석 뷰 전체(00~20)를 신 crawler 스키마
> (feat/beauty-captions, Flyway V15) 기준으로 재구축하는 설계.
> 브레인스토밍 세션에서 사용자 확정.

## 1. 배경

크롤러가 인플루언서 중심 파이프라인(V8~V15)으로 개편되면서 분석 뷰의 입력이 바뀌었다:

- `account` → `influencer` 리네임 + **뷰티 판정 3분류**(beauty·beauty_company·beauty_source·
  beauty_reason·beauty_judged_at) 신설. QUALIFIED 5,806 = 뷰티 인플루언서 1,496 /
  뷰티 회사 1,692 / 비뷰티 2,618 (2026-07-17 팀 덤프 기준).
- `content`는 캡션·지표 없는 **제어 테이블**로 축소 (short_code, content_type, owner_username,
  uploaded_at, influencer_id, origin, status, collected_at). `category_id`·`main_group`·
  `subcategory`·`discovery_keyword`·`ad_marked` 삭제.
- 신 크롤러는 **상세 수집을 하지 않고 열거만 한다** — SELF_GQL 프로필 원형에 내장된
  타임라인 12개 + HIKER_V2_CLIPS 릴스 1페이지(12개)를 `MediaItemExtractor`로 풀어
  content(ENUMERATION·PENDING)를 upsert. 캡션·지표는 payload 안에만 있다.
- `raw_post_detail`은 신 파이프라인에서 쓰기 코드가 없다(141행 전부 LEGACY_ENVELOPE,
  대시보드 읽기 전용). 구 뷰의 단일 스냅샷 소스가 소멸했다.
- 구 뷰 5종(00~10)+20은 구 스키마(account_id·category_id·ad_marked·raw_post_detail 중심)를
  참조해 신 DB에 적용 불가. 복원된 팀 덤프에는 `analytics` 스키마 자체가 없다.
- analysis DB의 기존 미러 데이터(contents 137 등)는 구 시대 산출물 — 신 raw와 계보 단절.

### 데이터 규모 (2026-07-17 덤프 실측)

| 항목 | 수치 |
|---|---|
| content (ENUMERATION) | 20,670 = FEED 7,569 + REELS 13,101 |
| content (DISCOVERY — 발굴 부산물, 수집 대상 아님) | 13,128 |
| raw_media_page | HIKER_V2_CLIPS 1,133페이지(아이템 13,114) + HIKER_GQL_MEDIAS 8 |
| raw_profile | HIKER_MOBILE 12,700 / SELF_GQL 4,880 / LEGACY 989 / DATALIKERS 98 |
| payload 커버리지 | ENUM 릴스 94%(12,313/13,101 — 미매칭 788 중 787은 타임라인에서만 열거돼 타임라인 뷰가 커버), ENUM 피드 95%(7,214/7,569) |
| clips 아이템 필드 | flat 접두사(1l/1f) 0건, 캡션 99.7%, 썸네일 100% |

## 2. 사용자 확정 결정

| # | 결정 | 내용 |
|---|---|---|
| 1 | **기반 브랜치** | [PR #30](https://github.com/subtle-madness/hypenow-backend/pull/30)(feat/beauty-captions→develop) 머지 후 develop에서 feat/* 브랜치. 설계·스펙은 머지 대기 중 진행 |
| 2 | **서빙 모수** | 뷰티 인플루언서만 — `status='QUALIFIED' AND beauty AND NOT beauty_company` (1,496계정). 회사·비뷰티·EXCLUDED 제외 |
| 3 | **미러 계약** | 형태 유지 + 우아한 공백 — 미러 8종·record·was 무변경. 소스 소멸 항목(피드 광고 여부, 카테고리 믹스)은 false/빈 결과로 서빙 |
| 4 | **LLM 입구** | 04_analysis_candidates 신설(후보 자격 + 캡션 재료), 03은 기준선 수치만 이식. '이미 분석됨' 대조·배치 상한은 Java 몫 |

구조 접근(A안): **플랫 뷰 체인** — 아이템 평탄화 뷰 2종을 base에 신설하고 UNION으로 구
`v_base_detail_history` 인터페이스를 재현. 상위 뷰는 소스 교체만. MATERIALIZED VIEW(B안)는
현 규모(평탄화 대상 ≈ 7만 행)에서 이득이 없고 하니스 BEGIN/ROLLBACK과 충돌해 기각 —
성능 문제가 생기면 상위 뷰 무변경으로 전환 가능.

## 3. 파일 구성

```
analytics/views/
  00_base.sql                ← 전면 재작성 (raw 접촉 유일 지점 — §4-4)
  01_recent_window.sql       ← 카테고리 컬럼 제거 + 뷰티 모수 필터
  02_serving.sql             ← 소스 교체 (hype_score·서빙 형태 무변경)
  03_analysis_baseline.sql   ← 소스 교체 (카테고리 맥락 3컬럼 NULL 상수)
  04_analysis_candidates.sql ← 신설 (LLM 캡션 선분석 후보)
  10_account_detail.sql      ← 소스 교체 (category_stats는 0행 상수 뷰)
  20_landing_stats.sql       ← 소스 교체 + 모수 뷰티∩마이크로
analytics/test/NN_*.test.sql ← 전부 신 스키마 시드로 재작성 (run.sh 골격 유지)
```

## 4. base 층 (00_base.sql)

### 뷰 구성

| 뷰 | 역할 |
|---|---|
| `v_base_influencer` | influencer 테이블 노출 — id, username, status, followers, beauty, beauty_company, beauty_judged_at. 모수 필터 재료 |
| `v_base_profile` | 계정별 최신 프로필 1건 — 소스별 payload 경로 분기 |
| `v_base_reel_item` | HIKER_V2_CLIPS 페이지 아이템 평탄화 |
| `v_base_timeline_item` | SELF_GQL 내장 타임라인 노드 평탄화 (피드 + 타임라인 노출 릴스) |
| `v_base_content_snapshot` | 위 둘 UNION × content 조인 = 지표 스냅샷 이력 (`v_base_detail_history` 후계) |
| `v_base_detail` | 콘텐츠별 최신 스냅샷 1건 + 메타 (`v_base_detail` 후계) |
| `v_base_content` | content 테이블 노출 (origin·status·influencer_id 포함) |
| `v_base_comment` | 댓글 평탄화 — 무변경 |

### 추출 경로 (crawler `MediaItemExtractor`·`ProfileExtractor`와 정합 — 계약은 crawler가 정의)

**v_base_reel_item** — `raw_media_page(source='HIKER_V2_CLIPS')`,
`jsonb_array_elements(payload->'response'->'items')`의 `->'media'`:

| 컬럼 | 경로 |
|---|---|
| short_code | `code` |
| taken_at | `taken_at` (epoch초 → timestamptz) |
| likes | `like_count` |
| comments | `comment_count` |
| views | `play_count` 폴백 `ig_play_count` |
| caption | `caption->>'text'` |
| thumbnail_url | `image_versions2->'candidates'->0->>'url'` |
| video_duration | `video_duration` |
| paid_partnership | `is_paid_partnership` |
| influencer_id·captured_at | 페이지 행 컬럼 |

**v_base_timeline_item** — `raw_profile(source='SELF_GQL')`,
`jsonb_array_elements(payload->'data'->'user'->'edge_owner_to_timeline_media'->'edges')`의 `->'node'`:

| 컬럼 | 경로 |
|---|---|
| short_code | `shortcode` |
| taken_at | `taken_at_timestamp` |
| likes | `edge_media_preview_like->>'count'` 폴백 `edge_liked_by->>'count'` |
| comments | `edge_media_to_comment->>'count'` |
| views | `video_view_count` — **릴스형 노드 한정, 0→NULL**(0은 미공개 표기) |
| caption | `edge_media_to_caption->'edges'->0->'node'->>'text'` |
| thumbnail_url | `display_url` |
| video_duration | 없음 → NULL |
| paid_partnership | 없음 → false |
| product_type | `product_type` (타임라인 노드의 22,347개가 'clips' — 타임라인은 피드 전용이 아님) |

### v_base_content_snapshot 규칙

- 두 평탄화 뷰를 UNION ALL 후 `content`에 **short_code로 조인**. content_type은 content
  테이블 값이 정본(crawler 판정 우선).
- **views NULL 규칙 계승(§6)**: content_type='FEED'면 views 무조건 NULL. 릴스는 clips
  아이템 값 우선, 타임라인 노드 `video_view_count`(0→NULL) 폴백 — 폴백은 행 단위가 아니라
  소스 단위(각 스냅샷 행은 자기 소스의 값을 실음).
- `captured_at` = 원형(페이지/프로필)의 수집 시각. 재방문마다 원형이 다시 쌓이므로 스냅샷
  이력이 자연 누적 — **+3일 지표 고정 규칙이 신 구조에서도 성립**한다.
- **합성 id**: `(원본행 id × 1000 + 아이템 서수) × 2 + 소스태그(reel=0, timeline=1)`.
  유일·안정(재실행 불변) bigint — `content_metric_snapshots` 미러 계약(id bigint) 유지.
  아이템 서수는 `WITH ORDINALITY`(페이지 내 위치 — 원형이 불변이므로 안정).

### v_base_profile 소스 분기

실컬럼(`username`·`followers`)을 우선 사용(COALESCE로 payload 폴백), 파생 필드는 source별 CASE:

| 컬럼 | SELF_GQL (`data->user`) | HIKER_MOBILE (`user`) | LEGACY/기타 (flat) |
|---|---|---|---|
| display_name | `full_name` | `full_name` | `fullName` |
| profile_image_url | `profile_pic_url_hd` 폴백 `profile_pic_url` | `profile_pic_url` | `profilePicUrl` |
| follows_count | `edge_follow->count` | `following_count` | `followsCount` |
| posts_count | `edge_owner_to_timeline_media->count` | `media_count` | `postsCount` |
| biography | `biography` | `biography` | `biography` |
| external_link | `external_url` | `external_url` | `externalUrl` |

최신 1건 선택은 소스 무관 `DISTINCT ON (influencer_id) … ORDER BY captured_at DESC, id DESC`.

## 5. 서빙 층 변경점

### 01_recent_window — v_recent_content

- 모수 필터 진입점: `v_base_content`(**origin='ENUMERATION'**만) × `v_base_influencer`
  (**QUALIFIED ∧ beauty ∧ ¬beauty_company**) × `v_base_detail`(INNER JOIN — 스냅샷 있는 것만).
- 컬럼: `category_id`·`main_group` **제거**. `ad_marked`는 이름 유지, 소스는 최신 스냅샷의
  paid_partnership(피드 항상 false).
- 윈도우 N=12(`analytics.recent-window`) 유지 — 계정당 재료 최대 ~24개(피드12+릴스12)라 실질 작동.

### 02_serving — 4종 모두 컬럼 이름·순서 무변경 (미러·record·was 무접촉)

- `hype_score()` 함수 무변경.
- `v_accounts`: `v_base_profile` ∩ 뷰티 모수.
- `v_contents`: 소스 교체. **+3일 고정(metric-pin-days)·최신 폴백·메타는 최신 스냅샷** 규칙 유지.
  모수는 01과 동일(뷰티 ∩ ENUMERATION).
- `v_content_comments`: 형태 유지(raw_comment 무변경 — 댓글 게이트 off 동안 신규 유입 없음).
- `v_content_metric_snapshots`: id만 합성 id로. 모수는 v_contents와 동일.

### 03_analysis_baseline — 컬럼 형태 유지 (기존 분석 Java 무접촉)

`category_top_percentile`·`category_avg_views`·`category_sample_size`는 **NULL 상수**
(main_group 소멸 — B4 캡션 분류 산출물이 대체 예정, analysis DB 소속이라 raw 뷰에서 조인 불가).

### 04_analysis_candidates — 신설 (LLM 캡션 선분석 입구)

미러 안 함. **raw만 보고 알 수 있는 자격 조건까지만** 뷰가 담당:

- 자격 = 뷰티 모수 ∩ ENUMERATION ∩ **캡션 존재**(비어있지 않음) ∩
  **숙성**(`uploaded_at + 'analytics.analyze-maturity-days'(기본 3)일 ≤ now()`).
- 컬럼 = short_code, content_type, account_handle, uploaded_at, caption(Batch 입력 재료),
  thumbnail_url, followers, views·likes·comments(고정 지표), metric_captured_at.
- '이미 분석됨' 제외(content_analyses 대조)·배치 상한·정렬 정책은 Java 몫 — Haiku 4.5 +
  Batch 파이프라인(별도 세션 설계)이 이 뷰를 SELECT 해 배치를 구성한다.

### 10_account_detail — 3종 서빙 형태 유지

- `v_account_summaries`: 소스 교체. sponsored 지표는 **릴스 유료 협찬만 잡힘**을 주석 명시.
- `v_account_category_stats`: **형태 유지 + 항상 0행**(`WHERE false`). 미러는 빈 테이블 →
  was는 빈 믹스 서빙. B4 연계 시 되살림.
- `v_account_content_series`: 소스 교체만.

### 20_landing_stats

로직 유지, 모수만 **뷰티 인플루언서 ∩ 마이크로 구간(팔로워 3,000~50,000)** 교집합 —
랜딩 카피 "뷰티 마이크로 인플루언서"와 정합. 콘텐츠 집계도 그 계정들의 ENUMERATION 콘텐츠.

## 6. 뷰 재료에서 제외한 것 (사유 기록)

| 항목 | 사유 |
|---|---|
| `raw_post_detail` | 신 파이프라인에 쓰기 코드 없음 — 141행 전부 LEGACY_ENVELOPE(구 시대). 대시보드 읽기 전용 |
| `HIKER_GQL_MEDIAS` | 유휴 경로 — `ReelsJob`이 HIKER_V2_CLIPS만 선택, 8페이지는 과거 실험 산물 |
| `reel_parse` | 영상 자체 파싱(OCR·STT·프레임) 로컬 실험 산출물 68행. crawler 코드·V15 마이그레이션에 없음 — 모수 부족으로 뷰 재료 부적합. 영상 신호 활용은 LLM 파이프라인 후속 검토 |
| 피드 광고 여부 | 타임라인 노드에 `is_paid_partnership` 없음 — false 간주. B4 캡션 분류가 대체 소스 |
| 카테고리(main_group) | raw에서 소멸 — B4 캡션 분류 산출물(analysis DB)이 유일 소스, 뷰 층위에서 복원 불가 |
| `public_email`(HIKER_MOBILE) | 계약 유지 결정으로 이번 범위 제외 — P1의 email null 확정을 뒤집을 재료로 후속 검토 |

## 7. 설정 키 (전부 기존 키 재사용, 신설 없음)

| 키 | 기본 | 쓰는 곳 |
|---|---|---|
| `analytics.recent-window` | 12 | 01 |
| `analytics.metric-pin-days` | 3 | 02 |
| `analytics.analyze-maturity-days` | 3 | 04 (B4 가드 키 승계) |
| `analytics.trend-threshold` | 0.15 | 10 |

## 8. 테스트 하니스

- 컨벤션 유지: `NN_*.test.sql`, 더미 시드 + BEGIN/ROLLBACK 격리, run.sh 골격 유지.
- 시드 전면 재작성 — 신 스키마 INSERT: influencer(뷰티/회사/비뷰티/EXCLUDED),
  content(ENUMERATION·DISCOVERY × FEED·REELS), raw_media_page(clips 미니 payload),
  raw_profile(SELF_GQL 내장 타임라인·HIKER_MOBILE 미니 payload), raw_comment.
- 고정할 핵심 기대값: ① 모수 필터 ② views NULL 규칙(피드 NULL·릴스 play_count·타임라인
  릴스 0→NULL) ③ +3일 고정·최신 폴백 ④ 합성 id 유일성 ⑤ 프로필 소스 분기 ⑥ 04 숙성
  가드·캡션 필수 ⑦ 20 모수 교집합 ⑧ category_stats 0행·baseline 카테고리 NULL.
- 부수 효과: 시드가 신 스키마에 직접 INSERT — **§8 CI 블로커(뷰·하니스의 구 스키마 전제)
  해소**. 프레시 DB V1~V15 + 하니스 구조가 성립, CI 연결은 후속.

## 9. 적용 절차 (구현 단계)

1. PR #30 머지 대기 → develop에서 `feat/analytics-views-new-schema` 브랜치(worktree).
2. 뷰 SQL 작성 → 로컬 `crawler-postgres-1`에 번호순 적용 — 덤프에 `analytics` 스키마가
   없으므로 충돌 없이 신규 생성.
3. 하니스 전 통과 + 실데이터 스모크(뷰티 1,496계정·릴스 커버리지 94%·피드 95% 수치 재확인).
4. 미러 스모크 1회 — 기존 MirrorJob이 무변경으로 도는 것이 계약 유지의 검증.
   미러 정기 실행·운영 반영은 범위 밖.

문서 갱신: ARCHITECTURE.md §3(raw 테이블 표 신 스키마화), §5(태스크 행), §7(결정 기록),
§8(CI 블로커 상태).

## 10. 후속 태스크 (이 설계가 여는 것)

- **B4 연계 카테고리 복원**: content_analyses의 캡션 분류 결과로 account_category_stats를
  Java 미러 단계에서 조합(뷰 층위 불가 — DB 경계).
- **LLM 캡션 선분석 파이프라인**: 04 뷰를 입구로 Haiku 4.5 + Batch 구현(별도 세션 설계 진행 중).
- **CI 하니스 연결**: §8 블로커 해소 후 프레시 DB + V1~V15 + run.sh를 CI에.
- **email 서빙 검토**: HIKER_MOBILE `public_email` — 계약 정비(record·DDL·was) 동반 필요.
