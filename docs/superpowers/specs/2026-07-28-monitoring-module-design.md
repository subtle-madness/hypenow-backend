# 모니터링 모듈 설계 — 시딩 캠페인 추이 추적

> 상태: 🟢 활성 · ✅ 구현됨(2026-07-29 — 트랙 MON, 개통 ops 대기)
> 작성: 2026-07-28

## 1. 배경·목적

브랜드가 인플루언서에게 시딩(협찬)을 맡기면, was(프론트)가 그 인플루언서의 계정 URL
또는 게시물 URL을 모니터링 대상으로 등록한다. 백엔드는 등록된 대상을 매일 크롤링해
지표 추이를 쌓고, was가 소비할 수 있는 형태로 제공한다.

- **계정 등록** = "시딩을 맡겼는데 게시물이 아직 안 올라온 상태". 프로필을 매일 감시하다
  **키워드 규칙(and/any/exclude 3종 목록 — §5)에 맞는 게시물이 올라오면 후보로
  감지**한다(예: 캡션에 '샤넬' 포함 ∧ '이벤트' 미포함). 감지는 자동 전환이 아니다 —
  FE에서 사용자가 후보를 **승인해야** 추적 대상이 프로필에서 그 게시물로 전환된다
  (오감지·무관 게시물 걸러내기).
- **게시물 등록** = 그 게시물 하나의 지표를 매일 스냅샷.
- 계정 대상은 상태와 무관하게 **프로필 지표 + 최근 릴스·피드 게시물 지표를 둘 다 매일
  스냅샷**한다 — 게시물 열거 응답이 키워드 매칭과 게시물 스냅샷에 함께 쓰인다.
- 게시물 지표는 **저장수·공유수·리포스트수·조회수·좋아요수·댓글수**(릴스·피드 공통) —
  HikerAPI(https://api.hikerapi.com/docs)의 미디어 계열 엔드포인트에서 취득.
- 등록 직후 첫 수집 결과가 수 초 내 프론트에 보여야 하고, 이후 매일 추이가 쌓인다.
- 대상은 **임의의 인스타 계정·게시물**(기존 뷰티 QUALIFIED 풀 밖 포함 — 브랜드 계정,
  비뷰티, 미발굴 계정 전부 가능).
- **등록(target)은 캠페인 단위다** — 같은 인플루언서 a를 브랜드 b(키워드 d)·브랜드
  c(키워드 e)가 각각 등록하면 target 2개가 생기고, 각자 자기 키워드·기간·상태
  기계·감지 후보를 가진다. 감지가 어느 캠페인 것인지는 target_id로 항상 명확하다.
- was에 주는 데이터는 일별 스냅샷 + 파생 집계(증감률 등).

핵심 구조 특성: 기존 파이프라인(발굴→수집→분석→서빙)은 단방향인데, 모니터링은
**수집 결과가 다시 수집 대상을 바꾸는 상태 기계**다(프로필 감시 → 게시물 추적 전환).
그래서 기존 층에 얹지 않고 자기 도메인으로 선다.

## 2. 결정 요약

| # | 결정 | 기각 대안 |
|---|---|---|
| 1 | **신규 `monitoring` 모듈** (4번째 Gradle 모듈, 별도 프로세스·컨테이너) | crawler 내 bounded context(사용자가 분리 명시 + crawler는 raw 쓰기 전용이라 서빙 적재 불가), analytics 편입(분석 층이 자체 수집원을 갖게 돼 층 정의 훼손) |
| 2 | **수집은 HikerAPI만** — 자체 얇은 Hiker 클라이언트 보유 | crawler 페처 재사용(모듈 간 Java 공유 금지 + SELF/Apify 기계 불필요라 유인 없음) |
| 3 | **명령(등록·승인·거절·연장·해지)은 내부 HTTP API, 조회는 was가 monitoring DB를 읽기 전용 SELECT** — API는 "층 사이는 DB로만" 원칙의 **명시적 예외**(명령 경로 한정), 읽기는 기존 analytics→was 패턴(생산자 정의 테이블·뷰 계약) 그대로 | 전부 DB(등록 시 동기 검증 불가), 전부 API(07-28 수정 — 단순 조회까지 monitoring 프로세스 가용성에 결합되고 조회 API 구현·유지 비용만 발생) |
| 4 | **monitoring 소유 단일 사설 DB** + 내부 2스키마(raw/public) — 원형만 스키마 분리 | 원형·서빙 두 DB 분리(외부 독자가 없어 실질 격리 이득 0, 운영 비용만 발생), analysis DB `monitoring` 스키마(was가 DB로 읽는 전제가 사라져 불필요), state/serving 추가 분리(상태도 API로 서빙되는 같은 소비자라 경계 근거 없음) |
| 5 | DB는 **기존 `postgres` 인스턴스(컨테이너)에 데이터베이스 신설** | 전용 Postgres 컨테이너(서버 메모리 제약 — 격리는 DB+계정 권한으로 이미 완성) |
| 6 | 감시(키워드 감지)·추적(추이) 모두 **일 1회 배치** + 등록 시 즉시 1회 수집 | 수 시간 간격 감지(비용 대비 불필요 — 주기는 런타임 설정으로 조정 가능하게 열어둠) |
| 7 | **모니터링 기간은 was(FE)가 등록 시 지정**, monitoring이 만료 판단·자동 종료 | 수동 해지만(FE 요구가 기간 설정) — 수동 해지도 병행 지원 |
| 8 | **감지 → 추적 전환은 FE 승인 게이트를 거친다** — 감지는 후보 축적일 뿐 상태 전이가 아니고, was의 승인 명령이 있어야 TRACKING 전환 | 감지 즉시 자동 전환(오감지·무관 게시물을 사용자가 걸러낼 수 없음) |
| 9 | **감지 이메일 알람 발송은 was — 매일 KST 09:00 고정 크론** — 수신자 매핑·이메일 주소·알람 on/off·발송 채널이 전부 app 스키마/was 소유. was 크론이 신규 감지 후보를 읽기 전용 SELECT로 조회(결정 3)해 알람 on 유저에게만 발송, 발송 워터마크는 app 스키마. monitoring은 감지 사실 기록까지 | monitoring 발송(PII·설정을 monitoring에 복제하거나 app 스키마를 읽어야 함 — 캡슐화 위반), monitoring→was 웹훅(감지 02:00·발송 09:00 고정이라 즉시성 이득 0, 역방향 의존만 추가) |
| 10 | **target은 캠페인(등록) 단위, 스냅샷은 관측 대상(계정·게시물) 단위** — 같은 인플루언서를 키워드가 다른 캠페인 여럿이 동시 감시 가능(감지 후보는 target 소속이라 귀속 모호성 없음), 수집·스냅샷은 계정당 1회로 공유(중복 크롤·중복 적재 없음) | target=인플루언서 단위(브랜드 b·c가 키워드 d·e로 같은 계정을 감시할 때 감지가 누구 캠페인인지 구분 불가), 스냅샷을 target별 중복 적재(같은 계정 N개 캠페인이면 N배 크롤·저장) |
| 11 | **명령 API 인증은 토큰이 아니라 전용 도커 네트워크(`monitoring-net`)** — was와 monitoring만 소속, 호스트 포트 미노출(`ports` 매핑 없음). 미소속 컨테이너(dev-was·crawler·caddy 등)는 DNS 해석부터 실패 → dev/운영 오배선이 connection error로 즉사(fail-closed), 유출될 공유 비밀 없음 | 정적 토큰(공유 비밀 — was가 뚫리면 토큰도 같이 뚫려 네트워크 격리 대비 추가 방어 0, env·검사 코드 관리 비용만. 07-29 대체), 무인증+평면 네트워크(dev-was 오배선이 조용히 운영 오염) |
| 12 | **dev 스택 편입 — `dev-monitoring` 신설** — dev-postgres에 monitoring DB, dev 전용 `dev-monitoring-net`(dev-was와 둘만), 스윕 크론 off(K 트랙 "dev 스케줄 전부 off" 원칙 — 등록 동기 수집만 동작, Hiker 실키 소량 사용) | dev 제외(dev-was의 모니터링 기능이 검증 불가 — 07-29 편입 결정) (07-29 트랙 W 리네임: dev-monitoring→test-monitoring·`:develop`→`:staging` — 정본은 ARCHITECTURE §7) |

### 통신 방식의 근거 (결정 3 상세)

기존 crawler↔analytics↔was 경계는 DB-only를 유지한다. monitoring 경계는 성격별 이원화:

- **명령은 API** — ①즉시성: 등록 요청-응답 안에서 계정 검증·첫 수집까지 동기로
  완결(Hiker 1~3초). ②상태 기계: 등록·승인·거절·연장·해지라는 명령·수명주기
  상호작용은 요청-응답 시맨틱이 자연스럽다. ③쓰기 캡슐화: monitoring DB에 쓰는
  주체는 monitoring 하나뿐 — 상태 전이 규칙이 한 곳에 산다.
- **조회는 DB 읽기 전용** — 단순 SELECT까지 API를 경유하면 조회가 monitoring 프로세스
  가용성에 결합되고 조회 API 구현·유지 비용만 는다. was에 **읽기 전용 계정**을 주되
  GRANT는 `public` 스키마의 조회 표면(테이블·뷰)만 — `raw` 스키마는 무권한(원형은
  was가 못 본다는 전역 불변식이 권한으로 강제되고, 2스키마 분리가 실질 권한 경계로
  기능한다). 읽기 계약은 생산자 monitoring이 정의 — analytics 분석 결과를 was가
  읽는 기존 패턴과 동일.

수용한 비용: 등록·승인 등 명령은 monitoring 프로세스 생사에 런타임 의존(영향 범위는
모니터링 기능에 국한 — 랭킹·상세 등 핵심 서빙 무관). 조회는 DB만 살아 있으면 되므로
monitoring이 재배포 중이어도 기존 추이 열람은 가능하다.

### "원형과 가공의 동거"가 맞는 이유 (결정 4 상세)

raw DB/analysis DB 분리의 진짜 근거는 데이터 성격이 아니라 **읽는 주체**(모듈 경계의
계약·접근 통제·부하 격리)다. monitoring은 원형·가공 모두 생산자·소비자가 자신 하나라
강제할 경계가 없다 — crawler DB에 원형(raw_profile jsonb)과 가공·상태(beauty_class,
content.status)가 동거하는 것과 같은 이치. 개념적 분리는 DB 안 스키마 경계로 유지하고,
볼륨 폭증 시 raw 스키마만 물리 분리하는 것은 설정 변경 수준으로 열려 있다.

## 3. 시스템 구조

```
프론트 ──HTTP──▶ was ──명령: 내부 HTTP──▶ monitoring ──HikerAPI──▶ 인스타
  (/v1/monitoring/…)  │  (전용 네트워크 monitoring-net     │
                      │   — was만 소속, 호스트 포트 없음)  └─읽기/쓰기─▶ monitoring DB
                      └──조회: SELECT (읽기 전용 계정) ─────────────▶   raw + public 2스키마
                                                                     (was는 public만 GRANT)
```

- was의 쓰기는 전부 monitoring API 명령 경유(직접 쓰기 불가 — 읽기 전용 계정),
  조회는 `public` 스키마 SELECT. 프론트는 monitoring의 존재를 모른다.
- 유저↔모니터링 대상 매핑(누가 등록했나)은 was 관심사 — was 소유 `app` 스키마에 저장.
- monitoring은 crawler DB·analysis DB에 아무 권한이 없다. 임의 계정이
  `influencer`/`content`/서빙 모수에 절대 섞이지 않는다(기존 파이프라인 무오염).

### 컨테이너 배치

| 컨테이너 | 변화 |
|---|---|
| `monitoring` (신설) | Spring Boot JVM, 컨테이너 포트 8083 — **호스트 포트 매핑 없음**, `monitoring-net` 전용 네트워크(was와 둘만 소속. 결정 11 — dev-analytics가 호스트 8083 점유 중이라 충돌도 회피) |
| `postgres` (기존) | `monitoring` 데이터베이스 신설 — 계정 2개: monitoring(전권), was(읽기 전용 — `public` 조회 표면만 GRANT, `raw` 무권한). 그 외 접근 불가(fail-closed), 크로스 DB 쿼리 불가 |
| `dev-monitoring` (신설 — dev 스택) | `:develop` 태그, dev-postgres의 monitoring DB, `dev-monitoring-net`(dev-was와 둘만), 스윕 크론 off (결정 12) |

`postgres-raw`가 아닌 `postgres` 인스턴스에 두는 이유: postgres-raw는 새벽 크롤 대량
쓰기·분석 스캔의 배치 성격, monitoring은 등록 즉시 응답하는 서빙 성격. (볼륨은 캠페인
수십~수백 건 규모라 어느 쪽이든 미미.)

## 4. monitoring DB — 2스키마

```
monitoring DB (쓰기는 monitoring 계정만, Flyway 이력 1개)
├── raw 스키마     원형. append-only, 재파싱·감지 오판 디버깅·필드 소급용 —
│   │              was 무권한(원형 비노출을 권한으로 강제) + 롤링 삭제 대상
│   └── fetch_payload(id, kind PROFILE/POSTS/POST, subject(username|short_code),
│                     fetched_at, http_status, payload jsonb)
└── public 스키마  가공 데이터 전부 — was 읽기 전용 GRANT(조회 계약 표면)
    │
    │  캠페인 단위 (등록마다 1행 — 같은 계정도 캠페인별 별도)
    ├── target(id, type ACCOUNT/POST, username, short_code, keyword_rule jsonb,
    │          status, tracked_short_code, tracked_since, registration_key,
    │          expires_at, registered_at, closed_at, last_fetched_at, fail_reason)
    ├── detected_candidate(id, target_id, short_code, detected_at,
    │                      caption_excerpt, status PENDING/APPROVED/REJECTED)
    │
    │  관측 대상 단위 (계정·게시물당 1행/일 — 캠페인 수와 무관, 공유)
    ├── profile_snapshot(username, captured_on, followers, following, media_count …)
    ├── post_snapshot(username, short_code, captured_on, content_type REELS/FEED,
    │                 likes, comments, views, saves, shares, reposts)
    └── 파생 집계   증감률·이동평균 — 입력이 전부 이 DB 안이므로 뷰로 정의
                    (analysis DB 파생 뷰 전례 — 미러 없이 항상 최신·과거 소급).
                    캠페인 화면용 조인(target→username/tracked_short_code→스냅샷)도
                    조회 뷰로 제공해 was가 내부 구조에 직접 의존하지 않게 한다
```

- **`target.id`(target_id)는 was↔monitoring 계약의 식별자** — 발급자(정본)는
  monitoring(등록 응답으로 전달), was는 `app` 스키마에 `(user_id, target_id)` 매핑으로
  **논리 참조만** 보관(FK·조인 없음 — `saved_influencers.handle` 관용구와 동일).
  ID는 불변·재사용 없음, 해지도 삭제가 아닌 상태 전이(CANCELED)라 참조가 끊기지
  않으며, 등록 중 크래시로 매핑이 유실돼도 멱등 등록(§8)으로 같은 ID를 재획득한다.
- **캠페인/관측의 이원 구조(결정 10)**: `target`·`detected_candidate`는 캠페인
  단위(브랜드 b·c가 같은 계정 a를 키워드 d·e로 각각 감시하면 target 2개, 감지 귀속
  명확), 스냅샷은 관측 대상 단위(같은 계정을 몇 캠페인이 보든 계정당 1행/일 — 수집도
  스윕에서 distinct username으로 그룹핑해 계정당 1회).
- state/serving을 더 가르지 않는 이유: 상태·후보도 was가 조회하는 같은 표면이다 —
  경계 양쪽의 접근 주체가 다르지 않으면 스키마 분리는 근거가 없다. 성격 차이
  (가변 상태 행 vs append-only 시계열)는 테이블 단위로 충분.
- 흐름: Hiker 응답을 `raw.fetch_payload`에 원형 그대로 → 같은 트랜잭션에서 파싱해
  `*_snapshot` 적재 → 활성 target별 키워드 매칭·상태 전이는 `target`·
  `detected_candidate` 갱신.
- 스냅샷 키는 `(username, captured_on)` / `(short_code, captured_on)` — 일 1회
  멱등(재실행 시 upsert).
- 원형 보존 정책은 사설이라 자유(예: 90일 롤링 삭제) — 구현 시 확정.

## 5. 상태 기계

```
등록(계정+키워드) → WATCHING ──키워드 감지──▶ 후보 기록(PENDING) · 감시는 지속
                      ▲                          │ was 승인(후보 선택)
                      │ was 거절(후보 기각)         ▼
등록(게시물) ──────────────────────────────────▶ TRACKING ──만료/해지──▶ EXPIRED/CANCELED
                   (수집 불가: 계정 소멸·비공개 등) ──▶ FAILED
```

- **WATCHING**: 프로필 지표 스냅샷 + 최근 릴스·피드 게시물 열거·**게시물 지표 스냅샷**
  (열거 응답 재사용) → 캡션 키워드 매칭. **키워드 규칙은 3종 목록**(각 0개 이상,
  AND·OR 중 최소 하나는 비어 있지 않아야 등록 가능):
  `and`(전부 포함) · `any`(하나 이상 포함) · `exclude`(하나라도 있으면 배제).
  매칭 = (and 전부 존재) ∧ (any 비었거나 하나 이상 존재) ∧ (exclude 전무).
  부분 문자열·대소문자 무시, 캡션 전문 대상(해시태그도 캡션의 일부라 자연 포함).
- **감지 = 후보 축적 (상태 전이 아님)**: 매칭된 게시물을 `detected_candidate`(PENDING)로
  기록하고 감시를 지속한다 — 승인 대기 중 다른 키워드 게시물이 올라오면 후보에 추가.
  같은 게시물은 한 번만 후보로 기록(재감지 시 중복 생성 없음).
  **등록 시각 이후 게시물만 감지 대상**(07-29 확정): 게시 시각(taken_at) ≥ 캠페인
  registered_at인 게시물만 키워드 매칭 — 등록 전의 옛 키워드 게시물이 첫 스윕에서
  후보로 뜨는 노이즈 차단. taken_at을 알 수 없는 게시물은 보수적으로 제외.
- **승인/거절 (was 명령)**: 승인하면 그 후보의 게시물로 TRACKING 전환(`tracked_short_code`
  확정, 승인 즉시 1회 수집 후 일별 스냅샷). 거절하면 그 후보만 REJECTED로 닫고 WATCHING
  지속. FE 통보는 was 조회 기반(목록에 "감지됨 — 승인 대기" 노출) + **감지 이메일
  알람** — 발송은 was(결정 9): 매일 09:00 크론이 신규 감지분을 SELECT로 가져와
  알람 on 유저에게만 발송. 푸시 알림은 범위 밖.
- **TRACKING**: 승인된(또는 직접 등록된) 게시물 지표 일별 스냅샷. 계정 대상은 전환
  후에도 프로필+최근 게시물 스냅샷을 계속 쌓고, 승인된 게시물이 최근 열거 범위에서
  밀려나도 개별 조회로 계속 추적한다.
- **만료**: was가 등록 시 `expires_at`을 넘기고, monitoring 일일 배치가 만료 판단 →
  수집 중단·EXPIRED. 수동 해지(CANCELED)·기간 연장(PATCH)도 지원. WATCHING 상태에서
  만료되면(끝내 승인된 게시물 없음) 그대로 EXPIRED.
- 일시 실패(Hiker 오류·타임아웃)는 상태 유지 + 다음 배치 재시도, 결정적 실패
  (404 계정 소멸 등)만 FAILED.

## 6. 계약 스케치

> was 개발자용 상세 계약(요청/응답 JSON·에러 어휘·조회 컬럼 정의·쿼리 예)은
> [docs/contracts/monitoring-was-contract.md](../../contracts/monitoring-was-contract.md)가
> 정본(living) — 아래는 설계 수준 요약.

### 명령 — monitoring 내부 API (was만 호출 — 전용 네트워크 `monitoring-net` 소속이 곧 인증, 토큰 없음)

| 메서드 | 경로 | 동작 |
|---|---|---|
| POST | `/api/targets` | 등록(멱등 키 `registration_key` 포함). **동기로 첫 Hiker 수집·검증까지 수행 후 응답**(계정 존재 확인 + 첫 스냅샷 포함 — was 타임아웃 여유 ~10s) |
| POST | `/api/targets/{id}/candidates/{candidateId}/approve` | 후보 승인 → TRACKING 전환(즉시 1회 수집 포함) |
| POST | `/api/targets/{id}/candidates/{candidateId}/reject` | 후보 기각 → WATCHING 지속 |
| PATCH | `/api/targets/{id}` | 기간 연장 등 |
| DELETE | `/api/targets/{id}` | 해지(CANCELED) |

### 조회 — was의 monitoring DB 읽기 전용 SELECT

- 대상: `public` 스키마의 조회 표면(target·detected_candidate·스냅샷·조회 뷰) —
  monitoring이 계약으로 정의·유지하고, 내부 재편 시 뷰로 호환 유지.
- 용례: 캠페인 목록·상태·후보(승인 대기 포함), 추이 화면, 09:00 이메일 알람 크론의
  신규 후보 조회(`detected_at > 워터마크`).
- monitoring DB 안 조인은 자유(같은 DB). 분석 결과·app 스키마와의 크로스 DB 조인은
  기존 규칙대로 금지 — 조합은 was 코드에서.

### was 공개 API (`/v1/monitoring/…`)

프론트 계약은 was가 정의(envelope·에러 공통 기존 규칙). 등록 응답에 monitoring의
동기 첫 수집 결과가 담기므로 프론트는 별도 폴링 없이도 즉시 결과를 받는다(이후
추이 화면은 일반 조회 — was가 SELECT로 서빙).

### 경계 총괄 (기존 계약 표에 추가)

| 경계 | 계약 | 정의하는 쪽 |
|---|---|---|
| was → monitoring (명령) | 내부 REST API | monitoring |
| monitoring → was (조회) | `public` 스키마 조회 표면(테이블·뷰) | monitoring |
| was → front | `/v1/monitoring` REST JSON | was |

## 7. 스케줄·비용

- 일 1회 배치(**KST 02:00** 크론)가 활성 대상 전체를 순회: WATCHING 감지 + TRACKING
  스냅샷 + 만료 처리. 주기·시각은 런타임 설정으로 조정 가능하게.
  (02:00은 기존 스택과 자원 경합 없음 — monitoring은 Hiker·자기 DB만 쓰므로
  crawler 새벽 윈도우·미러 04:30·분석 05:00과 독립.)
- 감지 이메일 알람은 was 소속 09:00 크론(결정 9) — monitoring 스케줄과 무관, 감지
  (02:00)와 발송(09:00) 사이 7시간 여유라 이벤트 전달 배선 불필요.
- Hiker 비용: **distinct 계정 기준**(같은 계정을 여러 캠페인이 감시해도 수집은 1회 —
  결정 10). 계정당 일 프로필 1콜 + 게시물 열거·지표 콜(저장·공유·리포스트까지 받는
  엔드포인트 조합에 따라 게시물별 개별 콜이 필요할 수 있음 — 구현 시 확정),
  게시물 대상은 일 1콜. 수백 계정 기준 감당 범위, 초과 성장 시 열거 범위 축소로 조절.
- 등록 시 즉시 1회 수집은 배치와 동일 코드 경로 재사용.

## 8. 실패 처리·운영

- **접근 통제**: 토큰 없음 — `monitoring-net` 전용 네트워크 소속(was뿐)이 곧 인증(결정 11).
  호스트 포트 미노출·Caddy 미노출. 미소속 컨테이너는 `monitoring` 호스트명 해석부터 실패.
  서버에서의 수동 디버깅은 `docker exec <was 컨테이너> curl http://monitoring:8083/...` 경유.
- **was 쪽**: monitoring 호출 타임아웃·5xx 시 프론트에 명확한 에러 전달. 등록 멱등은
  was가 생성해 넘기는 `registration_key` 기준 — 같은 키 재시도는 기존 target 반환
  (크래시 복구), 키가 다르면 같은 계정·키워드라도 별도 캠페인(결정 10과 정합).
- **배포**: CD 매트릭스에 monitoring 추가(was·analytics·crawler와 동일 경로).
  compose에 서비스 1개 + `postgres`에 DB·계정 생성(init 스크립트).
- **백업**: backup.sh에 monitoring DB pg_dump 추가.
- **알람**: 컨테이너 다운 알람 대상에 monitoring 추가(커스텀 메트릭 기존 경로).
- **메모리**: JVM ~0.5GB — dev 스택 포함 여유 실측 범위 내.

## 9. 테스트 전략

- Hiker 호출은 포트로 추상화해 테스트에서 fake(기존 "LLM 실 API 안 때림" 컨벤션).
- 상태 전이(감지→승인/거절·만료·실패 분기)는 단위 테스트로 고정 — 같은 계정을 보는
  캠페인 2개가 각자 키워드로 독립 감지되는 케이스 포함.
- DB 접점은 Testcontainers(2스키마 Flyway 적용 + was 읽기 계정의 raw 무권한 검증 포함).
- 명령 계약은 was 쪽 클라이언트 테스트(fake 서버) + monitoring 쪽 MockMvc,
  조회 계약은 was 조회 쿼리를 monitoring Flyway 스키마 위에서 실행하는 테스트로 고정.

## 10. 열린 항목 (구현 계획에서 확정)

- Hiker 엔드포인트·필드 매핑 확정 — 저장·공유·리포스트수를 포함하는 미디어 엔드포인트
  선정, 열거 응답만으로 6지표가 다 나오는지(안 나오면 게시물별 개별 콜) 실측.
- 원형 보존 기간, 게시물 열거 페이지 수(최근 N개 범위).
- 내부 API 에러 코드 어휘, was `/v1/monitoring` 응답 스키마 상세.

> **해소 현황 (2026-07-29 구현 시점 — 본문은 설계 당시 기록이라 불변, 아래가 결론이다)**
>
> - **Hiker 엔드포인트·필드 매핑 → 확정.** 실측 결과 v1 계열은 6지표 중 좋아요·댓글만 줘서
>   전량 **v2 계열로 교체**(`/v2/user/by/username` · `/v2/user/medias` · `/v2/media/by/code`).
>   `/v2/user/medias`가 릴스 재생수를 안 줘서 `/v2/user/clips` 보강 1콜을 더해 **계정당 3콜**.
>   정본: [plans/2026-07-28-monitoring-hiker-findings.md](../plans/2026-07-28-monitoring-hiker-findings.md).
> - **게시물 열거 페이지 수 → 기본 1페이지**(설정 `monitoring.enumerate-pages`로 조정 가능).
> - **내부 API 에러 코드 어휘 → 계약 v1.0에서 동결**
>   ([contracts/monitoring-was-contract.md](../../contracts/monitoring-was-contract.md) §2).
>   was `/v1/monitoring` 응답 스키마는 was 몫이라 이 트랙 범위 밖.
> - **미결(후속 과제)**: raw 원형(`raw.fetch_payload`) 보존 기간·롤링 삭제, 종결(EXPIRED·CANCELED·FAILED)
>   캠페인의 데이터 청소 정책. 둘 다 구현에 없다 — 데이터가 쌓이기 시작한 뒤 별도 결정.
