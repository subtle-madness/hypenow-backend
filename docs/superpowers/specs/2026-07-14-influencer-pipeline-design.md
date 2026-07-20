# 인플루언서 중심 파이프라인 전환 설계

> 상태: ✅ 구현됨 — 단 §CollectJob 세부(6개월 백필·피드/릴스 2스트림 커서 페이지네이션·
> taken_at 컷오프)는 07-15 collect 분리 설계가 "방문당 최근 한 묶음 + 재방문 주기"로 재정의

2026-07-14 · 브랜치 `feat/influencer-pipeline` (feat/direct-comment-crawler 위에서 분기)

## 배경과 목표

지금 파이프라인은 **게시물 중심**이다: 키워드로 게시물을 발굴(discover)하고, 게시물별로
작성자 프로필을 판정(qualify)한 뒤, 게시물별 +3일에 상세·댓글을 수집(aggregate)한다.

새 요구는 **인플루언서 중심**이다:

1. **발굴** — 키워드로 인플루언서를 발굴한다 (크롤링 로직은 유지, 결과의 주인공이 게시물→작성자로 바뀜)
2. **수집** — 발굴된 인플루언서 중 팔로워 범위(전역 하나) 안에 있으면, 그 인플루언서의
   **6개월치 게시물(피드+릴스) 전부**의 상세·댓글을 가져온다.
   첫 방문은 무조건 6개월 백필, 이후 추적 방문의 범위는 데이터를 보고 결정(설정으로 조절).

기존 데이터는 이관한다: 인플루언서 명단 4,176명 + 발굴 raw 데이터 전부.
DB는 재생성하지 않고 **제자리 진화**(Flyway V8~)로 전환한다. 사전 백업:
`~/project/current/soma/hypenow/db-backups/crawler-20260713.dump` (149MB, 14테이블 검증됨).

## 핵심 원칙: 원형 보존, 해석은 사후

후처리를 세 계층으로 나누고 선을 긋는다.

| 계층 | 이번 작업 | 내용 |
|---|---|---|
| **raw** | 스키마+로직 | 응답 수신 즉시 **원형 그대로** 저장. 변형·envelope 없음. `source` 태그로 해석 방법 기록 |
| **정형/제어** | 스키마+로직 | 저장 "후" 원형에서 크롤러 운영에 필요한 최소 제어 필드만 추출해 실컬럼에 기록 |
| **분석 소스** | 만들지 않음 | AI 분석 입력 생성(광고 판별, 텍스트 정제 등). 모양이 분석 요구에 달려 있어 지금 정할 필요가 없음. raw가 자기완결이므로 언제든 전체 재실행으로 채울 수 있다 |

- 저장 순서가 안전성의 핵심: **저장 먼저, 파싱은 그 다음.** 파싱이 실패해도 raw는 남고,
  재추출 배치로 복구한다. (기존 방식의 실패 사례: dfce3f1 — 매퍼 버그로 응답 전건 유실)
- 기존 매퍼(DetailMapper·ProfileMapper 등)는 "저장할 payload 생성"에서 **"저장된 원형에서
  제어 필드 추출"**로 역할이 바뀐다. 예외: CommentMapper의 페이지네이션 파싱(다음 커서
  읽기)은 크롤링 진행 자체에 필요해 수집 중에 돌지만, 저장되는 건 페이지 응답 원형이다.
- 제어 필드 채택 기준: **"크롤러나 운영 화면이 안 쓰면 정형 계층에 안 넣는다."**
  `ad_marked`(AdSignals)는 이 기준으로 탈락 — 분석 소스 계층으로 이관 예정(이번엔 제거만).
- 정형/제어 계층의 역할: ① 크롤러 동작(중복 방지·범위·판정·스케줄·재시도)
  ② 운영 가시성(대시보드·열람) ③ 식별자 뼈대(나중에 분석 계층이 influencer/content id 참조).

## 파이프라인: 잡 3개

```
discover ──▶ qualify ──▶ collect        (기존 aggregate 폐기·대체)
 키워드로       인플루언서       인플루언서별
 발굴          단위 판정        6개월 수집·추적
```

### DiscoverJob (저장만 변경)
활성 `search_keyword` 순회 → 해시태그 발굴(기존 fetcher 유지) → raw 원형 저장 →
제어 필드 추출 → `influencer` upsert + `influencer_discovery` 출처 기록(키워드 텍스트 스냅샷).
발굴된 게시물 자체도 `content`에 upsert한다(`raw_discovery_post.content_id` FK 유지,
short_code dedup) — 단 수집은 CollectJob이 QUALIFIED 인플루언서의 것만 다룬다.

### QualifyJob (게시물 단위 → 인플루언서 단위)
`status=DISCOVERED` 인플루언서 배치 → 프로필 조회(raw 원형 저장) → followers 추출 →
**전역 팔로워 범위**(app_setting)로 QUALIFIED/EXCLUDED. 범위 변경 시 재판정 가능.

### CollectJob (신규)
QUALIFIED 중 방문 대상 선정(`first_collected_at IS NULL` = 백필 대상, 그 외 추적 주기 도래 순) →

1. **프로필 갱신** — 방문 시 자동 (username→user_id 해석 겸용, followers 최신화)
2. **게시물 열거** — HikerAPI 두 스트림, 커서 페이지네이션:
   - 피드: `/gql/user/medias?user_id=&flat=true` (커서 `profile_grid_items_cursor`)
   - 릴스: `/v2/user/clips?user_id=` (커서 `page_id` ← 응답 `next_page_id`)
   - 페이지 응답 원형을 raw로 저장, `taken_at` 컷오프(첫 방문 6개월, 이후 설정 윈도우)에서 중단
   - **shortCode로 두 스트림 중복 제거** 후 `content` upsert
3. **댓글 수집** — 게시물별 self GraphQL(무료, 기존 DirectCommentFetcher). 상세는 별도
   수집 안 함 — **열거 응답이 상세와 동일한 media 객체 전체**이므로 열거=상세 확보.

### 열거 실측 근거 (2026-07-14, 노션 "크롤링으로 얻어올 수 있는 데이터"에 상세)

- honeyzumma(팔로워 11.7k, 릴스 위주): 스트림당 13페이지로 6개월 도달, 26요청 ≈ $0.026(standard).
  union 143 = 겹침 142 + medias만 1
- im_miiin(팔로워 873, 소형): 4요청으로 전체 커버. union 29 = **겹침 0**
  (medias만 12 캐러셀 + clips만 17 릴스) → **두 스트림+dedup이 필수임이 실증됨**
- 필드: 조회수(play_count)·좋아요·댓글수·캡션 전문·is_paid_partnership·taken_at 모두 포함
- 함정 3가지: ① 날짜 필터 파라미터 없음 → 최신순 커서 + `taken_at` 컷오프
  ② 고정 게시물이 맨 앞(오래된 것 가능) → `timeline_pinned_user_ids`/`clips_tab_pinned_user_ids`로
  컷오프 판단에서 제외 ③ gql flat 응답의 숫자 필드에 `1l`/`1f` 접두사(`1ltaken_at`) → 추출기에서 처리
- `/v1/user/medias/chunk`(기존 posts 보충용)는 play_count=0·캡션 누락으로 **탈락**.
  조회수 목적이던 `HikerMediasSupplement`(posts 보충)도 함께 제거

### 에러 처리

- 응답 수신 즉시 raw 저장 → 이후 파싱·추출 실패는 데이터 유실 없음. 추출 실패 행은
  제어 컬럼 NULL로 남고 재추출 배치로 복구
- 게시물 부분 실패: `content.status=FAILED` + `collect_attempts` 상한, 다음 방문에서 재시도
- 인플루언서 단위 실패(비공개 전환·삭제 등): 방문 기록만 남기고 다음 대상으로 진행

## DB 스키마 (Flyway V8~)

### 데이터 흐름별 저장처 (한눈에)

| 무엇을 | 어디서 얻고 | raw 저장처 | 추출되는 제어 필드 |
|---|---|---|---|
| 발굴 게시물 | 해시태그 발굴 (기존 fetcher) | `raw_discovery_post` | shortCode·업로드시각·작성자 → `content`, `influencer`, `influencer_discovery` |
| 프로필 (팔로워 등) | 방문/판정 시 프로필 조회 | `raw_profile` | followers·userId → `influencer` |
| 게시물 목록+상세 (6개월 열거) | `/gql/user/medias` + `/v2/user/clips` | **`raw_media_page` (신규, 페이지 단위 원형)** | shortCode·업로드시각·타입 → `content` |
| 댓글 | 게시물별 self GraphQL | `raw_comment` | (제어 필드 없음 — 페이지 커서만 진행 중 파싱) |
| 게시물 단위 상세 | — 수집 안 함 (열거=상세) | `raw_post_detail` **신규 기록 없음** | 기존 데이터 보존 + 향후 재사용 대비 유지 |

### raw 계층 (payload 원형화)

- `raw_discovery_post` / `raw_post_detail` / `raw_comment` / `raw_profile`:
  - `source` 컬럼 추가 (예: `HIKER_GQL_MEDIAS`, `HIKER_V2_CLIPS`, `SELF_GQL`, `APIFY_ACTOR`,
    구버전 envelope 행은 `LEGACY_ENVELOPE_*`)
  - generated column 제거 → **코드가 저장 직후 쓰는 실컬럼**으로 교체(NULL 허용):
    raw_post_detail.short_code 등 조회 편의 컬럼은 유지하되 파생 방식만 변경
  - 기존 payload(정규화 envelope + `_raw*`)는 그대로 두고 source로 구분
- `raw_media_page` 신규: 게시물 열거 응답의 **페이지 단위 원형** 저장
  (`influencer_id`, `crawl_run_id`, `source`, `payload`, `captured_at`).
  열거 응답을 게시물 단위로 쪼개 `raw_post_detail`에 넣지 않는다 — 원형 보존 원칙.
  게시물별 필드는 제어 계층(`content`)이 추출로 보유하고, 분석 계층이 나중에 per-post
  데이터가 필요하면 페이지 원형을 재해석한다. (`raw_post_detail`은 기존 데이터 보존 +
  게시물 단위 상세 소스를 다시 쓰게 될 경우를 위해 유지)

### 정형/제어 계층

| 테이블 | 처리 | 핵심 컬럼 |
|---|---|---|
| `search_keyword` | 신규 | `keyword`(unique), `enabled`, `created_at`. 분류 계층 없음. **이름 수정 기능 없음**(수정=삭제+추가) |
| `influencer` | `account` 개명·확장 | `username`(unique), `status`(DISCOVERED/QUALIFIED/EXCLUDED), `followers`, `last_profiled_at`, `first_collected_at`, `last_collected_at` |
| `influencer_discovery` | 신규 | `influencer_id` FK, `keyword`(**텍스트 스냅샷** — search_keyword id 참조 금지), `discovered_post_short_code`, `discovered_at`. append-only |
| `content` | 재편 | `short_code`(unique), `influencer_id` FK, `content_type`, `uploaded_at`, `status`(PENDING/COLLECTED/FAILED), `collect_attempts`, `first_seen_at`, `collected_at`. 분류 컬럼·`ad_marked` 제거 |
| `content.origin` | 신규 (V9) | `DISCOVERY`/`ENUMERATION`. 해시태그 발굴 게시물은 인플루언서 발굴의 부산물(`DISCOVERY`)일 뿐 — raw만 보관하고 상세·댓글 수집 대상이 아니다. 진짜 수집 대상은 QUALIFIED 인플루언서의 6개월 열거(CollectJob)가 만든 게시물(`ENUMERATION`)뿐이다. CollectJob의 열거 upsert가 기존 `DISCOVERY` 행을 다시 잡으면(범위 안이면 항상 그렇게 된다) `ENUMERATION`으로 승격시켜 유실 없이 정식 수집 범위로 편입한다. 댓글 수집 대상 조회(`findByInfluencerIdAndStatusAndOrigin`)와 대시보드 게시물 수집 카드 집계는 `ENUMERATION`만 본다 — `DISCOVERY`는 별도 "발굴 보관" 참고 총계로만 노출한다. 이관된 기존 행은 전부 발굴 시대 데이터이므로 `DISCOVERY`로 채웠다(default 없이 명시 세팅 강제). |
| `crawl_run` | 수정 | `category_id` 제거 → 잡 이름 + 대상 파라미터(키워드 텍스트/인플루언서) 기록 |
| `app_setting` | 유지 | 전역 팔로워 min/max, 백필 개월수(기본 6), 추적 윈도우, 배치 크기, 댓글 상한 |
| `category`/`category_keyword`/`collection_rule` | **drop** | 이관 검증 후 |

- 이력·raw 계층은 살아있는 설정을 **id로 참조하지 않는다** — 키워드처럼 UI에서 바뀌는
  값은 텍스트 스냅샷으로 박제 (키워드 수정 시 과거 이력이 오염되는 문제 방지)
- `reel_parse`는 외부 도구 소유 — 건드리지 않음

### 데이터 이관 (같은 DB 안 SQL, 덤프 복원 불필요)

1. `search_keyword` ← `category_keyword` 키워드 54개 (enabled 승계, 대분류/중분류 라벨 버림)
2. `influencer` ← `account` 4,176명 전원 `DISCOVERED`. 프로필 있는 989명은 최신
   `raw_profile.followers` 복사 (판정은 새 QualifyJob이 전역 규칙으로 재수행)
3. `influencer_discovery` ← 기존 `content`의 (owner_username, discovery_keyword, first_seen_at) 역산
4. 기존 raw 4테이블 + `raw_run_item` 데이터는 손대지 않고 보존, `source`에 구버전 표시
5. 검증(건수 대조) 후 구 테이블 drop

## UI 변경

- **분류 체계 화면 → 검색 키워드 화면**: 대분류-중분류-소분류 제거, 평탄한 키워드
  목록(추가/활성 토글/삭제)만. 이름 수정 없음
- **잡 화면**: discover / qualify / collect 세 버튼 (aggregate 제거)
- **대시보드**: 게시물 상태 카드 → 인플루언서 상태 카드(DISCOVERED/QUALIFIED/EXCLUDED,
  백필 완료/대기, 게시물 수집 진행률)
- **설정 화면**: 팔로워 범위·백필 개월수·추적 윈도우·배치 크기 추가 (무중단 변경, 기존 메커니즘)
- **데이터 열람**: 인플루언서 → 게시물 → 댓글 드릴다운으로 재편

## 테스트

- 추출기 단위 테스트: 실측 저장한 원형 샘플(gql flat `1l` 접두사, v2 clips, pinned 케이스)로
  제어 필드 추출 검증
- Flyway 이관 테스트: Testcontainers로 구스키마+샘플 → V8~ 적용 → 건수·매핑 대조
- 잡 통합 테스트: 기존 패턴(Testcontainers + fake fetcher) 승계 — 백필 컷오프, 두 스트림
  dedup, 고정 게시물 제외, 부분 실패 재시도
- 스모크(실과금, CI 금지): 소형 계정 1명으로 collect 1회 — 열거 페이지 수·raw 저장·추출 확인

## 이번 작업에서 하지 않는 것

- 분석 소스 계층 (스키마 포함) — raw 자기완결성으로 자리만 확보
- AdSignals 광고 판별 — 제거만, 이관은 분석 계층 때
- 추적 방문 자동 스케줄 최적화 — 수동 트리거로 시작, 주기·윈도우는 데이터 보고 결정
- 발굴 소스 다변화 — 기존 discover fetcher 그대로
