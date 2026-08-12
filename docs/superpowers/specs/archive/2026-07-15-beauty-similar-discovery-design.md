# 뷰티 판정 + 유사 계정 발굴 설계

> 상태: ✅ 구현됨 — 본문 "BEAUTY 배치 상한 없음"과 달리 구현은
> `crawler.beauty.batch-limit`(기본 500) 안전 상한을 둠(초과분은 다음 실행에서 처리)

2026-07-15. 해시태그 기반 발굴에 더해, 판정 완료(QUALIFIED)된 인플루언서의 유사 계정을
새 발굴 경로로 추가한다. 시드의 뷰티 순도가 수율을 결정하므로(검증 실험 참조),
로컬 Claude로 뷰티 여부를 먼저 판정해 뷰티 시드만 유사 계정을 수확한다.

## 배경과 검증 실험

QUALIFIED 무작위 10명으로 HikerAPI `/v2/user/suggested/profiles?expand_suggestion=true`를
실제 호출해 확인한 사실:

- 호출당 유사 계정은 **정확히 30개 고정** (페이지네이션 없음)
- 10명 union 269개 중 **DB에 없던 신규 230개(85%)**, 시드 간 겹침 1개뿐
- 응답 필드는 `username, full_name, pk, is_private, is_verified`뿐 —
  **팔로워 수·bio·카테고리 없음** → 팔로워/뷰티 판정은 기존 qualify의 프로필 수집을 거쳐야만 가능
- **시드가 뷰티면 유사 계정도 뷰티로 쏠림**: `yeon_beauty._` 30개 중 17개 뷰티 vs
  비뷰티 시드(팝업스토어·여행·매거진)는 0개
- 10명 중 1명은 `Not eligible for chaining`(403 InvalidTargetUser) — 재시도 무의미
- QUALIFIED 516명 전원이 `raw_profile` 보유, 4개 소스(LEGACY_ENVELOPE 481·SELF_GQL 21·
  HIKER_MOBILE 10·DATALIKERS 4) 모두 원형에 biography/full_name/category 존재
- `ig_user_id` 보유는 516명 중 72명뿐 → 나머지는 pk 해석 폴백 필요

로컬 Claude 판정도 실증: 최신 raw_profile의 bio/카테고리/이름 10명분을
`claude -p --model haiku`에 배치로 넘겨 정확한 JSON 판정을 받았다(인스타 API 0회, 비용 $0).

## 파이프라인 전체 그림

```
(기존) discover → qualify(팔로워 3천~5만)
(신규)                └→ ① BEAUTY 잡: QUALIFIED 미판정분 → 로컬 Claude 판정 → beauty 저장
                            └→ ② SIMILAR 잡: beauty=true 시드 → 유사 계정을 DISCOVERED로 유입
                                  └→ 기존 qualify → 통과 시 다시 ①의 대상 (재귀 확장)
```

뷰티 판정은 시드 선별용이다. 유사 계정으로 발굴된 계정 자체의 뷰티 판정도
qualify 통과 후 같은 BEAUTY 잡이 자연히 수행한다.

## 데이터 모델 (마이그레이션 V10)

`influencer`에 4개 컬럼 추가:

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `beauty` | boolean NULL | NULL=미판정. SIMILAR 시드 자격 조건 |
| `beauty_source` | text NULL | `CLAUDE` 또는 `MANUAL` — 수동 오버라이드 구분 |
| `beauty_reason` | text NULL | 판정 근거 한 줄 (명단 페이지 표시용) |
| `similar_processed_at` | timestamptz NULL | 유사 계정 수확 완료(또는 수확 불가) 마킹 |

`influencer_discovery`는 스키마 변경 없음. 유사 발굴 출처는
`keyword = "유사:{시드username}"` 텍스트 스냅샷, `discovered_post_short_code`는 NULL —
기존 원칙(키워드는 텍스트 스냅샷, id 참조 금지)과 일치하고 명단 페이지 발굴 맥락에 그대로 표시된다.

## BEAUTY 잡 (`JobName.BEAUTY`)

- 대상: `status=QUALIFIED AND beauty IS NULL`. 재판정 옵션(rejudge) 시
  `beauty_source='CLAUDE'`인 판정분도 포함하되 **`MANUAL`은 절대 덮지 않는다**.
  rejudge는 qualify의 requalify와 같은 방식 — 트리거 요청의 boolean 플래그로 전달.
- 재료: 인플루언서별 최신 `raw_profile` 원형에서 username·full_name·category·bio를
  소스별로 추출. `ProfileExtractor`에 소스별 추출 메서드를 추가한다.
  - LEGACY_ENVELOPE: 최상위 `fullName`/`businessCategoryName`/`biography`
  - DATALIKERS: 최상위 `full_name`/`category_name`(또는 `business_category_name`)/`biography`
  - HIKER_MOBILE: `user.full_name`/`user.category`/`user.biography`
  - SELF_GQL: `data.user.full_name`/`data.user.category_name`/`data.user.biography`
- 판정: 50명/배치로 headless Claude 호출 → JSON 배열
  `[{"username","beauty","confidence","reason"}]` 파싱 → `beauty`,
  `beauty_source='CLAUDE'`, `beauty_reason` 저장.
- 어댑터: `application/port/out/BeautyJudge` 포트 +
  `adapter/out/claude/ClaudeCliBeautyJudge` 구현.
  `ProcessBuilder`로 `claude -p --model haiku` 실행(stdin으로 프롬프트 전달),
  배치당 타임아웃 120초. 응답의 마크다운 코드펜스는 벗겨서 파싱한다.
  포트 뒤에 있으므로 서버 배포 시 Anthropic API 구현으로 교체 가능.
- 실패 격리: 배치 단위 — CLI 오류·파싱 실패·응답 누락 username은 해당 계정을
  beauty NULL로 남겨 다음 실행에 재시도. 잡은 멈추지 않는다.
- 전제: 백엔드가 claude CLI 로그인된 로컬 맥에서 구동(현 관리툴 구조 그대로).
- Summary: `judged`(true/false 각각), `skipped`(재료 부족), `failedBatches`.

## SIMILAR 잡 (`JobName.SIMILAR`)

- 시드: `status=QUALIFIED AND beauty=true AND similar_processed_at IS NULL`,
  id 순 배치 상한(`similar.batch-limit` app_setting, 기본 50).
- 시드마다:
  1. `igUserId` 없으면 Hiker username 조회로 pk 해석 — 해석 실패 시 failedSeeds++,
     마킹하지 않고 다음 실행 재시도. 해석 성공 시 `influencer.ig_user_id`에 백필.
  2. `/v2/user/suggested/profiles?user_id={pk}&expand_suggestion=true` 호출
     → 유사 계정 최대 30개(username 기준 dedupe, 시드 자신 제외).
  3. 각 유사 계정 upsert: 신규면 `Influencer(username)` 저장(DISCOVERED),
     신규·기존 모두 `influencer_discovery("유사:{시드}")` 기록(기존 관례).
  4. raw 응답은 `crawl_run`(시드당 1 run, JobName.SIMILAR) + `raw_run_item` 원형 보존.
  5. 시드 `similar_processed_at` 마킹.
- **`Not eligible for chaining`(403 InvalidTargetUser)은 실패가 아니라 수확 불가** —
  `similar_processed_at`을 마킹해 재시도하지 않는다(ineligibleSeeds로 집계).
  그 외 오류는 시드 단위 격리(마킹 없음, failedSeeds).
- 유사 계정 사전 필터 없음(응답에 판정 재료가 없음) — 팔로워 판정은 qualify 몫.
- Summary: `processedSeeds, newInfluencers, knownInfluencers, ineligibleSeeds, failedSeeds`.
- 유사 계정 파싱은 기존 `HikerSuggestedSupplement`의 호출·순회 로직을
  `fetch(userId)` 형태로 추출해 재사용하고, enrich는 그것을 쓰도록 리팩터한다.

## UI·배선

- `JobName`에 BEAUTY·SIMILAR 추가, `JobService` 스위치 분기, `JobLock` 자동 적용.
- 대시보드에 잡 버튼 2개 + 실행 이력 라벨 + `JobCostEstimator`:
  BEAUTY $0, SIMILAR = 시드 수 × Hiker 단가 × pk 해석 보정(igUserId 없는 비율만큼 +1회).
- **수동 오버라이드**: 인플루언서 명단 페이지에 뷰티 컬럼(✓/✗/미판정 + reason 툴팁) +
  행마다 "뷰티/뷰티 아님" 두 버튼 → POST 엔드포인트가 `beauty`를 해당 값으로 설정하고
  `beauty_source='MANUAL'`, `beauty_reason='수동 판정'`을 기록. 미판정 상태에서도 바로
  지정 가능. 이후 재판정에서도 보존.
- 스케줄 자동 실행 없음 — 수동 버튼만. 필요해지면 ScheduleRunner에 한 줄.
- 설정: `similar.batch-limit`(기본 50). BEAUTY는 로컬 무료라 배치 상한 설정 없이
  미판정 전량 처리(내부 50명/호출 배치만 존재).

## 스코프 제외

- 유사 계정 응답 자체의 사전 필터(팔로워·뷰티) — 재료가 없어 불가능
- Anthropic API 기반 판정(포트 교체로 대비만)
- 스케줄 자동 실행
- 기존 raw_profile의 relatedProfiles 마이닝(절약액 ~$0.5, 복잡도 대비 무가치)

## 비용·규모 감각

- BEAUTY: $0 (구독 포함), 516명 ≈ 11배치, 수 분
- SIMILAR 발굴: 뷰티 시드 수 × ~$0.001 (pk 해석 포함 시 ×2)
- 본체는 후속 qualify 프로필 수집: 시드당 신규 ~25명 유입 × $0.0006~0.001

## 테스트

- BeautyJudgeJob: fake BeautyJudge 포트로 저장·MANUAL 보존·rejudge 동작·배치 실패 격리
- ClaudeCliBeautyJudge: 코드펜스 포함/없음 응답 파싱, username 누락 처리 (프로세스 실행은 얇게 유지)
- SimilarJob: 신규 upsert·기존 skip·출처 기록·마킹·ineligible 처리·pk 해석 폴백·시드 자신 제외
- ProfileExtractor bio/category 추출: 실제 payload 형태 fixture 4종 (기존 테스트 리소스 방식)
