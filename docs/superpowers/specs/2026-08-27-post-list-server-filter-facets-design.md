# 게시물 목록 서버 필터·패싯 + 인플루언서 집계 API 설계

> 상태: 🟢 활성 (2026-08-27)

FE 변경요청 2건(2026-08-27)을 한 PR로 처리한다: ① 브랜드 게시물 목록의 요청당 고정비
제거(P0)·서버 필터 5종(P1)·패싯 카운트+influencerCount(P1)·태그 안 된 게시물 개수(P2),
② 인플루언서 집계 API 신설(`GET /v1/brand-monitoring/influencers`).

## 배경 — P0 고정비의 정체 (실측)

FE 실측(dev-api, 계정 119·12개월·2,000건): 페이지 크기와 무관하게 요청당 ~1.9초 고정비.
로컬 실측(perf119 = 계정 119 실데이터 사본)으로 원인 확정:

- 인덱스 쿼리(`findBrandPostIndex`)의 **DB 실행은 3.5ms** (EXPLAIN ANALYZE).
- 고정비의 본체는 **협찬 판정용 캡션 원문 7.7MB(4,982행)를 매 요청 전송·JDBC 매핑**하는
  비용이다. 협찬 판정이 "조회 시 Java 계산·저장 없음"(키워드 소급 설계 —
  `BrandSponsorshipClassifier` javadoc)이라 캡션을 매번 실어 날랐다.
- FE가 관찰한 "4병렬 = 순차와 동일(7.4s)"도 같은 원인: 요청당 ~2초의 매핑이 CPU 바운드라
  2코어 호스트에서 CPU를 나눠 쓰며 직렬화된다. `monitoring-ro` 풀(max 3)은 부차 요인.
  고정비를 없애면 함께 해소된다 — 풀 크기는 실측 후 필요할 때만 손댄다.

FE가 제안한 수집 시점 사전 집계 버킷은 채택하지 않는다: "협찬 판정은 저장하지 않는다
(소급성)" 설계 결정과 정면 충돌하고, 유저별 표시 창(`collection_months`)·자유 기간
범위 때문에 일 단위 버킷이 필요하며, influencerCount(distinct)는 버킷 합산이 불가능하다.
캡션 전송 자체를 없애면 사전 집계 없이 목표(0.5초)에 도달한다.

## A. 슬림 인덱스 개편 (P0)

`BrandReadRepository.findBrandPostIndex`에서 caption 원문을 제거하고 판정 결과만 받는다.

- `BrandSponsorshipClassifier`에 **Postgres ARE 호환 정규식 빌더**를 추가한다. 기존 마커
  상수 4갈래(부분 문자열·라틴 해시태그 토큰·reklam 단어 접두·캡션 선두 접두 표기)에서
  정규식 1개를 생성해 쿼리 파라미터로 전달하고, SQL은
  `lower(m.caption) ~ :markerRegex AS caption_marker`(boolean)만 반환한다.
  - Postgres ARE는 lookbehind/lookahead가 없다 — 등가 변환:
    `(?<![\p{L}\p{N}])reklam` → `(^|[^[:alnum:]])reklam`,
    `\s+l(?=\s)` → `\s+l[[:space:]]`, 해시태그 전체 토큰 일치 → `#(ad|…)($|[^[:alnum:]_])`.
  - 판정 트리는 Java에 남는다: `classify(Boolean isPaidPartnership, boolean captionMarker)`
    오버로드 신설. 기존 caption 버전은 하이드레이트·레거시 폴백 경로가 그대로 쓴다.
    마커 상수는 계속 Java 한 곳 — 소급성(조회 시 계산)도 유지된다.
  - **동치성 봉인**: Testcontainers(PostgreSQL) 골든 코퍼스 테스트 — 기존 주석의 함정
    사례(#adventure 비매치, "광고 아님"·"adorable" 비매치, reklamdır 매치, "광고 ㅣ" 매치,
    소문자 l 구분자, CJK 마커, 대소문자 혼용) 전부 포함해
    `SQL 매치 결과 == containsSponsorshipMarker` 를 캡션별로 단언한다.
- 인덱스 프로젝션 확장(전부 소형 컬럼): `m.content_type`, `m.ad_verdict`,
  `t.author_username`, author 조인 3필드(`a.username, a.full_name, a.followers` —
  `LEFT JOIN author_profile a ON a.ig_user_id = t.author_ig_user_id`, PK 조인이라 신규
  인덱스 불필요). ig_user_id 미해결 행의 username 폴백은 지금처럼 Java 배치 조회
  (`findAuthorsByUsername` 슬림 버전, 희소 경로).
- `PostRef` 확장: `contentType(reels/feed), adVerdict, authorUsername, authorFullName,
  authorFollowers` 추가. 판정 함수(source·sponsorship·KST 달력일)는 풀 조립과 동일 원칙
  유지 — ref 기반 counts가 전량 풀 조립 값과 정의상 일치한다는 기존 불변식을 지킨다.

예상 효과: 전송량 7.7MB → 수백 KB. perf119로 before/after 실측하고, dev-api 배포 후
FE 실측표와 같은 조건으로 재측정해 회신한다(백엔드 직접 측정 요청에 대한 답변 포함).

## B. 신규 필터 5종 (P1) — 전부 ref 위 Java 판정

| 파라미터 | 판정 | 비고 |
|---|---|---|
| `contentType` | `reels`/`feed` | `all`·생략 = 무필터(기존 `normalizeFilter` 관용구) |
| `follower` | 6.21 토큰 `500-3k`/`3k-10k`/`10k-30k`/`30k-50k` | 하한 포함·상한 배타(디스커버리와 동일 경계). followers null은 필터 시 제외 |
| `keyword` | username·fullName 소문자 부분 일치 | |
| `adRisk` | `true`만 유효(생략·false = 무필터) | 아래 판정 규칙 |
| `authorUsername` | 계정명 일치(대소문자 무시) | 인플루언서 상세 전용 |

**adRisk 판정 규칙 = FE `hasAdDisclosureIssue` 복제** (celfit-front
`src/lib/monitoring/ad-disclosure.ts:78-106` 확정):

```
sponsorship == sponsored  AND  ad_verdict ∈ { NOT_DISCLOSED, INSUFFICIENT }
```

협찬 선행 조건이 핵심이다(오가닉엔 표기 위험을 따지지 않는다 — FE 주석의 확정 결정).
노출 게이트(expose 토글 AND 조회자 링크가 competitor 아님)도 동일 적용 — 게이트가 닫힌
조회에서는 adRisk 필터가 아무것도 매치하지 않고 카운트도 0이다(FE가 adDisclosure를
null로 받아 0이 되는 현행 동작과 일치).

## C. 패싯 카운트 + influencerCount (P1) — `meta.facets` (additive)

기존 `meta.counts`(필터 적용 전 전량·flat)와 파라미터 생략 동작은 그대로 두고(하위 호환),
**신규 키 `meta.facets`**로 FE 요청 셰이프를 내린다. FE 예시는 `counts` 자리에 그렸지만
기존 counts를 쓰는 화면이 있어 키를 분리한다 — FE에 통보한다.

```json
"meta": {
  "total": 823, "limit": 2000, "page": {"offset": 0, "limit": 25},
  "counts": { "…기존 그대로…" },
  "influencerCount": 1607,
  "facets": {
    "contentType": {"all": N, "reels": n, "feed": n},
    "sponsorship": {"all": N, "sponsored": n, "organic": n, "unknown": n},
    "source":      {"all": N, "tagged": n, "direct": n},
    "adRisk": n
  }
}
```

- 각 축의 값은 **그 축 필터만 해제하고 나머지 필터 전부(기간 포함) 적용**한 개수다.
  각 축의 `all`은 그 상태의 총계. `adRisk`는 adRisk 필터만 해제한 상태의 위험 게시물 수.
- `influencerCount` = 링크 창 + uploadedFrom/To만 적용(나머지 필터 무시)한 distinct
  authorUsername 수(작성자 미상 제외). ref에 author가 실리므로 스트림으로 계산한다.
- `meta.total`(필터 적용 후)·`meta.page` 계약 불변.

## D. 인플루언서 집계 API (②) — `GET /v1/brand-monitoring/influencers`

- **파라미터**: `accountIds`(필수, 쉼표 구분) / `uploadedFrom`·`uploadedTo` /
  `sort`(7종, 기본 posts) / `keyword` / `follower`(6.21 토큰) /
  `sponsorship`(sponsored=협찬 1건 이상, organic=0건) / `offset`·`limit`(생략 시 전량,
  6.1 규약).
- **소유권**: `BrandLinkRepository.findAllActiveByUser`로 내 활성 링크 집합을 뽑고, 요청
  accountIds가 **전부** 그 안에 있어야 통과 — 하나라도 아니면 기존 FORBIDDEN 관용구.
  브랜드/경쟁사 구분 없음(둘 다 내 링크면 됨). 계정별 `collection_months` 창을 각자 적용.
- **산지**: 계정별 슬림 인덱스(A 재사용) + 신규 `findLatestMetricsForBrand`(게시물별 최신
  스냅샷의 content_type·views·likes·likes_hidden·comments 경량 프로젝션 —
  `findLatestViewsForBrand`를 이걸로 흡수). 과도기 레거시 폴백 게시물도 포함한다
  (FE 전량 집계와의 일치 조건 — FE는 목록 응답 전량을 집계하고 있었다).
- **집계 규칙**(FE 명세 그대로, 구현 시 celfit-front 집계 코드와 1:1 대조):
  - views·likes·comments·postCount·sponsoredCount: 기간 내 게시물 합계. 피드는 views
    null(기여 0). 스냅샷 없는 게시물은 postCount에만 기여.
  - likesKnownCount: 최신 스냅샷 `likes_hidden=false`인 게시물 수. 숨김 게시물의 likes는
    합산에서 제외(0으로 더하지 않음).
  - followers·fullName·profilePicUrl: `author_profile` 최신 관측값(계정 전역 1행 —
    FE도 게시물 객체에 실린 동일 산지 값을 쓰므로 결과 동일). profilePicUrl은 기존
    `resolveImageUrl`(아카이브 `/img/` 우선) 규칙 적용. profileUrl은 기존 카드와 동일
    형식(`https://www.instagram.com/{username}/`).
  - latestPostAt: 기간 내 최신 taken_at(KST ISO).
  - 같은 username이 여러 계정에 있으면 합산해 1행.
- **정렬 7종** + 동점 username 오름차순. `likes`: likesKnownCount=0은 맨 뒤.
  `engagement` = (likes+comments)/(followers×postCount)×100 — followers null/0·postCount
  0·likesKnownCount 0이면 값 없음으로 맨 뒤. `avg_views` = round(views/postCount).
- **응답**: FE 예시 셰이프 그대로(항목 12필드), `meta {total, offset, limit}` flat.
  예상 크기 계정 4개 기준 ~0.5MB(<1MB 목표).
- **연계**: 인플루언서 상세는 ①의 `authorUsername` 필터로 처리(FE 요청서 명시).

## E. 태그 안 된 게시물 개수 (P2) — `GET /accounts/{accountId}/hashtag-posts/count`

`{count: N}` 단일 응답(ApiResponse data). 개수는 단순 SQL COUNT가 아니라 어셈블러의
기존 Java 필터 2종(브랜드 풀 겹침 제외·내 태그 교집합) 적용 후 값이므로, 슬림 프로젝션
(short_code + 판정 입력) 위에서 같은 판정 함수를 공유해 센다 — 목록과 숫자 불일치 금지.

## 성능·검증 (완료 판정)

- limit=25 요청 0.5초 이내: perf119 로컬 실측 + dev-api 배포 후 재측정.
- 필터 조합·패싯·influencerCount의 FE 일치: 전량 응답을 참값으로 서버 필터 결과를
  대조하는 통합 테스트로 커버. 분류기 동치성은 골든 코퍼스 테스트.
- 하위 호환: 기존 `meta.counts`·파라미터 생략(전량 모드) 동작 불변을 테스트로 고정.
- 병렬 처리: 고정비 제거 후 dev-api에서 4병렬 재측정해 FE에 결과 회신(풀 조정은 실측이
  요구할 때만).

## 결정 요약

| 결정 | 선택 | 근거 |
|---|---|---|
| P0 방식 | 슬림 인덱스 + SQL 마커 매치 | 사전 집계는 소급성 설계와 충돌·버킷화 불가 축 존재. 캡션 전송 제거만으로 목표 도달 |
| adRisk 규칙 | FE `hasAdDisclosureIssue` 복제 | 칩 숫자 일치가 완료 판정. sponsored 선행 조건 포함 |
| 패싯 키 | 신규 `meta.facets` | 기존 counts 하위 호환 요구와 FE 예시 셰이프 충돌 해소 |
| ② 집계 위치 | was Java(슬림 ref 재사용) | 판정 함수 공유로 ①과 정의상 일치, SQL 중복 없음 |
| P2 형태 | 전용 count 엔드포인트 | 계정 표면 계약 무변경, 필요 시점에만 호출 |
| 작업 단위 | ①+② 한 PR | 사용자 결정 |
