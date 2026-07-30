# 계정 뷰티 판정 품질 — 실측 기반 사후 재판정

> 상태: 🟢 활성 · 설계 확정(구현 미착수)
> 날짜: 2026-07-30
> 관련: PR #204(발굴 표면 게시물 실측 비율 게이트 — analytics/was 층, 본 스펙과 상보)

## 1. 문제

서빙 게이트(`status='QUALIFIED' AND beauty AND NOT beauty_company`, 실질 `beauty_class='INFLUENCER'`)를
통과한 뷰티 인플루언서 7,095개 중 **뷰티 게시물 비율 0%인 계정이 886개**다. 이 중 20개를
스팟체크(bio·캡션 원문 판독)한 결과 **17개(85%)가 오판**이었다 — 육아·다이어트·여행·피트니스
계정이 뷰티로 분류돼 있었다. 표본 비율을 그대로 적용하면 약 753개 규모다.

원인은 판정 재료의 빈곤과 판정 시점의 구조에 있다.

## 2. 원인 규명 (2026-07-30 코드·실측)

### 2-1. HIKER_MOBILE 응답에는 게시물이 없다 (파싱 누락이 아니다)

`ProfileExtractor.recentCaptions()`는 캡션을 `raw_profile.payload`에 임베드된 타임라인에서만
뽑는다. `SELF_GQL`은 타임라인이 있어 캡션이 들어가지만 **`HIKER_MOBILE`/`DATALIKERS`는 항상
빈 리스트**다.

이것이 파서 한계가 아니라 응답 자체의 한계임을 실측으로 확인했다. monitoring 모듈에 동일
엔드포인트(`/v2/user/by/username`)의 실계정 응답 캡처가 있다
(`monitoring/src/test/resources/hiker/profile.json`). `user` 객체 251개 키 중 게시물 목록·캡션
관련 필드가 전무하고, 게시물 관련 키는 `latest_reel_media`/`latest_besties_reel_media`(둘 다
epoch 정수)뿐이다.

**DATALIKERS는 대응 실측 픽스처가 없어 확인 불가**다. 코드 주석("flat 구조, HIKER_MOBILE 경로
재사용")만 근거다.

스팟체크 표본 20개 중 7개가 이 경로로 캡션 0건 상태에서 판정됐다. 극단적으로 bio가 이모지
3개(`👸🏻 👑 🏆`)뿐인 계정이 인스타그램 자기신고 `category_name`("Beauty, cosmetic & personal
care") 하나만 근거로 뷰티 인플루언서로 분류됐다.

다만 **캡션 유무가 유일한 변수는 아니다** — 캡션 0건이면서도 실제로 100% 뷰티인 대조군 계정도
있었다. bio 품질이 더 결정적으로 보인다.

### 2-2. 닭-달걀이 파이프라인 정의에 못 박혀 있다

`InfluencerRepository.findCollectTargets`가 `status='QUALIFIED' AND beauty=true`만 대상으로
한다. 즉 **게시물 수집 자체가 뷰티 판정 통과 후에만 시작된다.**

스팟체크 표본 25개 전원이 `beauty_judged_at` < 해당 계정 게시물의 최초 크롤 시각이었던 것은
우연이 아니라 이 정의의 직접적 귀결이다. 판정 시점에 게시물 근거는 원리적으로 존재하지 않는다.

### 2-3. 재판정 루프가 사실상 죽어 있다 — 886개 오판이 고착된 진짜 이유

`InfluencerRepository.findRejudgeTargets` 조건:

```
i.status = :status AND i.beautySource = CLAUDE AND i.beauty = false
AND (i.beautyJudgedAt is null
     OR i.beautyJudgedAt < (select max(rp.capturedAt) from RawProfile rp
                            where rp.influencerId = i.id))
```

두 겹으로 막힌다.

- **`beauty = false`만 재판정한다.** 뷰티로 잘못 통과한 false positive는 영구 고착이며,
  886개가 자동 교정될 경로가 현재 존재하지 않는다.
- **재판정 트리거인 `raw_profile` 갱신도 안 일어난다.** 프로필 재수집은 `CollectJob`의 주기
  재방문(`RevisitCutoff`)뿐인데 그 대상도 `beauty=true`다. 비뷰티 판정 계정은 프로필이 다시
  안 긁혀 false negative 재판정도 거의 돌지 않는다.

### 2-4. 판정 재료를 다시 확보하는 수단이 제거된 상태

과거 RESNAPSHOT(재스냅샷) 잡이 있었고 2026-07-18에 제거됐다. 당시 결정 기록에 "HIKER 등 캡션
없는 소스의 대량 qualify 웨이브 재처리엔 이 기능이 필요, git 이력(9ce462d)에서 복원 가능"이라는
메모가 남아 있다. 지금 문제는 그때 인지됐다가 재발한 것이다.

### 2-5. LLM 출력 검증 갭

`ClaudeCliBeautyJudge.parse()`(세 어댑터 공용)에 있는 것: 코드펜스 제거, JSON 파싱 실패 시 배치
예외, `username`/`class` blank 스킵, enum 외 `class` 값 스킵. `BeautyJob.applyVerdicts`에서
요청 목록에 없는 지어낸 username 무시.

없는 것:
- **응답 개수 ≠ 요청 개수 검증** — 일부 username이 누락돼도 예외 없이 통과하고, 해당 계정은
  다음 실행까지 미판정 방치되며 **로그조차 없다.**
- 중복 username 응답 처리 — 두 번 다 적용되고 뒤 값이 이긴다(경고 없음).
- `reason` 내용 검증.

스팟체크 표본 20개 중 4건이 `beauty_reason`은 비뷰티를 서술하면서 `beautyClass=INFLUENCER`를
반환한 모순이었다(예: reason이 "육아·이유식 콘텐츠… 자기주도이유식·유아식 협찬"인데
INFLUENCER). **코드 매핑은 정상이다** — `applyVerdicts`가 같은 `Verdict`에서 두 값을 쓰고
`Influencer.classify()`가 `beauty`/`beauty_company`를 `cls`에서 파생한다. LLM 출력 자체의
모순이며, 검증 층이 없는 것이 문제다.

부수 발견: `GeminiBeautyJudge.RESPONSE_SCHEMA`의 enum이 4분류만 포함해 V21에서 추가된
`FOREIGN_INFLUENCER`가 빠져 있다.

## 3. 결정 — 채택안과 폐기안

### 3-1. 캡션은 이미 crawler DB에 있다

게시물 캡션의 원천은 analytics가 아니라 **crawler 소유 `raw_media_page.payload`**다(ReelsJob이
`HIKER_V2_CLIPS`로 적재). `analytics/views/00_base.sql`의 `v_base_reel_item`이 바로 여기서 캡션을
뽑아 쓴다.

따라서 **오판 886개는 이미 자기 게시물 캡션 원문을 crawler DB에 갖고 있고, 추가 크롤 0으로
재판정할 수 있다.** `MediaItemExtractor.extract()`가 제어 필드(shortCode·takenAt·type·pinned)만
뽑고 캡션을 버리고 있을 뿐이다.

### 3-2. 채택: 사후 재판정 + 프롬프트·검증층

### 3-3. 폐기: SELF_GQL 리스냅샷으로 판정 전 캡션 확보 (재도입 금지)

캡션 0건 계정을 `SELF_GQL`로 다시 긁으면 판정 **전에** 캡션을 확보할 수 있고, 이것이 미판정
18,545개까지 커버하는 유일한 경로다. 그러나 대상 규모가 미판정 18,545 + 오판 886 + 그 외 캡션
0건 판정분이라 **계정당 프로필 1콜 × 최소 2만 콜**이다. 크롤 비용 대비 이득이 맞지 않아
드롭했다.

이 결정의 대가는 명확하다: **미판정 18,545개(전체의 34%)는 이 트랙으로 해결되지 않는다.**
그들은 `beauty` NULL이라 게시물 수집 대상이 아니고, 판정 재료가 영원히 bio + `category_name`뿐이다.

### 3-4. 폐기: 근거 빈약 계정의 판정 보류·비뷰티 확정

캡션 0건 + bio 빈약(예: 이모지 3개)이라 `category_name`만 남는 계정의 처리로 세 안을 놓고
**"일단 통과시키되 근거를 기록한다"(i)를 택했다.**

- **(ii) 판정 보류(NULL 유지)** — 서빙에서 확실히 빠지지만 게시물도 안 긁히므로 영구 미판정이
  된다. 3-3을 드롭한 이상 재료가 생기지 않는다. 미판정 풀만 키운다.
- **(iii) 보수적 비뷰티 판정** — 서빙에서 빠지고 판정도 종결되지만, 진짜 뷰티 계정을 놓치는
  false negative가 늘고 **이건 측정 자체가 불가능하다**(발굴 안 된 계정은 어디에도 안 남는다).

(ii)·(iii)은 3-3 없이는 되돌릴 길이 없는 일방향 결정이고, (i)만 자기교정 경로가 살아 있다.
오판이 서빙에 노출되는 문제는 PR #204의 게시물 실측 비율 게이트가 이미 막는다. 이 트랙의 몫은
판정 자체를 실측으로 되돌리는 것이다.

## 4. 설계

### 4-1. 재판정 대상·트리거 (본체)

`findRejudgeTargets`에 **두 번째 재판정 경로**를 추가한다. 기존 경로(비뷰티 + 프로필 갱신)는
그대로 두고 OR로 잇는다.

```
판정 시점 캡션이 0건이었고 (beauty_caption_count = 0)
AND 지금은 그 계정 게시물 캡션이 N건 이상 쌓였고
AND beauty_judged_at < 그 캡션들의 적재 시각
```

- **이 경로는 `beauty` 값을 조건에 걸지 않는다.** `beauty=true`도 대상이 되며, 오판 886개가
  여기로 들어온다.
- `beauty_source = MANUAL` 제외는 유지한다(수동 판정은 어떤 경로로도 재대상 금지).
- 정렬은 기존과 같이 `beauty_judged_at asc nulls first, id`.
- 미판정 우선 선정(`findByStatusAndBeautyIsNull`)이 배치 한도를 먼저 쓰는 현행 순서도 유지한다.

**무한 재대상 방지**: 재판정 시점엔 캡션이 존재하므로 판정 후 `beauty_caption_count > 0`이 되어
조건이 자연 해제된다. (2026-07-21 "뷰티-무대분류 무한 재대상 루프" 사고와 같은 형태를 피하려고
조건이 상태 갱신으로 닫히게 잡았다.)

**뒤집힘의 부작용**: `true → false`가 되면 `findCollectTargets`에서 빠져 게시물 수집이 멈춘다.
의도한 동작이다. 반대로 한번 false가 되면 재료가 더는 안 쌓여 이 경로로 되돌아오지 못한다 —
3-3을 드롭한 대가이며, 이 판정은 최소한 실측 캡션 기반이라 최초 판정보다 근거가 강하다.

**N(최소 캡션 건수)은 3으로 한다.** 캡션이 빈 게시물이 섞이므로 1건은 근거로 약하고,
`CAPTION_COUNT=5`(프롬프트에 넣는 상한)보다는 낮춰 대상을 지나치게 좁히지 않는다.

**구현 시작 시 확인할 것**: 캡션 원천이 `raw_media_page.payload`인데 릴스
경로(`HIKER_V2_CLIPS`)는 `v_base_reel_item`이 캡션을 뽑아 쓰므로 확실하다. 피드
경로(`HIKER_V1_MEDIAS`, `CollectJob.supplementFeedPage`)는 payload에 캡션이 들어있는지
미확인이다. 실제 payload로 확인하고, 없으면 릴스 캡션만 쓴다.

`MediaItemExtractor`에 캡션 추출 경로를 추가한다(현재 제어 필드만 뽑고 캡션을 버린다).

### 4-2. 판정 근거 기록 (스키마)

`influencer`에 nullable 컬럼 2개를 추가한다. expand 단계이므로 `migration-guard`를 그대로
통과한다(`ADD COLUMN` nullable, `DROP`/`RENAME`/`SET NOT NULL` 없음).

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `beauty_caption_count` | `smallint null` | 판정에 실제로 넣은 캡션 건수 |
| `beauty_basis` | `varchar null` | LLM이 밝힌 주근거: `CAPTION` / `BIO` / `CATEGORY_ONLY` |

`beauty_basis`가 자기신고 `category_name` 단독 근거 문제에 직접 대응한다 — 키워드 휴리스틱 없이
**LLM이 "category만 보고 판단했다"고 스스로 명시하게** 만든다.

저확신은 별도 boolean 없이 `beauty_caption_count = 0 OR beauty_basis = 'CATEGORY_ONLY'` 파생으로
본다.

기존 행은 두 컬럼이 NULL이다. NULL은 "기록 이전 판정"을 뜻하며, 4-1의 재판정 조건은
`beauty_caption_count = 0`을 요구하므로 **NULL 행은 새 경로에 걸리지 않는다.** 886개를 포함한
기존 판정분을 대상에 넣으려면 백필이 필요하다 — 5절 참조.

### 4-3. 프롬프트

`category_name`을 금지 근거로 만들지 **않는다.** 프롬프트에서 NOT_BEAUTY로 기울이면 그것은
사실상 3-4의 (iii)이고, false가 되면 게시물이 안 쌓여 되돌릴 길이 없어 채택안 (i)과 모순된다.

대신 **상충 시 우선순위만** 정한다:

> `category`는 계정주가 자율 선택한 미검증 자기신고 필드다. bio·캡션의 실제 내용과 상충하면
> 실제 내용을 우선하라.

근거가 아예 없을 때는 기울이지 않고 `basis: CATEGORY_ONLY`로 표시하게 한다.

출력 JSON 필드 순서를 `class` → `reason`에서 **`reason` → `class`로 뒤집는다.** 근거를 먼저 쓰게
하면 2-5의 모순(비뷰티 근거를 쓰고 INFLUENCER 반환)이 구조적으로 줄어든다.

출력 스키마에 `basis` 필드를 추가한다.

### 4-4. 출력 검증층

**넣는다:**
- 응답 개수 ≠ 요청 개수면 누락 username 목록을 로그로 남긴다(현재는 조용히 미판정 방치되고
  로그조차 없다). 예외로 올리지는 않는다 — 나머지 계정의 판정 결과를 버리게 되므로.
- 중복 username 응답 경고 로그(적용 동작은 현행 유지, 뒤 값이 이김).
- `basis` 값 검증 — enum 외 값이면 해당 필드만 null로 두고 판정은 살린다(`class`와 달리
  판정 자체를 버릴 이유가 없다).
- `GeminiBeautyJudge.RESPONSE_SCHEMA`에 누락된 `FOREIGN_INFLUENCER` 추가 + `basis` 반영.
  별건 버그지만 같은 파일이라 동승시킨다.

**넣지 않는다 — `reason`↔`class` 규칙 기반 모순 탐지.** 비뷰티 도메인 키워드(육아·다이어트·
여행 등) 목록으로 잡는 방식은 뷰티 인플루언서도 육아를 다루므로 오탐이 크고 목록 자체가
유지보수 부채다. 2차 LLM 검증은 판정 비용이 2배가 된다. 모순은 4-3의 필드 순서 뒤집기로
완화하고 남는 것은 4-1의 실측 재판정이 교정한다.

## 5. 배포·운영 순서

1. 마이그레이션(컬럼 2개 추가) → 코드 배포. expand 단계라 롤링 중 신구 코드 공존에 안전하다.
2. **기존 판정분 백필**: 886개를 포함한 기존 행은 `beauty_caption_count`가 NULL이라 재판정
   경로에 걸리지 않는다. `raw_profile` 소스가 `HIKER_MOBILE`/`DATALIKERS`였던 행을
   `beauty_caption_count = 0`으로 백필해야 대상이 된다. 이 UPDATE는 dry-run(대상 카운트
   SELECT) → 승인 → 실행 순서로 한다.
3. **규모 확인은 백필 dry-run 시점에 한다.** 로컬에 실데이터가 없어 사전 측정이 불가하고,
   재판정 대상 건수가 곧 1회성 LLM 비용이다. 실행 자체는 `app_setting`의
   `beauty.batch-limit`이 회당 상한을 잡으므로 폭주하지 않는다.

## 6. 테스트

기존 관례를 따른다(JUnit5 + AssertJ + Mockito, 한글 서술형 메서드명).

- `BeautySelectionIntegrationTest`(Testcontainers 실DB) — 새 재판정 경로의 선정 쿼리를 고정한다.
  케이스: 캡션0+캡션쌓임(대상), 캡션0+캡션 N건 미만(비대상), 캡션0+`beauty=true`(대상 — 오판
  교정 경로), `MANUAL`(비대상), 재판정 후 재대상 안 됨(무한 루프 방지), 기록 이전 NULL 행(비대상).
- `BeautyJobTest`(단위) — `beauty_caption_count`/`beauty_basis` 저장, 응답 누락 시 로그·미판정
  유지, 중복 username 경고.
- `ProfileExtractorTest` / `MediaItemExtractorTest` — `raw_media_page.payload`에서 캡션 추출.
- 어댑터 테스트 3종 — `basis` 파싱, enum 외 `basis` 값 처리, Gemini 스키마 5분류.
- **`trimSurrogateSafe` 테스트는 반드시 유지한다.** 2026-07-21 서로게이트 쌍 절단으로 배치 10개
  중 9개가 400 실패한 운영 장애 이력이 있다.

## 7. 범위 밖

- **미판정 18,545개(전체 34%)** — 3-3을 드롭한 이상 이 트랙으로 해결되지 않는다. 별도 트랙.
- **`beauty_basis`/`beauty_caption_count`의 analytics·was 노출** — 이 트랙은 crawler 내부 판정
  품질까지다. 미러가 필요하면 별건.
- **DATALIKERS 응답의 게시물 유무 실측** — 대응 픽스처가 없어 확인 불가 상태로 남긴다. 설계는
  캡션 0건을 전제로 하므로 결론은 바뀌지 않는다.
- **발굴 표면의 게시물 실측 비율 게이트** — PR #204(analytics/was 층)에서 이미 다룬다.
