# 모노레포 전환 계획

- 작성일: 2026-07-09
- 상태: 실행
- 결정: 같은 Java/Spring 스택, 모노레포(Gradle 멀티모듈), `common` 모듈은 나중.

## 목표 구조

```
celfit/                     ← git 루트 (기존 crawler의 .git 승격, 히스토리 보존)
├── settings.gradle         ← 멀티모듈 (include 'crawler','analytics','was')
├── build.gradle            ← 루트 공통 (플러그인 버전·java 21·group·repo)
├── gradlew, gradlew.bat, gradle/   ← 공유 wrapper (루트)
├── compose.yaml            ← raw DB + analysis DB
├── .gitignore              ← 루트 통합
├── docs/                   ← 프로젝트 문서 (이동)
├── crawler/                ← 모듈: Apify 수집 → raw DB (기존 코드 전부)
│   ├── src/  build.gradle  README.md
├── analytics/              ← 모듈: raw 읽기 → 집계·랭킹 → analysis DB
│   ├── views/ seed/ test/  (기존 SQL 카탈로그)
│   ├── build.gradle  src/  (신규 스켈레톤)
└── was/                    ← 모듈: analysis DB 읽기 → 클라이언트 API (신규 스켈레톤)
```

DB 2개(같은 Postgres 인스턴스, 별도 database): `crawler`(raw) / `analysis`(서빙).

## 실행 단계 (각 단계 후 검증)

### Phase 1 — Git 루트 승격 + 파일 재배치 (히스토리 보존)
1. `mv crawler/.git celfit/.git` → 루트가 celfit/.
2. 루트로 이동: `gradlew gradlew.bat gradle/ settings.gradle compose.yaml .gitignore docs/ analytics/`.
3. crawler 자체(`src/ build.gradle README.md`)는 `crawler/`에 그대로 → git이 root→crawler/ rename으로 추적.
4. 검증: `git status`가 src/build.gradle/README.md만 crawler/로 rename, 나머지 무변경. `git rev-list --count` 여전히 57.

### Phase 2 — 루트 멀티모듈 빌드 배선
1. 루트 `settings.gradle` 재작성 (name 'celfit', include 3모듈).
2. 루트 `build.gradle` 신규 (플러그인 apply false + subprojects 공통).
3. `crawler/build.gradle`에서 버전·group·java·repo 제거, 플러그인은 버전 없이 apply.
4. 검증: `./gradlew :crawler:compileJava :crawler:compileTestJava` 성공.
5. 커밋 (구조 이동 + crawler 빌드).

### Phase 3 — analytics·was 모듈 스켈레톤 + analysis DB
1. `analytics/build.gradle` + 최소 `@SpringBootApplication` + analysis DataSource 설정.
2. `was/build.gradle` + 최소 `@SpringBootApplication` + web + analysis DataSource(읽기).
3. `compose.yaml`에 `analysis` database 추가 (init script 또는 2번째 서비스).
4. 검증: `./gradlew build -x test` 전체 모듈 컴파일.
5. 커밋 (스켈레톤).

## 범위 밖 (나중)
- analytics 실제 materialization 로직, WAS 실제 엔드포인트.
- `common` 공유 모듈.
- 원격 레포명 변경(hypenow-crawler → 모노레포명) — GitHub 측 작업.
- 옛 파이프라인 레포(wip-mac-migration)와 무관.

## 롤백
Phase 1 커밋 전이면 `mv celfit/.git celfit/crawler/.git` + 파일 원복. 커밋 후 문제 시 `git reset --hard` 로 직전 상태 복귀 (아직 push 안 함).
