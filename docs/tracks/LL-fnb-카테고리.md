# LL — F&B 카테고리 추가(판정 2축화)

- **소속 트랙군**: crawler 판정 트랙 — 설계: [specs/2026-08-23-fnb-category-design.md](../superpowers/specs/archive/2026-08-23-fnb-category-design.md)
- **의존**: 트랙 P(뷰티 판정 v3 한국어 필터)·CC(FOREIGN_INFLUENCER 재판정)의 판정 잡 구조 위에서 동작
- **상태**: ✅ 구현 완료(2026-08-24, 한 PR) — 수집 토글 on은 백필 완료 후 별도 결정(⬜)

## 내용

뷰티 단일 카테고리였던 판정을 **뷰티 + F&B 2축**으로 확장한다. 한 계정이 두 축 모두일 수
있으므로(복수 카테고리 허용) 축별 boolean 병렬이 자연 모델 — `influencer`에 `fnb_*` 컬럼을
뷰티 구조 그대로 복제하고, LLM 판정은 **1콜에서 두 축을 동시에** 낸다(콜 수 증가 없음).
서빙 모수(분석 뷰 01/02/20·was API)는 뷰티 그대로 두고, F&B는 판정·명단까지만 준비한다.

### 태스크

| # | 내용 | 상태 |
|---|---|---|
| 1 | `influencer` F&B 축 컬럼(`fnb_class`·`fnb`/`fnb_company`·`fnb_judged_at`·`fnb_source`/`fnb_reason`/`fnb_basis`·`fnb_caption_count`) + 카테고리 중립 enum `CategoryClass` — 마이그레이션 `V20260824082708__influencer_fnb.sql`(expand only, CHECK 제약 포함) | ✅ |
| 2 | 판정 프롬프트·파서 2축화 — `BeautyJudge.Verdict`에 `fnbClass`/`fnbReason`/`fnbBasis`, 어댑터 3종(claude-api·claude-cli·gemini) 공유 프롬프트 1벌, `MAX_TOKENS` 8192→16384 | ✅ |
| 3 | 신규 유입은 두 축 적용 / 백필(`beauty` 판정됨 ∧ `fnb IS NULL`)은 **F&B 축만 적용** — 기존 뷰티 판정(MANUAL 포함) 보존. 신규 우선·남는 자리를 백필로 채우는 선정 순서 | ✅ |
| 4 | 수집·시드·비용 추정 F&B 편입 게이트 — `app_setting` `fnb.pipeline-enabled`(기본 `false`). off면 collect·reels·similar 선정 쿼리 현행 그대로 | ✅ |
| 5 | 어드민 — 명단 F&B 필터·수동 오버라이드(MANUAL 보호 동일)·대시보드 "F&B 판정" 타일(백필 잔여 관측), `BeautyJob.Summary` F&B 카운트 | ✅ |
| 6 | `v_base_influencer`에 `fnb`·`fnb_company` 노출(소비자 없음, 서빙 무변경) + SQL 하니스 `00_base` 단언 | ✅ |
| 7 | 문서·PR | ✅ |
| — | **수집 토글 on**(`UPDATE app_setting SET value='true' WHERE key='fnb.pipeline-enabled'`) — F&B 모수·Hiker 비용 확인 후 별도 결정 | ⬜ |

### 주요 결정

- **범용 `influencer_category` 테이블을 지금 만들지 않는다** — 카테고리 2개 시점엔 조인 전환
  비용이 이득보다 크다. 3개 이상으로 늘면 그때 이관(스펙 §1 대안 기각 사유).
- **`BeautyClass`·beauty 축 저장값은 그대로** — `BEAUTY_SERVICE`·`NOT_BEAUTY` 문자열이 운영 DB에
  박혀 있어 통일 rename 위험이 이득보다 크다. 신규 축만 중립 enum `CategoryClass`
  (`INFLUENCER`/`COMPANY`/`SERVICE`/`FOREIGN_INFLUENCER`/`NONE`)를 쓴다.
- **포트·잡 이름은 `BeautyJudge`/`BeautyJob`/`JobName.BEAUTY` 유지** — 크론 키·어드민·중지 플래그가
  물려 있어 rename 이득이 없다. 주석으로 "판정 잡(두 카테고리)"임을 명시.
- **한 PR로 배포 가능** — 마이그레이션이 expand only이고 파이프라인 편입은 토글 off라 롤링 안전.

### 운영 메모 (코드 아님)

- 배포 직후부터 새벽 판정 크론이 신규 2축 판정 + 백필을 자동 시작한다(배치 한도
  `beauty.batch-limit` 내). 당기려면 어드민에서 판정 수동 트리거.
- 백필 진행률은 대시보드 "③-2 F&B 판정" 타일의 미판정 수로 본다.
- 토글 on은 재기동 불필요 — 잡이 실행 시점마다 `app_setting`을 읽는다.

## 검증

- `./gradlew test`(4모듈 전체) 통과 — PR 직전 실행.
- `BeautyJobTest`: 양축 적용(신규) / F&B만 적용·뷰티 보존(백필) / 뷰티축 무응답 / 적용 축 0개면
  저장·카운터·로그 스킵. `BeautySelectionIntegrationTest`: 백필 선정 순서(신규 우선).
- parse 테스트 3종(claude-api·claude-cli·gemini): 양축 JSON·한 축 누락·5분류 외 값 방어.
- 수집 게이트: `CollectJob`·`ReelsJob`·`SimilarJob`·`JobCostEstimator` 테스트에 토글 off/on 케이스.
- 어드민: `UiSmokeTest`(F&B 필터·타일 배지)·`InfluencerBeautyControllerTest`(F&B 오버라이드).
- SQL 하니스 `00_base.test.sql`: `v_base_influencer` fnb 컬럼 노출.

## 후속 후보

- **F&B 축 rejudge 대칭 확장** — 기존 뷰티 rejudge 3종(재료 갱신·캡션 0건 등)의 F&B 대응.
  재료(`fnb_caption_count`·`fnb_judged_at`)는 이번에 쌓기 시작했으므로 필요 시 바로 확장 가능.
- **카테고리 서빙 개편** — 랭킹·상세·was API가 카테고리를 인지하도록. 현재 `v_base_influencer`의
  `fnb`·`fnb_company` 노출이 그 재료다(소비자 없음).
- **`SettingsService` boolean 설정 UI** — `fnb.pipeline-enabled` 토글이 지금은 운영 수동 UPDATE다.
  boolean 설정이 더 늘면 어드민에서 켜고 끄는 편이 낫다.

## 관련 문서

- [specs/2026-08-23-fnb-category-design.md](../superpowers/specs/archive/2026-08-23-fnb-category-design.md) — 설계 전문.
- [plans/archive/2026-08-24-fnb-category.md](../superpowers/plans/archive/2026-08-24-fnb-category.md) — 구현 계획(실행 완료).
