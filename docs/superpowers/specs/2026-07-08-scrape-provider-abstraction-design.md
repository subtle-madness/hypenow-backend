# 스크레이핑 프로바이더 추상화 설계 (Apify + 서드파티 병행)

날짜: 2026-07-08
상태: 설계 제시 완료, 사용자 검토 대기

## 배경과 목표

수집 비용의 대부분(특히 댓글)을 차지하는 Apify 액터를 더 싼 서드파티 API(1차 후보:
HikerAPI, ~$0.6/1k 요청 vs Apify ~$1.5~2.6/1k)로 **작업별로** 갈아 끼울 수 있게 한다.
프록시·안티봇은 두 경우 모두 업체 책임이므로 우리 부담 없음. 목표는 "프로바이더 추가 =
어댑터 클래스 하나 + 설정 한 줄"이 되는 구조.

## 인터뷰 기록 (blindspot-interview)

| 질문 | 결정 |
|---|---|
| 전환 방식 | **작업별 설정** (discover/profile/detail/comment 각각 프로바이더 지정). 자동 폴백 없음 — 수동 전환만 |
| 서드파티 응답 저장 전략 | **하이브리드 정규화**: `raw_run_item`에는 프로바이더 원형 그대로, typed 테이블(`raw_discovery_post` 등)에는 어댑터가 Apify 호환 필드명으로 변환해 저장 |
| 이번 범위 | 포트 추상화 + 작업별 라우팅 + **HikerAPI 어댑터 구현까지**. 실 호출 스모크는 계정·키 준비 후 별도 (키 없이는 스텁 JSON 테스트로 검증) |
| 포트 형태 | **도메인 작업 포트** (접근안 1) — 아래 설계 참조 |

### 묻지 않고 기본값으로 정한 항목 (저영향·가역)

- `crawl_run.actor_id`를 "소스 식별자"로 재해석: `apify~instagram-comment-scraper` / `hiker~media-comments` 식 기록 (마이그레이션 불필요)
- HikerAPI는 동기 HTTP — 폴링 없음, 어댑터 내부에서 흡수
- 페이지네이션(댓글 상한 등)은 어댑터가 chunk 반복 호출로 상한까지 채움
- 설정은 기존 `app_setting` 런타임 체계에 `provider.*` 키 추가, 기본값 전 작업 `apify`
- `ApifyException` → `ScrapeException` 개명

### 의도적 보류

- HikerAPI 실 응답 캡처·스모크 테스트 — API 키 준비 후 (사용자 액션: hikerapi.com 계정 + 크레딧)
- 두 번째 서드파티(예비 업체) 이중화 — 필요해지면 별도 스펙
- 자동 폴백 — 현 단계 미채택

## 설계

### 1. 포트 계층 (`crawling/application/port/out`)

```java
public interface ScrapeRunnerPort {
    ScrapeResult discover(String keyword, int limit);
    ScrapeResult profiles(List<String> usernames);
    ScrapeResult postDetails(List<String> urls, ContentType type);
    ScrapeResult comments(List<String> urls, int limitPerPost);
}
```

- `ScrapeResult(String sourceId, String externalRunId, List<Map<String,Object>> items, List<Map<String,Object>> rawItems)`
  - `items` = 정규화(Apify 호환) — 파이프라인·typed 테이블용
  - `rawItems` = 프로바이더 원형 — `raw_run_item` 아카이브용 (Apify 어댑터는 둘이 동일 참조)
  - `externalRunId` = Apify run id / Hiker는 null 허용
- `ApifyResult`·`ApifyException`·`Actors`·`ActorInputs`는 애플리케이션 계층에서 제거
  (`Actors`/`ActorInputs`는 Apify 어댑터 내부로 이동)

### 2. 라우팅

- `RoutingScrapeRunner implements ScrapeRunnerPort` — 메서드별로 설정을 읽어
  해당 프로바이더 어댑터에 위임. `@Primary`로 잡에 주입.
- 잡·CrawlExecutor는 포트만 의존 — 프로바이더 분기 없음.
- `CrawlExecutor.execute(job, trigger, categoryId, keyword, ScrapeCall)` 로 시그니처 변경.
  `ScrapeCall = ScrapeResult call(ScrapeRunnerPort r)` 함수형 인터페이스 —
  잡이 `r -> r.comments(urls, limit)` 형태로 호출을 기술.
- `crawl_run.actor_id` ← `result.sourceId()`. 아카이브는 `result.rawItems()` 사용.

### 3. Apify 어댑터 (`adapter/out/apify`, 기존 코드 이관)

- `ApifyScrapeRunner implements ScrapeRunnerPort` — 기존 `ApifyClient`(비동기
  시작→폴링→dataset)를 그대로 사용. run-sync 금지 원칙 유지.
- 한글 키워드 `keywordSearch: true` 우회, 유형별 상세 액터 분기 등 기존 동작 무변경.

### 4. HikerAPI 어댑터 (`adapter/out/hiker`, 신규)

- `HikerScrapeRunner implements ScrapeRunnerPort` + `HikerHttp`(JDK HttpClient,
  `x-access-key` 헤더, 토큰 로그·URL 비노출) + `HikerProperties`(base-url, token,
  retry 상한).
- 엔드포인트 매핑: discover=`/v1/hashtag/medias(_recent)`, profiles=`/v1/user/by/username`,
  postDetails=`/v2/media/info/by/code`, comments=`/v2/media/comments` (chunk 페이지네이션).
  정확한 경로·파라미터는 구현 시 OpenAPI 스펙으로 확정.
- `HikerFieldMapper` — 필드 매핑 단일 집중점:
  `code`→`shortCode`, `like_count`→`likesCount`, `comment_count`→`commentsCount`,
  `play_count`→`videoPlayCount`, `user.username`→`ownerUsername`,
  `follower_count`→`followersCount`, `caption.text`→`caption`, `taken_at`(epoch)→
  `timestamp`(ISO), 댓글의 media 매칭용 `postUrl` 합성 등. 매핑 누락 필드는 원형이
  `raw_run_item`에 남으므로 사후 복구 가능.
- 429·5xx·타임아웃은 어댑터 내 유한 재시도(backoff), 소진 시 `ScrapeException`.
  aggregate 잡의 기존 "빈 응답 = 재시도, attempts 3회 초과 FAILED" 의미론 무변경.

### 5. 설정·운영

- `app_setting` 키: `provider.discover` / `provider.profile` / `provider.detail` /
  `provider.comment`, 값 ∈ {`apify`, `hiker`}, 기본 `apify`. 값 비우면 기본 복귀
  (기존 settings 규칙과 동일). `/ui/settings`·`GET/PUT /admin/settings` 노출.
- 토큰: `HIKER_TOKEN` 환경변수. 미설정 상태에서 hiker로 전환 시도 시 명확한 에러.
- 권장 운영: Apify로 실측 → HikerAPI 스모크 검증 → `comment`·`profile`부터 전환
  (비용 비중 최대) → 안정 확인 후 확대.

### 6. 에러 처리

- 포트 예외는 `ScrapeException` 하나 — 잡·executor의 catch 지점 변경 최소화.
- 프로바이더별 원인(HTTP 상태, Apify run 상태)은 예외 메시지에 sourceId와 함께 포함
  — `crawl_run.error`로 추적.

### 7. 테스트

- `FakeApifyRunner` → `FakeScrapeRunner` (4개 메서드 스텁). 잡 테스트는 기계적 수정.
- Apify 어댑터: 기존 `ApifyClientTest` 유지 + 포트 구현 위임 테스트.
- Hiker 어댑터: 실 응답 형태의 스텁 JSON으로 `HikerFieldMapper` 정규화 검증
  (shortCode·likesCount 등 canonical 필드 존재 확인 = generated column 호환 보장),
  페이지네이션·재시도 단위 테스트.
- 통합: Testcontainers 기존 체계. 라우터는 설정별 위임 대상 검증.
- 스모크(실 과금, CI 금지): 키 준비 후 README 절차에 hiker 섹션 추가 예정.

### 8. 변경 없는 것

DB 스키마·generated column·`DiscoveryItemParser`·`AdSignals`·잡 비즈니스 로직·
스케줄러·UI(설정 화면 항목 추가 제외).
