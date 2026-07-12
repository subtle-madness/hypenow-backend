# analytics — 분석 층

raw DB(crawler)를 읽어 분석 결과를 analysis DB에 내놓는 모듈.
설계: [../docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md](../docs/superpowers/specs/2026-07-12-analytics-data-layer-design.md)

## 구성

- `views/` — raw DB `analytics` 스키마의 뷰. 파일명 번호순 적용.
  - `00_base.sql` — base 뷰 4종. **raw 테이블·payload를 만지는 유일한 SQL.**
  - `01_recent_window.sql` — 계정별 최근 N개 윈도우 (`v_recent_content`)
  - `02_serving.sql` — 서빙 형태 뷰 3종 (`v_accounts`·`v_contents`·`v_content_comments`) — 미러 대상과 1:1
- `mirror/` — 타입 기반 미러: 뷰 SELECT → 공유 record 매핑 → analysis DB 테이블
  TRUNCATE+INSERT (한 트랜잭션, 컬럼↔record 대조 가드). 대상 등록은 `MirrorConfig`.
  대상: accounts·contents·content_comments (등록: MirrorConfig).
- `test/` — SQL 하니스. 더미 시드를 BEGIN/ROLLBACK으로 격리해 뷰 기대값을 고정.

## 실행

    ./test/run.sh                    # 뷰 적용 + SQL 테스트 전체 (crawler-postgres-1 필요)
    ./test/run.sh test/00_base.test.sql   # 지정 테스트
    ../gradlew :analytics:test       # Java 테스트 (Docker 필요)
    ../gradlew :analytics:bootRun    # 미러 1회 실행 (analytics.mirror-on-startup=true)

## app_setting 런타임 키 (뷰가 직접 읽음)

| 키 | 기본 | 의미 |
|---|---|---|
| `analytics.recent-window` | 12 | 계정 단위 지표의 최근 N개 윈도우 (§4-1) |

⚠️ 값은 **평문 정수만** — 비정수 값이 들어가면 `v_recent_content`와 그 위의 모든 계정 단위
뷰가 캐스팅 에러로 깨진다 (뷰가 `value::int`로 직접 읽음, 앱 레벨 검증 없음).
