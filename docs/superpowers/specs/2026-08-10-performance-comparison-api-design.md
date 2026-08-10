# 성과 비교 집계 API 설계 — GET /v1/performance-dashboard/comparison

> 상태: 🟢 활성

## 배경

성과 대시보드의 비교 막대(계정 × 5구간)는 FE가 목데이터로 그리고 있다. TOP 10 두 개는
목록 API 전량(1,746건)으로 클라이언트가 만들 수 있지만, 5구간 × 계정 집계는 서버 몫이다.
FE 요구사항(08-10 수신)을 바탕으로 설계했고, covered 판정 기준은 실제 수집 정책과의
충돌을 확인해 **수집 완료 기준**으로 확정했다(아래 §covered).

## 결정 요약

| 항목 | 결정 |
|---|---|
| 집계 방식 | 메모리 집계 — `PerformanceContentAssembler.assemble()` 전량 재사용 (SQL 집계 기각) |
| 계정 축 | 유저의 활성 브랜드 연결 전부, 연결 순서대로 |
| covered | 계정 단위: `lastSweptAt != null`(ready)이면 5구간 전부 true, 아니면 전부 false |
| individual | 비교에서 제외 (`brandAccountId` null — 계정 귀속 불가) |
| 지표 원천 | 콘텐츠별 최신 스냅샷 |
| 합계 null 규칙 | non-null 값의 합, non-null이 하나도 없으면(0건 포함) null |
| 참여율 | 계산하지 않음 — `followersSum`(분모)만 제공, FE가 계산 |

## 엔드포인트

`GET /v1/performance-dashboard/comparison` — 기존 `V1PerformanceDashboardController`에 추가.
인증 필수. 기간 파라미터 없음 — 5구간을 항상 전부 내린다.

### 요청 파라미터

| 쿼리 | 값 | 비고 |
|---|---|---|
| `source` | `all`\|`tagged`\|`direct`\|`individual` | 목록 API와 동일한 `normalizeFilter` 관용구 — 허용 값 밖 400, `all`·미지정 = 전량 |
| `sponsorship` | `all`\|`sponsored`\|`organic`\|`unknown` | 〃 |
| `campaignId` | `all`\|`none`\|`{id}` | 목록 API의 `matchesCampaign` 관용구 재사용 |

`source=individual`은 전 계정·전 구간이 빈 결과(contentCount 0)가 된다 — individual은
계정 귀속이 불가능하기 때문(의도된 동작, FE 공유 필요).

### 구간 정의 (업로드일 기준, 서로 안 겹침, Asia/Seoul)

`today = LocalDate.now(Asia/Seoul)`. 달력월 연산은 `LocalDate.minusMonths`(말일 클램프).

| key | 범위 |
|---|---|
| `1w` | today−6일 ~ today |
| `1w_1m` | today−1개월 ~ today−7일 |
| `1m_3m` | today−3개월 ~ today−1개월−1일 |
| `3m_6m` | today−6개월 ~ today−3개월−1일 |
| `6m_12m` | today−12개월 ~ today−6개월−1일 |

업로드일 키는 목록 API와 같은 `PerformanceContentAssembler.uploadedOn`(혼재 포맷 앞 10자
날짜). 업로드일 미상(post 없는 collecting·detecting 등)·12개월 밖 콘텐츠는 어느 구간에도
들어가지 않는다.

참고: tagged 계열의 서빙 윈도우는 365일(`BrandPostAssembler.WINDOW_DAYS`)이라 `today−12개월`이
366일 전인 해(윤년 경유)엔 구간 하한 부근 1일치가 조립 전량에 아예 없을 수 있다 — 집계는
조립 전량 기준이므로 별도 처리 없이 그 정의를 그대로 따른다(목록과 항상 일치).

### 응답

```json
{ "accounts": [{
    "brandAccountId": "2",
    "username": "cclime.beauty",
    "collectionStartedAt": "2026-05-14T09:12:00+09:00",
    "buckets": [{
      "key": "1w",
      "covered": true,
      "contentCount": 31,
      "views": 87420, "likes": 2824, "comments": 328,
      "followersSum": 412000,
      "viewsMissingCount": 4, "likesHiddenCount": 12, "followersMissingCount": 0
    }]
}]}
```

- `accounts`는 활성 브랜드 연결(`BrandLinkRepository.findAllActiveByUser`) 순서대로.
  콘텐츠 0건이어도 계정은 실린다. monitoring 계정 행이 없는 연결은 목록 API와 동일하게
  경고 로그 후 생략.
- `username`은 brand_account 관측값, `collectionStartedAt`은 `registered_at`(KST ISO).
- `buckets`는 항상 5개 전부, 위 표 순서대로.
- nullable 필드는 키를 생략하지 않고 명시적 null(계약 무결성 #1).

## covered — 수집 완료 기준

FE 원안은 "등록 전 구간은 빗금"(등록일 기준)이었으나, 실제 크롤링 정책(v1 08-09)은 등록 시
백필이 **등록 윈도우 365일 전체**를 열거한다(`BrandCollectService.enumerationCutoff` —
`last_swept_on` null이면 전체 윈도우). 즉 어제 등록한 경쟁 계정도 첫 스윕 완주 후엔
12개월치 태그 게시물이 있다. 등록일 기준으로 covered=false를 내리면 백필로 실제 수집된
막대가 빗금에 가려진다.

따라서 **covered는 계정 단위**다:

- `lastSweptAt != null`(= collectionStatus `ready`, 한 번이라도 스윕 완주) → 5구간 전부 `true`
- 아니면(collecting·error — 아직 서빙할 수집분 없음) → 5구간 전부 `false`

covered=false여도 집계값은 계산해서 내린다(direct 콘텐츠는 레거시 파이프라인이라 스윕
완주 전에도 존재할 수 있다) — covered는 "수집이 불완전하다"는 표시일 뿐 데이터를 숨기지
않는다. 응답 셰이프는 FE 요구(구간별 플래그) 그대로 유지한다 — 판정 규칙만 계정 단위이고,
향후 구간별 정밀 판정이 필요해져도 셰이프 변경 없이 규칙만 바꾸면 된다.

**FE 회신 필요**: 빗금은 "등록 전 과거"가 아니라 "아직 첫 수집 전" 상태에서만 나타난다.

## 집계 규칙 (계정 × 구간)

- **귀속**: `content.brandAccountId == 계정 id`(문자열 비교). individual(null)은 제외.
  direct는 매핑이 곧 소속 선언이라 tagged 관측 없이도 brandAccountId가 채워져 있어 정상
  귀속된다(`PerformanceContentAssembler.fromLegacy`).
- **contentCount**: 구간에 든 콘텐츠 수(항상 0 이상의 정수 — null 아님).
- **지표 원천**: 콘텐츠별 **최신 스냅샷**(스냅샷은 날짜 오름차순 계약이라 마지막 원소 —
  목록의 `commentsTotal`과 같은 관용구).
- **views·likes·comments**: 구간 내 콘텐츠들의 최신 스냅샷 값 중 non-null의 합.
  **non-null이 하나도 없으면(구간이 0건인 경우 포함) null** — 합 0(전부 관측됐는데 0)과
  null(전부 미제공)을 구분한다(FE 규칙 ③ — 피드는 views가 항상 null).
- **followersSum**: 콘텐츠별 `item.followers` non-null의 합, 없으면 null. 같은 작성자가
  여러 건이면 건수만큼 중복 합산 — FE가 (likes+comments)÷followersSum으로 콘텐츠 가중
  평균을 내는 구조 그대로다(비율을 서버가 미리 계산하지 않는다 — FE 규칙 ②).
- **viewsMissingCount**: 최신 스냅샷 views가 null인 콘텐츠 수(스냅샷 자체가 없는 경우 포함).
- **likesHiddenCount**: 최신 스냅샷 `likesHidden=true`인 콘텐츠 수(스냅샷 없으면 미포함).
- **followersMissingCount**: `item.followers` null인 콘텐츠 수.

## 구현 형태

- **`PerformanceComparisonAssembler`**(신규, `was/v1/perfdashboard`): 구간 산출·귀속·합산을
  정적 순수 함수 위주로 — DB 없이 단위 테스트한다. 계정 목록 로딩(브랜드 연결 → brand_account
  행)만 인스턴스 배선.
- **`PerformanceComparisonResponse`**(신규 record): `accounts[]` → `AccountComparison(
  brandAccountId, username, collectionStartedAt, buckets[])` → `Bucket(key, covered,
  contentCount, views, likes, comments, followersSum, viewsMissingCount, likesHiddenCount,
  followersMissingCount)`.
- **컨트롤러**: HTTP 표면만 — 필터 값 공간 검증(기존 `normalizeFilter` 재사용) 후 어셈블러
  호출. monitoring 비활성 환경에선 브랜드 연결이 없으므로 `accounts: []`.

## 테스트

- 단위(순수 함수): 구간 경계(오늘·각 경계일·12개월 밖·업로드일 미상 제외), null 합계 규칙
  (0건 → null, 전부 null → null, 일부 null → non-null 합), covered 두 상태, individual 제외,
  direct 귀속, 필터(source·sponsorship·campaignId `none`) 적용, missing/hidden 카운트.
- 통합(컨트롤러): 기존 `V1PerformanceDashboardControllerTest` 관용구로 1~2케이스(응답 셰이프
  ·필터 400).
