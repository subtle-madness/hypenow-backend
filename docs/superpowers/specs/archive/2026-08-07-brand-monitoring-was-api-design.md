# 브랜드 모니터링 was API — 설계

> 상태: ✅ 구현/반영됨 (2026-08-07 — PR #354로 전체 구현·운영 배포. FE 브랜드뷰 모니터링 API 명세를 dev 기준 was·monitoring에 착지시키는 설계.
> 수집 파이프라인 정본은 DECISIONS 08-06 개정 행 · 트랙 [MON-BT](../../../tracks/MON-BT-브랜드-태그-모니터링.md))

## 0. 한 줄 요약

FE 브랜드뷰 명세(브랜드 모니터링 v1 · 성과 대시보드 v1 · 캠페인 확장 v2)를 **레거시
`/v1/monitoring/**` 완전 동결 + 전부 추가** 원칙으로 구현한다. was는 신규 패키지 3개, app
스키마는 컬럼 1 + 테이블 2 추가, monitoring은 브랜드 전용 테이블 3개에 nullable 컬럼 추가와
파싱 확장(API 콜 증가 0)만 손댄다.

## 1. 범위와 전제

- **입력 계약**: FE가 준 "브랜드뷰 모니터링 API 명세"(2026-08-06) — 엔드포인트·응답 셰이프·
  에러 코드·envelope·`users.instgram_account_name` 철자·불변 정책이 강제 계약이고, §10 저장
  구조는 권장(프로젝트 관례 우선 명시)이다.
- **레거시 보호(FE §1.1)**: 기존 `/v1/monitoring/**` 컨트롤러·서비스·응답, 기존 monitoring
  캠페인 추적 파이프라인(`target`·`post_snapshot` 계열)은 0줄 변경.
- 수집 실체는 08-06 구현된 브랜드 전용 파이프라인(브랜드 7테이블 + 매일 전량 105개 스윕 +
  `POST/DELETE /api/brands`)이다. FE 명세 §3의 수집 정책 서술(감지/추적 분리, 게시자 월 1회)은
  구현 개정 전 문서라 계약 표면만 지키고 내부는 현행 파이프라인을 따른다.
- 사용자 결정(08-07 브레인스토밍): 전체 1스펙 / monitoring 파싱·스키마 확장 포함 / 백필 오류는
  monitoring에 기록 / 직접 등록은 레거시 파이프라인 재사용 / 캠페인 관계는 1:1 유지 /
  was 코드는 브랜드 전용 계층 분리(접근안 A).

## 2. FE 명세와 의도적으로 다른 지점 (FE 공유 필요)

| 항목 | FE 명세 | 이 설계 | 이유 |
|---|---|---|---|
| 등록 순서(§5.2) | 커밋 → job enqueue | **monitoring 동기 검증 → 커밋** | `instgram_account_name`은 불변 — 오타 username 영구 저장 사고 차단. 관측 차이는 "없는 계정이 error 폴링 대신 즉시 422"뿐이고 422 `INSTAGRAM_ACCOUNT_NOT_FOUND`는 FE 에러 표에 이미 있음 |
| `lastDetectedAt`/`lastTrackedAt` | 구분된 두 시각 | **동일값**(`last_swept_at`) | 08-06 개정으로 감지/추적 구분 폐지 — 매일 전량 스윕 하나 |
| ID 형태 | 예시 `brand_lizda`·`ig_ABC123` | 숫자 문자열(`"17"`)·shortcode(`"ABC123"`) | 계약은 string + URL-safe 정규식뿐 — 예시 접두사는 비계약 |
| `BRAND_ACCOUNT_LIMIT_REACHED` | 409 정의 | 코드만 예약(도달 불가) | 1계정·불변 정책에선 ALREADY_EXISTS/IMMUTABLE이 전 케이스 커버 |
| 판정 근거 저장 권장(§4.4) | 내부 저장 권장 | 저장 안 함(조회 시 계산) | 캡션 원문이 이미 저장돼 있어 언제든 재판정 가능, 키워드 개선이 과거분에 즉시 소급 |

## 3. 스키마

### 3-1. app (was) — 마이그레이션 1개

```sql
-- FE §10.1 요청 철자 그대로(instgram — 명세가 명시적으로 고정)
ALTER TABLE app.users ADD COLUMN instgram_account_name varchar(30);
CREATE INDEX ON app.users (instgram_account_name);

-- user↔브랜드 활성 연결 (FE user_brand_account 역할)
CREATE TABLE app.brand_monitorings (
    id         bigserial PRIMARY KEY,
    user_id    bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    brand_id   bigint NOT NULL,          -- monitoring brand_account.id 논리 참조(크로스 DB FK 금지)
    username   text   NOT NULL,          -- 정규화 username 사본(조회 편의)
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz               -- §5.4 삭제 = 연결 soft-delete
);
CREATE UNIQUE INDEX ON app.brand_monitorings (user_id) WHERE deleted_at IS NULL;

-- 직접 등록 매핑: 레거시 아이템에 "브랜드 화면 소속" 표식 (FE brand_direct_media 역할)
CREATE TABLE app.brand_direct_posts (
    user_id            bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    brand_id           bigint NOT NULL,
    short_code         text   NOT NULL,
    monitoring_item_id bigint NOT NULL REFERENCES app.monitoring_items(id) ON DELETE CASCADE,
    created_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, short_code)
);
```

### 3-2. monitoring — 마이그레이션 1개 (전부 nullable ADD, 브랜드 전용 테이블만)

```sql
ALTER TABLE brand_account
    ADD COLUMN full_name text, ADD COLUMN profile_pic_url text,
    ADD COLUMN is_verified boolean, ADD COLUMN external_url text,
    ADD COLUMN following bigint, ADD COLUMN media_count bigint,   -- 스윕 최신값(추이는 기존 snapshot)
    ADD COLUMN backfill_error text,             -- 초기 백필 재시도 소진 시 기록, 성공 시 클리어
    ADD COLUMN backfill_completed_at timestamptz,   -- 최초 백필 완주 시각(collectionCompletedAt)
    ADD COLUMN last_swept_at timestamptz;           -- lastDetectedAt·lastTrackedAt 공급
ALTER TABLE brand_post_meta
    ADD COLUMN video_url text, ADD COLUMN video_duration double precision,
    ADD COLUMN is_paid_partnership boolean;
ALTER TABLE author_profile ADD COLUMN is_verified boolean;
```

- 파싱 확장: `ProfileInfo`(+is_verified·external_url) · `AuthorInfo`(+is_verified) ·
  `PostInfo`(+video_url·video_duration·is_paid_partnership) — 이미 받는 응답에서 추가 파싱만,
  API 콜 증가 0. `PostInfo`는 캠페인 경로와 공유 record지만 추가 필드는 레거시 소비처가 무시.
- `takenAt` 타임스탬프는 기존 `brand_tagged_post.taken_at` 사용(메타 컬럼 추가 불필요).
- 기존 캠페인 테이블은 0줄 변경 — expand-contract 가드 대상 없음.

## 4. was 코드 배치 (접근안 A — 브랜드 전용 계층 분리)

```
was/monitoring/                  # 기존 seam — 추가만
  BrandReadRepository              # 신규: brand_* 테이블 읽기 전용 배치 조회(형제: MonitoringReadRepository)
  MonitoringCommandClient          # 기존 파일에 brand register/deregister 메서드 2개 추가
was/v1/brandmonitoring/          # 신규 — /v1/brand-monitoring/**
  V1BrandAccountsController        # accounts 목록·단건·등록·삭제
  V1BrandPostsController           # posts 목록·상세 · direct-posts · direct-registrations
  BrandAccountAssembler            # BrandAccount 조립 + 상태 유도
  BrandPostAssembler               # BrandPost 조립 — tagged(브랜드 테이블) + direct(레거시 테이블) 합성
  BrandSponsorshipClassifier       # 협찬 판정 순수 함수
  (+DTO record들)
was/v1/perfdashboard/            # 신규 — /v1/performance-dashboard/**
  V1PerformanceDashboardController
  PerformanceContentAssembler      # 3계열 통합 + shortcode 중복 제거
was/v2/monitoring/               # 신규 — /v2/monitoring/campaigns/**
  V2CampaignContentsController
```

조립 관용구는 기존 `TrackingItemAssembler`와 동일: shortcode 컬렉션으로 테이블당 1 SQL
왕복(N+1 금지). 레거시 아이템 조립은 `TrackingItemAssembler`를 호출자로 재사용(중복 구현 0).

## 5. 브랜드 계정 라이프사이클

### 5-1. 등록 (POST /v1/brand-monitoring/accounts → 202)

```
1. 형식 검증: ^[A-Za-z0-9._]{1,30}$ · ".." 금지 · @ 제거·trim·소문자 정규화 (위반 400)
2. 사전 상태: 같은 값+활성 연결 → 409 BRAND_ACCOUNT_ALREADY_EXISTS
              다른 값 저장됨     → 409 BRAND_ACCOUNT_IMMUTABLE (기존 값 유지)
3. monitoring POST /api/brands 동기 호출 (트랜잭션 밖 — 멱등 replay라 재시도 안전)
   IG 계정 없음(404) → 422 INSTAGRAM_ACCOUNT_NOT_FOUND · 비공개(422) → 422 ·
   monitoring 불능 → 503 SERVICE_UNAVAILABLE
4. was 트랜잭션: user 행 FOR UPDATE → 2번 재확인(동시 요청 방어) →
   instgram_account_name 저장(이미 같은 값이면 그대로) + brand_monitorings 생성 → 커밋
   실패 시 best-effort로 DELETE /api/brands 보상 호출(고아 brand_account 정리 — 실패해도 무해)
5. 202 + BrandAccount(collectionStatus "collecting")
```

- **재등록**: 삭제(soft-delete된 연결) 후 **같은 username만** 재등록 허용 — 연결 재생성 +
  monitoring replay. 다른 username은 항상 409 IMMUTABLE (§5.4 불변 우회 차단).
- 회원가입 시 계정명 확보 케이스는 이번 범위 밖(가입 플로우 무수정) — 등록 API 단일 진입.

### 5-2. 상태 유도 (폴링 GET /accounts/{id})

| brand_account 조건 | collectionStatus |
|---|---|
| `last_swept_on` null · `backfill_error` null | `collecting` |
| `last_swept_on` null · `backfill_error` 있음 | `error` + collectionError(code `BACKFILL_FAILED`, 한국어 메시지) |
| `last_swept_on` 있음 | `ready` |

- `collectionStartedAt`=`registered_at` · `collectionCompletedAt`=`backfill_completed_at` ·
  `lastDetectedAt`=`lastTrackedAt`=`last_swept_at` · `nextScheduledAt`=다음 스윕 크론 계산값.
- `BrandAccount.id` = `brand_account.id` 문자열. 소유권은 `brand_monitorings` 활성 연결로 검증
  (남의 brandId 접근 403, 명세 §2.1).
- monitoring 쪽: 백필 실패(재시도 소진) 시 `backfill_error` 기록, 이후 스윕(백스톱) 성공 시
  클리어 + `last_swept_on`/`last_swept_at`/`backfill_completed_at` 설정 — error→ready 자연 복구.
  일상 스윕 실패는 계정 error가 아니다(그날 스냅샷 부재일 뿐, FE §2.5).

### 5-3. 삭제 (DELETE /accounts/{id} → 204)

`brand_monitorings.deleted_at` 설정 → 같은 brand를 참조하는 다른 활성 연결이 없으면
monitoring `DELETE /api/brands/{username}`(CLOSED — 수집 중단), 있으면 monitoring 유지.
`users.instgram_account_name`·레거시 아이템·캠페인 연결·직접 등록 아이템은 전부 무수정(§5.4).

## 6. 브랜드 게시물 조회

### 6-1. 목록 (GET /accounts/{id}/posts)

- **tagged**: `brand_tagged_post`에서 윈도우 내(`taken_at ≥ KST 오늘−90일`, 최신순 105개) →
  shortcode 묶음으로 `brand_post_meta`·`brand_post_snapshot`(전량, 날짜 오름차순)·
  `brand_post_comment`·`author_profile` 배치 조회.
- **direct**: `app.brand_direct_posts` → 레거시 테이블(`MonitoringReadRepository`) 배치 조회 →
  `BrandPost` 셰이프 변환.
- 같은 shortcode가 양쪽이면 1건 병합, `source: "direct"` 우선(명시 등록 보존).
- 필터(`source`·`sponsorship`·`uploadedFrom/To`)·정렬(`uploaded_desc`/`performance_desc`)은 was
  메모리 처리(~105+α건). `meta.counts`는 필터 적용 전 전량 기준, `meta.lastCollectedAt`=`last_swept_at`.
- `trackingStatus`: tagged=`tracking`(기본 목록은 윈도우 내만 — `ended`는 목록 밖 보존),
  direct=레거시 아이템 상태 그대로 매핑.
- 스냅샷 매핑: FEED는 views·shares·reposts null 강제(레거시 동일), `likesHidden`·`sharesHidden`
  그대로, views는 IG+FB 합산 규칙 재사용. FE 계약에 없는 `sharesHidden`은 응답에 포함
  (레거시 계약과 동형 — FE가 이미 소비 중인 필드셋 유지).
- 댓글: `recentComments` 최신순 저장분 전량(수집 상한 45), author는 `AuthorMask` 재사용,
  `commentsTotal`=최신 스냅샷 comments, `commentsCollectedCount`=저장 댓글 수.

### 6-2. 상세 (GET /posts/{postId})

`BrandPost.id` = **shortcode**. 소유권: 내 브랜드 tagged 또는 내 direct 매핑에 존재해야 함 —
아니면 404(존재 여부 비노출). 응답은 목록과 동일 조립(전체 snapshots·recentComments 포함).

### 6-3. 협찬 판정 (BrandSponsorshipClassifier)

```
1. is_paid_partnership == true                  → sponsored
2. 캡션 확정 키워드(#광고·#협찬·#유료광고·"유료 광고" 등) → sponsored
3. is_paid_partnership == false · 키워드 없음     → organic
4. is_paid_partnership == null  · 키워드 없음     → unknown
```

조회 시 계산(저장 없음) — 키워드 리스트는 상수로 시작, 개선 시 과거분 즉시 소급.

### 6-4. 직접 등록 (POST /accounts/{id}/direct-posts → 202)

기존 `V1MonitoringRegistrationService` 경로 재사용(shortcode 정규화·URL 검증 포함):

- 이미 내 tagged/direct에 존재 → entry `duplicate`
- 이미 레거시 아이템으로 추적 중 → `brand_direct_posts` 매핑만 추가 → `success` + monitoringItemId
- 신규 → 레거시 등록(`monitoring_registrations` entry → item 생성) + 매핑 추가 → `pending`
- `GET /direct-registrations/{registrationId}`는 기존 등록 상태 테이블 조회로 entry 결과
  (`pending|success|failed|duplicate`) 조립 — 별도 상태 저장소 신설 없음.
- 등록 성공분은 브랜드 화면(`direct`)과 성과 대시보드 양쪽에 자연 노출(완료 조건 4).

## 7. 성과 대시보드

### 7-1. 통합 (GET /v1/performance-dashboard/contents)

```
1. 레거시 아이템 전량: TrackingItemAssembler.assembleList() 재사용
   → source: brand_direct_posts 매핑 있으면 "direct", 없으면 "individual"
2. 브랜드 tagged 전량: BrandPostAssembler 재사용
3. shortcode 병합(§6.4 우선순위 individual > direct > tagged):
   - 레거시 아이템 있음 → item·상태·기간·캠페인 전부 레거시 우선, tagged 겹침은 additionalSources
   - tagged만 → TrackingItem 합성: id "bt_"+shortcode(레거시 숫자 id와 충돌 방지), mode "url",
     status "tracking", handle·displayName·followers=author_profile, registeredAt=first_seen 날짜,
     trackingDays=90, campaignId null, post 채움
4. canonicalPostId = shortcode (post 없는 detecting·not_uploaded는 null — item.id로만 식별)
```

- **스냅샷 병합**(양쪽 보유 시): 날짜별, 지표별 non-null 우선. 둘 다 값이면 브랜드 값
  (03:00 스윕이 캠페인 02:00보다 늦음 — "늦게 수집된 원천값" 규칙 부합). 충돌 debug 로그.
- `meta.statusCounts`: 업로드 기간 필터 미적용, 소유·출처·캠페인·brandAccountId 필터는 적용
  (§6.3). `data`는 전 필터 적용. 정렬·증가분은 FE 책임(원시 스냅샷만 제공).
- 단건 `GET /contents/{contentId}`: contentId=canonicalPostId(순수 shortcode만) → 소유 범위
  재조립, 없으면 404.

## 8. 캠페인 v2

- `POST /v2/monitoring/campaigns/{campaignId}/contents` `{contentIds[], trackingDays}` —
  contentId(=shortcode)별:
  - 내 레거시 아이템 존재 + campaign 없음 → `campaign_id` 연결 → `success`
  - 존재 + 같은 캠페인 → `duplicate`(CAMPAIGN_CONTENT_ALREADY_EXISTS)
  - 존재 + 다른 캠페인 → `duplicate` + 사유(1:1 유지 — 이동은 기존 PATCH)
  - 미존재(tagged만) → 레거시 등록 파이프라인으로 아이템 생성(url 모드·canonical URL·
    trackingDays·campaignId) → `success`/`pending` + monitoringItemId
  - entry 단위 부분 성공. 동기 완결 시 200, 등록 생성 섞이면 202.
- `DELETE .../contents/{contentId}` → 아이템 `campaign_id` null(모니터링 지속, §7.2) → 204.
- 기존 `/v1/monitoring/campaigns` CRUD·`V1CampaignService` 무수정.

## 9. 에러 매핑

- 기존 `ApiResponse` envelope·`V1ApiException` 핸들러 재사용. 신규 코드:
  `BRAND_ACCOUNT_ALREADY_EXISTS`·`BRAND_ACCOUNT_IMMUTABLE`·`BRAND_ACCOUNT_LIMIT_REACHED`(예약)·
  `POST_ALREADY_REGISTERED`·`CAMPAIGN_CONTENT_ALREADY_EXISTS`·`INSTAGRAM_ACCOUNT_NOT_FOUND`(422)·
  `INSTAGRAM_POST_NOT_FOUND`(422 — 직접 등록 entry 실패 사유로도 사용).
- monitoring 번역: 404→422 `INSTAGRAM_ACCOUNT_NOT_FOUND` · 422(비공개)→422 ·
  `MonitoringUnavailableException`→503 `SERVICE_UNAVAILABLE`.
- 메시지는 FE 그대로 노출 가능한 한국어 문장(기존 관례).
- FE 선배포 필요(명세 §2.6): `ApiErrorCode` 유니언에 신규 코드 추가가 백엔드 배포에 선행.

## 10. 테스트

- **was**(Testcontainers 통합, 모듈 단위 실행): 컨트롤러별 계약 테스트가 FE §14 체크리스트
  커버 — envelope·명시적 null·`@` 정규화·409/422 분기·불변 정책(다른 값 409 + DB 유지)·
  삭제 후 컬럼 유지·shortcode 중복 제거·additionalSources·statusCounts 기간 무관·0건 빈 배열·
  **레거시 v1 응답 무변경 회귀**. 협찬 판정·스냅샷 병합·상태 유도는 순수 함수 단위 테스트.
- **monitoring**: 파싱 확장 단위 테스트(신규 필드 값/부재 null), 백필 오류 기록·스윕 클리어
  통합 테스트, 스윕의 brand_account 신규 컬럼 갱신 확인.

## 11. 구현 순서 (계획 문서에서 단계화)

1. monitoring 확장: 마이그레이션 + 파싱 + 백필 오류 기록·스윕 갱신
2. app 마이그레이션 + 브랜드 계정 등록·조회·삭제·폴링 (BrandReadRepository·커맨드 확장 포함)
3. 브랜드 게시물 목록·상세 + 협찬 판정
4. 직접 등록 + 등록 상태 조회
5. 성과 대시보드 통합·중복 제거
6. 캠페인 v2
7. 계약 테스트 총정리 + 레거시 회귀 확인
