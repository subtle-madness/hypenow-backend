# FE 요청 2건 — /growth 댓글 모수 통일 + 수집 상한 모수 통일

> 상태: ✅ 구현됨

## 배경

FE가 개요 탭을 `/v1/performance-dashboard/growth`로 옮기며 게시물 전량(13.19MB / 12.17초)을
받지 않게 됐다. 그 전환의 전제로 서버가 내려야 할 값 2건이 남았고, 둘 다 **FE가 우회할 수 없다** —
우회하려면 없애려던 전량 응답을 다시 받아야 한다.

두 건 모두 **모수 불일치**다: 같은 화면의 두 숫자가 서로 다른 게시물 집합을 세고 있다.

## ① `/growth` 댓글 모수 — `ratedComments` 신설

### 문제

`PerformanceGrowthAggregator.foldOne`에서 `comments` 합산만 좋아요 숨김 게이트 **바깥**에 있었다.
`likes`·`followersSum`은 `if (likesHidden) … else { … }`의 else 안이고 `comments`만 밖이라,
숨김 게시물의 댓글이 분자에 남았다.

FE 실측(8/22~28, 249건):

| 값 | 서버 | 숨김 제외 | 판정 |
|---|---|---|---|
| `likes` | 15,518 | 15,518 | ✓ |
| `followersSum` | 1,708,875 | 1,708,875 | ✓ (08-27 ①에서 수정됨) |
| `comments` | 2,738 | 2,317 | ✗ 421건 과대 |

`followersSum`이 08-27에 수정될 때 같이 잡히지 않은 이유는, 그 수정이 **분모**를 분자에 맞추는
작업이었고 분자의 두 항(`likes`·`comments`)이 서로 다른 위치에서 더해진다는 사실은 그 시야에
들어오지 않았기 때문이다.

### 결정

기존 `comments`는 **건드리지 않고** `ratedComments`를 신설한다(additive). `comments`를 제자리
수정하면 그 값을 쓰는 소비자의 화면 숫자가 그날 바뀐다.

| 항목 | 결정 |
|---|---|
| 게이트 | `hasSnapshots && !latestLikesHidden` — **`likes`와 문자 그대로 같은 위치**(같은 else 블록) |
| `comments` | 08-06 계약 유지(관측 전량, 숨김 포함) |
| 참여율 | `(likes + ratedComments) ÷ followersSum` |
| null 규칙 | 분자에 실린 게시물이 없으면 null(0 아님) — 기존 합계 규칙 그대로 |

**`followersSum`의 게이트(추가로 `likes != null && followers != null`)에 맞추지 않은 이유**:
`likes` 합이 이미 팔로워 미상 게시물을 포함하므로, `ratedComments`만 더 좁히면 **분자 안에서**
`likes`와 `comments`의 모수가 갈린다. `likes`와 완전 대칭으로 두는 쪽이 분자를 한 모수로 유지한다.
분자·분모 사이에 남는 잔차(팔로워 미상 게시물)는 08-27에 수용된 기존 비대칭이고, 그 규모는
`followersMissingCount`로 이미 노출된다.

**두 값을 같은 블록 안에서 더하는 것이 설계의 요점이다** — 분자의 두 항이 떨어진 자리에서 더해지면
모수가 갈렸는지를 코드를 읽어서 알 수 없다. 이번 결함이 정확히 그 형태였다.

### 응답 계약

`PerformanceGrowthResponse.Point`에 `ratedComments`(nullable) 추가. 총계 축과 계정 시리즈가 같은
`foldOne`을 타므로 두 축이 자동으로 일관된다.

## ② 수집 상한 모수 — `/posts`와 `/influencers` 통일

### 문제

`/posts`는 08-27 ③에서 모수를 **최신 업로드순 2,000**으로 선컷했는데, `/influencers`는 그 컷을 타지
않았다. 두 표면은 같은 화면의 앞뒤(목록 → 상세)라 숫자끼리 모순됐다:

| | `/influencers` | `/posts` |
|---|---|---|
| marynmay 관련 인원 | 2,800명 | 1,607명 |
| harthbeauty 게시물 | 14개 | 10개 |

목록에 "게시물 14개"라고 써 놓고 누르면 10개가 나온다. FE는 우회할 수 없다 — 인플루언서 화면은
게시물을 받지 않으므로 상한 밖 4건이 누구 것인지 알 방법이 없다.

### 결정

컷 규칙을 `BrandCollectionCap`으로 분리하고 두 표면이 **같은 것을 부른다**.

| 항목 | 결정 |
|---|---|
| 상한 | 2,000 (수집 정책 `collection-post-limit`과 같은 값) |
| 순서 | 최신 업로드순 — 업로드일 내림차순(미상 마지막) + shortcode 타이브레이크(**전순서**) |
| 호출 위치 | 링크 표시 창 필터 **뒤**, 업로드 기간·분류 필터 **앞** |
| 단위 | 브랜드 계정별(교차 중복 제거는 컷 이후) |
| 신호 | `meta.collectionCapped` — 계정 중 하나라도 걸렸으면 true |

**컷을 함수로 분리한 이유**: 값만 맞추고 각자 인라인으로 두면, 정렬 타이브레이크가 갈리는 순간
같은 상한을 쓰고도 상한 경계에서 **서로 다른 2,000개**를 고른다. 지금과 같은 종류의 버그가 재발한다.
게시물 목록의 기본 정렬(`uploaded_desc`)이 컷과 같은 순서인 것도 우연이 아니라 "최근 것부터"라는
한 규칙이라, 정렬 정의도 이 클래스로 모았다.

**컷이 기간 필터보다 앞인 이유**: 뒤로 밀면 기간을 좁힐 때마다 상한 밖 게시물이 되살아나 같은
계정의 모수가 필터에 따라 달라진다. 앞에 두면 상한이 "수집 정책"이라는 고정된 의미를 유지한다.

**컷이 계정 단위인 이유**: 상한이 브랜드 계정별 수집 정책이라, 계정을 합친 뒤 자르면 요청에 넣은
계정 수에 따라 계정별 모수가 달라진다.

`/influencers`의 `meta.limit`은 이미 페이지 크기라 상한값 자체는 내리지 않는다(`/posts`는
`meta.limit`이 상한값이고 페이지 정보가 `meta.page`로 분리돼 있다 — 표면별 기존 계약을 유지).

## 검증

- `BrandCollectionCapTest`(5) — 상한 이하 무변경·정확히 2,000 경계·입력 순서 무관·동률 결정성·
  업로드일 미상 우선 배제.
- `V1BrandInfluencersControllerTest`(+3) — 2,100건 시드에서 `total` 2,000·`collectionCapped` true,
  상한 경계(u1999 있음 / u2099 없음), 컷이 기간 필터보다 앞임(잘린 날짜로 좁혀도 0건).
- `PerformanceGrowthAggregatorTest`(+2) — `comments`와 `ratedComments`가 같은 버킷에서 갈림,
  숨김만 있는 버킷은 `ratedComments` null·`comments` 유지.
- `V1PerformanceDashboardControllerTest`(+1) — JSON 계약(총계 축·계정 축 둘 다).

## 남은 것 (범위 밖)

`/comparison`(`PerformanceComparisonAssembler.aggregate`)은 **08-27 ①의 `followersSum` 수정이
반영되지 않았다** — 팔로워를 무조건 더하는 구 규칙이고, 그 값이 테스트로 고정돼 있다
(`PerformanceComparisonAssemblerTest`: 숨김·스냅샷 없는 게시물의 팔로워가 분모에 들어가는 단언 2건).
`comments`도 숨김 포함이다.

`followersSum`은 신설 필드가 아니라 기존 소비자가 있는 필드라, 고치면 그 화면의 참여율이 바뀐다.
**FE가 `/comparison`을 아직 호출하는지**에 따라 답이 갈린다:

- 호출자가 없다면 → 고치는 게 아니라 표면을 제거한다.
- 쓰고 있다면 → 제자리 수정(값 변경, 두 표면이 같은 수를 말함) vs `ratedFollowers`·`ratedComments`
  신설(additive, 대신 같은 응답에 옳은 필드와 틀린 필드가 공존) 중 선택이 필요하다.
