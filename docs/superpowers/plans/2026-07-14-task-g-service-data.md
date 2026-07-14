# 태스크 G — 서비스 데이터(`app` 스키마) + 후보 관리

> 상태: ✅ 구현/실행/반영됨

## 1. 범위

analysis DB에 `app` 스키마를 신설하고(was 소유 Flyway, 별도 이력 테이블), MVP 기획의
**후보 관리**(후보 저장·상태·메모)를 REST API로 구현한다. 로그인·인증은 §8 미결 유지 —
단 스키마가 확장(사용자별 후보)을 막지 않게 surrogate PK를 둔다.

## 2. 설계 결정 (검토한 대안 포함)

| 결정 | 선택 | 기각한 대안 / 근거 |
|---|---|---|
| 저장 접근 | JdbcClient (기존 was 패턴) | JPA 신규 도입 — was 전 패키지가 JdbcClient+record라 패턴 이원화·의존성 추가 손해. `app` 스키마 엔티티 자유(§4-4)는 유지되는 권리일 뿐 의무 아님 |
| 메모 구조 | `candidates.memo text` 단일 컬럼 | 메모 이력 테이블 — MVP 기획이 단수 "메모", YAGNI. 필요 시 `candidate_memos` 추가로 확장 |
| 상태 전이 규칙 | **자유 전이 + 동일 상태 거부**(400). 순서(검토중→컨택 예정→협업 중)는 enum에 라이프사이클 시맨틱으로만 정의 | 정방향 한 단계 강제 — 착오 승격을 되돌릴 수 없고 스킵(바로 협업 중)도 실사용에서 자연스러움. 규칙이 생기면 `CandidateService` 한 곳만 고침(§4-2) |
| 리소스 키 | API는 `handle`(프론트가 아는 키), 내부 PK는 surrogate id | handle PK — 로그인 도입 시 `owner_id` 추가·unique(owner_id, handle) 전환을 id PK가 수월하게 함 |
| 인플루언서 참조 | 논리 참조만(문자열 handle) | FK — 분석 결과와 서비스 데이터는 물리 분리 대비로 조인·FK 금지(§4-4). 분석 결과에 없는 handle도 저장 허용(선탐색 후보) |
| 상태 저장 | text + CHECK (영문 코드) | 한글 라벨 저장 — 코드/라벨 분리, 라벨은 프론트 소관 |
| Flyway 이력 | `app.flyway_schema_history_app` (schemas=app) | analytics 이력(public.flyway_schema_history)과 테이블·스키마 모두 분리 — 공유 dev DB 충돌 없음 |

## 3. 스키마 (V1 — was 소유)

```sql
CREATE TABLE app.candidates (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    handle     text NOT NULL UNIQUE,          -- 인플루언서 handle — 분석 결과와 논리 참조만
    status     text NOT NULL DEFAULT 'REVIEWING'
               CHECK (status IN ('REVIEWING', 'CONTACT_PLANNED', 'COLLABORATING')),
    memo       text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
```

상태 코드 ↔ 라벨: `REVIEWING`=검토중 · `CONTACT_PLANNED`=컨택 예정 · `COLLABORATING`=협업 중.

## 4. REST 계약 (초안 — celfit-front 후보 관리 UI 계약 부재로 was가 설계)

handle은 저장 시 정규화: trim + 선행 `@` 제거 + 소문자.

| 메서드 | 경로 | 요청 | 응답 | 오류 |
|---|---|---|---|---|
| POST | `/api/candidates` | `{handle, memo?}` | 201 + 후보 JSON | 400 handle 공백 · 409 중복 |
| GET | `/api/candidates?status=` | — | 200 `{items:[후보…]}` (updated_at desc) | 400 잘못된 status |
| GET | `/api/candidates/{handle}` | — | 200 후보 JSON | 404 |
| PUT | `/api/candidates/{handle}/status` | `{status}` | 200 갱신된 후보 | 400 무효/동일 상태 · 404 |
| PUT | `/api/candidates/{handle}/memo` | `{memo}` (null=삭제) | 200 갱신된 후보 | 404 |
| DELETE | `/api/candidates/{handle}` | — | 204 | 404 |

후보 JSON: `{id, handle, status, memo, createdAt, updatedAt}`.

## 5. 구현 자리

- `was/config/FlywayConfig` — was 유일 Flyway(analysis DB, schemas=`app`, table=`flyway_schema_history_app`).
  분석 결과 스키마(public)는 건드리지 않는다.
- `was/candidate/` — `CandidateStatus`(enum, was 로컬 — 생산자+소비자 쌍 미성립이라 계약 모듈 아님),
  `Candidate`(행 record), `CandidateRepository`(JdbcClient, `app.candidates` 한정),
  `CandidateService`(상태 전이·트랜잭션 — §4-2), `CandidateController` + 요청/응답 record(+정적 `from()`).
- `WebConfig` CORS: 후보 API가 쓰기 메서드를 쓰므로 GET 한정 → GET·POST·PUT·DELETE로 확장.

## 6. 테스트

- `CandidateRepositoryTest` (IntegrationTest/Testcontainers) — **실제 V1 마이그레이션이 만든 스키마**로
  CRUD 왕복 검증(was가 Flyway를 소유하므로 손 DDL 불필요 — 분석 결과 테이블 테스트와 다른 점).
- `CandidateServiceTest` — 상태 전이 규칙(동일 상태 거부·자유 전이)·정규화 단위 검증.
- `CandidateControllerTest` (@WebMvcTest) — JSON 계약·400/404/409.
