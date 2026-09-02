# qualify 빈 응답 Hiker 폴백 + 종결 장치 설계

> 상태: 🟢 활성 · ✅ 구현/실행/반영됨

## 배경

DISCOVERED 122건(2026-08-25 운영 실측)이 qualify에서 영구 deferred 상태다. 익명
`web_profile_info`가 이들에게 200 + user 없음(빈 응답)만 반환해 followers가 영영 NULL이고
(IG가 일부 계정을 익명 API에서 숨김 — 셀럽·비활성화 등), 빈 응답 Hiker 유료 폴백은 COLLECT
전용이라 회수 경로가 없었다. COLLECT 전용이었던 이유는 qualify의 followers-NULL 재선정에
종결 장치가 없어 폴백을 열면 소멸 계정에 무한 재과금되기 때문 — 따라서 폴백 개방과 종결
장치를 함께 넣는다.

## 설계

1. **컴포지트 페처**(`SelfWithHikerFallbackProfileFetcher`): 빈 응답 트랙을 COLLECT 전용 →
   **COLLECT + QUALIFY**로 확대. 메커니즘 불변 — SELF 빈 응답 연속 2회(인메모리 스트릭)에
   도달한 계정만 Hiker 2차 조회, 폴백도 빈 응답이면 confirmedEmpty로 보고.
2. **QualifyJob 종결 장치**: `applyChunk`에서 `confirmedEmpty` 계정을 **즉시 DELETED 소프트
   딜리트** — 기존 404 종결 경로와 대칭. 이후 재선정(followers-NULL은 DISCOVERED만)에서
   빠져 재과금이 끊긴다.

### collect(30일 유예)와 달리 즉시 종결하는 이유

collect 대상은 판정을 통과한 자산이라 보수적으로 다루지만, DISCOVERED는 아직 리드일 뿐이다.
소프트 딜리트라 되돌릴 수 있고, 같은 계정이 재발굴되면 다시 파이프라인에 들어온다.

### 기대 동작 (122건 기준)

배포 후 qualify 2회 실행이면 스트릭 임계 도달 → 계정당 Hiker 1콜. Hiker가 데이터를 주면
followers가 채워져 즉시 판정, Hiker도 빈 응답이면 DELETED 종결. 비용 최대 122콜 ≈ $0.08
일회성. 스트릭이 인메모리라 재기동 시 리셋되지만, 리셋돼도 "2회 방문 후 폴백"이 다시
성립할 뿐 누수는 없다.

## 변경 파일

- `SelfWithHikerFallbackProfileFetcher`: 빈 응답 트랙 조건 `job == COLLECT` →
  `job == COLLECT || job == QUALIFY`, 클래스 주석 갱신.
- `QualifyJob.applyChunk`: confirmedEmpty → DELETED 루프 추가.
- 테스트: 페처의 QUALIFY 폴백·confirmedEmpty 보고, QualifyJob의 소프트 딜리트 종결.
