# 캡션 기반 광고 표기 판정 (브랜드 직접 태그 게시물) 설계

> 상태: ✅ 구현됨(2026-08-17 설계 확정 → 2026-08-18 구현, 08-19 운영 노출 개통 — 후속은 트랙 MON-BT 문서 참조)
> **2026-08-18 사용자 정정(§6)**: 최초 설계는 브랜드가 시딩 인플루언서 계정을 별도 등록하는
> 신규 관리 표면(monitoring `brand_seeded_account` + `.../seeded-accounts` API 5종 + was
> 프록시 5종)을 전제했으나, 구현 당일 이 표면이 잘못된 신설이라는 판단으로 전면 철회됐다.
> `seededAuthor` 판정은 신규 등록 목록이 아니라 **이미 존재하는 캠페인 관리 데이터**
> (`app.monitoring_items`·`app.brand_direct_posts`)에서 was가 조회 시점에 직접 도출한다 —
> §6은 이 교체를 반영해 재기술했다. `brand_seeded_account` 테이블·마이그레이션 자체는
> expand-contract상 이번엔 DROP하지 않고 미사용 상태로 남아 있다. 상세는
> [monitoring-was-contract.md §9](../../../contracts/monitoring-was-contract.md)(v2.12)와
> [트랙 MON-BT](../../../tracks/MON-BT-브랜드-태그-모니터링.md) 참조.

## 1. 목적

브랜드 계정에 직접 태그된 게시물(`brand_tagged_post` + `brand_post_meta`)의 캡션이
광고 표시 규정을 지켰는지 게시물 단위로 판정해, 브랜드 고객에게 시딩 게시물의
표기 리스크를 보여준다. 광고주(브랜드)도 표시광고법상 책임 주체이므로, 브랜드의
리스크 관리 대상은 정확히 자기 시딩 캠페인 게시물이다.

**스코프 제외**: 해시태그 감지 계열(`brand_hashtag_post`)은 이번 대상이 아니다.

## 2. 판정 기준의 정본

- **공정거래위원회예규 제499호 「추천·보증 등에 관한 표시·광고 심사지침」(2026-06-01 시행)**
  — 특히 Ⅴ.6.(광고주와 추천·보증인과의 경제적 이해관계 공개).
  전문: https://www.law.go.kr/행정규칙/추천·보증등에관한표시·광고심사지침
- 보조: 공정위 「경제적 이해관계 표시 안내서」
  https://www.ftc.go.kr/www/selectReportUserView.do?key=10&rpttype=1&report_data_no=8706

캡션으로 판정 가능한 요건은 세 가지다(조문 번호는 Ⅴ.6. 기준):

1. **표기 존재** — 경제적 이해관계 표시 문구가 있는가. 인스타 유료 파트너십 라벨
   (`is_paid_partnership`)도 인정.
2. **표현 적절성** — 나.(3) 명확한 내용: 적절 예 `'#광고'`, `'#협찬'`, `'대가성 광고'`,
   `'금전적 지원'`, `'무료 상품'`, `'상품 협찬'`, `'상품 할인'` 등. 부적절 예 `'체험 후기'`,
   `'체험단'`, `'선물'`, `'~에서 보내주셨어요'`, 브랜드 해시태그 단순 언급(`#[브랜드명]`),
   `'[브랜드명]×[계정명]'`, 이해하기 어려운 줄임말. 나.(4) 동일 언어: `'AD'`, `'PR'`,
   `'Sponsor'`, `'spon'`, `'sp'`, `'Collabo'`, `'앰버서더'`, `'땡스 투'` 등 외국어 단독은
   부적절(전체적으로 한국어로 읽히면 예외).
3. **위치** — 나.(1)+다.(2)(사진 매체): 본문 첫 부분 또는 **첫 번째 해시태그**가 원칙.
   본문 중간에 구분 없이 삽입, '더보기'를 눌러야만 확인, 여러 해시태그 사이에 묻힘은 부적절.

**캡션으로 판정 불가**(제품 한계로 명시): 사진·영상 안의 삽입 문구(다.(2)①, 다.(3)),
글자 크기·색상(나.(2)), 댓글 표기. 따라서 이 기능의 "미표기"는 **캡션·라벨 기준**이라는
한정된 의미이고 프론트 문구에도 이 한정이 필요하다.

## 3. 확정된 제품 결정

- 판정 대상: **직접 태그 계열만**.
- 결과 형태: 게시물당 단일 verdict + **위반 사유 코드 + 근거 문구**.
- 판정 방식: 하이브리드(규칙 선처리 → LLM은 추출만 → 코드가 최종 판정).
- 실행 시점: **수집 직후 인라인**(enrich 체인 내 판정 단계).
- 노출: 게시자 프로필 보강 완료 시점에 목록 노출, 댓글·판정은 프론트 폴링으로
  나중에 채워지는 프로그레시브 방식.
- 뒷광고(미표기 광고) 감지: **광고성 추론을 하지 않는다**. 상업적 신호 축은 검토 후
  기각(공구·할인코드를 쓰는 게시물은 거의 표기를 하므로, 위장 뒷광고를 구조적으로 못
  잡는다). 어투 기반 광고 개연성 추정도 기각(팬의 자발적 후기와 구분 불가, 오탐 경고가
  기능 신뢰를 무너뜨림). 대신 **시딩 계정 등록**(§6)으로 추론 없이 확정한다.

## 4. 데이터 모델

`brand_post_meta`에 컬럼 추가(verdict는 브랜드와 무관한 게시물 속성이므로 meta 소속):

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `ad_verdict` | text (CHECK) | `DISCLOSED` 준수 / `NOT_DISCLOSED` 미표기 / `INSUFFICIENT` 불충분 / `UNCERTAIN` 판단불가. NULL = 미판정(판정 중·재시도 대상) |
| `ad_verdict_source` | text (CHECK) | `RULE` / `LLM` (해시태그 감지의 verdict_source 컨벤션 준수) |
| `ad_violations` | jsonb | 위반 코드 배열: `NO_DISCLOSURE`, `AMBIGUOUS_EXPRESSION`, `FOREIGN_LANGUAGE`, `HIDDEN_PLACEMENT` (복수 가능) |
| `ad_evidence` | jsonb | 근거 문구 배열 `[{phrase, category, offset}]` — 판정의 설명 가능성 담보 |
| `ad_judged_at` | timestamptz | 판정 시각 |
| `judged_caption_hash` | text | 판정 시점 캡션 md5 — 캡션 변경 재판정 트리거 |

신규 테이블 `brand_seeded_account`(§6): `(brand_id, username)` PK + `created_at`.

마이그레이션은 monitoring 버전 공간, UTC 채번, 순수 추가(expand-contract 무관).

## 5. 판정 파이프라인 — 4단계, 결정적인 것부터

### Tier 0 — 메타 규칙 (LLM 무관)
- `is_paid_partnership = true` → `DISCLOSED` (RULE). 플랫폼 공식 라벨이 최강 증거.
- 캡션 공백: 사진/캐러셀 → `NOT_DISCLOSED` + `[NO_DISCLOSURE]` (RULE),
  릴스·동영상(videoUrl 보유 FEED 포함) → `UNCERTAIN` (영상 내 표기가 정본 위치라 캡션 부재로 단정
  불가 — HikerClient는 contentType을 REELS/FEED 2값으로만 매핑하므로 일반 피드의 단일 동영상도
  contentType=FEED로 온다, videoUrl 유무로 판별).

### Tier 1 — 고신뢰 사전 매칭 (결정적, 코드)
지침 원문 예시 중 **오탐 여지가 없는 패턴만** 사전화: `#광고`, `#유료광고`, `#협찬`,
`광고입니다`, `유료 광고`, `대가성 광고`, `협찬받(았|은)`, `제공받아 작성`,
`소정의 (수수료|원고료|광고료)` 류. 매칭 + 위치 적절(§5 위치 규칙)이면 여기서
`DISCLOSED` 확정하고 **LLM 콜 생략**. `광고` 단독 같은 저정밀 패턴은 사전에 넣지
않는다("광고판이 예쁘네요" 오탐). 확정 못 하면 Tier 2로.

Tier 1은 **LLM 콜 없이 곧장 DISCLOSED를 확정**하므로 false DISCLOSED가 이 단계에서
낼 수 있는 최악의 오류다. 두 가지 안전장치를 둔다:
- **해시태그 토큰 경계**: `#광고`류 패턴은 다음 글자가 문자·숫자·밑줄이면 매칭하지
  않는다(정규식 `(?![\p{L}\p{N}_])`) — `#광고아님`이 `#광고`의 접두로 오탐하는 사고 방지.
- **캡션 수준 부정 가드**: `내돈내산` 또는 `(광고|협찬)\s*(이|가|은|는)?\s*(아니|아님)`
  (붙여쓰기·띄어쓰기, "아니"/"아님" 두 활용형, 조사 결합("이/가/은/는") 부정문까지 모두
  커버) 같은 부정·자비 구매 신호가 캡션 어디든 있으면, 다른 고신뢰 패턴이 매칭돼도
  Tier 1은 확정을 포기하고 Tier 2(LLM)로 넘긴다. 이건 **NOT_DISCLOSED 확정이 아니라
  판단 보류**다 — "내돈내산이지만 #광고", "#광고 아님 사비로 구매", "협찬이 아니라
  그냥 산거예요 #광고"처럼 문맥이 필요한 캡션의 최종 판정은 문맥을 읽을 수 있는 LLM
  몫으로 남긴다. (정규식은 두 차례 구멍이 발견돼 넓혔다: ① `광고\s*아니` 2음절
  리터럴만으로는 "아님" 활용형을 못 잡음 → `(아니|아님)` 대안 추가. ② `(광고|협찬)\s*(아니|아님)`은
  "협찬이 아니라"·"광고가 아니에요"처럼 명사와 부정어 사이에 조사가 끼는 경우를
  못 잡음 → `(이|가|은|는)?` 선택적 조사 그룹 추가.)
- `협찬받`도 과거형 확정 문구(`협찬받았`/`협찬받은`)로 좁혔다 — `협찬받고 싶어요`(모집·희망)
  오탐 방지. `협찬받아 작성` 류는 Tier 1에서 빠지지만 Tier 2가 처리하므로 재현율
  손실은 없다(LLM 콜만 소폭 늘어남).

### Tier 2·3의 역할 분담 원리

LLM과 코드는 서로 다른 질문에 답한다. **LLM**: "이 캡션 어딘가에 '대가를 받았다는
의미'의 문구가 있는가, 있다면 정확히 어느 단어들인가" — 표기 문구의 변형은 열린
공간이라(어순·조사 변형, 신조어, 부정 문맥 `광고 아니고 내돈내산`, `광-고` 류 변형
표기) 사전·정규식으로는 재현율과 정밀도를 동시에 얻을 수 없다. **코드**: "LLM이
인용한 문구가 (1) 캡션에 실존하는가(substring — 환각 차단), (2) 어디에 있는가
(오프셋 산수), (3) 규칙표에 넣으면 verdict가 무엇인가(조합표)" — 전부 결정적이고
LLM 없이 단위 테스트된다. 최종 판정을 결정적 규칙으로 빼두면 LLM의 재량 표면이
"문구 추출·분류" 하나로 좁아져 골드셋 평가와 evidence 기반 감사가 가능해진다.

### Tier 2 — LLM 추출 (판단이 아니라 추출)
- 기존 `GeminiHttp` seam 재사용, 모델 `gemini-3.1-flash-lite`(설정 키로 교체 가능),
  responseSchema 강제.
- 출력: `disclosures: [{phrase(캡션 원문 그대로), category: CLEAR|AMBIGUOUS|FOREIGN|UNCERTAIN}]`
- 프롬프트에 지침 원문의 적절/부적절 예시 목록을 그대로 싣고 few-shot으로 사용.
- LLM의 담당: 사전이 못 잡는 변형 표기, 신조어, 부정 문맥(`광고 아니고 내돈내산` — 표기
  아님), 다의어 문맥.

### Tier 3 — 코드 검증·조합 (환각 차단 + 결정적 판정)
- **환각 차단**: LLM이 추출한 모든 `phrase`를 캡션과 exact substring 대조, 없는 문구는
  폐기·로그. 오프셋·위치는 전부 코드가 계산한다.
- **위치 규칙**(지침 다.(2)): '더보기' 접힘은 렌더링 기준(기기·폰트·이모지 폭)이라
  텍스트만으로 정확 판정이 불가능하다 — 근사임을 인정하고 불확실성이 전부
  "위반 아님" 쪽으로 떨어지게 설계한다.
  - **첫 번째 해시태그는 오프셋 무관 인정** — 지침 다.(2)③이 명시한 규칙. 접힘 판정
    대상은 본문 텍스트 표기로 좁아진다.
  - **본문 표기는 3구간(band)**: 확실히 보임(문구 전체가 보임 상한 이내 & 첫 2줄 이내)
    → 적절 / 확실히 접힘(시작 오프셋이 접힘 하한 초과 또는 3번째 줄 이후) →
    HIDDEN_PLACEMENT / **회색 지대 → 게시자에게 유리하게 적절 인정**(위반 플래그
    없음). 지침 원문도 "'더보기'를 눌러야**만** 확인할 수 있는 경우"를 부적절로
    규정하므로, 확실한 경우만 위반으로 보는 것이 규정 취지에 부합한다.
  - 글자 수는 **그래핌 단위**로 세고, 줄바꿈 규칙(3번째 줄 이후 = 접힘)을 병행한다.
    경계값 2개(보임 상한/접힘 하한)는 설정으로 두고 골드셋 단계에서 실기기 실측으로
    캘리브레이션한다(§10).
- **조합표**:

| 추출 결과 | verdict | violations |
|---|---|---|
| CLEAR 문구가 적절 위치에 존재 | DISCLOSED | — |
| CLEAR 있으나 전부 묻힌 위치 | INSUFFICIENT | HIDDEN_PLACEMENT |
| AMBIGUOUS만 존재 | INSUFFICIENT | AMBIGUOUS_EXPRESSION (+묻힘 병기) |
| FOREIGN만 존재 | INSUFFICIENT | FOREIGN_LANGUAGE |
| 문구 없음 | 사진: NOT_DISCLOSED / 릴스·동영상: UNCERTAIN | NO_DISCLOSURE (사진만) |
| UNCERTAIN 문구뿐 | UNCERTAIN | — |

- LLM 콜 실패·파싱 실패·스키마 위반은 verdict NULL 유지 → 다음 스윕에서 자동 재시도.
  판정값을 오염시키지 않는다.

## 6. 시딩 계정 판정 — 캠페인 데이터 도출 (2026-08-18 사용자 정정 — 신규 등록 표면 철회)

> 이 절의 최초 버전은 "브랜드가 시딩 인플루언서 계정(username) 목록을 별도로 등록한다"는
> 신규 관리 표면을 전제했다(monitoring `brand_seeded_account` 테이블 + `.../seeded-accounts`
> CRUD API 5종 + was 프록시 5종). 구현 당일 그 전제가 잘못됐다는 판단이 내려졌다 —
> **브랜드는 이미 캠페인 관리 화면에서 "누구를 추적 중인지"를 알고 있는데, 같은 정보를
> 다시 입력하게 하는 신규 표면은 중복 작업이자 잘못된 설계였다.** 이하는 이 정정을 반영한
> 재기술이다.

브랜드가 누구에게 시딩했는지는 **이미 캠페인 관리 데이터에 존재한다** — 캠페인에 배정된
계정 추적, 캠페인에 배정된 직접 등록 게시물이 그 신호다. was는 이 데이터를 조회 시점에
조합해 시딩 계정 집합을 구한다:

- **시딩 계정의 게시물 + `NOT_DISCLOSED` = 위반 확정** (추론 없음).
- 비시딩 계정의 게시물은 표기 상태를 정보로만 표시(오가닉 가능성).

설계 원칙(유지): 시딩 여부는 **판정 결과에 저장하지 않는다** — 조회 시 조인(계산)으로
낸다. 캠페인 배정을 나중에 바꿔도 재판정이 필요 없다.

**산출 기준**(user 스코프 — 캠페인은 브랜드가 아니라 유저 단위 개념이다) — 게시물
작성자 username(소문자)이 다음 합집합에 속하면 시딩 계정:

1. **캠페인 연결 계정 추적**: `app.monitoring_items`에서 `user_id=?` AND `mode='account'`
   AND `campaign_id IS NOT NULL` AND `canceled_at IS NULL`인 행의 `input_value`(등록 시 이미
   소문자 정규화 저장).
2. **캠페인 연결 게시물의 작성자**: `app.brand_direct_posts`(`user_id=?`) 중
   `monitoring_item_id`가 가리키는 `app.monitoring_items.campaign_id IS NOT NULL`(canceled
   제외)인 short_code들의 게시자 username — 게시자는 monitoring DB `brand_post_meta.username`
   에서 조회한다. **app 스키마와 monitoring DB는 물리적으로 다른 DB라 SQL 조인 불가** —
   was 코드에서 두 단계로 조합한다(시스템 경계 원칙: 조합은 was 코드에서).

구현: `MonitoringItemRepository.findCampaignLinkedAccountHandles`(1) +
`BrandDirectPostRepository.findCampaignLinkedShortCodes`(2, app 스키마 내부 조인) +
`BrandReadRepository.findPostMeta`(2의 게시자 조회, 기존 메서드 재사용) →
`BrandPostAssembler.resolveSeededUsernames(userId)`가 합집합을 낸다. 노출 토글이 꺼져
있으면(`monitoring.brand.ad-disclosure.expose=false`) 이 조회 자체가 생략된다(기존 드라이런
방어 그대로 유지).

**철회된 것**: monitoring `BrandSeededAccountRepository` + `GET/PUT/POST/DELETE
.../seeded-accounts[/{seededUsername}]` API 5종, was `V1BrandAccountsController`/
`V1BrandAccountService`의 프록시 5종, `MonitoringCommandClient`의 시딩 CRUD 5종,
`BrandReadRepository.findSeededUsernames`. `brand_seeded_account` 테이블·마이그레이션은
이미 develop 머지·스테이징 적용 상태라 expand-contract상 이번엔 DROP하지 않고 미사용
상태로 남긴다(추후 contract 단계에서 DROP).

(참고 — 철회 전 신규 등록 표면에 적용됐던 정규화 규칙: trim·선행 `@` 제거·소문자·중복
제거. 캠페인 도출 경로에서는 `input_value`가 등록 시 이미 소문자 정규화 저장되고,
`brand_post_meta.username`은 monitoring이 관측한 원문이라 was 소비부에서 방어적으로
`Locale.ROOT` 소문자화만 한 번 더 한다 — 별도 등록 입력이 없으니 `@` 제거 등 입력
정규화는 더 이상 필요 없다.)

## 7. 실행 위치·동시성

- enrich 체인에 판정 단계 추가. 대상 선정은 브랜드 처리 중
  `ad_verdict IS NULL OR judged_caption_hash <> md5(caption)` — 신규 판정, 캡션 변경
  재판정(사후 #광고 추가가 실제로 흔함), 첫 배포 후 기존 게시물 백필을 한 메커니즘으로
  커버한다. 캡션은 매 스윕 `EXCLUDED.caption`으로 재업서트되므로 변경 감지가 성립한다.
- LLM 콜은 Hiker 동시성 관리용 `enrichWorker`와 **분리된 전용 소형 풀**(동시 3~4)로.
  LLM 지연이 Hiker 보강 처리량을 잠식하지 않게 한다.
- 판정 실패는 수집·보강에 영향 없음(격리 유지). verdict NULL은 다음 스윕이 캡션 해시 재비교로
  자동 재시도하지만, 이는 **180일 이하(추적 창) 게시물 한정**이다 — 180일 초과 게시물은 크롤
  정책상 재열거 자체가 없어(`BrandCrawlPolicy.due` 무조건 false) verdict NULL이 영구 잔존할 수
  있었다(2026-08-18 스펙 리뷰 정정 — 아래 백필 단계가 이 공백을 흡수한다).

### 7-1. 미판정 잔여 백필 (2026-08-18 개정, 08-18 상한 제거·기동 즉시 실행 재개정)

사용자 확정 원칙: **광고 판정은 처음에 전량 돌고, 이후에는 캡션 변경분만 돈다.** `judgePosts`
(스윕 경유 후보 선정)만으로는 이 원칙이 180일 추적 창 안에서만 성립한다 — 창 밖(배포 시점
재고·수집 기간만큼 쌓인 오래된 게시물)은 정기 스윕이 다시 만나지 않아 최초 1회 판정 기회 자체가
없다. `AdDisclosureJudgeService.backfillUnjudged()`가 이 공백을 메운다:

- 대상 선정은 브랜드 스코프 없이 전역 `brand_post_meta WHERE ad_verdict IS NULL`
  (`BrandPostMetaRepository.findUnjudged`) — 부분 인덱스(`idx_brand_post_meta_unjudged`)가
  이 조회를 커버해, 잔량이 줄수록(판정이 수렴할수록) 조회 비용도 0에 수렴한다.
- 캡션은 이미 `brand_post_meta`에 저장돼 있으므로 **Hiker 재조회 없이** 저장된
  caption·content_type·video_url·is_paid_partnership만으로 판정한다. Tier0~3 규칙은
  `judgeCore`로 추출해 `judgePosts` 경로(`PostInfo` 입력)와 완전히 공유 — 입력 소스만 다를
  뿐 같은 캡션이면 항상 같은 verdict를 낸다.
- **상한이 없다**(08-18 재개정 — 종전 `monitoring.brand.ad-disclosure.backfill-per-night`
  설정·야간 상한 개념을 삭제). `backfillUnjudged()`는 잔량이 0이 될 때까지 500건씩
  `findUnjudged`를 반복 조회하며 전량 처리한다(배치 크기는 구현 디테일이지 상한이 아니다).
  LLM 전용 풀(동시 4)이 자연 속도 제한이다. 배치마다 "잔여 N건" info 로그를 남긴다. 한 배치가
  통째로 재시도해도 갱신되지 않으면(영구 실패) 그 배치는 이번 호출에서 더 이상 재조회하지
  않고 종료한다 — 재시도는 다음 호출의 몫이라, 전량 실패 배치가 같은 호출 안에서 무한
  루프하는 것을 막는다.
- **기동 즉시 실행**(08-18 신설) — 앱 기동 완료(`ApplicationReadyEvent`) 시
  `AdDisclosureBackfillStartupRunner`가 별도 데몬 스레드에서 `backfillUnjudged()`를 즉시
  시작한다(부팅 블로킹 없음). 배포 직후 재고가 다음 야간 스윕까지 기다리지 않고 바로
  판정되는 것이 목적이다. 판정 킬 스위치(`monitoring.brand.ad-disclosure.enabled`)가
  꺼져 있으면 스레드조차 띄우지 않고 스킵한다. 실패는 이 스레드 안에서 격리해 앱 기동·운영에
  영향을 주지 않는다.
- **야간 스윕 말미 훅은 유지하되 의미가 바뀐다** — 종전에는 이 훅이 "1회 전량 백필"의 유일한
  실행 지점이었지만, 이제 최초 전량 판정은 기동 러너가 맡으므로 스윕 말미 훅은 **실패 잔량
  재시도 안전망**이다. `BrandSweepJob`이 브랜드 루프 종료 직후(아카이브 잡들과 같은
  finally) `backfillUnjudged()`를 1회 호출한다 — 이 역시 상한 없이 잔량 전부를 처리한다.
  판정 킬 스위치(`enabled`)가 꺼져 있으면 이 단계도 함께 스킵한다. 실패는 격리해 스윕 결과에
  영향을 주지 않는다.
- **동시 실행 방어** — 기동 백필과 스윕 말미 백필이 겹칠 수 있다(예: 기동 직후 첫 야간
  스윕이 아직 끝나지 않은 백필과 부딪히는 경우). `AdDisclosureJudgeService` 내부의
  `AtomicBoolean` 가드가 이중 실행을 막는다 — 이미 실행 중이면 나중 호출은 즉시
  `(remaining=0, processed=0)`을 반환하고 info 로그만 남긴 채 스킵한다.
- 스윕 경유 판정(당일 due 게시물)과도 겹칠 수 있다 — 스윕이 이미 판정에 성공한 게시물은
  `ad_verdict`가 채워져 있어 다음 `findUnjudged` 조회에 잡히지 않는다. 스윕에서 **실패**해
  verdict가 NULL로 남은 게시물만 재시도 대상이 될 수 있는데, 이는 판정 로직이 멱등(같은
  캡션 → 같은 verdict)이라 무해한 재시도다 — LLM 비용이 이중으로 나가는 경우는 있지만,
  검증 결과가 갈리지는 않는다.

## 8. 노출 게이트 변경 (프로그레시브 서빙)

`enriched_at` 세팅 시점을 "게시자 프로필 보강 완료 직후"로 당긴다(finally 보장 유지).
was 게이트 SQL(`enriched_at IS NOT NULL`)은 무수정 — 의미만 "게시자 보강 완료 = 노출
가능"으로 바뀌고, 댓글 수집과 광고 판정은 노출 후 프론트 폴링으로 채워진다.
**기존 댓글 게이트 의미가 바뀌는 변경**이므로 트랙 문서에 명시한다.

## 9. was API

직접 태그 목록 응답(`BrandPostResponse`)에 필드 추가:

- `adDisclosure`: 판정값 4종 또는 null(판정 중)
- `adViolations`: 위반 코드 배열
- `adEvidence`: 근거 문구 배열
- `seededAuthor`: boolean — 캠페인 데이터 도출 시딩 여부(조회 시 계산, §6 참조 — 2026-08-18
  정정으로 별도 등록 목록이 아니라 캠페인 관리 데이터 조인이 산출 기준)

"미표기 위반 확정" 배지 조합(`seededAuthor && adDisclosure == NOT_DISCLOSED`)은
프론트가 구성한다. 프론트 문구는 "캡션·라벨 기준"임을 한정하고, 비시딩 계정에는
"위반" 단정 문구를 쓰지 않는다.

## 10. 정확도 검증 체계

1. **지침 원문 예시 = 테스트 케이스**: 예규 제499호의 적절/부적절 예시 전부를 단위
   테스트로 고정. 지침이 명시한 사례를 틀리면 빌드 실패.
2. **골드셋 평가**: 배포 전 실데이터 캡션 ~200건 수동 라벨링 후 정확도 측정.
   핵심 지표는 **NOT_DISCLOSED 오탐률**(브랜드에게 잘못된 경고). 미달 시 프롬프트
   보강 또는 모델 상향(`judge-model` 설정 키).
3. **드라이런 후 개통**: 판정 파이프라인 먼저 배포 → 기존 게시물 전량 판정 →
   verdict 분포·샘플 검토 → was 노출 개통(어셈블러 조건 처리로 토글).

## 11. 테스트

- Tier 1 사전·위치 규칙·Tier 3 조합표: 순수 단위 테스트(LLM 무관).
- Tier 2: fake `GeminiHttp`로 스키마 파싱·환각 차단(substring 폐기)·실패 격리 검증.
- 게이트 순서 변경(§8): 통합 테스트로 "게시자 보강 완료 시 노출, 댓글·판정 미완이어도
  노출" 확인.
- was: 어셈블러 필드 매핑 + `seededAuthor` 조인 테스트.
