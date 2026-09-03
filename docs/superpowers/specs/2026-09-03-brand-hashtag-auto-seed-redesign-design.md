# 브랜드 해시태그 자동 시드 재설계 — 계정명 절삭 폐기, 태그된 게시물 빈도 + IG 표시명 AI

> 상태: 🟢 활성 · 설계 확정(2026-09-03, 2차 개정) · 구현 PR 개설(운영 정리 §5 잔여)
>
> 대체: 2026-08-17(be39cbd7)·08-27 §4의 **계정명 선행 접두사 절삭** 유도 규칙. **08-28 "태그 생성 권한
> was 일원화" 결정은 그대로 유지한다**(monitoring은 계산만, 쓰기는 was). 폐기 사유는
> [DECISIONS.md](../../../DECISIONS.md)에 기록한다. 1차 초안(monitoring이 직접 쓰는 A안)은 "같은
> 태그를 두 모듈이 각자 쓰는 구조"라 사용자 지적으로 폐기됐다.

## 1. 문제

was가 브랜드 링크 생성 시 심는 자동 태그(`V1BrandAccountService.seedLedgerTagsSafely` →
`BrandHashtagTags.derive`)는 계정명을 소문자화한 뒤 **첫 무효 문자(점) 앞까지 자른 접두사**다.
IG 해시태그 실동작(`#cclime.beauty`를 치면 `#cclime`이 된다)에 맞춘 규칙이지만, 점이 든 계정명에서는
결과가 계정과 무관한 일반어가 된다.

- `dr.piel_official` → `dr`. 그 태그가 monitoring에 push돼 해시태그 스윕이 `#dr` 전체를 긁는다
  (무관 게시물 대량 유입 + Hiker 콜 낭비).
- 점이 든 계정명은 애초에 그 자체로 해시태그가 될 수 없으므로, 문자열을 어떻게 자르든(점 제거·
  최장 구간·절삭) 결과는 전부 추측이다. 실제 소비자가 쓰는 태그는 `#닥터피엘` 같은 상호다.

현 상태(08-28 이후): 자동 시드는 **was 한 곳**에서만 한다. monitoring은 `brand_hashtag`에 스스로
아무것도 심지 않는다. 이 원칙은 유지한다.

## 2. 결정 요약

| 항목 | 결정 |
|---|---|
| 자동 태그 재료 | 계정명 문자열 절삭 **폐기**. 1순위 = 그 브랜드에 **태그된 게시물**의 캡션 해시태그 빈도. |
| 임계 | 최다 태그의 **등장 게시물 수 ≥ 7**이면 그 태그 **1개**. 비율 조건 없음. |
| 임계 미만 | AI가 **IG 표시명(full_name)**에서 브랜드 상호를 뽑아 해시태그 1개로 낸다. 표시명에 상호가 없으면 계정명 핵심으로. **회사명(app.users.company_name)·바이오는 쓰지 않는다.** |
| 결과 보장 | 브랜드당 **항상 1개**. AI 출력이 무효면 코드가 정리하고, 그래도 비면 계정명에서 점·언더스코어를 뺀 값. 경쟁사 포함 **전 브랜드** 동일. |
| 누가 계산 | **monitoring** — 태그된 게시물·표시명·Gemini가 전부 monitoring에 있다. 내부 조회 API 1개, **DB 쓰기 없음**. |
| 누가 쓴다 | **was만**(08-28 결정 유지). 장부(`app.brand_hashtag_tags`) + 기존 일반 태그 add 경로로 monitoring push. |
| 시점 | 링크 생성 시가 아니라 **초기 백필 완료 뒤**. was 단건 폴링의 수집 완료 판정 자리 + 태그 목록·해시태그 게시물 조회에 같은 훅. |
| 중복·부활 방지 | 브랜드당 시드 기록 1행(`app.brand_hashtag_seed`) + 링크 행 `hashtag_seeded_at`. 계산은 브랜드당 1회, 장부 삽입은 사용자당 1회. |
| 운영 정리 | 이미 심긴 절삭 태그를 monitoring·was 양쪽에서 삭제. 재시드는 훅이 자동으로 한다(스크립트 불요). |

## 3. monitoring — 계산 API (쓰기 없음)

### 3-1. 엔드포인트

`GET /api/brands/{username}/hashtag-suggestion` (내부 API, `BrandController` 태그 GET과 동형).
브랜드 미존재·비ACTIVE는 404. 응답:

```json
{ "path": "FREQ" | "AI" | "FALLBACK", "tag": "닥터피엘", "topCount": 12, "candidatePosts": 40 }
```

`tag`는 항상 비어 있지 않다. 상태를 저장하지 않으며, 같은 입력엔 같은 답을 낸다(AI temperature 0).
백필 완료 여부는 검사하지 않는다 — 호출 시점 게이트는 was 책임(§4-2).

### 3-2. 1순위 — 태그된 게시물 캡션 빈도

- 모수: `brand_tagged_post`(brand_id) ⋈ `brand_post_meta`(short_code)의 `caption`. **태그 피드로
  들어온 행만**(`tag_detected_at IS NOT NULL` — 해시태그 스윕으로 편입된 행은 제외한다. §5 정리 뒤에도
  `#dr`로 긁혀 온 게시물 행이 남는데, 그 캡션이 집계에 섞이면 새 규칙이 오염을 물려받는다).
- 추출: `#([\p{L}\p{N}_]+)` 전역 매치, `toLowerCase(Locale.ROOT)`. **게시물당 중복 제거** 후 태그별
  등장 게시물 수를 센다.
- 제외: stoplist(§3-5)·순수 숫자 태그.
- 정렬: 등장 게시물 수 내림차순 → 동률이면 그 태그가 등장한 게시물의 최근 `taken_at` 내림차순 →
  태그 사전순(결정적).
- 최다 태그의 등장 수가 `min-posts`(기본 7) 이상이면 `path=FREQ`, 그 태그 1개.

### 3-3. 2순위 — AI: IG 표시명 → 상호 해시태그

- 입력: `brand_account.full_name`(IG 표시명, 등록 시 Hiker 프로필로 저장·매일 스윕 갱신)과
  `username`. **회사명·바이오는 넣지 않는다.**
- 지시 요지: "인스타그램 브랜드 계정의 표시명과 계정명이다. 소비자가 이 브랜드를 게시물에 태그할 때
  가장 흔히 쓸 해시태그 **1개**를 내라. 표시명에 상호가 있으면 그 상호(한글이면 한글)를 쓰고,
  표시명에 상호가 없으면(비어 있음·영문 약자만) 계정명에서 `_official`·`.official`·`_kr` 같은
  접미사와 장식을 뗀 브랜드 핵심을 해시태그로 자연스러운 형태로 붙여 써라(점·언더스코어 처리는
  네 판단). `#` 없이, 공백·특수문자 없이, JSON `{"hashtag": "..."}`로만." temperature 0.
- 기대 예: 표시명 "닥터피엘 Dr.PIEL" → `닥터피엘`. 표시명 "" + `dr.piel_official` → `drpiel`.
  표시명 "" + `dr_piel.official` → `drpiel`. `cclime_official` → `cclime`.
- 전송: 광고 판정과 같은 `GeminiHttp` seam(Vertex, `LlmTransportConfig`)·같은 모델 설정 재사용.
- 출력 정리(버리지 않는다): 선행 `#` 제거 → strip → `toLowerCase(Locale.ROOT)` → `[\p{L}\p{N}_]`
  외 문자 **제거** → 30자 초과면 절단 → stoplist·순수 숫자면 빈 값 취급. 남은 값이 있으면 `path=AI`.
- AI 호출 실패(전송 예외·파싱 실패)·`ai-enabled=false`·정리 결과 빈 값 → §3-4.

### 3-4. 최종 안전장치 — 절대 비지 않는다

계정명에서 `.`·`_`를 제거하고 소문자화한 값(`dr.piel_official` → `drpielofficial`). `path=FALLBACK`.
계정명은 항상 존재하므로 이 값은 비지 않는다. FALLBACK 비율은 지표로 본다(§3-6) — 높으면 AI 경로가
죽은 것이다.

### 3-5. 설정 키 (monitoring app_setting, Flyway 시드 — UTC 채번)

| key | 기본값 | 의미 |
|---|---|---|
| `brand.hashtag-seed.min-posts` | `7` | FREQ 임계(등장 게시물 수, 이상) |
| `brand.hashtag-seed.stoplist` | `광고,협찬,이벤트,공구,체험단,유료광고,광고포함,ad,sponsored,pr` | FREQ 후보·AI 결과 제외(쉼표 구분, 소문자) |
| `brand.hashtag-seed.ai-enabled` | `true` | AI 경로 킬 스위치(false면 FREQ→FALLBACK) |

읽기는 `IgSourceSettings`와 같은 TTL 캐시 관용구(5초).

### 3-6. 관측

- 응답 1건당 info 로그 1줄: username, path, tag, topCount, candidatePosts.
- Micrometer 카운터 `brand.hashtag.suggest`(tag `path`=freq|ai|fallback, `result`=ok|error).

## 4. was — 유일한 작성자

### 4-1. 저장 (additive 마이그레이션 1개, `was db/migration/app`, UTC 채번)

```sql
CREATE TABLE app.brand_hashtag_seed (
    brand_id  bigint PRIMARY KEY,          -- monitoring brand_account.id 논리 참조
    path      text   NOT NULL,             -- FREQ|AI|FALLBACK|SKIP
    tag       text,                        -- SKIP이면 NULL
    seeded_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE app.brand_monitorings ADD COLUMN hashtag_seeded_at timestamptz;  -- NULL = 이 링크에 아직 미반영
```

### 4-2. 시드 메서드 하나 — `ensureAutoSeeded(userId, brandId)`

호출 지점 3곳(전부 best-effort, 실패해도 응답을 막지 않는다):
1. 단건 폴링 `get(userId, brandId)` — 응답 조립에서 `backfill_completed_at`이 non-null(수집 완료)일 때.
2. `getHashtagTags(userId, brandId)`.
3. 해시태그 게시물 목록·개수 조회(`V1BrandPostsController`의 조립 직전 — assembler는 HTTP 의존이
   없어 서비스 층에서 부른다. 개수는 탭 뱃지가 목록보다 먼저 렌더돼 빈 장부에서 0을 보이면 사용자가
   목록을 열지 않으므로 함께 건다). 메인 게시물 목록(`GET /accounts/{id}/posts`)에는 걸지 않는다
   (수집 중 초 단위 폴링 경로).

로직:
```
link = 활성 링크(userId, brandId); if link.hashtagSeededAt != null → return           // 사용자당 1회
seed = brand_hashtag_seed[brandId]
if seed == null:
    if brand_account.backfill_completed_at == null → return                              // 게시물 없음, 다음 호출
    if monitoring 활성 태그(commandClient.getHashtagTags) 비어 있지 않음:
        seed = INSERT (brandId, 'SKIP', NULL)   // 이미 사용자 관리 태그가 있는 브랜드 — 자동 태그 얹지 않음
    else:
        s = commandClient.getHashtagSuggestion(username)                                 // §3-1
        seed = INSERT (brandId, s.path, s.tag)  ON CONFLICT DO NOTHING 후 재조회(동시 호출 경합)
if seed.tag != null:
    commandClient.addHashtagTags(username, [seed.tag])   // 기존 일반 태그 add 경로 — monitoring push
    hashtagTagRepository.addTags(userId, brandId, [seed.tag])
UPDATE brand_monitorings SET hashtag_seeded_at = now() WHERE id = link.id
```

- monitoring push는 기존 `seedLedgerTagsSafely`와 같은 순서(push 먼저, 실패해도 장부 진행)·같은
  격리. push 성공 시 monitoring의 `triggerHashtagSweepIfNonEmpty`가 즉시 스윕을 건다(백필 완료 뒤라
  08-28 가드에 걸리지 않는다).
- 두 번째 사용자가 같은 브랜드에 링크하면 seed 행이 이미 있으므로 계산 없이 태그를 자기 장부에 복사한다.
- 사용자가 자동 태그를 지운 뒤 다시 조회해도 `hashtag_seeded_at`이 찍혀 있어 되살아나지 않는다.
- 기존 링크 전부 `hashtag_seeded_at IS NULL`로 시작한다 → 다음 조회에서 훅이 돈다. 이미 태그가 있는
  브랜드는 SKIP으로 기록만 남기고 아무것도 심지 않는다(§5 정리로 태그가 0개가 된 브랜드만 새로 시드).

### 4-3. 삭제

- `was/.../v1/brandmonitoring/BrandHashtagTags.java`와 그 테스트 삭제. `seedLedgerTagsSafely`와 링크
  생성 경로의 호출 삭제. 절삭 유도 규칙은 어디에도 남지 않는다.
- `MonitoringCommandClient`에 `getHashtagSuggestion(username)` 추가(GET, 404는 기존 BRAND_NOT_FOUND 승격과 동형).

### 4-4. FE 계약

응답 형태·등록 API 불변. 자동 태그가 "등록 즉시"가 아니라 "수집 완료 뒤 첫 조회"에 나타나는 타이밍만
바뀐다 — FE 통지 1건.

## 5. 운영 데이터 정리

배포 후 1회. 실행 전 대상 목록을 먼저 뽑아 눈으로 확인한다(IG 계정명은 ASCII·점·언더스코어만 허용되므로
절삭 접두사 = 첫 점 앞 구간).

```sql
-- 대상 확인(monitoring DB)
SELECT h.brand_id, a.username, h.tag, h.created_at, h.deleted_at
FROM brand_hashtag h JOIN brand_account a ON a.id = h.brand_id
WHERE position('.' in a.username) > 0
  AND h.tag = lower(split_part(a.username, '.', 1));
```

- monitoring: 위 행을 **hard DELETE**(tombstone 아님 — 사용자가 의도한 태그가 아니다). 같은 태그를
  사용자가 직접 넣은 흔적(`created_at`이 링크 생성 시각과 다름)이 있으면 그 행은 제외한다.
- was: `DELETE FROM app.brand_hashtag_tags WHERE (brand_id, tag) IN (위 집합)`.
- 그 태그로 이미 수집된 `brand_hashtag_post`·매칭 태그 행은 남긴다(격리 필터가 장부 기준이라 화면에서
  사라지고, 스윕은 태그가 없으니 더 긁지 않는다. §3-2 가드가 집계 오염을 막는다). 물리 정리는 비범위.
- **재시드는 자동**: 정리로 태그가 0개가 된 브랜드는 사용자 다음 조회에서 §4-2 훅이 계산·시드한다.
  스크립트·replay 호출 불요. 배포 다음 날 `brand_hashtag_seed`의 path 분포와 AI·FALLBACK 태그를 눈으로
  검토해 트랙 문서에 기록한다.

## 6. 테스트

- monitoring 단위: 추출·게시물당 dedup·stoplist·숫자 제외·정렬(동률 taken_at·사전순)·임계 경계(6→AI,
  7→FREQ)·AI 출력 정리 전 분기(`#` 제거, 대소문자, 무효 문자 제거, 30자 절단, stoplist·숫자 → 빈 값)·
  FALLBACK 값(`dr.piel_official` → `drpielofficial`)·`ai-enabled=false` → AI 미호출.
- monitoring GeminiHttp fake: 정상 JSON·비JSON·예외 각각 FALLBACK으로 수렴하고 응답은 항상 tag 비어있지 않음.
- monitoring 컨트롤러: 404(미존재·비ACTIVE)·200 본문.
- was 서비스: `ensureAutoSeeded` 분기 전부 — 링크 이미 시드됨 → 무동작 / seed 없음+백필 미완 → 무동작 /
  seed 없음+monitoring 태그 있음 → SKIP 기록·장부 무변화 / seed 없음+태그 없음 → suggestion 호출·push·
  장부·마커 / seed 있음 → 호출 없이 복사 / push 실패 → 장부는 진행 / 동시 호출 → seed 행 1개.
- was 컨트롤러: 훅 3곳 호출 여부(폴링은 수집 완료일 때만).
- was 컴파일: `BrandHashtagTags` 삭제 후 참조 전부 제거, `V1BrandAccountsControllerTest`의 링크 생성
  시딩 기대 갱신.

## 7. 비범위·후속

- 다중 태그 시드(국문·영문 병행) — 1개 고정. 사용자가 태그 관리 UI로 추가.
- 주기적 재시드 — 없음. 브랜드당 1회.
- 수집된 `#dr` 게시물 물리 정리.
- 메인 게시물 목록에서의 훅(FE가 태그 목록 호출을 없애면 자동 태그 반영이 늦어짐) — 트랙 문서 후속.
- AI 결과 품질의 자동 검증 — 없음. 배포 후 수동 검토(§5).
