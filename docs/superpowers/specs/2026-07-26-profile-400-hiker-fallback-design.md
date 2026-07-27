> 상태: 🟢 활성 · ✅ 구현됨

# 프로필 조회 400 → Hiker 폴백(`SELF_HIKER_FALLBACK`) 설계

## 배경

인스타 `web_profile_info`(SELF 자체크롤)가 특정 계정에서 **IP 무관 HTTP 400**을 반환하는
버그(비즈니스 카테고리 관련 추정)가 07-23 기준 계정 약 29%까지 확산됐다. 임시 대응으로
`profile.source`를 `HIKER_MOBILE`로 전면 전환했지만, 두 가지 손해가 있다:

- **피드 열거 상실**: 게시물 열거는 SELF 프로필 원형(edge_owner_to_timeline_media)에서만
  가능하다. HIKER 프로필은 게시물이 없어 `HIKER_V1_MEDIAS` 폴백(igUserId 기반, 유료)에 의존.
- **비용**: 정상 계정 ~71%까지 유료 Hiker 호출을 쓴다.

계정별로 400일 때만 Hiker로 폴백하면 정상 계정은 SELF(무료·피드 포함)로, 버그 계정만
Hiker로 처리할 수 있다.

## 결정 사항

1. **폴백 트리거는 HTTP 400만.** 버그가 IP 무관·결정적이라 재시도 무의미 → 즉시 폴백.
   404(계정 소멸)·블록(429/401/403) 소진·기타 실패는 기존 동작 그대로(폴백 없음).
2. **컴포지트 페처 + 새 `ProfileSource.SELF_HIKER_FALLBACK`.** `SelfProfileFetcher` 내장이나
   `ProfileSourceSelector` 오케스트레이션 대신 별도 `ProfileFetcher` 구현체로. 기존 셀렉터·
   `app_setting` 런타임 토글 컨벤션 그대로 SELF(순수) ↔ SELF_HIKER_FALLBACK ↔ HIKER_MOBILE
   전환 가능.
3. **혼합 배치의 아이템별 소스는 셰이프 감지로 구분.** 인터페이스 개편(아이템·소스 페어 반환)은
   변경 범위가 과하고, 페이로드 마커 주입은 "응답 원형 그대로" 컨벤션 위반이라 배제.

## 구조

### 1. 컴포지트 페처 `SelfWithHikerFallbackProfileFetcher`

`ProfileFetcher` 구현, `LABEL = "profile-self-hiker"`.

1. SELF 경로로 배치 조회하면서 HTTP 400이 난 계정 목록을 따로 수집
2. 400 계정들만 `HikerMobileProfileFetcher` 경로(`/v2/user/by/username`)로 2차 조회
3. 두 결과의 items·notFound를 병합해 반환

**crawl_run은 1건.** 호출자가 `ex.runId()`로 raw를 저장하므로 컴포지트가 자기 라벨로
`executor.execute()`를 한 번 감싸고, 내부에서 두 페처의 collect 로직을 직접 호출한다.
이를 위해:

- `SelfProfileFetcher.collect(usernames, badRequestOut)` — 패키지 가시성, 400 계정을
  out-리스트로 수집(기존 스킵 동작·로그는 유지). 400은 지금처럼 회로 차단 카운터와 무관
  (블록 신호 아님).
- `HikerMobileProfileFetcher.collect(usernames)` — 패키지 가시성으로 개방.

`source()`는 `SELF_HIKER_FALLBACK`, `rawSource()`는 `SELF_GQL`(혼합 배치의 기본 소스 역할).

### 2. 설정·enum

- `ProfileSource`에 `SELF_HIKER_FALLBACK` 추가.
- 전환은 기존대로 `app_setting`의 `profile.source` 수동 UPDATE(런타임 토글). **이번 작업은
  옵션 추가까지만** — 실제 전환은 사용자가 결정·실행한다(피드 열거 제약상 소스 변경은
  사용자 확인 필수). 마이그레이션 불필요.
- 어드민 UI(`ProfileSourceUiController`) 소스 목록에 새 값 노출.

### 3. 아이템별 소스 감지 `ProfileExtractor.detect(payload, defaultSource)`

- 루트에 `"data"` 맵 → `SELF_GQL`
- 루트에 `"user"` 맵 또는 `"pk"` 키 → `HIKER_MOBILE`
- 그 외 → `defaultSource` (LEGACY_ENVELOPE 등 기존 소스 보호)

두 원형의 루트 구조가 확실히 달라(SELF_GQL은 `{"data":{"user":…}}`, HIKER_MOBILE은
`{"user":…}` 또는 flat) 감지가 안정적이다.

적용 지점 3곳 — 배치 고정 `source`를 아이템별 `detect(item, source)`로 교체:

- `CollectJob.refreshProfile` — `RawProfile.source`에 감지값 저장(다운스트림 정확성)
- `QualifyJob.applyChunk` — 동일
- `ProfileSupplementer.apply` — `DEFICIENT`에 `SELF_HIKER_FALLBACK` 추가 + userId 추출을
  아이템별 감지로

`raw_profile.source`에 감지된 실제 소스가 행 단위로 저장되므로 다운스트림(analytics·
뷰티판정)은 정확하다 — 현재도 운영상 HIKER_MOBILE 행이 섞여 있어 혼합 소스 처리는 기존
경로가 이미 감당한다.

## 동작 결과

| 계정 유형 | 결과 |
|---|---|
| 정상(~71%) | SELF_GQL 원형 — 피드 열거 정상, Hiker 비용 0 |
| 400 버그(~29%) | HIKER_MOBILE 원형 — 프로필 확보, 게시물은 기존 `HIKER_V1_MEDIAS` igUserId 폴백이 커버 |
| 404·블록 소진 | 기존 동작 그대로(폴백 없음) |

## 테스트

- 컴포지트: 400 계정만 Hiker로 넘어가는지, 결과 병합, 400 없으면 Hiker 무호출
- `SelfProfileFetcher`: 400 계정이 badRequestOut에 수집되는지(기존 스킵 동작 유지 확인)
- `ProfileExtractor.detect`: 세 분기
- `ProfileSupplementer`·`ProfileSourceSelector`·어드민 UI: 새 소스 반영
