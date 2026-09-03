# 브랜드 모니터링 게시물·인플루언서 응답 influencerId 동봉

> 상태: ✅ 구현됨 · 작성 2026-09-03 / 작성자 백엔드 / 수신 프론트엔드

FE의 "인플루언서 저장" 버튼(`POST /v1/saved-influencers` body `{influencerId, memo}`)이 브랜드
모니터링 화면에서 바로 쓸 수 있는 값을, 게시물·인플루언서 응답에 `influencerId`(string | null)로
동봉했다. 정규 인플루언서 검색(발굴) 화면을 거치지 않고 브랜드 게시물 목록에서 바로 저장할 수
있게 하는 것이 목적이다.

## 1. 필드 의미

- 타입: `string | null`. **nullable은 키 생략이 아니라 명시적 `null`**이다(계약 무결성 규칙 #1).
- 값: 게시물 작성자(또는 인플루언서 집계 행의 계정) username을 소문자 정규화한 값이 "발굴 존재"
  판정을 통과하면 그 원본 handle, 통과하지 못하면 `null`이다.
- **null이면 FE는 저장 버튼을 비활성화해야 한다.** null인 채로 `POST /v1/saved-influencers`를
  호출하면 `influencerId`가 발굴 색인에 없는 계정이라 저장 시점 존재 검증에서 404가 난다(아래
  §4 참조) — 서버가 미리 걸러 준 신호를 FE가 다시 무시하지 않게 해 달라는 요청이다.
- `influencerId`가 있으면 그 값은 `GET /v1/influencers/{influencerId}` 상세 조회에도 그대로
  쓸 수 있다(같은 값 공간, §3).

## 2. 적용 표면 3곳

### 2-1. `GET /v1/brand-monitoring/accounts/{accountId}/posts`

`BrandPostResponse`의 최상위 필드로 추가했다(작성자 필드들 근처가 아니라 record 끝에 두었다 —
기존 필드 순서를 보존하기 위해서다. **필드 위치는 JSON 키 순서를 보장하지 않으니 키 이름으로
읽을 것**).

```jsonc
{
  "id": "ABC123",
  "authorUsername": "glowdeep_92",
  // ...기존 필드 전부 동일...
  "influencerId": "glowdeep_92"   // 또는 null
}
```

### 2-2. `GET /v1/brand-monitoring/accounts/{accountId}/hashtag-posts`

`BrandHashtagPostResponse`(리라우팅 전용 슬림 셰이프, 다음 릴리스에 엔드포인트째 제거 예정)에도
같은 필드를 동봉했다. 판정을 다시 하지 않고 §2-1의 값을 그대로 옮긴다.

```jsonc
{
  "shortcode": "HHH",
  "authorUsername": "hashtag_influencer",
  "brandPostId": "HHH",
  "influencerId": "hashtag_influencer"   // 또는 null
}
```

### 2-3. `GET /v1/brand-monitoring/influencers`

`BrandInfluencerResponse`(작성자 단위 집계)에도 동봉했다. 이 표면은 게시물이 아니라 **작성자
(username) 단위로 접힌 행**이라, `influencerId`도 그 작성자의 게시물이 몇 건이든 항상 같은 값이다.

```jsonc
{
  "username": "glowdeep_92",
  "postCount": 3,
  "influencerId": "glowdeep_92"   // 또는 null
}
```

## 3. "발굴 존재" 판정 기준

정본은 발굴 상세 조회 `GET /v1/influencers/{influencerId}`가 성공하는 계정 집합이다
(`V1InfluencerController` → `V1InfluencerRepository.findProfile`). 그 조회의 predicate를 그대로
재사용했다 — 새 규칙을 만들지 않았다.

```sql
SELECT a.handle, ...
FROM accounts a
LEFT JOIN account_summaries s ON s.handle = a.handle
LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = a.handle
WHERE a.handle = :h
```

즉 **analysis DB `accounts` 테이블에 그 handle이 존재하는가**뿐이다. `accounts`는 analytics
미러가 채우는 발굴 서빙 모수(뷰티 ∪ F&B, QUALIFIED 상태, 프로필 보유 — `analytics.v_accounts`)라,
크롤링만 되고 아직 발굴 목록에 노출되지 않는 계정(비뷰티·비F&B, 미수집, 자격 미달 등)은 이
판정에서 자동으로 걸러진다. `account_summaries`·`image_assets` 조인은 프로필 부가 정보용이라
influencerId 존재 판정 자체에는 영향이 없다(LEFT JOIN).

`POST /v1/saved-influencers`의 저장 시점 존재 검증(`V1SavedRepository.findInfluencer`)도 같은
SQL 조각(`accounts` 단일 존재 확인)을 쓴다 — 두 표면의 판정이 갈리지 않는다.

배치 조회용으로 새로 추가한 리포지토리 메서드
`V1InfluencerRepository.findExistingHandlesByLower(Collection<String> lowerUsernames)`도 같은
predicate를 `handle IN (...)`(PK 정확 일치) 배치로 물을 뿐이다.

## 4. 정규화 규칙

인스타그램 username은 규격상 소문자·숫자·`.`·`_`만 허용되므로 `accounts.handle`은 항상 소문자다.
그래서 배치 조회는 **`accounts.handle`과 소문자 입력을 정확 일치(PK 인덱스)로 매칭**한다 —
`lower()` 매칭은 accounts PK 인덱스를 못 타 매 요청 seq scan이라 쓰지 않는다. 게시물 작성자
관측값의 대소문자 정규화(소문자화)는 **조립 측(`BrandPostAssembler`)이 책임**진다 — 배치 조회에
넘기기 전에 소문자로 낮춘다.

## 5. `GET /v1/influencers/{id}` · `POST /v1/saved-influencers`와의 관계

- `influencerId`가 non-null이면 `GET /v1/influencers/{influencerId}`가 200을 반환하는 것이
  보장된다(같은 판정 기준이므로).
- `influencerId`가 non-null이면 `POST /v1/saved-influencers` body에 그대로 실어 저장할 수 있다.
  `memo`는 별도 입력.
- `influencerId`가 null이면 두 API 모두 그 계정 handle로는 실패한다(404) — 이 값이 FE의 "저장
  가능 여부" 판정 그 자체다.

## 6. 범위 밖 — 레거시 인플루언서 모니터링

레거시 인플루언서 모니터링 표면(`/v1/monitoring/items`, `TrackingItemResponse` 기반)은 이번
변경 범위 밖이다. 브랜드 모니터링(`/v1/brand-monitoring/*`)과는 다른 화면·다른 응답 셰이프라,
"인플루언서 저장" 버튼이 필요한 화면이 브랜드 모니터링 쪽인지 레거시 인플루언서 모니터링 쪽인지
FE가 확인해 주면 좋겠다 — 원 요청은 브랜드 모니터링 화면 기준으로 구현했다.

## 7. 성능 노트 — 배치 조회

응답 조립 시 페이지 안 distinct 작성자 handle을 모아 `findExistingHandlesByLower` **1회**로
존재 집합을 조회한다(N+1 없음, 빈 집합이면 조회 자체를 생략). `GET /v1/brand-monitoring/influencers`
표면은 인덱스 캐시(`BrandIndexCache`)가 이 판정 결과까지 함께 캐싱하므로, 발굴 색인에 새로 계정이
편입되거나(또는 빠지거나) 그 사실이 이 표면에 반영되기까지 캐시 무효화 주기(스윕·유저 쓰기·KST
자정·배포 중 먼저 오는 것, 최대 하루)만큼 지연될 수 있다 — 기존에도 용인해 온 지연 패턴과 같은
급이라 별도 처리는 하지 않았다.
