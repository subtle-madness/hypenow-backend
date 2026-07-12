# hypenow 백엔드 — 메인 설계 문서

> **살아있는 문서.** 구조·결정이 바뀌면 이 문서를 먼저 고친다. 상세한 시점 기록(왜 그렇게 정했는지의
> 전말)은 `docs/superpowers/specs/`의 dated 문서에 남기고, 여기서는 **현재 유효한 그림**만 유지한다.
> 각 섹션을 고칠 때 하단 [결정 기록](#7-결정-기록)에 한 줄을 추가한다.
>
> 마지막 갱신: 2026-07-12

## 1. 제품 한 장 요약

**hypenow** — 인스타그램 뷰티 인플루언서 콘텐츠 분석 툴.
타깃: **마이크로인플루언서를 발굴하려는 뷰티 브랜드 마케터.**

MVP 범위:
- 콘텐츠 랭킹 페이지 (운영 중 — was 대시보드)
- **게시물 상세 드로어** — 랭킹에서 클릭 시 (성과·벤치마크 + 댓글 분석·감지·"왜 잘됐나")
- **인플루언서 상세 페이지** — 드로어에서 진입 (정체성·성과·일관성·커머셜 + 페르소나·AI 브리핑)
- **후보 관리** — 후보 저장·상태(검토중/컨택 예정/협업 중)·메모

기준 기획: 상세 분석 확정안 (2026-07-10 Artifact, 게시물 드로어 v3 + 인플루언서 상세 v4)
프론트: celfit-front.vercel.app (별도 저장소)

## 2. 시스템 구조

3-tier. 층 사이는 DB로만 통신한다 (모듈 간 HTTP/큐 없음).

```
[크롤링]  crawler  ──쓰기──▶  raw DB (crawler)          크롤링 원본. 분석의 고정 입력
[분석]    analysis ──읽기── raw DB
                   ──쓰기──▶  분석 결과 (analysis DB)    was가 보여줄 데이터
[서빙]    was      ──읽기── 분석 결과 ──▶ celfit-front
                   ──읽기/쓰기──▶  서비스 데이터 (app 스키마)   로그인·후보 관리 등 일반 앱 데이터
```

| 모듈 | 데이터 접근 | 역할 | 기술 |
|---|---|---|---|
| `crawler` | raw DB 쓰기 | Apify로 발굴→판정→상세 수집, 원형(raw) 적재 | Spring Boot, JPA, Flyway, Thymeleaf 어드민 |
| `analytics` | raw 읽기 → 분석 결과 쓰기 | 분석 뷰 정의 + **미러**(분석 결과를 analysis DB에 채움). LLM 분석도 이 층 소속 | 헤드리스 배치, JdbcTemplate ×2 |
| `was` | 분석 결과 읽기 + 서비스 데이터 읽기/쓰기 | REST API 서빙 + 서비스 기능(로그인·후보 관리 등) | Spring Boot, JdbcClient |

**데이터 배치**: 저장 영역은 세 가지 — raw(크롤링 원본) / 분석 결과(미러 테이블) / **서비스 데이터**(was가
쓰는 일반 앱 데이터: 로그인·후보 관리 등). 서비스 데이터는 분석 결과와 **스키마로 분리**(analysis DB 내
`app` 스키마)하고, 셋 모두 현재 **한 Postgres 인스턴스**(포트 5433)에 논리 분리만 되어 있다. 부하를 보고
물리 분리를 결정한다 — 접근 규율(§4-3)을 지키는 한 어느 경계든 설정 변경으로 분리 가능하다.

**미러란**: raw DB에 정의된 분석 뷰(`analytics.*`)를 실행해 결과를 analysis DB의 평탄 테이블로
채우는 배치 (`MaterializationService`: 뷰당 drop→create→전량 insert, 1회 실행 후 종료).
레플리카가 아니라 **분석 층이 결과물을 내놓는 행위 그 자체** — 뷰는 DB를 못 넘으므로 이 잡이 tier 경계다.

## 3. 데이터

### raw DB (crawler 소유 — 분석 작업에서 불변)

| 테이블 | 내용 |
|---|---|
| `content` | 게시물 메타 (short_code, owner, uploaded_at, 분류 계층, ad_marked, 상태) |
| `raw_post_detail` | Apify 상세 payload(jsonb) + generated 컬럼 (likes, comments_count, video_play_count, caption) |
| `raw_comment` | 댓글 원문 payload + generated (writer, text, written_at) |
| `raw_profile` | 프로필 스냅샷 payload + generated (username, followers) |
| `app_setting` | 런타임 설정 key-value (분석 뷰도 여기서 임계값을 읽음) |

### 분석 뷰 (raw DB의 `analytics` 스키마)

`analytics/views/NN_*.sql` 번호순 적용. 00(base) ~ 08(크리에이터 기둥) 존재.
카탈로그: [analytics/README.md](analytics/README.md)

### analysis DB

- **분석 결과** — 미러된 평탄 테이블 (`content_ranking`, `category_performance`, …) + `materialization_meta`(신선도). analytics가 쓰고 was가 읽는다.
- **서비스 데이터 (`app` 스키마)** — 로그인·후보 관리 등 was가 직접 읽고 쓰는 일반 앱 데이터.
  분석 결과와 스키마로 격리, 나중에 물리 분리 가능.

## 4. 관통하는 설계 원칙

1. **최근 N개 윈도우** — 모든 계정 단위 지표는 계정별 최신 게시물 N개(기본 12)만 잘라 계산.
   재크롤링이 누적돼도 계정 간 비교가 공정. N은 `app_setting`으로 런타임 조정.
2. **LLM도 분석 층의 일부** — 별도 시스템이 아니라 "raw를 읽어 분석 결과를 analysis DB에 채우는 일"의
   한 종류. 비LLM 집계와 나란히 화면별 태스크에 들어간다.
3. **was의 데이터 접근 규율** — raw DB에는 접근하지 않는다. 분석 결과는 **읽기만**, 쓰기는
   **서비스 데이터(`app` 스키마)에만** 한다. 분석 결과와 서비스 데이터를 SQL 조인하지 않는다
   (조합은 was 코드에서) — 이 규율이 지켜지는 한 두 영역은 언제든 물리 분리 가능.
4. **표기 원칙** — 표본 크기가 약점으로 안 보이게 UI는 %·라벨 중심. 백엔드는 `sampleSize`와
   비율의 분자·분모 원값을 항상 제공하고, 노출·전환은 프론트가 정한다.
5. **검증 컨벤션** — 분석 뷰는 SQL 하니스(`analytics/test/run.sh`, 더미 시드 + BEGIN/ROLLBACK 격리)로
   기대값을 고정. Java는 Testcontainers/MockMvc. LLM 호출은 테스트에서 실 API를 때리지 않는다(포트 fake).

## 5. 현재 상태 · 작업 트랙

> 상태가 바뀌면 이 표를 갱신한다. ✅ 완료 · 🔨 진행 중 · ⬜ 대기 · ⏸ 보류

**운영 중**: crawler 파이프라인(discover→qualify→aggregate), 분석 뷰 00~08, 미러 9종, was 랭킹 대시보드.

**상세 분석 작업 트랙** (설계: [specs/2026-07-12-detail-analysis-design.md](docs/superpowers/specs/2026-07-12-detail-analysis-design.md)):

| # | 태스크 | 내용 | 의존 | 상태 |
|---|---|---|---|---|
| A | 집계 공통 | 최근 N개 윈도우 뷰 + 설정 키 | — | ⬜ |
| F | LLM 공통 | 호출 골격 + **정확도/비용 스파이크** + 모듈 소속 확정 | — | ⬜ |
| B1 | 드로어 비LLM 집계 | 작성자 요약·성과·벤치마크 뷰 + 미러 | A | ⬜ |
| B2 | 드로어 댓글 LLM | 감성·키워드·구매의도 → 집계 + 미러 | F | ⬜ |
| B3 | 드로어 콘텐츠 LLM | 감지 + 콘텐츠 속성 + "왜 잘됐나" | F, B2 | ⬜ |
| C1 | 인플루언서 비LLM 집계 | 정체성·성과·일관성·커머셜 + 1:N 뷰 + 미러 | A | ⬜ |
| C2 | 인플루언서 계정 LLM | 광고유형·페르소나·브리핑·적합성 | F, C1, B3 | ⬜ |
| D | 드로어 API | `GET /api/posts/{shortCode}` | B1 | ⬜ |
| E | 인플루언서 API | `GET /api/influencers/{username}` | C1 | ⬜ |
| G | 서비스 데이터 | `app` 스키마 신설 + 후보 저장·상태·메모 (로그인 등 일반 앱 데이터의 기반) | 독립 | ⬜ |

권장 순서: A → B1, 병렬로 F(스파이크). 상세 구현 계획은 태스크 착수 시 작성.

## 6. 데이터 제약 (해석 주의 — 모든 지표 설계의 전제)

- **피드 게시물은 조회수가 항상 NULL** (인스타가 공개 안 함). 평균·히트·확산배율 계산 시 NULL 규칙 필수.
- 조회수 = 인스타 공개 재생수(`videoPlayCount`, 폴백 `videoViewCount`). 비로그인 취득 가능 실측 확인(07-10).
- 성과는 업로드 **+3일 단일 스냅샷** — 시계열 아님(추이 그래프는 기획에서 제외됨).
- 댓글은 게시물당 **최대 50개** 수집 → 목업의 "214개 분석"은 불가, 카피 정정 필요(미결).
- 저장·공유·도달·노출 지표 없음. 팔로워는 qualify 시점 값.
- LLM 댓글 분류 실측 비용: 게시물 1,000건당 Opus ≈ $61 / haiku ≈ $12.2 (동기·무캐시·무배치 기준).

## 7. 결정 기록

> 새 결정은 맨 위에 추가. 전말은 링크된 dated 문서에.

| 날짜 | 결정 | 근거/상세 |
|---|---|---|
| 2026-07-12 | 3-tier 확정: 미러=tier 경계(필수), LLM=분석 층 소속, 태스크 A~G 분해. **서비스 데이터**(로그인·후보 관리 등 was가 쓰는 앱 데이터)는 분석 결과와 스키마 분리(`app`), 물리 분리 고려 | [specs/2026-07-12-detail-analysis-design.md](docs/superpowers/specs/2026-07-12-detail-analysis-design.md) |
| 2026-07-10 | 상세 분석 확정안(드로어 v3·인플루언서 v4) + 구현 계획 초안 3건(현재는 참고 자료) | [plans/2026-07-10-*](docs/superpowers/plans/) |
| 2026-07-09 | 모노레포 통합(crawler/analytics/was), was 랭킹 대시보드, 미러 도입 | [plans/2026-07-09-monorepo-migration.md](docs/superpowers/plans/2026-07-09-monorepo-migration.md) |
| 2026-07-09 | 분석 = SQL 뷰 방식(A안), `analytics` 스키마, 더미 시드 검증 | [specs/2026-07-09-analytics-catalog-design.md](docs/superpowers/specs/2026-07-09-analytics-catalog-design.md) |
| 2026-07-09 | 제품 방향: 분석 단위 = 크리에이터, 마이크로인플루언서 발굴 | [specs/2026-07-09-influencer-analysis-decisions.md](docs/superpowers/specs/2026-07-09-influencer-analysis-decisions.md) |
| 2026-07-07 | crawler: Apify 원형(raw) 적재 + discover→qualify→aggregate 3단계 | [specs/2026-07-07-crawler-design.md](docs/superpowers/specs/2026-07-07-crawler-design.md) |

## 8. 미결 (팀 논의 대기)

| 항목 | 상태 |
|---|---|
| 드로어 댓글 카피 | "214개 분석" 불가 → "최근 최대 50개" 정정 or 상한 상향+비용 재승인 |
| LLM 모델 | F 스파이크 결과로 결정 (기본 opus, haiku는 1/5 비용) |
| LLM 코드 모듈 소속 | analysis 쪽 제안, F에서 확정 |
| 미러 갱신 주기 | 현재 수동 1회. 자동화 여부·주기 |
| 서비스 데이터 상세 | `app` 스키마 구성·로그인 방식 등은 G 착수 시 설계 |
| 감성 비율 분모 | 기본 표기는 전체(스팸 포함), 원값 제공으로 프론트 전환 가능 |

## 9. 문서 맵

- **이 문서** — 현재 유효한 구조·상태·결정 (항상 최신 유지)
- [crawler/README.md](crawler/README.md) — 수집 파이프라인 실행·운영
- [analytics/README.md](analytics/README.md) — 분석 뷰 카탈로그·테스트
- `docs/superpowers/specs/` — 시점별 설계 기록 (결정의 전말)
- `docs/superpowers/plans/` — 시점별 상세 구현 계획 (착수 시 작성, 실행 후 이력)
