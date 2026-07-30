# 모니터링 v3 3-트랙 병합 후 갭 파악

> 상태: ✅ 해소 완료 (2026-07-30) — 아래 파악 결과 전 항목 처리, 해소 내역은 말미 "해소 결과" 절 참조
>
> feat/monitoring-v3-was에 feat/monitoring-v3-p2(댓글·계정 메타)와
> feat/monitoring-alarm-module(승인 폐지·alarm_event·메일 발송)을 병합(1fbda87c)하고
> V15 경합을 해소(c5cd7aaf)한 시점의 전수 정합 검사 결과. 계약 기준:
> [monitoring-was-contract.md](../../../contracts/monitoring-was-contract.md) v2.1 /
> [monitoring-frontend-api-spec.md](../../../contracts/monitoring-frontend-api-spec.md) 6.25~6.33.
> 테스트: :was: 638 · :monitoring: 167 전부 그린.

## A. 잘못된 부분 (정합 어긋남)

### A-1. 지금 고쳐야 함 (다음 구현 착수의 전제)

1. **`RegisterRequest`에 `userId` 필드 없음** — 계약 v2.1 §2-1은 userId 필수(누락 400).
   실행기(Noop 대체)를 구현하는 순간 monitoring이 전부 400을 반환한다.
   `was/monitoring/RegisterRequest.java` + `RegistrationExecutor` 전달 경로 확인.
2. **다이제스트 표면이 v1 설계 잔재** — was 어디에도 `alarm_event` 참조가 0건.
   `app.monitoring_digests`+`monitoring_alarm_state`(DIGEST 워터마크)는 "was가 이벤트를
   직접 감지" 전제였는데 v2.1은 alarm_event 대장이 단일 원천(발송도 monitoring).
   6.32 서빙을 alarm_event 기반으로 재설계해야 함: 인앱 묶음(다이제스트) 생성은
   alarm_event를 유저·날짜별 집계(크론 또는 조회 시), 읽음은 alarm_event.id 기준
   was 워터마크. `monitoring_alarm_state`는 폐기 후보.

### A-2. 후속 태스크에서 정리 (죽은 코드 — 지금은 무해)

3. `MonitoringCommandClient.approve/reject` + `ApproveResult`·`RejectResult` — v2.1 명령은 3종(등록·연장·해지). 호출 시 404.
4. `MonitoringReadRepository.findCandidates/findPendingCandidatesSince` + `CandidateRow`·`PendingCandidate` — detected_candidate는 v2 deprecated(신규 적재 중단, 영구 빈 결과).
5. 상태 유도표(계획 문서)의 3행 "WATCHING+PENDING→collecting" — 승인 폐지로 신규 도달 불가. Task 10 착수 전 유도표 개정.
6. `TargetRow`에 `user_id` 미포함(계약 §3 노출 컬럼), Extend/CancelResult Javadoc 절 번호 stale — 참고 수준.

### A-3. 문제 없음 확인

- 옵트아웃: 테이블 정본(alarm V15, 대문자 어휘) + was 경계 매핑(`MonitoringEventTypes.toStorage/toFront`) — 계약 §6과 일치.
- extend/cancel URL·바디, 캠페인·처리 내역·설정 API — 어긋남 없음.

## B. 빠진 부분 (미구현·산지 부재)

### B-1. monitoring 표면 부재 — **개통 완전 차단** (P1 잔여, 팀/후속 세션 전달)

| 항목 | 프론트 계약 | 실측 |
|---|---|---|
| `post_meta`(caption·uploaded_at·thumbnail_url) | TrackedPost.caption·uploadedAt **null 불가** | V1~V4 어디에도 없음. Hiker PostInfo가 캡션을 갖고도 감지 매칭 후 폐기(영속화 안 함) |
| hidden/error 상태 신호 | hidden(재공개 시 복귀)·error(복구 시 복귀) — 진행 중 상태 | TargetStatus 5종뿐. **비공개·삭제·오류가 전부 FAILED(종결)로 닫히고 재스윕 대상에서 영구 제외** — 컬럼 추가가 아니라 상태 머신 재설계 필요 |
| `sweep_run` 배치 워터마크 | meta.lastCollectedAt = 마지막 성공 배치 완료 시각 + 이후 스냅샷 배제 | 없음. target.last_fetched_at은 캠페인 단위라 부분 실패 시 혼재 재현 |
| matchedKeywords 산지 | TrackedPost.matchedKeywords | v2 자동 전환 경로가 detected_candidate에 INSERT하지 않아 **P2의 matched_keywords 컬럼이 사실상 무산** — 감지 시점에 target 또는 alarm_event payload에 기록 필요. (계약 v2.1 문서도 이 모순 미반영) |

### B-2. was 작업 잔여 (monitoring 표면은 충분 — 착수 가능)

- 6.26 GET 목록(어셈블러 — v2.1 뷰·post_comment·profile_meta 소비), 6.29 PATCH, 6.30 cancel
- 등록 실행기(Noop 대체 — userId 전제 A-1 선행), share resolve 배선(§2-6 API는 monitoring에 존재)
- 다이제스트 생성 경로(A-2 재설계 방향으로), 탈퇴 cancel 루프
- 6.27 중복 판정의 자연 종결 제외(target 상태 반영)

### B-3. 픽스처 표류 (잠재 위험)

`was/src/test/resources/monitoring-schema.sql`의 post_meta·tracked_hidden_at·fetch_failing·sweep_run은 실구현에 없다(선반영). 이 4객체를 전제로 어셈블러를 구현하면 테스트는 그린인데 실환경은 깨진다 — **B-1이 해소되기 전까지 이 픽스처 객체에 의존하는 신규 코드 작성 금지.**

## C. 권장 순서

1. A-1-1(userId) → 등록 실행기 구현 → 6.29·6.30 (monitoring 무관·완결 가능)
2. A-1-2 재설계 확정 → 다이제스트 생성·6.32 재정렬
3. 6.26 어셈블러 — B-1 중 post_meta·hidden/error·sweep_run이 채워진 뒤(그 전엔 caption 폴백 불가로 계약 위반 확정)
4. B-1은 monitoring 쪽 확장(상태 머신 포함)이 필요 — 확장 요구 문서 개정판으로 전달

## 해소 결과 (2026-07-30)

권장 순서대로 전 항목 처리 완료. was 638 · monitoring 167 테스트는 병합 시점 기준이며 이후 태스크마다 회귀 그린 유지.

- **A-1-1 (userId)** — 해소. `RegisterRequest`에 `userId` 필드 추가, `RegistrationExecutor`·`NoopRegistrationExecutor` 전달 경로 배선. 등록 실행기 구현으로 이어짐.
- **A-1-2 (다이제스트 재설계)** — 해소. `alarm_event` 단일 원천 기반으로 6.32를 재설계: 생성 크론이 유저·날짜 단위로 **멱등 재계산**(워터마크 폐지), 늦은 배치 따라잡기 크론을 별도 배선해 그날 발송을 보장. `monitoring_alarm_state`(구 DIGEST 워터마크)는 폐기.
- **A-2 (죽은 표면 제거)** — 해소. `MonitoringCommandClient.approve/reject`·`ApproveResult`/`RejectResult`, `findCandidates`/`findPendingCandidatesSince`·`CandidateRow`/`PendingCandidate` 제거. 상태 유도표의 "WATCHING+PENDING→collecting" 3행 삭제(승인 폐지로 도달 불가 확정). `TargetRow`에 `user_id` 반영.
- **B-1 (monitoring 표면 부재)** — `feat/monitoring-p1` 머지로 해소. post_meta(캡션·게시일·썸네일)·hidden/error 상태 신호(+복귀)·`sweep_run` 배치 워터마크·`matched_keywords` 산지 4종 전부 반입(계약 v2.2). 픽스처가 선반영해둔 4객체(post_meta·tracked_hidden_at·fetch_failing·sweep_run)를 실구현과 재대조 완료 — 표류 없음.
- **B-2 (was 작업 잔여)** — 해소. 6.26 GET 목록 완전 어셈블러(`TrackingItemAssembler`), 6.29 PATCH·6.30 cancel, 등록 실행기(Noop 대체), 다이제스트 생성 경로, 6.27 중복 판정의 자연 종결 제외(재등록 허용), 탈퇴 시 모니터링 해지 루프(배치 대상 즉시 제외) 전부 구현.
- **B-3 (픽스처 표류)** — 해소. `was/src/test/resources/monitoring-schema.sql`을 P1 실구현(V5) 기준으로 재대조·정합 확인.

**잔여 미해소**: 23:50 이후 도착 이벤트 유실 가능성(스윕 배치 경계 부근, `sweep_run` 완료 가드로 후속 최적화 여지 — 현재는 허용 범위로 수용).
