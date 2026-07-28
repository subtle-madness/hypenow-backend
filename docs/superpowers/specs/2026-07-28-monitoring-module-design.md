# 모니터링 모듈 설계 — 시딩 캠페인 추이 추적

> 상태: 🟢 활성 · 설계 확정 (구현 미착수)
> 작성: 2026-07-28

## 1. 배경·목적

브랜드가 인플루언서에게 시딩(협찬)을 맡기면, was(프론트)가 그 인플루언서의 계정 URL
또는 게시물 URL을 모니터링 대상으로 등록한다. 백엔드는 등록된 대상을 매일 크롤링해
지표 추이를 쌓고, was가 소비할 수 있는 형태로 제공한다.

- **계정 등록** = "시딩을 맡겼는데 게시물이 아직 안 올라온 상태". 프로필을 매일 감시하다
  **키워드(예: 캡션에 '샤넬')가 담긴 게시물이 올라오면 후보로 감지**한다. 감지는 자동
  전환이 아니다 — FE에서 사용자가 후보를 **승인해야** 추적 대상이 프로필에서 그
  게시물로 전환된다(오감지·무관 게시물 걸러내기).
- **게시물 등록** = 그 게시물 하나의 지표(좋아요·댓글·조회수)를 매일 스냅샷.
- 등록 직후 첫 수집 결과가 수 초 내 프론트에 보여야 하고, 이후 매일 추이가 쌓인다.
- 대상은 **임의의 인스타 계정·게시물**(기존 뷰티 QUALIFIED 풀 밖 포함 — 브랜드 계정,
  비뷰티, 미발굴 계정 전부 가능).
- was에 주는 데이터는 일별 스냅샷 + 파생 집계(증감률 등).

핵심 구조 특성: 기존 파이프라인(발굴→수집→분석→서빙)은 단방향인데, 모니터링은
**수집 결과가 다시 수집 대상을 바꾸는 상태 기계**다(프로필 감시 → 게시물 추적 전환).
그래서 기존 층에 얹지 않고 자기 도메인으로 선다.

## 2. 결정 요약

| # | 결정 | 기각 대안 |
|---|---|---|
| 1 | **신규 `monitoring` 모듈** (4번째 Gradle 모듈, 별도 프로세스·컨테이너) | crawler 내 bounded context(사용자가 분리 명시 + crawler는 raw 쓰기 전용이라 서빙 적재 불가), analytics 편입(분석 층이 자체 수집원을 갖게 돼 층 정의 훼손) |
| 2 | **수집은 HikerAPI만** — 자체 얇은 Hiker 클라이언트 보유 | crawler 페처 재사용(모듈 간 Java 공유 금지 + SELF/Apify 기계 불필요라 유인 없음) |
| 3 | **was ↔ monitoring은 내부 HTTP API** — "층 사이는 DB로만" 원칙의 **명시적 예외** | DB 폴링/LISTEN-NOTIFY(등록 시 동기 검증 불가 — 사용자 결정으로 API 채택) |
| 4 | **monitoring 소유 단일 사설 DB** + 내부 2스키마(raw/public) — 원형만 스키마 분리 | 원형·서빙 두 DB 분리(외부 독자가 없어 실질 격리 이득 0, 운영 비용만 발생), analysis DB `monitoring` 스키마(was가 DB로 읽는 전제가 사라져 불필요), state/serving 추가 분리(상태도 API로 서빙되는 같은 소비자라 경계 근거 없음) |
| 5 | DB는 **기존 `postgres` 인스턴스(컨테이너)에 데이터베이스 신설** | 전용 Postgres 컨테이너(서버 메모리 제약 — 격리는 DB+계정 권한으로 이미 완성) |
| 6 | 감시(키워드 감지)·추적(추이) 모두 **일 1회 배치** + 등록 시 즉시 1회 수집 | 수 시간 간격 감지(비용 대비 불필요 — 주기는 런타임 설정으로 조정 가능하게 열어둠) |
| 7 | **모니터링 기간은 was(FE)가 등록 시 지정**, monitoring이 만료 판단·자동 종료 | 수동 해지만(FE 요구가 기간 설정) — 수동 해지도 병행 지원 |
| 8 | **감지 → 추적 전환은 FE 승인 게이트를 거친다** — 감지는 후보 축적일 뿐 상태 전이가 아니고, was의 승인 명령이 있어야 TRACKING 전환 | 감지 즉시 자동 전환(오감지·무관 게시물을 사용자가 걸러낼 수 없음) |

### API 예외의 근거 (결정 3 상세)

기존 crawler↔analytics↔was 경계는 DB-only를 유지한다. monitoring만 API 경계인 이유:

- **즉시성**: 등록 요청-응답 안에서 계정 검증·첫 수집까지 동기로 완결(Hiker 1~3초).
- **상태 기계**: 등록·해지·전환·만료라는 명령/수명주기 상호작용이 많아 요청-응답 시맨틱이 자연스럽다.
- **데이터 완전 캡슐화**: was가 monitoring DB를 아예 안 보므로 monitoring DB는 사설
  구현 세부가 된다 — 스키마 변경이 API 계약만 지키면 무파급(database-per-service).

수용한 비용: 모니터링 기능의 등록·조회가 monitoring 프로세스 생사에 런타임 의존
(영향 범위는 모니터링 기능에 국한 — 랭킹·상세 등 핵심 서빙 무관).

### "원형과 가공의 동거"가 맞는 이유 (결정 4 상세)

raw DB/analysis DB 분리의 진짜 근거는 데이터 성격이 아니라 **읽는 주체**(모듈 경계의
계약·접근 통제·부하 격리)다. monitoring은 원형·가공 모두 생산자·소비자가 자신 하나라
강제할 경계가 없다 — crawler DB에 원형(raw_profile jsonb)과 가공·상태(beauty_class,
content.status)가 동거하는 것과 같은 이치. 개념적 분리는 DB 안 스키마 경계로 유지하고,
볼륨 폭증 시 raw 스키마만 물리 분리하는 것은 설정 변경 수준으로 열려 있다.

## 3. 시스템 구조

```
프론트 ──HTTP──▶ was ──내부 HTTP──▶ monitoring ──HikerAPI──▶ 인스타
  (/v1/monitoring/…)   (도커 내부망,        │
                        정적 토큰,          └─읽기/쓰기─▶ monitoring DB (사설)
                        Caddy 미노출)              raw + public 2스키마
```

- was는 프론트 요청을 받아 monitoring API를 호출·조합해 내려줄 뿐, monitoring DB에
  접속 계정 자체가 없다. 프론트는 monitoring의 존재를 모른다.
- 유저↔모니터링 대상 매핑(누가 등록했나)은 was 관심사 — was 소유 `app` 스키마에 저장.
- monitoring은 crawler DB·analysis DB에 아무 권한이 없다. 임의 계정이
  `influencer`/`content`/서빙 모수에 절대 섞이지 않는다(기존 파이프라인 무오염).

### 컨테이너 배치

| 컨테이너 | 변화 |
|---|---|
| `monitoring` (신설) | Spring Boot JVM, 포트 8083 (crawler 8080 · was 8081 · analytics 8082 다음) |
| `postgres` (기존) | `monitoring` 데이터베이스 신설 — 전용 계정만 접근(fail-closed), 크로스 DB 쿼리 불가로 격리 완성 |

`postgres-raw`가 아닌 `postgres` 인스턴스에 두는 이유: postgres-raw는 새벽 크롤 대량
쓰기·분석 스캔의 배치 성격, monitoring은 등록 즉시 응답하는 서빙 성격. (볼륨은 캠페인
수십~수백 건 규모라 어느 쪽이든 미미.)

## 4. monitoring DB — 2스키마

```
monitoring DB (monitoring 계정만 접근, Flyway 이력 1개)
├── raw 스키마     원형. append-only, 재파싱·감지 오판 디버깅·필드 소급용 —
│   │              수명·용도가 달라(롤링 삭제 대상) 유일하게 스키마로 격리
│   └── fetch_payload(id, target_id, kind PROFILE/POSTS/POST, fetched_at,
│                     http_status, payload jsonb)
└── public 스키마  가공 데이터 전부 — 상태 기계·시계열·파생 집계
    ├── target(id, type ACCOUNT/POST, username, short_code, keyword,
    │          status, tracked_short_code, tracked_since,
    │          expires_at, registered_at, closed_at, last_fetched_at, fail_reason)
    ├── detected_candidate(id, target_id, short_code, detected_at,
    │                      caption_excerpt, status PENDING/APPROVED/REJECTED)
    ├── profile_snapshot(target_id, captured_on, followers, following, media_count …)
    ├── post_snapshot(target_id, short_code, captured_on, likes, comments, views)
    └── 파생 집계   증감률·이동평균 — 입력이 전부 이 DB 안이므로 뷰로 정의
                    (analysis DB 파생 뷰 전례 — 미러 없이 항상 최신·과거 소급)
```

- state/serving을 더 가르지 않는 이유: 상태·후보도 was API 응답에 그대로 나가는
  데이터라 소비자가 같다 — 경계 양쪽의 접근 주체가 다르지 않으면 스키마 분리는
  근거가 없다. 성격 차이(가변 상태 행 vs append-only 시계열)는 테이블 단위로 충분.
- 흐름: Hiker 응답을 `raw.fetch_payload`에 원형 그대로 → 같은 트랜잭션에서 파싱해
  `*_snapshot` 적재 → 키워드 매칭·상태 전이는 `target`·`detected_candidate` 갱신.
- 스냅샷 키는 `(target_id, captured_on)` — 일 1회 멱등(재실행 시 upsert).
- 원형 보존 정책은 사설이라 자유(예: 90일 롤링 삭제) — 구현 시 확정.

## 5. 상태 기계

```
등록(계정+키워드) → WATCHING ──키워드 감지──▶ 후보 기록(PENDING) · 감시는 지속
                      ▲                          │ was 승인(후보 선택)
                      │ was 거절(후보 기각)       ▼
등록(게시물) ────────────────────────────▶ TRACKING ──만료/해지──▶ EXPIRED/CANCELED
                   (수집 불가: 계정 소멸·비공개 등) ──▶ FAILED
```

- **WATCHING**: 프로필 지표 스냅샷 + 최근 게시물 열거 → 캡션 키워드 매칭(부분 문자열,
  대소문자 무시. 키워드 1개 이상 등록 시 OR 매칭 — 세부는 구현 시 조정).
- **감지 = 후보 축적 (상태 전이 아님)**: 매칭된 게시물을 `detected_candidate`(PENDING)로
  기록하고 감시를 지속한다 — 승인 대기 중 다른 키워드 게시물이 올라오면 후보에 추가.
  같은 게시물은 한 번만 후보로 기록(재감지 시 중복 생성 없음).
- **승인/거절 (was 명령)**: 승인하면 그 후보의 게시물로 TRACKING 전환(`tracked_short_code`
  확정, 승인 즉시 1회 수집 후 일별 스냅샷). 거절하면 그 후보만 REJECTED로 닫고 WATCHING
  지속. FE 통보는 was 조회 기반(목록에 "감지됨 — 승인 대기" 노출) — 푸시 알림은 범위 밖.
- **TRACKING**: 승인된(또는 직접 등록된) 게시물 지표 일별 스냅샷. 전환 후에도 프로필
  스냅샷을 계속 쌓을지는 구현 시 결정(기본: 게시물만).
- **만료**: was가 등록 시 `expires_at`을 넘기고, monitoring 일일 배치가 만료 판단 →
  수집 중단·EXPIRED. 수동 해지(CANCELED)·기간 연장(PATCH)도 지원. WATCHING 상태에서
  만료되면(끝내 승인된 게시물 없음) 그대로 EXPIRED.
- 일시 실패(Hiker 오류·타임아웃)는 상태 유지 + 다음 배치 재시도, 결정적 실패
  (404 계정 소멸 등)만 FAILED.

## 6. API 계약 스케치

### monitoring 내부 API (was만 호출 — 정적 토큰 인증, 외부 미노출)

| 메서드 | 경로 | 동작 |
|---|---|---|
| POST | `/api/targets` | 등록. **동기로 첫 Hiker 수집·검증까지 수행 후 응답**(계정 존재 확인 + 첫 스냅샷 포함 — was 타임아웃 여유 ~10s) |
| GET | `/api/targets/{id}` | 상태 + 최신 스냅샷 + 감지 후보 목록(PENDING 포함) |
| GET | `/api/targets/{id}/timeseries` | 일별 추이 + 파생 집계 |
| POST | `/api/targets/{id}/candidates/{candidateId}/approve` | 후보 승인 → TRACKING 전환(즉시 1회 수집 포함) |
| POST | `/api/targets/{id}/candidates/{candidateId}/reject` | 후보 기각 → WATCHING 지속 |
| PATCH | `/api/targets/{id}` | 기간 연장 등 |
| DELETE | `/api/targets/{id}` | 해지(CANCELED) |

### was 공개 API (`/v1/monitoring/…`)

프론트 계약은 was가 정의(envelope·에러 공통 기존 규칙). 등록 응답에 monitoring의
동기 첫 수집 결과가 담기므로 프론트는 별도 폴링 없이도 즉시 결과를 받는다(이후
추이 화면은 일반 조회).

### 경계 총괄 (기존 계약 표에 추가)

| 경계 | 계약 | 정의하는 쪽 |
|---|---|---|
| was → monitoring | 내부 REST API | monitoring |
| was → front | `/v1/monitoring` REST JSON | was |

## 7. 스케줄·비용

- 일 1회 배치(**KST 02:00** 크론)가 활성 대상 전체를 순회: WATCHING 감지 + TRACKING
  스냅샷 + 만료 처리. 주기·시각은 런타임 설정으로 조정 가능하게.
  (02:00은 기존 스택과 자원 경합 없음 — monitoring은 Hiker·자기 DB만 쓰므로
  crawler 새벽 윈도우·미러 04:30·분석 05:00과 독립.)
- Hiker 비용: 대상당 일 1~2콜(프로필 or 게시물) — 수백 대상 기준 무시 가능 수준.
- 등록 시 즉시 1회 수집은 배치와 동일 코드 경로 재사용.

## 8. 실패 처리·운영

- **인증**: 내부 API는 사전 공유 정적 토큰(`MANUAL_DISCOVERY_TOKEN` 전례), 미설정 시
  503 fail-closed. Caddy에 미노출 — 도커 내부 네트워크만.
- **was 쪽**: monitoring 호출 타임아웃·5xx 시 프론트에 명확한 에러 전달(등록 재시도는
  멱등 — 같은 대상 재등록은 기존 target 반환).
- **배포**: CD 매트릭스에 monitoring 추가(was·analytics·crawler와 동일 경로).
  compose에 서비스 1개 + `postgres`에 DB·계정 생성(init 스크립트).
- **백업**: backup.sh에 monitoring DB pg_dump 추가.
- **알람**: 컨테이너 다운 알람 대상에 monitoring 추가(커스텀 메트릭 기존 경로).
- **메모리**: JVM ~0.5GB — dev 스택 포함 여유 실측 범위 내.

## 9. 테스트 전략

- Hiker 호출은 포트로 추상화해 테스트에서 fake(기존 "LLM 실 API 안 때림" 컨벤션).
- 상태 전이(감지→전환·만료·실패 분기)는 단위 테스트로 고정.
- DB 접점은 Testcontainers(3스키마 Flyway 적용 검증 포함).
- was↔monitoring 계약은 was 쪽 클라이언트 테스트(fake 서버) + monitoring 쪽 MockMvc.

## 10. 열린 항목 (구현 계획에서 확정)

- 키워드 매칭 세부(복수 키워드 AND/OR, 해시태그 포함 여부).
- TRACKING 전환 후 프로필 스냅샷 지속 여부.
- 원형 보존 기간, 게시물 열거 페이지 수(최근 N개 범위).
- 내부 API 에러 코드 어휘, was `/v1/monitoring` 응답 스키마 상세.
