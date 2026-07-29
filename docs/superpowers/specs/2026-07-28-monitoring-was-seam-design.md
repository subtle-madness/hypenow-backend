# was ↔ monitoring 통신 계층 (seam) 설계

> 상태: 🟢 활성 · 구현 착수 전 (2026-07-28 브레인스토밍 승인분)
>
> 계약 정본: monitoring 모듈 소유. was가 참조한 스냅샷은
> [docs/contracts/monitoring-was-contract.md](../../contracts/monitoring-was-contract.md) (v0.1 + 07-28 토큰 제거 델타).

## 1. 배경·범위

마케팅 모니터링 시스템(캠페인 단위 인스타 계정/게시물 감시)을 도입한다. monitoring은
**별도 컨테이너 서버**로 이 repo 밖에서 구현되며(구현 미착수), was와의 인터페이스는 계약
문서가 정의한다: **쓰기=monitoring 내부 명령 API 5개**(등록·승인·거절·연장·해지),
**읽기=monitoring DB `public` 스키마 SELECT**(조회 API 없음, 읽기 전용 계정).

이번 작업 범위 (사용자 확정):

- **포함** — was 쪽 통신 계층 전부: 명령 클라이언트, monitoring DB 읽기 전용 조회 계층,
  `app` 스키마 매핑 테이블(user×target×멱등키) 마이그레이션, 오케스트레이션 서비스.
- **제외** — 프론트용 `/v1/monitoring/**` 컨트롤러(프론트 API 스펙 수령 후 별도 작업),
  09:00 이메일 알람 크론(발송 내용이 프론트 기획에 의존), monitoring 서버 자체.

접근안은 3안 비교 후 **1안(정식 seam 계층: 멱등키 선저장 + 조건부 활성화)** 채택.
기각: 2안 "호출 성공 후 매핑 저장"(크래시 시 고아 target + 재시도가 새 키로 나가 중복
캠페인 — 계약이 멱등키를 둔 이유가 무력화됨), 3안 "클라이언트 최소형"(범위 결정과 불일치).

## 2. 구성요소 (was `monitoring` 평탄 패키지)

| 구성요소 | 역할 |
|---|---|
| `MonitoringProperties` | `monitoring.enabled` / `api.base-url` / `datasource.*` 바인딩 |
| `MonitoringConfig` | `@ConditionalOnProperty(monitoring.enabled)` — RestClient·monitoring JdbcClient 빈 조립 |
| `MonitoringCommandClient` | 명령 5개 HTTP 호출 + 에러 승격 |
| `MonitoringReadRepository` | monitoring DB 베이스 테이블 4개 SELECT |
| `MonitoringCampaignService` | user_id 기준 오케스트레이션 (키 선저장·소유 검증) |
| `MonitoringCampaignMappingRepository` | `app.monitoring_campaigns` CRUD (기존 primary JdbcClient) |
| app Flyway `V13__monitoring_campaigns.sql` | 매핑 테이블 DDL (V12까지 사용 중 — 번호는 머지 직전 재확인) |

인증: 없음 — monitoring API는 도커 내부망 전용(Caddy 미노출)이라 정적 토큰을 제거하기로
결정(07-28). 이에 따라 `UNAUTHORIZED`/`TOKEN_UNSET` 에러 어휘도 계약에서 빠졌다.

## 3. 두 번째 DataSource — 자동구성 back-off 회피

monitoring DataSource를 **스프링 빈으로 등록하면 안 된다.** Spring Boot의
`DataSourceAutoConfiguration`은 DataSource 빈이 하나라도 정의되면 back-off 하므로, 기존
analysis DB 기본 연결(세션 JDBC·app Flyway 포함)이 깨진다. 따라서:

- monitoring용 `HikariDataSource`와 그 위의 `JdbcClient` **둘 다 빈으로 노출하지 않는다** —
  `JdbcClientAutoConfiguration`도 JdbcClient 빈 존재 시 back-off 하므로, JdbcClient를 빈으로
  두면 기존 리포지토리 전부의 타입 주입이 모호해진다. `MonitoringConfig` 내부에서 직접
  생성해 도메인 빈 3개(`MonitoringCommandClient`·`MonitoringReadRepository`·
  `MonitoringCampaignService`)만 노출. close는 config `@PreDestroy`.
- `monitoring.enabled=false`(기본) → 오늘과 완전히 동일. `true`여도 기존 자동구성 무손상.
- 접속 계정은 계약대로 읽기 전용(`public` 스키마 SELECT만 GRANT) — 쓰기 시도는 DB 권한
  오류로 fail-closed(의도된 동작, was에서 방어 로직 불필요).
- 풀 크기 최소(max 2~3) — 조회 전용·저트래픽.
- 커넥션 풀은 지연 초기화(`initializationFailTimeout=-1`) — monitoring DB 장애·오설정이 was
  부팅을 막지 않는다(부가 서브시스템이 본체를 끌어내리지 않게, 07-20 전면 500 교훈). 오설정은
  첫 조회 시점에 드러난다 (최종 리뷰 반영).

## 4. 명령 클라이언트

- RestClient 단일 인스턴스. 타임아웃 connect 2s / read 10s — 계약 권고(등록·즉시수집 승인
  10s, 나머지 5s)를 최대치 하나로 단일화. 명령별 구분은 실익 대비 복잡도라 생략 (결정).
- 메서드: `register(RegisterRequest)`(ACCOUNT/POST 공용), `approve(targetId, candidateId)`,
  `reject(targetId, candidateId)`, `extend(targetId, expiresAt)`, `cancel(targetId)`.
  요청·응답 DTO는 계약 JSON 그대로의 record + Jackson 3(`tools.jackson.*`).
- **에러 2계열** (호출자가 구분해야 하는 유일한 축은 "재시도 가능성"):
  - monitoring이 준 에러 바디 `{code, message}` → `MonitoringApiException(code, message,
    httpStatus)`. code 어휘는 **해석·분기 없이 그대로 담아 위로** — 프론트 어휘 변환은
    나중 컨트롤러 몫 ("분류값·라벨은 생산자가 확정" 원칙).
  - 전송 실패(연결 불가·타임아웃·바디 없는 5xx) → `MonitoringUnavailableException` —
    "같은 registrationKey로 재시도 가능" 신호.

## 5. 조회 계층

- 조회 대상은 계약이 확정한 **베이스 테이블 4개만**: `target`, `detected_candidate`,
  `profile_snapshot`, `post_snapshot`. 초안 뷰(`v_target_overview`,
  `v_target_timeseries`)는 monitoring 쪽 확정 후 후속 반영.
- DTO는 계약 §3 컬럼명 그대로의 record. 지표 6종 null 허용(피드 조회수 등) 준수.
- 메서드는 알려진 플로우 역산으로 최소만:
  - `findTargets(Collection<Long> ids)` — 캠페인 목록 대비
  - `findCandidates(targetId)` — 후보 목록
  - `findPendingCandidatesSince(Instant)` — 이메일 알람 크론 대비 (계약 §3 예시 쿼리)
  - `profileTimeseries(username)` / `postTimeseries(targetId)` — 추이 (후자는 계약 예시의
    `tracked_short_code` 서브쿼리 그대로)
- monitoring DB **안**에서의 조인은 자유, `app` 스키마·분석 결과와의 크로스 DB 조인은
  기존 규칙대로 금지(조합은 was 코드에서).

## 6. 오케스트레이션 + app 매핑 테이블

`app.monitoring_campaigns`:

```sql
id               bigserial PK
user_id          bigint NOT NULL REFERENCES app.users(id)  -- 같은 스키마 내 FK는 허용 (선례: signup_codes.used_by)
registration_key uuid   NOT NULL UNIQUE    -- was가 생성하는 멱등 키
target_id        bigint NULL               -- monitoring target 논리 참조 (크로스 DB — FK 금지)
created_at       timestamptz NOT NULL
```

알람 발송 워터마크 컬럼은 이메일 크론 작업 때 후속 마이그레이션으로 (YAGNI).

- **등록(2단계)**: 키 생성 → 매핑 INSERT(target_id NULL) → `POST /api/targets` →
  target_id UPDATE → 결과 반환. 전송 실패 시 **같은 키로 즉시 1회 재시도**(멱등 replay
  200 활용), 그래도 실패면 pending 행(target_id NULL) 유지한 채 예외 전파.
- **승인/거절/연장**: `(user_id, target_id)` 매핑 존재 확인(소유 검증, 없으면 not-found)
  → 클라이언트 위임.
- **삭제**: `cancel` 호출 성공(멱등 — 이미 종결이어도 200) 후 매핑 행 DELETE. 이 순서
  고정으로 "monitoring엔 살아있는데 매핑만 사라진" 상태를 방지. 계약 §5대로 target 행은
  monitoring에서 사라지지 않는다(상태 전이만).

### 알려진 한계 (의도된 보류)

- 즉시 재시도까지 실패했는데 monitoring엔 target이 생긴 경우(응답 유실) 고아 target이
  가능하다. pending 행이 남아 있으므로, 프론트 API 설계 때 같은 요청의 재시도가 pending
  행의 키를 재사용하도록 마저 닫는다. pending 행 청소 잡도 그때 함께 판단.
- 탈퇴(deleteAccount)는 users FK CASCADE로 매핑을 cancel 없이 지운다 — monitoring target은
  고아가 되지만 expires_at 자연 만료로 수렴(유계). 탈퇴 시 cancel 루프는 후속(§8).

## 7. 테스트·검증

- **클라이언트**: MockRestServiceServer — 정상 응답 파싱, 에러 code 승격, 타임아웃→
  `MonitoringUnavailableException`.
- **조회**: Testcontainers PostgreSQL + 계약 §3에서 유도한 DDL 픽스처(테스트 리소스
  `monitoring-schema.sql`). ⚠️ 계약이 v0.1 초안이라 **픽스처 표류 위험** — monitoring
  구현 확정 시 실제 스키마와 대조하는 후속 작업 필요.
- **서비스**: 2단계 등록 정합(선저장→확정), replay 재시도, 소유 검증, 삭제 순서.
- **마이그레이션**: V13은 기존 app Flyway 테스트 경로로 실적용.
- **비활성 기본값**: 기존 테스트 전체가 `monitoring.enabled` 미설정으로 도는 것 자체가
  "무영향" 회귀 검증.

## 8. 문서·후속

- 계약 스냅샷은 `docs/contracts/monitoring-was-contract.md` — 정본 갱신 시 사본 교체.
- ARCHITECTURE §5(작업 트랙)·§7(결정 기록) 갱신은 구현 PR에서 함께.
- 탈퇴 시 캠페인 cancel 루프
- 조회 표면의 유저 스코프 가드(컨트롤러 — MonitoringReadRepository Javadoc 참조)
- 이메일 크론용 역방향 조회 findByTargetIds(Collection) 추가
- 후속(이 설계 밖): 프론트용 `/v1/monitoring/**` 컨트롤러(프론트 스펙 수령 후), 이메일
  알람 크론(+워터마크 마이그레이션), 초안 뷰 확정 반영, DDL 픽스처 대조.
