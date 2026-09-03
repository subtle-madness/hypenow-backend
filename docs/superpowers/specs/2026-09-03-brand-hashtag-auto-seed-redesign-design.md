# 브랜드 해시태그 자동 시드 재설계 — 계정명 절삭 폐기, 태그된 게시물 빈도 + AI 폴백

> 상태: 🟢 활성 · 설계 확정(2026-09-03) · 구현 미착수
>
> 대체: [2026-08-27 해시태그 직접 수집 설계](2026-08-27-hashtag-direct-collection-design.md) §4의
> "was에 유도 규칙 복제" 결정과 2026-08-17(be39cbd7)의 "계정명 선행 접두사 절삭 1종 시드" 규칙을
> 함께 대체한다. 그 문서들은 불변 보존하고, 폐기 사유는 [DECISIONS.md](../../../DECISIONS.md)에 기록한다.

## 1. 문제

브랜드 등록 시 monitoring이 `brand_hashtag`에 심는 자동 태그는 계정명(username)을 소문자화한 뒤
**첫 무효 문자(점) 앞까지 자른 접두사**다(`BrandHashtagTags.derive`, 2026-08-17). IG 해시태그
실동작(`#cclime.beauty`를 치면 `#cclime`이 된다)에 맞춘 규칙이지만, 점이 든 계정명에서는 결과가
계정과 무관한 일반어가 된다.

- `dr.piel_official` → `dr`. 해시태그 스윕이 `#dr` 전체를 긁는다(무관 게시물 대량 유입 + Hiker 콜 낭비).
- 점이 든 계정명은 애초에 그 자체로 해시태그가 될 수 없으므로, 문자열을 어떻게 자르든(점 제거·
  최장 구간·절삭) 결과는 전부 추측이다. 실제 소비자가 쓰는 태그는 `#닥터피엘` 같은 브랜드명이다.
- 같은 규칙이 **두 벌** 존재한다: monitoring `service.BrandHashtagTags`와 was
  `v1.brandmonitoring.BrandHashtagTags`(08-27 장부 갭 수정 때 복제). "규칙을 바꾸면 두 곳을 같이
  고쳐야 한다"는 상태 자체가 지시("한 곳에서만") 위반이다. was는 이미 monitoring 태그를 GET해
  장부에 옮기는 `ensureSeeded` 경로를 갖고 있어 복제가 필요 없었다.

## 2. 결정 요약

| 항목 | 결정 |
|---|---|
| 자동 태그 재료 | 계정명 문자열 절삭 **폐기**. 그 브랜드에 **태그된 게시물**(brand_tagged_post)의 캡션 해시태그 빈도. |
| 임계 | 최다 태그의 **등장 게시물 수 ≥ 7**이면 그 태그 **1개** 시드. 비율 조건 없음. |
| 임계 미만 | brandName(own 연결의 회사명)이 있으면 **AI가 brandName만 보고 해시태그 1개 생성**해 시드. brandName 없으면(경쟁사) **0개**. |
| 시점 | 신규 등록 = 백필 완주 직후·해시태그 스윕 트리거 직전. replay 재등록 = 등록 시 동기(기존 게시물 사용). |
| 실행 조건 | 그 브랜드에 `brand_hashtag` 행이 **하나도 없을 때만**(활성·tombstone 모두). |
| 유도 규칙 위치 | **monitoring 단일**. was 복제본·등록 시 장부 시딩 삭제. was는 조회 시 장부가 비어 있으면 monitoring 태그를 승계. |
| 운영 정리 | 이미 심긴 절삭 태그를 monitoring·was 양쪽에서 삭제하고 영향 브랜드를 새 규칙으로 재시드. |

## 3. 시드 규칙 상세 (monitoring)

### 3-1. 트리거 시점과 조건

- **신규 등록**: `BrandRegistrationService.runBackfillSafely`에서 전 페이지 보강 `allOf.join()` 뒤,
  `triggerHashtagSweep(row)` **앞**에 실행한다. 이 순서라야 등록 직후 스윕이 새 태그를 바로 집는다.
  백필이 예외로 끝나면 시드하지 않는다(다음 replay 재등록이 백스톱 — 기존 격리 계약과 동일).
- **replay 재등록**(기존 ACTIVE 브랜드 재등록 분기): 현재 `seedHashtagsSafely` 자리에서 동기 실행한다.
  이미 수집된 태그된 게시물이 있으므로 백필을 기다릴 필요가 없다.
- **실행 조건**: `SELECT count(*) FROM brand_hashtag WHERE brand_id = ?` 가 0일 때만. tombstone 행도
  센다 — 사용자가 지운 태그가 되살아나면 안 되고(08-17 tombstone 계약 유지), AI 콜을 브랜드
  생애 최대 1회로 묶는다. 조건 미충족이면 아무것도 하지 않고 로그도 debug 수준.
- brandName은 등록 API 파라미터로만 들어오고 DB에 없다. 신규 등록의 비동기 백필 태스크에는
  등록 시점 값을 클로저로 넘긴다. (경쟁사 연결은 was가 null을 보낸다 — #406 게이트 그대로.)

### 3-2. 후보 집계 — 태그된 게시물 캡션

- 모수: `brand_tagged_post`(brand_id) ⋈ `brand_post_meta`(short_code)의 `caption`. 태그된 게시물 =
  다른 사용자가 이 브랜드 계정을 태그한 게시물이다. "브랜드가 태그될 때 사람들이 함께 다는
  해시태그"가 곧 감지에 쓸 태그이므로 자사 게시물보다 나은 신호다.
- 추출: `#([\p{L}\p{N}_]+)` 전역 매치, `toLowerCase(Locale.ROOT)`. **게시물당 중복 제거** 후 태그별
  등장 게시물 수를 센다(한 캡션에 같은 태그 세 번은 1).
- 제외 목록(§3-5 `stoplist`)에 든 태그는 후보에서 뺀다. 순수 숫자 태그도 뺀다.
- 정렬: 등장 게시물 수 내림차순 → 동률이면 그 태그가 등장한 게시물의 최근 `taken_at` 내림차순 →
  태그 사전순. 결정적이어야 테스트가 봉인된다.

### 3-3. 임계와 선택

- 최다 태그의 등장 게시물 수가 `min-posts`(기본 7) **이상**이면 그 태그 1개를 `brand_hashtag`에
  INSERT(`insertTags` 재사용, ON CONFLICT DO NOTHING).
- 미만이면 §3-4로.
- 태그된 게시물이 0건이어도 같은 경로다(최다 수 0 < 7 → AI 폴백).

### 3-4. AI 폴백 — brandName → 해시태그 1개

- 입력은 **brandName 하나**(사용자 결정 — 계정명·표시명·바이오는 넣지 않는다). null·공백이면
  호출 없이 0개 종료(경쟁사 연결).
- 전송: 광고 판정과 같은 `GeminiHttp` seam(Vertex, `LlmTransportConfig`)·같은 모델 설정을 재사용한다.
  새 HTTP 클라이언트를 만들지 않는다.
- 프롬프트 요지: "다음 브랜드명을 가진 한국 브랜드에 대해 소비자가 인스타그램 게시물에 가장
  흔히 다는 해시태그 1개를 JSON `{"hashtag": "..."}`로만 답하라. `#` 없이, 공백·특수문자 없이."
  temperature 0.
- 검증 후에만 저장: 선행 `#` 제거 → strip → `toLowerCase(Locale.ROOT)` → `[\p{L}\p{N}_]+` 전체
  일치 → 길이 2~30 → 순수 숫자 아님 → stoplist 아님. 하나라도 실패하면 warn 로그 + 0개.
- 실패(전송 예외·파싱 실패·검증 실패)는 모두 격리한다. 등록·백필·스윕 흐름을 막지 않는다.
- `ai-enabled`(§3-5)가 false면 AI 경로를 건너뛰고 0개(킬 스위치).

### 3-5. 설정 키 (app_setting, monitoring Flyway 시드 — UTC 채번)

| key | 기본값 | 의미 |
|---|---|---|
| `brand.hashtag-seed.min-posts` | `7` | 최다 태그 시드 임계(등장 게시물 수, 이상) |
| `brand.hashtag-seed.stoplist` | `광고,협찬,이벤트,공구,체험단,유료광고,광고포함,ad,sponsored,pr` | 후보·AI 결과 제외 태그(쉼표 구분, 소문자) |
| `brand.hashtag-seed.ai-enabled` | `true` | AI 폴백 킬 스위치 |

읽기는 `IgSourceSettings`와 같은 TTL 캐시 관용구(5초). 기준값 변경은 후속 마이그레이션으로,
런타임 UPDATE는 임시 조정만(CLAUDE.md 규칙).

### 3-6. 관측

- 시드 결과 1건당 info 로그 1줄: brand, 경로(`FREQ`/`AI`/`NONE`), 태그, 최다 수, 후보 게시물 수.
- Micrometer 카운터 `brand.hashtag.seed`(tag: `path`=freq|ai|none|skip, `result`=ok|invalid|error).

## 4. was 변경

- `was/.../v1/brandmonitoring/BrandHashtagTags.java`와 그 테스트 **삭제**. `V1BrandAccountService.
  seedLedgerTagsSafely`와 등록 경로의 호출 삭제. was에는 유도 규칙이 남지 않는다.
- **조회 시 승계**: `getHashtagTags`(GET hashtag-tags)와 해시태그 게시물 목록(`BrandHashtagPostAssembler`
  의 장부 읽기 지점)에서, **이 사용자의 장부가 비어 있을 때만** 기존 `ensureSeeded(userId, brandId,
  username)`를 호출한다. `ensureSeeded`는 monitoring 활성 태그 중 "어느 사용자 장부에도 없는 태그"를
  호출 사용자 장부에 넣는 기존 메서드라 그대로 쓴다(최초 조작자 승계 관용구).
  - 비용: 장부가 비어 있는 동안만 monitoring GET 1회/조회. 태그가 영원히 0개인 브랜드(경쟁사)는
    화면을 열 때마다 1회 나가는데, 내부 HTTP 1콜이라 수용한다.
  - 부활 방지: 사용자가 지운 태그는 was가 monitoring에서도 지우거나(단독 소유) 남이 소유 중이라
    "소유된 태그"로 걸러지므로, 조회 승계로 되살아나지 않는다.
- FE 계약 변화 없음. GET hashtag-tags 응답 형태·등록 API 동일. 자동 태그가 "등록 즉시"가 아니라
  "수집 완료 뒤"에 나타나는 타이밍만 바뀐다 — FE 통지 1건.

## 5. 운영 데이터 정리와 재시드

배포 후 1회 실행. 실행 전 대상 목록을 먼저 뽑아 눈으로 확인한다(IG 계정명은 ASCII·점·언더스코어만
허용되므로 절삭 접두사 = 첫 점 앞 구간).

```sql
-- 대상 확인(monitoring DB)
SELECT h.brand_id, a.username, h.tag, h.created_at, h.deleted_at
FROM brand_hashtag h JOIN brand_account a ON a.id = h.brand_id
WHERE position('.' in a.username) > 0
  AND h.tag = lower(split_part(a.username, '.', 1));
```

- monitoring: 위 행을 **hard DELETE**(tombstone이 아니라). 사용자가 의도한 태그가 아니고, 행이
  남으면 §3-1 "행 0일 때만" 조건에 걸려 재시드가 막힌다. 같은 태그를 사용자가 직접 넣은 흔적
  (`created_at`이 등록 시각과 다름)이 있으면 그 행은 제외한다.
- was: `DELETE FROM app.brand_hashtag_tags WHERE (brand_id, tag) IN (위 집합)`.
- 그 태그로 이미 수집된 `brand_hashtag_post`·매칭 태그 행은 남긴다 — 격리 필터가 장부 기준이라
  화면에서 사라지고, 스윕은 태그가 없으니 더 긁지 않는다. 물리 정리는 비범위.
- **재시드**: 대상 브랜드마다 monitoring 내부 register 엔드포인트를 replay로 호출한다(ACTIVE 브랜드
  재등록 분기 → §3-1 동기 시드 → 스윕). own 브랜드는 `brandName`을 was `app.users.company_name`
  에서 조회해 넘기고, **경쟁사 전용 브랜드(`has_own_link=false`)는 반드시 `accountType=competitor`로
  호출**한다 — 기본값(own)으로 부르면 `has_own_link`가 true로 뒤집혀 광고 판정 모수가 오염된다.
  재시드 결과(FREQ/AI/NONE 분포)는 트랙 문서에 기록한다.

## 6. 테스트

- 단위(monitoring): 추출·게시물당 dedup·stoplist·숫자 제외·정렬(동률 taken_at·사전순)·임계 경계
  (6→AI, 7→FREQ)·AI 검증 규칙 전 분기(`#` 제거, 대소문자, 무효 문자, 길이, 숫자, stoplist)·
  brandName null → 호출 0·`ai-enabled=false` → 호출 0·실행 조건(행 존재 → skip, tombstone만 있어도 skip).
- 단위(monitoring, GeminiHttp fake): 정상 JSON·비JSON·예외 각각 격리되어 등록 흐름이 계속됨.
- 서비스: `runBackfillSafely` 순서 — 시드가 `triggerHashtagSweep` 앞에서 1회 호출되고 백필 실패
  경로에서는 호출 안 됨. replay 분기에서 동기 호출.
- was: `BrandHashtagTags` 삭제 후 컴파일·기존 `V1BrandAccountsControllerTest`의 장부 시딩 기대
  갱신. 조회 승계 — 장부 비어 있음+monitoring 태그 있음 → 승계, 장부 있음 → monitoring 미호출,
  남이 소유한 태그 → 미승계.
- 기존 `BrandHashtagTagsTest`(monitoring)는 `derive` 삭제와 함께 제거하고 `isValidTag`만 남긴다.

## 7. 비범위·후속

- 경쟁사 브랜드 폴백(표시명·바이오·계정명 입력) — 사용자 결정으로 0개. 필요해지면 별도 spec.
- 다중 태그 시드(국문·영문 병행) — 1개 고정. 사용자가 태그 관리 UI로 추가.
- 야간 스윕에서의 주기적 재시드 — 없음. 재시드는 replay 재등록 경로뿐.
- 수집된 `#dr` 게시물 물리 정리.
- 자동 태그 노출 시점이 늦어진 것에 대한 FE 안내 문구.
