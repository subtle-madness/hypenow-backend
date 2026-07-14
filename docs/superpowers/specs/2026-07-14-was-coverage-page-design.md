# was /coverage — 커버리지 라이브 추적 페이지 설계

> 상태: 🟢 활성 · ✅ 구현/실행/반영됨

## 목적

content-ranking 프론트가 요구하는 필드별로 analysis DB 미러의 채움 정도를
브라우저에서 상시 추적한다. `analytics/check/coverage.sh`(CLI·가드용)의 웹 버전 —
접속할 때마다 실DB를 조회하므로 LLM 분석 배치가 채워지는 진행률을 새로고침만으로 볼 수 있다.

## 결정 사항

- **위치**: was 모듈 `/coverage` (Thymeleaf). 별도 서버·아티팩트 재발행 대신 was 선택 —
  was는 분석 결과 읽기 전용이라 경계 위반이 없고, 프로세스가 늘지 않는다.
- **범위**: 요약 타일 4개(contents·accounts·스냅샷 최신일·LLM 분석 수) + 필드 커버리지
  매트릭스 14행. 실데이터 카드 미리보기·추이 기록은 범위 제외 (저장 없음, 읽기만).
- **갱신**: `<meta http-equiv="refresh" content="60">` — 60초 자동 새로고침.

## 구조

`com.celfit.was.coverage` 평탄 패키지, 두 클래스:

- `CoverageRepository` — JdbcClient로 analysis DB 집계 1회 조회.
  매트릭스 14행(화면 요소·소스·채움·상태)과 타일 값을 반환.
  상태 판정(준비됨/일부 누락/부분/없음)은 SQL CASE — CLI 스크립트와 판정 로직 일치.
  DB 조회 실패 시 기존 `AnalysisRepository`처럼 warn 로그 + 빈 값 강등(페이지 불사).
- `CoverageController` — `GET /coverage`, 모델에 타일·매트릭스를 담아 `coverage.html` 렌더.

## 알려진 트레이드오프

매트릭스 정의가 두 곳에 존재한다: `analytics/check/coverage.sql`(CLI)과 was의 쿼리(웹).
항목 변경 시 둘 다 고쳐야 하며, 양쪽 파일에 상호 참조 주석을 남긴다.
한 곳으로 모으는 방법(analytics가 커버리지 뷰를 Flyway로 제공)은 14행짜리 표에 과해서 보류.

## 검증

- 컨트롤러 스모크 테스트: `GET /coverage` 200 + 모델 채움.
- `:was:bootRun` 후 실제 페이지가 CLI(`./check/coverage.sh`) 출력과 일치하는지 육안 대조.
