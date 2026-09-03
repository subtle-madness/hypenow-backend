# 발굴 카드 댓글 범위 · 공동구매 뱃지 필드 추가 - 회신

> 상태: 🟢 활성 · 작성 2026-09-03 / 작성자 백엔드 / 수신 프론트엔드
>
> 대상: `GET /v1/influencers`(발굴 목록), `GET /v2/influencers/{influencerId}/similar`(유사 카드),
> 둘 다 같은 `InfluencerCard` 응답 형태를 공유합니다(6.21).

## 요약

요청하신 필드 4개를 `InfluencerCard`에 추가했습니다. **전부 additive**이고 기존 필드는 하나도
바뀌지 않았습니다(하위 호환).

| 필드 | 타입 | 의미 |
|---|---|---|
| `minComments` | `number \| null` | 최근 창(아래 §1) 댓글 수 최소값 |
| `maxComments` | `number \| null` | 최근 창 댓글 수 최대값 |
| `groupPurchaseCount` | `number` | 최근 12개 캡션 중 공동구매 시그널이 있는 게시물 수 |
| `hasGroupPurchase` | `boolean` | `groupPurchaseCount > 0` |

## 1. minComments·maxComments, avgComments와 완전히 같은 창

`avgComments`를 만드는 것과 **동일한 소스**(`account_content_series`, 계정별 최근 12개 창, 뷰티/F&B
서빙 모수·ENUMERATION·지표 스냅샷 보유 게시물만)에서 `comments`의 최소·최대를 뽑습니다. 댓글이
아직 수집되지 않은 게시물(`comments IS NULL`)은 `avgComments`가 그 게시물을 평균에서 자연히 빼는
것과 똑같이 `minComments`·`maxComments` 계산에서도 제외됩니다(SQL `MIN`/`MAX`의 표준 NULL 무시
동작이라, 별도 필터를 걸지 않았습니다).

- 창 안에 댓글 값이 있는 게시물이 **0건**이면(전부 미수집, 또는 창 자체가 비어 있음)
  `minComments`·`maxComments`는 둘 다 `null`입니다. `avgComments`가 `null`이 되는 것과 같은
  조건입니다.
- `minComments ≤ avgComments ≤ maxComments` 불변식이 항상 성립합니다(같은 표본에서 파생되므로).

## 2. groupPurchaseCount·hasGroupPurchase, 상세 화면과 같은 창 · FE와 같은 정규식

**주의**: 이 두 필드는 `minComments`·`maxComments`와 **다른 창**을 씁니다. 저희 쪽에서 두 창을
비교해보니 다음과 같이 갈립니다.

- `avgComments`/`minComments`/`maxComments`의 창(`account_content_series`)은 뷰티/F&B 서빙
  모수·`ENUMERATION` 콘텐츠·지표 스냅샷 보유라는 게이트가 걸려 있어, 상세 화면(6.4)의
  `recentContents` 12개와 **행 집합이 다를 수 있습니다**(주로 스냅샷 미보유·비서빙 게시물 차이).
- 공동구매 뱃지는 프론트가 **상세 API(6.4) `recentContents`의 캡션 12개**에 규칙을 적용해
  그리시는 걸로 파악했습니다. 그래서 `groupPurchaseCount`도 그 12개와 **정확히 같은 모수**
  (게시일 내림차순 최근 12개, 게이트 없음)로 계산했습니다. 발굴 카드 뱃지와 상세 화면 뱃지의
  숫자가 어긋나지 않게 하기 위해서입니다.

판정 규칙은 celfit-front `src/lib/discover/group-purchase.ts`와 **정확히 동일**합니다.

```
정규식: /공동구매|#공구/  (find, 부분일치, 앵커 없음)
```

- `공동구매`는 캡션 어디에 있어도 매칭됩니다(본문·해시태그 구분 없음).
- `공구`는 반드시 `#` 바로 뒤에 올 때만 매칭됩니다. `#공구오픈`·`#공구템`처럼 뒤에 다른 글자가
  붙는 **접두 매칭은 인정**합니다(정규식이 뒤쪽에 앵커가 없으므로). `#` 없이 맨몸으로 등장하는
  "공구"(예: "메이크업 공구 정리했어요")는 매칭되지 않습니다.
- 캡션이 `null`이면 미매칭으로 처리합니다.
- 대소문자 구분은 한글이라 해당 없습니다.

Java 구현은 `Pattern.compile("공동구매|#공구")`의 `find()`이고, FE 테스트 케이스 4종(공동구매
본문/해시태그, `#공구` 단독·접두 조합, 맨몸 "공구"·무관한 문구, 목록 카운트·빈 목록)을 그대로
유닛 테스트(`GroupPurchaseSignalTest`)로 옮겨 검증했습니다.

## 3. 캐시 반영 시점

발굴 목록은 Redis 캐시(`influencer-discovery`, TTL 1시간)를 씁니다. 캐시 키 prefix에
**배포 빌드 시각**(`hypenow:cache:{buildTime epoch}:...`)이 들어가는데, 매 배포마다 이 빌드
시각이 새로 찍히기 때문에 **이번 배포부터는 자동으로 새 prefix를 씁니다**. 구 배포가 만든
캐시 엔트리(새 필드 없이 저장된 것)는 절대 재사용되지 않고, 배포 직후 첫 요청부터 새 필드가
그대로 채워져 나갑니다. 구 prefix 엔트리는 그냥 TTL(최대 1시간)까지 두면 자연 만료되어
Redis 메모리에서 빠집니다. 별도 캐시 무효화 작업은 필요하지 않았습니다.

## 4. 응답 예시 (배포 전 로컬 확인, §5 참고)

```json
{
  "id": "example_handle",
  "handle": "example_handle",
  "avgComments": 87,
  "minComments": 42,
  "maxComments": 160,
  "sponsoredCount": 2,
  "groupPurchaseCount": 1,
  "hasGroupPurchase": true
}
```

댓글 창 표본이 전부 미수집이면:

```json
{
  "avgComments": null,
  "minComments": null,
  "maxComments": null
}
```

공동구매 캡션이 없으면:

```json
{
  "groupPurchaseCount": 0,
  "hasGroupPurchase": false
}
```

## 5. 검증 상태 (알려드릴 것)

`./gradlew :was:test` 전체(2,132건) 통과 확인했고, 새 필드 관련 유닛·통합 테스트(어셈블러의
min/max·NULL 제외·공동구매 카운트, 리포지토리의 12개 창 컷·경계값, 캐시 JSON 왕복)를
새로 추가했습니다. 다만 로컬 실데이터 확인은 **로컬 analysis DB에 이번 발굴 카드가 참조하는
사전집계 matview(`account_sponsored_counts` 등)와 `account_content_series`/`contents` 미러
데이터가 비어 있어서** 실제 응답 표본은 못 실었습니다(운영·staging DB는 정상 상태로 추정됩니다,
로컬 전용 데이터 공백입니다). 배포 후 실제 응답으로 한 번 더 확인 부탁드립니다.
