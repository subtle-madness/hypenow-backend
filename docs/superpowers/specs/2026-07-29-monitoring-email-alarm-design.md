# 모니터링 이메일 알람 1차 (게시물 감지) 설계

> 상태: 🟢 활성 · 구현 착수 전 (2026-07-29 승인분)
>
> 선행: [2026-07-28-monitoring-was-seam-design.md](2026-07-28-monitoring-was-seam-design.md) (PR #161 머지됨).
> 계약 정본: [docs/contracts/monitoring-was-contract.md](../../contracts/monitoring-was-contract.md) — **v1.0 기준**
> (monitoring 모듈이 리포에 합류하며 구현 반영·확정. seam은 v0.1 기준이라 알람 쿼리 1건 정렬 필요 — §4).

## 1. 배경·범위

계약 §4의 "이메일 알람 (was 소속 09:00 크론)"을 구현한다. 프론트 설정 화면(콘텐츠 모니터링
알림 — 이벤트 4종 × 앱 내/이메일 토글)이 확정되며 사용자 결정 3건이 내려졌다:

1. **이메일 문안은 임시(test)** — 나중에 교체가 쉬운 구조로(문안 조립 클래스 분리).
2. **딥링크 제외** — 메일 본문에 프론트 화면 링크 없음(경로 미정).
3. **알람 설정은 유저 설정에 이벤트별 이메일 토글로 확정** — 기본 on, 앱 내 알림은 항상 on 고정.

범위 (사용자 확정):

- **포함** — 이벤트 1종 **게시물 감지(POST_DETECTED)**의 09:00 이메일 크론 + 알람 설정
  **저장 계층**(V15, 기본 on) + 워터마크 + 역방향 조회(`findByTargetIds`) + 계약 v1.0 정렬.
- **제외** — 설정 토글 API(프론트 /v1 작업 때), 앱 내 알림(프론트 API 영역),
  **모니터링 종료·미업로드 확정**(target 상태로 유도 가능 — 해석 확인 후 후속, 이벤트 추가가
  쉬운 구조로만 준비), **게시물 숨김**(계약 v1.0에도 감지 신호 없음 — monitoring 쪽 계약
  확장 필요, 팀원 전달 사항).

## 2. 저장 계층 (app 스키마 — V15)

```sql
-- 이메일 알람 옵트아웃: 행 없음 = 알림 on(기본). 설정 화면의 이벤트별 이메일 토글 저장소.
app.monitoring_email_opt_outs (
    user_id    bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    event_type text   NOT NULL CHECK (event_type IN
                      ('POST_DETECTED','POST_HIDDEN','UPLOAD_MISSED','MONITORING_ENDED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, event_type)
)

-- 발송 워터마크: 이벤트별 1행 — 중복 발송 방지는 전적으로 was 책임(계약 §4).
app.monitoring_alarm_state (
    event_type       text PRIMARY KEY,
    last_notified_at timestamptz NOT NULL
)
-- 시드: ('POST_DETECTED', now()) — 마이그레이션 시각 이후 감지분부터 발송(과거분 일괄 발송 방지)
```

- **옵트아웃 방식**인 이유: 기본 on 정책이라 "행 없음 = on"이 UI와 정확히 일치하고, 토글
  API가 없는 지금도 빈 테이블로 전원 on 동작. 신규 가입자도 자동 on.
- event_type 어휘는 4종을 미리 CHECK로 고정(설정 화면과 1:1) — 크론은 1종만 소비.
- 워터마크가 이벤트별 행이라 후속 이벤트 추가 시 마이그레이션 없이 INSERT만.

## 3. 크론 — `MonitoringAlarmJob`

`MonitoringConfig` 조건부 @Bean(= `monitoring.enabled=true`일 때만 존재·스케줄 등록,
`@EnableScheduling`도 MonitoringConfig에 붙여 비활성 환경 무영향).

매일 **09:00 KST**(`@Scheduled(cron="0 0 9 * * *", zone="Asia/Seoul")`):

1. 워터마크 조회 → `findPendingCandidatesSince(watermark)` (계약 §3 알람 쿼리)
2. 후보의 target_id → `findByTargetIds`로 (user_id) 확정 — 매핑 없는 후보(탈퇴 CASCADE 등)는
   스킵+warn
3. 옵트아웃 유저 제외 → 유저 이메일 조회 → **유저당 1통**(신규 후보 N건 묶음) 발송
4. **부분 실패 정책: 실패가 1건이라도 있으면 워터마크 유지**(다음 회차 재발송 — 이미 받은
   유저는 중복 수신 가능. 알람은 유실보다 중복이 낫다는 판단, 임시 문안 단계라 수용).
   전부 성공(또는 발송 대상 0명)이면 배치의 `max(detected_at)`로 전진 — `now()`가 아니라
   처리분 기준이라 크론 실행 중 새로 감지된 행을 놓치지 않는다.
5. 발송은 기존 `MailSender` 재사용 — Resend 키 없으면 `LoggingMailSender`(로컬·테스트 안전).

문안은 `MonitoringAlarmMailComposer`로 분리 — 임시 문안(제목 "[hypenow] 새 게시물 감지 N건",
본문에 @계정/게시물/캡션 발췌 나열, 딥링크 없음). 교체는 이 클래스 하나 수정으로 완결.

## 4. 계약 v1.0 정렬 (기존 seam 보수)

- `MonitoringReadRepository.findPendingCandidatesSince`에 **`AND t.status IN
  ('WATCHING','TRACKING')` 추가** — v1.0이 알람 쿼리에 명시한 필수 조건(종결 캠페인의 잔여
  PENDING은 승인·거절이 모두 409라 알람이 나가면 안 됨).
- `MonitoringCampaignMappingRepository.findByTargetIds(Collection<Long>)` 신설 — seam 최종
  리뷰에서 확인된 알람 크론 갭.
- **테스트 DDL 픽스처 대조 완료(후속 종결)**: monitoring 모듈 합류로 실제 DDL
  (`monitoring/src/main/resources/db/migration/V1__core_tables.sql`)과 대조 — 계약 4테이블의
  컬럼·타입·제약이 픽스처와 일치(실물은 FK·인덱스·DEFAULT 추가 보유 — 조회 검증에 무영향).

## 5. 테스트·검증

- **저장 계층**: IntegrationTest — 옵트아웃 유무 판정, 워터마크 조회·전진(후퇴 방지 가드),
  유저 이메일 조회, V15 실적용.
- **조회 정렬**: 종결 캠페인 후보가 알람 쿼리에서 제외되는 테스트 추가.
- **크론**: 픽스처 DB + 실제 리포지토리 + mock MailSender — 유저당 묶음 1통, 옵트아웃 제외,
  매핑 없는 후보 스킵, 부분 실패 시 워터마크 유지, 전부 성공 시 max(detected_at) 전진,
  신규 0건 무발송.
- **비활성 기본값**: monitoring.enabled 미설정 시 잡 빈 부재(기존 Disabled 테스트 패턴).

## 6. 문서·후속

- ARCHITECTURE §5·§7 갱신은 구현 PR에서 함께.
- 후속: 설정 토글 API(프론트 /v1), 모니터링 종료·미업로드 확정 알람(해석 확인 후 — 워터마크
  행 INSERT + 크론 메서드 추가로 확장), 게시물 숨김(monitoring 계약 확장 필요 — **팀원 전달**),
  앱 내 알림(프론트 API), 문안 정식 교체 + 딥링크(프론트 경로 확정 후).
