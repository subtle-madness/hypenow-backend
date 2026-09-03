# 발굴 카드 댓글 범위 · 공동구매 뱃지 필드 추가 - 회신

> 상태: 🟢 활성 · 작성 2026-09-03 / 갱신 2026-09-03(판정 소스를 서버 테이블로 교체) / 작성자 백엔드 /
> 수신 프론트엔드
>
> 대상: `GET /v1/influencers`(발굴 목록), `GET /v2/influencers/{influencerId}/similar`(유사 카드) -
> 둘 다 같은 `InfluencerCard` 응답 형태를 공유합니다(6.21). `GET /v1/influencers/{influencerId}`(상세,
> 6.4)의 `recentContents[]`에도 신규 필드가 하나 붙었습니다(아래 §2-1).

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

## 2. groupPurchaseCount·hasGroupPurchase, 판정 소스가 서버 판정 테이블로 바뀌었습니다

**중요한 변경**: 처음 필드를 내보낼 때는 was가 celfit-front `group-purchase.ts`와 같은 정규식을
직접 계산했습니다. 지금은 그 계산을 걷어내고, analytics의 `GROUP_PURCHASE_JUDGE` 잡이 채우는 서버
판정 테이블 `group_purchase_judgments`를 was가 그대로 읽습니다. **필드 이름·타입·계산 대상 창은
전혀 바뀌지 않았습니다** - 판정 로직이 있는 위치만 was 정규식에서 analytics 잡으로 옮겨갔습니다.

바뀐 이유는 실측입니다. 운영 캡션 전량(295,621건)을 정규식으로 다시 돌려보니, `#` 없이 맨몸으로
쓰인 "공구"(예: "이번 공구는 ~", "[공구] ~")가 인플루언서 캡션의 기본형인데 기존 정규식이 이걸
전부 놓쳐서 **누락이 2.5배**였습니다(계정 12개 창 적중 게시물 625건 → 실제로는 2,206건). 맨몸
"공구"를 전부 인정하면 도구(연장) 의미 오탐이 섞여 들어와서(코퍼스 전체 20건 안팎), 캡션마다
LLM을 돌리는 대신 "규칙으로 확정되는 곳은 규칙, 애매한 곳만 LLM"으로 나눴습니다(모니터링
`AdDisclosureJudgeService`와 같은 원칙).

### 판정 규칙 (analytics가 적용, was는 결과만 읽음)

캡션 1건에 순서대로 적용합니다. 앞 단계에서 확정되면 뒤는 보지 않습니다.

| 단계 | 조건 | 결과 |
|---|---|---|
| 확정 참 | `공동구매` 포함 | true (LLM 없음) |
| 확정 참 | `#공구` 포함 | true |
| 애매 분류 | `공구` 포함 + 캡션 어디든 도구 어휘 동반 | LLM이 판정 |
| 확정 참 | `공구` 포함, 도구 어휘 없음 | true |
| 확정 거짓 | `공구`·`공동구매` 둘 다 없음 | false (LLM 없음) |

도구 어휘(애매 분류를 트리거하는 단어, 캡션 전체 대상): `없이`, `조립`, `설치`, `나사`, `드릴`,
`망치`, `볼트`, `드라이버`, `렌치`, `톱`, `목재`, `목공`, `철물`, `전동`, `DIY`, `수리`, `공구함`,
`공구통`, `공구박스`, `공구세트`, `공구 ?(를|가) (들|필요|사용|이용|챙)`.

애매분만 LLM(Gemini)이 "이 게시물이 인플루언서 공동구매(공구) 판매 게시물인가, `공구`가 연장·도구
의미로만 쓰였으면 아니오"를 판정합니다. 실측 결과 규칙 미적중 맨몸 "공구" 표본 50건은 전부
공동구매 용법이었고, 도구 의미는 코퍼스 전체에서 20건 안팎이라 애매 분류로 늘어나는 LLM 콜은
많지 않습니다.

### 판정은 30분 주기, 신규 게시물은 잠깐 "미판정"일 수 있습니다

`GROUP_PURCHASE_JUDGE` 잡은 낮 시간 30분 간격으로 돕니다. 게시물이 미러된 직후부터 최대 30분
사이는 아직 판정 행이 없거나(신규) LLM 호출이 실패해 `verdict`가 `NULL`(대기, 다음 실행이 자동
재시도)일 수 있습니다. **이 두 경우 모두 `groupPurchaseCount`에 포함되지 않고, 6.4의
`groupPurchase`는 `false`로 내려갑니다** - 신뢰성을 우선해서 "판정 안 됨"과 "공동구매 아님"을
같은 값으로 취급합니다. 잡이 돌고 나면 최대 30분 안에 정정된 값이 반영됩니다.

`groupPurchaseCount`가 보는 창은 여전히 `minComments`·`maxComments`와 **다른 창**입니다.
`avgComments`/`minComments`/`maxComments`의 창(`account_content_series`)은 뷰티/F&B 서빙
모수·`ENUMERATION` 콘텐츠·지표 스냅샷 보유라는 게이트가 걸려 있어, 상세 화면(6.4)의
`recentContents` 12개와 행 집합이 다를 수 있습니다. `groupPurchaseCount`는 상세 API(6.4)
`recentContents`와 **정확히 같은 모수**(게시일 내림차순 최근 12개, 게이트 없음)로 계산해서
발굴 카드와 상세 화면의 숫자가 어긋나지 않습니다.

## 2-1. 6.4 recentContents[].groupPurchase (신규 필드)

상세 API(`GET /v1/influencers/{influencerId}`) 응답의 `recentContents[]` 각 항목에
`groupPurchase: boolean` 필드가 새로 붙습니다. 값은 위와 같은 서버 판정 테이블 조회입니다 -
미판정(판정 행이 없거나 `verdict`가 `NULL`)은 `false`입니다.

```json
{
  "recentContents": [
    { "id": "SC123", "caption": "이번 공구 오픈합니다", "groupPurchase": true },
    { "id": "SC124", "caption": "일상 기록", "groupPurchase": false }
  ]
}
```

`6.23 유사 카드`는 6.21 발굴 카드와 같은 어셈블러를 재사용하므로 별도 작업 없이 함께 갱신됩니다.

### FE 쪽 요청

`src/lib/discover/group-purchase.ts`의 클라이언트 판정(캡션 정규식 직접 계산)을 제거하고, 위
`groupPurchaseCount`·`hasGroupPurchase`(6.21)·`groupPurchase`(6.4) 서버 값으로 뱃지를 그려주시길
요청드립니다. 판정 로직이 이제 analytics 서버 잡 하나로 모여서, FE·구 was 정규식·서버 판정기가
서로 다른 답을 낼 걱정이 없어집니다.

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

공동구매로 판정되지 않았거나 아직 미판정이면(§2 30분 주기 참고):

```json
{
  "groupPurchaseCount": 0,
  "hasGroupPurchase": false
}
```

## 5. 검증 상태 (알려드릴 것)

`./gradlew :was:test` 전체(2,127건) 통과 확인했고, 판정 소스 교체에 맞춰 유닛·통합 테스트를
다시 정리했습니다(리포지토리의 서버 판정 테이블 조인·12개 창 컷·NULL·행 없음 경계값, 어셈블러의
저장소 집계값 전달, 상세 6.4 `groupPurchase`가 verdict=true/NULL/행 없음 세 갈래를 정확히
구분하는지, 캐시 JSON 왕복). 클라이언트 정규식과 동일한 로직을 was 안에서 직접 계산하던
`GroupPurchaseSignal`은 제거했습니다 - 정답은 이제 서버 판정 테이블 하나입니다. 다만 로컬
실데이터 확인은 **로컬 analysis DB에 이번 발굴 카드가 참조하는 사전집계 matview
(`account_sponsored_counts` 등)와 `account_content_series`/`contents`/`group_purchase_judgments`
미러 데이터가 비어 있어서** 실제 응답 표본은 못 실었습니다(운영·staging DB는 정상 상태로
추정됩니다, 로컬 전용 데이터 공백입니다). 배포 후 실제 응답으로 한 번 더 확인 부탁드립니다.
