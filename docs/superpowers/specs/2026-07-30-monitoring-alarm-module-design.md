# 모니터링 알람 모듈 + 승인 플로우 제거 (계약 v2) 설계

> 상태: 🟢 활성 · 구현 착수 전 (2026-07-30 승인 대기)
>
> 대체: [2026-07-29-monitoring-email-alarm-design.md](2026-07-29-monitoring-email-alarm-design.md)
> (was 크론 방식 — PR #183 미머지 폐기. 알람 소유가 monitoring으로 이동하며 전면 재설계).
> 계약 정본: [docs/contracts/monitoring-was-contract.md](../../contracts/monitoring-was-contract.md)
> — 이 트랙에서 **v2.0**으로 개정.

## 1. 배경·사용자 결정 (2026-07-29~30)

프론트 알림 설정 화면이 확정되며 방향이 바뀌었다:

1. **승인 플로우 제거** — 감지되면 승인 없이 바로 수집 시작. 단 **첫 감지 1건만 자동
   수집**(캠페인:추적 게시물 = 1:1 유지, `tracked_short_code` 단수 구조 보존).
2. **알람은 monitoring 서버의 알람 모듈이 소유** — was는 알람 경로에서 완전히 빠진다.
   수신자 해석을 위해 **`target.user_id`를 monitoring이 저장**(등록 API에 userId 추가).
3. **알람 이벤트 4종, 제외 없음**:
   | event_type | 화면 문구 | 발생 |
   |---|---|---|
   | `COLLECTION_STARTED` | 게시물 수집 시작 | 게시물 직접 등록(즉시) + 스윕 자동 전환(첫 감지) |
   | `COLLECTION_ENDED` | 게시물 수집 종료 | 기간 만료(EXPIRED 전이) |
   | `METRICS_HIDDEN` | 일부 지표 비공개 | 스냅샷 지표 non-null→null 전환 |
   | `CONTENT_UNAVAILABLE` | 콘텐츠 비공개/삭제/수집 오류 | FAILED 전이(계정/게시물 삭제·비공개 — 재시도로 해소 불가한 결정적 실패) |
4. **일시 오류(Hiker 5xx·타임아웃)는 알람이 아니라 재시도 대상** — 시스템이 당일 안에
   수집을 완수해야 한다(현재는 로그만 남기고 다음날까지 구멍).
5. **발송 시각**: 직접 등록發 수집 시작은 **즉시**(시딩 수십 건은 묶어 1통), 나머지는
   **09:00 KST**.
6. 이메일 문안은 임시(교체 쉬운 구조), 딥링크 제외, 알람 토글은 유저 설정(기본 on) — 기존 결정 유지.
7. **앱 내 알림 + 히스토리**: 알람 이벤트 대장이 단일 원천 — was가 읽기 전용으로 서빙(프론트 API 때).

진행: **PR① = 본 스펙**(monitoring 개편 + 알람 모듈 + 계약 v2 + app 옵트아웃 테이블),
**PR② = was 정렬**(명령 클라이언트 approve/reject 제거·userId 전달 — ① 머지 후 별도).

## 2. monitoring 개편

### 2-1. `target.user_id` (V3 마이그레이션)

```sql
ALTER TABLE target ADD COLUMN user_id bigint;   -- was 유저의 논리 참조 (크로스 DB — FK 금지)
```
- nullable(expand 단계 — 기존 행은 null 유지, `SET NOT NULL`은 다음 릴리스에서 판단).
- 등록 API `RegisterRequest`에 `userId` **필수** 필드 추가(검증: null이면 VALIDATION 400).
- user_id가 null인 기존 target은 알람 이벤트 적재를 스킵(수신자 불명 — warn 로그).
- 조회 뷰 `v_target_overview`에 user_id 노출(was의 향후 소유 스코프 조회용 — DROP+CREATE).

### 2-2. 첫 감지 자동 수집 (승인 플로우 제거)

- `DailySweepJob.sweepTarget`: WATCHING 캠페인에서 키워드 매칭 게시물 발견 시
  `detected_candidate` 적재 대신 **`markTracking(targetId, shortCode)` 직행**.
  같은 계정 열거에서 지표가 이미 스냅샷에 저장돼 있으므로 추가 `collectPost` 콜은 열거
  밖 게시물일 때만(기존 단건 보강 경로 재사용). 매칭 후보가 같은 스윕에 여러 개면
  **taken_at 최신 1건** 채택(첫 감지 1건 규칙).
- 전환 직후 `COLLECTION_STARTED` 이벤트 적재(스윕發 — dispatch 09:00).
- **제거**: `approve`/`reject` 엔드포인트·`TargetCommandService`의 두 메서드·관련
  DTO(Approve/RejectResponse)·`CandidateNotFoundException`. `detected_candidate`는
  신규 적재 중단(테이블·기존 행 잔존 — DROP은 expand-contract 규칙대로 다음 릴리스).
- 스윕의 무트랜잭션 정책 유지: 전환 UPDATE와 이벤트 적재는 각각 짧은 커밋. 구 approve의
  "트랜잭션 안 외부콜" 패턴은 승계하지 않는다.

### 2-3. 일시 오류 당일 재시도

- **콜 레벨**: `JdkHikerHttp`에 5xx·IO 오류 시 짧은 백오프 재시도(기본 2회, 프로퍼티).
  404·비공개(결정적)는 재시도하지 않는다.
- **스윕 레벨**: 계정 수집이 일시 오류로 실패하면 실패 계정을 모아 **스윕 말미에 재시도
  라운드**(기본: 10분 간격 × 최대 3라운드, 프로퍼티). 라운드까지 전부 실패하면 로그만
  (운영 관측 — 알람 발송 없음, 다음날 스윕이 자연 회복).

## 3. 알람 모듈 (`com.celfit.monitoring.alarm` 패키지 — 같은 앱·같은 DB)

### 3-1. 대장 `alarm_event` (V3에 동봉)

```sql
CREATE TABLE alarm_event (
    id             bigserial PRIMARY KEY,
    target_id      bigint NOT NULL,               -- 논리 참조(target 행은 불멸이라 조인 안전)
    user_id        bigint NOT NULL,               -- 수신자 (was 유저 논리 참조)
    event_type     text   NOT NULL CHECK (event_type IN
                   ('COLLECTION_STARTED','COLLECTION_ENDED','METRICS_HIDDEN','CONTENT_UNAVAILABLE')),
    payload        jsonb  NOT NULL,               -- 문안 재료: username, short_code, 상세(숨은 지표 목록 등)
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    dispatch_after timestamptz NOT NULL,          -- 발송 레인: 즉시(=occurred_at) 또는 당일 09:00 KST
    email_status   text NOT NULL DEFAULT 'PENDING' CHECK (email_status IN
                   ('PENDING','SENT','SKIPPED_OPTOUT','SKIPPED_NO_RECIPIENT','FAILED')),
    email_attempts smallint NOT NULL DEFAULT 0,   -- 발송 시도 횟수 — FAILED 재시도 상한 관리
    email_sent_at  timestamptz
);
CREATE INDEX alarm_event_pending_idx ON alarm_event (dispatch_after) WHERE email_status = 'PENDING';
CREATE INDEX alarm_event_user_idx ON alarm_event (user_id, occurred_at DESC);   -- 앱 내 알림/히스토리 서빙용
```

- **id 기반 대장 = 워터마크 없음** — 순서·유실 문제 원천 제거. 발송 실패는 행 단위
  FAILED → 다음 틱 그 행만 재시도(전체 재발송 없음). **재시도 상한 5회**(프로퍼티) —
  due 조회는 `email_status IN ('PENDING','FAILED') AND email_attempts < 상한`.
  상한 도달 행은 FAILED로 종결(무한 Resend 호출 방지 — 구현 검토 중 발견·정정).
- 수신자 확인 불가(유저 삭제·이메일 부재)는 `SKIPPED_NO_RECIPIENT`로 종결 —
  옵트아웃 관측치와 분리(구현 검토 중 발견·정정).
- 앱 내 알림·히스토리의 단일 원천: was가 읽기 전용 SELECT로 서빙(v2 조회 표면에 추가).
  읽음 상태는 app 스키마 워터마크(프론트 API 작업 때).

### 3-2. 이벤트 적재 지점

| 이벤트 | 지점 | dispatch_after |
|---|---|---|
| COLLECTION_STARTED (직접 등록) | `RegistrationService` POST 등록 성공 직후 | `now()` (즉시 레인) |
| COLLECTION_STARTED (자동 전환) | 스윕 `markTracking` 직후 | 당일 09:00 KST |
| COLLECTION_ENDED | `expireOverdue`를 **`RETURNING id, user_id, ...`로 개조** | 당일 09:00 KST |
| METRICS_HIDDEN | `SnapshotWriter.savePost` — 직전(최신) 스냅샷과 비교 | 당일 09:00 KST |
| CONTENT_UNAVAILABLE | `closeFailed`/`closeAll` (FAILED 전이 지점) | 당일 09:00 KST |

METRICS_HIDDEN 오탐 방지 규칙:
- content_type별 **상시 null 지표는 비교 제외**(FEED: views·saves·shares·reposts 등 —
  실측 어휘는 구현 시 PostInfo 주석 기준).
- **릴스 조회수는 clips 보강 성공 시에만 비교** — 보강 실패가 views를 조용히 null로
  만드는 오탐 경로(`HikerClient` 삼킴 지점). CollectService→SnapshotWriter로
  "views 신뢰 가능" 플래그를 전달한다.
- null→null, null→값 복귀는 이벤트 아님. 같은 게시물의 동일 지표 반복 이벤트는
  전이 시점 1회만(직전 스냅샷 대비 전이만 잡히므로 자연 보장).

### 3-3. 발송 크론 (매 5분, 프로퍼티 — SweepScheduler 대칭 패턴)

1. `email_status='PENDING' AND dispatch_after <= now()` 를 유저별 그룹.
2. **디바운스**: 해당 유저의 즉시 레인 이벤트 중 최신 `occurred_at`이 **10분 이내**면
   이번 틱 스킵(시딩 수십 건 연속 등록 흡수 — 잦아들고 ~10분 뒤 1통). 대기 기본 10분,
   프로퍼티. 09:00 레인은 이미 쌓인 상태라 디바운스 영향 없음.
3. **옵트아웃 필터**: app 읽기 전용 계정으로 `app.monitoring_email_opt_outs` 조회 —
   꺼진 이벤트 행은 `SKIPPED_OPTOUT`으로 종결(대장엔 남아 앱 내 알림으로는 서빙 가능).
4. 유저당 **1통 통합**(이벤트 타입별 섹션, 임시 문안 — Composer 분리), `app.users.email`
   조회 후 발송. 행별 SENT/FAILED + email_sent_at.
5. 발송기: Resend HTTP 클라이언트를 monitoring에 신설(was 07-19 구현 관용구 이식,
   키 미설정 시 로깅 폴백). 수신자 없음(user 삭제)·이메일 null은 FAILED가 아니라
   SKIPPED 계열로 종결(재시도 무의미).

### 3-4. app 읽기 전용 접근 (역방향 계약)

- 알람 모듈이 analysis DB의 `app.users(email)` + `app.monitoring_email_opt_outs`만
  SELECT — 전용 읽기 전용 롤 `alarm_reader`(두 객체만 GRANT). 접속은 별도 DataSource
  (monitoring DB DataSource와 분리, 지연 초기화 — was seam의 back-off 회피 교훈 적용은
  불필요: monitoring은 자동구성 DataSource가 1개뿐이라 두 번째는 수동 조립).
- 계약 문서에 "알람 모듈→app 읽기 전용(두 객체 한정)" 절 신설.

## 4. app 스키마 (was Flyway V15 — 이 PR에 동봉)

```sql
CREATE TABLE app.monitoring_email_opt_outs (
    user_id    bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    event_type text   NOT NULL CHECK (event_type IN
               ('COLLECTION_STARTED','COLLECTION_ENDED','METRICS_HIDDEN','CONTENT_UNAVAILABLE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, event_type)
);
```
- 행 없음 = on(기본) — 설정 화면과 1:1, 빈 테이블로 전원 on. 쓰기(토글 API)는 was 소유
  (프론트 작업 때), 알람 모듈은 읽기만. **PR①에 동봉하는 이유**: 알람 발송 필터가 이
  테이블에 의존 — 배포 순서 결합 제거.
- (PR #183의 V15 워터마크 테이블은 폐기 — id 대장으로 대체됨.)

## 5. 계약 v2.0 개정 목록

- 등록 §2-1: `userId` 필수 필드 추가. 응답 status: ACCOUNT 등록도 첫 감지 시 자동
  TRACKING임을 명시.
- §2-2 승인 / §2-3 기각 **삭제** (명령 5→3개: 등록·연장·해지).
- §3: `detected_candidate` 표면 제거(deprecated 주석), `target.user_id` 추가,
  `v_target_overview`에 user_id, **`alarm_event` 조회 표면 추가**(was가 앱 내
  알림/히스토리 서빙 — 읽기 전용). `pending_candidates` 컬럼 제거 또는 상수 0 처리.
- §4 플로우: 감지→승인 절 삭제, 알람 절을 "monitoring 알람 모듈 소유"로 교체(발송
  레인·디바운스 포함). 기존 "was 09:00 크론·워터마크" 절 삭제.
- 신설: 알람 모듈→app 읽기 전용 역방향 계약(§ 신규).
- **호환 주의 명시**: v2 배포 시 구 was의 approve/reject 호출은 404 — 현재 프론트 /v1
  미배선이라 실호출자 없음(dev 스모크만 주의). 등록 userId 미전송(구 was)은 400 —
  동일하게 실호출자 없음. PR②가 was를 정렬한다.

## 6. 테스트·검증

- 기존 인프라 재사용: `TestDb`(컨테이너 싱글턴 + resetAndMigrate), fake `HikerHttp`,
  발송기는 fake(수신 기록) 주입.
- 자동 수집: 스윕 테스트 개편 — 후보 적재 단언을 자동 전환 단언으로 교체, 첫 1건 규칙
  (같은 스윕 다중 매칭 시 taken_at 최신), 기존 approve 관련 테스트 삭제.
- 재시도: fake HikerHttp가 N회 실패 후 성공하는 시나리오 — 콜 레벨·스윕 라운드 각각.
- 알람: 이벤트 적재 5지점 각 1개 이상, METRICS_HIDDEN 오탐 3종(상시 null 제외·clips
  보강 실패 스킵·값 복귀 무이벤트), 발송 크론(디바운스·옵트아웃 SKIPPED·행 단위
  FAILED 재시도·유저당 1통 통합), 만료 RETURNING.
- was 회귀: PR①은 was 코드 무변경(V15 마이그레이션만) — `:was:test` 전체 통과 확인.
- 계약 문서와 구현 대조는 최종 리뷰에서.

## 7. 배포·운영 (개통 체크리스트에 추가)

- monitoring 컨테이너 env: `RESEND_API_KEY`, 알람 크론·디바운스 프로퍼티(기본값 내장),
  `alarm_reader` DSN(analysis DB — 롤 생성 절차는 deploy/README §13에 추가).
- was env의 MONITORING_* 배선은 PR #183에서 폐기됐으므로 이 트랙에서 재정리(변경 없음
  — was는 이미 배선된 seam 그대로).
- 기존 운영 target 행의 user_id는 null — 알람 제외 상태로 시작. 필요 시 was 매핑
  (`app.monitoring_campaigns`)에서 일회성 백필 UPDATE 런북(dry-run→승인→실행).

## 8. 후속 (이 설계 밖)

- PR②: was 정렬 — 명령 클라이언트 approve/reject 제거, RegisterRequest userId, 관련 테스트.
- 프론트 API 때: 알람 히스토리 서빙(/v1 — alarm_event 읽기), 읽음 워터마크(app), 설정 토글 API.
- 정식 문안 + 딥링크(프론트 경로 확정 후).
- `detected_candidate`·approve 흔적 DROP(다음 릴리스 contract 단계), `target.user_id` NOT NULL 승격 검토.
- 탈퇴 시 캠페인 cancel 루프(기존 후속 유지).
