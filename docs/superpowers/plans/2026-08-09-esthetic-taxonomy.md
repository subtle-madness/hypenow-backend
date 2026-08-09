# 에스테틱 대분류 추가 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: 🟢 활성 · 계획 확정(2026-08-09) — 스펙 [2026-08-09-esthetic-taxonomy-design.md](../specs/2026-08-09-esthetic-taxonomy-design.md)

**Goal:** `beauty_taxonomy`에 대분류 `esthetic`(에스테틱, 소분류 14개)을 시드해 디바이스·툴·피부 시술 게시물이 `main_category`로 분류되게 한다.

**Architecture:** 어휘는 analysis DB `beauty_taxonomy` 테이블이 단일 원천 — additive INSERT 마이그레이션 1개면 LLM 프롬프트 분류표·sanitize·역유도·카테고리 믹스 뷰가 전부 자동 반영된다(코드 무접촉). 기존 분석분 소급은 ops SQL 동봉만 하고 실행은 보류(스펙 §5).

**Tech Stack:** Flyway(analytics `db/migration/analysis`), JUnit + Testcontainers(PostgreSQL), psql ops 스크립트

## Global Constraints

- 마이그레이션 파일명은 UTC 타임스탬프 채번: `V20260809063533__esthetic_taxonomy.sql` (CLAUDE.md — 정수 연번 금지)
- 순수 additive INSERT만 — DROP/RENAME/타입 변경 금지(expand-contract, CI migration-guard)
- 라벨은 스펙 §3 표기 그대로 한 글자도 바꾸지 않는다 — celfit-front 필터와 1:1 계약, `deriveMain`·프론트 매칭 모두 정확 일치 기반
- 테스트 실행 전 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` (미설정 시 Testcontainers 대량 실패 — CLAUDE.md 함정)
- 커밋 메시지 한국어, prefix `feat(analytics):`/`docs:`
- celfit-front 어휘 추가는 별도 저장소 작업 — 이 계획 범위 밖(스펙 §4-3, 백엔드 선행 안전)

---

### Task 1: 어휘 시드 마이그레이션 (TDD)

**Files:**
- Modify: `analytics/src/test/java/com/celfit/analytics/llm/BeautyTaxonomySeedTest.java`
- Create: `analytics/src/main/resources/db/migration/analysis/V20260809063533__esthetic_taxonomy.sql`

**Interfaces:**
- Consumes: `BeautyTaxonomyLoader(DataSource).get()` → `BeautyTaxonomy` (기존, 변경 없음)
- Produces: `beauty_taxonomy`에 `main_value='esthetic'` 14행 — Task 2의 ops SQL과 프론트 동기화가 이 어휘를 전제

- [ ] **Step 1: 시드 테스트를 새 어휘 기대로 수정 (failing test)**

`BeautyTaxonomySeedTest.java`에서 기존 테스트 2개를 수정하고 1개를 추가한다:

`대분류_slug는_프론트_배포본_6종이다()` — 이름과 기대를 7종으로:

```java
	@Test
	void 대분류_slug는_프론트_배포본_7종이다() {
		assertEquals(Set.of("skincare", "suncare", "makeup", "cleansing", "haircare", "fragrance", "esthetic"),
				taxonomy.mainCategories());
	}
```

`시드는_소분류_72행이다()` — 이름과 기대를 86행으로(72 + 14):

```java
	@Test
	void 시드는_소분류_86행이다() {
		// 시드 행 누락·중복을 총량으로 방어 (프론트 배포본 소분류 수 — Set은 동명 라벨이 접혀 SQL로 센다)
		assertEquals(86L, db.queryForObject("SELECT count(*) FROM beauty_taxonomy", Long.class));
	}
```

신규 테스트 추가 (클래스 끝에, `java.util.List` import 필요):

```java
	@Test
	void 에스테틱_어휘가_시드된다() {
		// 2026-08-09 스펙 §3 — 디바이스·툴·피부 시술 14개. 라벨은 프론트 계약이라 표기 그대로 검증.
		assertTrue(taxonomy.mainCategories().contains("esthetic"));

		Set<String> labels = taxonomy.allMidAndSubLabels();
		for (String label : List.of("뷰티 디바이스", "뷰티 툴", "피부 시술·관리",
				"LED 마스크", "미세전류 기기", "괄사", "에스테틱 관리", "경락 마사지", "필링 시술")) {
			assertTrue(labels.contains(label), label);
		}

		// 소분류 칩 어휘: 소분류만 포함, 중분류 미포함
		assertTrue(taxonomy.allSubLabels().contains("스킨부스터"));
		assertFalse(taxonomy.allSubLabels().contains("피부 시술·관리"));

		// 역유도: 에스테틱 라벨만 있으면 esthetic으로 복구된다 (sanitize 경로)
		assertEquals("esthetic", taxonomy.deriveMain(List.of("경락 마사지", "괄사")));
	}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :analytics:test --tests "com.celfit.analytics.llm.BeautyTaxonomySeedTest"
```

Expected: FAIL — `대분류_slug는_프론트_배포본_7종이다`(esthetic 없음), `시드는_소분류_86행이다`(72행), `에스테틱_어휘가_시드된다` 모두 실패.

- [ ] **Step 3: 시드 마이그레이션 작성**

`analytics/src/main/resources/db/migration/analysis/V20260809063533__esthetic_taxonomy.sql`:

```sql
-- 에스테틱 대분류 추가 (2026-08-09 스펙): 게시물 단위 사각지대 해소 —
-- 디바이스·시술 콘텐츠는 isBeauty 기준("제품·시술·루틴")에 걸려 is_beauty=true인데
-- 어휘 밖이라 sanitize가 걸러 main_category=null로 남았다(카테고리 필터 미노출).
-- 스코프(사용자 확정): 홈케어 디바이스 + 수동 툴 + 피부 중심 시술·관리.
--   왁싱·반영구·네일·헤어 시술, 에스테틱 전문 화장품은 제외 — 피부 축 일관성 유지.
-- 라벨은 celfit-front 필터 어휘와 1:1 — 표기 수정 시 프론트와 함께 갱신할 것 (V30 헤더와 동일 원칙).
-- '필링 시술'은 클렌징 소분류 '필링'과 의도적으로 다른 라벨 — 정확 일치 매칭이라 한 글자로 분리된다.
-- 순수 additive INSERT — expand-contract 안전, 롤링 배포 무해.
INSERT INTO beauty_taxonomy (main_value, main_label, mid_label, sub_label, main_order, mid_order, sub_order) VALUES
  ('esthetic','에스테틱','뷰티 디바이스','LED 마스크',7,1,1),
  ('esthetic','에스테틱','뷰티 디바이스','미세전류 기기',7,1,2),
  ('esthetic','에스테틱','뷰티 디바이스','고주파 기기',7,1,3),
  ('esthetic','에스테틱','뷰티 디바이스','클렌징 기기',7,1,4),
  ('esthetic','에스테틱','뷰티 디바이스','제모 기기',7,1,5),
  ('esthetic','에스테틱','뷰티 툴','괄사',7,2,1),
  ('esthetic','에스테틱','뷰티 툴','페이스 롤러',7,2,2),
  ('esthetic','에스테틱','뷰티 툴','마사지 도구',7,2,3),
  ('esthetic','에스테틱','피부 시술·관리','에스테틱 관리',7,3,1),
  ('esthetic','에스테틱','피부 시술·관리','경락 마사지',7,3,2),
  ('esthetic','에스테틱','피부 시술·관리','피부과 레이저',7,3,3),
  ('esthetic','에스테틱','피부 시술·관리','스킨부스터',7,3,4),
  ('esthetic','에스테틱','피부 시술·관리','리프팅 시술',7,3,5),
  ('esthetic','에스테틱','피부 시술·관리','필링 시술',7,3,6);
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :analytics:test --tests "com.celfit.analytics.llm.BeautyTaxonomySeedTest"
```

Expected: PASS (전체 테스트 클래스 — 수정 2개 + 신규 1개 + 기존 나머지 회귀 없음).

- [ ] **Step 5: analytics 모듈 전체 테스트 (회귀 확인)**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :analytics:test
```

Expected: PASS — FlywaySchemaTest(새 마이그레이션 적용)·sanitize·로더 테스트 포함 전체 green.

- [ ] **Step 6: Commit**

```bash
git add analytics/src/main/resources/db/migration/analysis/V20260809063533__esthetic_taxonomy.sql analytics/src/test/java/com/celfit/analytics/llm/BeautyTaxonomySeedTest.java
git commit -m "feat(analytics): 에스테틱 대분류 시드 — 디바이스·툴·피부 시술 14개 소분류 (V20260809063533)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 소급 재자격 ops SQL 동봉 (실행 보류)

**Files:**
- Create: `analytics/ops/requalify_esthetic_candidates.sql`

**Interfaces:**
- Consumes: raw DB(`crawler`)의 `analytics.v_analysis_candidates` 뷰, analysis DB의 `content_analyses`
- Produces: 운영자가 수동 실행하는 문서화된 스크립트 — 자동 실행 경로 없음

**배경 (스펙 §5):** `content_analyses`는 INSERT-only + 분석 잡 NOT EXISTS 제외라 기존 분석분은 자동 소급되지 않는다. 선례(`reprocess_uncategorized_content_analyses.sql`)와 결정적 차이: 이번 삭제 대상은 브랜드·광고 신호가 채워진 **정상 행**이라, 후보 뷰에 되살아나지 못하면 분석을 통째로 잃는다. 후보 뷰는 raw DB, `content_analyses`는 analysis DB — 같은 postgres 클러스터의 다른 DB라 SQL 조인 불가. `\c`로 세션을 옮기고 파일 경유(`\copy`)로 교집합을 만든다. temp 테이블은 `\c` 후(같은 세션)에 만들어야 유지된다.

- [ ] **Step 1: ops 스크립트 작성**

`analytics/ops/requalify_esthetic_candidates.sql`:

```sql
-- [일회성 운영 SQL] 에스테틱 소급 재자격 — 대분류 esthetic 추가(V20260809063533) 후속.
--
-- 배경: 기존 분석분 중 디바이스·시술 게시물은 is_beauty=true AND main_category IS NULL로
--   남아 있다(구 어휘엔 에스테틱이 없어 sanitize 드랍). content_analyses는 INSERT-only +
--   분석 잡 NOT EXISTS 제외라 자동 재분석은 없다 → 행을 삭제해 재자격시킨다(self-heal).
--
-- ⚠️ 선례(reprocess_uncategorized_content_analyses.sql)와 결정적 차이 — 삭제 대상이 정상 분석
--   행(브랜드·광고 신호 포함)이다. 후보 뷰(v_analysis_candidates: 제때 크롤 OR 최근 N 윈도우)에
--   되살아나지 못하는 행을 지우면 기존 분석을 통째로 잃는다. 따라서 삭제는 반드시
--   **"여전히 후보 뷰에 있는 short_code"와의 교집합**으로 한정한다 — 삭제분은 다음 데일리 잡이
--   새 분류표(에스테틱 포함)로 전량 재분석한다(에스테틱이면 채워지고 아니면 다시 null — 멱등).
--
-- 비용: 삭제 건수만큼 LLM 콜 재발생. 실행 전 아래 dry-run 카운트로 건수 확인 후 결정할 것.
-- 재분석 전까지 해당 콘텐츠는 서빙·통계에서 일시 빠진다(데일리 잡 1회 내 복구).
--
-- 실행 위치: 운영 postgres 컨테이너 psql (crawler·analysis 두 DB를 \c로 오간다).
--   예: docker exec -it crawler-postgres-1 psql -U crawler -d crawler -f /tmp/requalify.sql
-- dry-run: 그대로 실행하면 ROLLBACK. 반영은 맨 끝 ROLLBACK을 COMMIT으로 바꿔 재실행.

-- ① raw DB: 현재 분석 후보인 short_code 추출 (자격 판정은 뷰가 정본 — 제때/윈도우 조건 승계)
\c crawler
\copy (SELECT short_code FROM analytics.v_analysis_candidates) TO '/tmp/esthetic_requalify_candidates.csv'

-- ② analysis DB: 후보 교집합만 삭제 (temp 테이블은 \c 이후 같은 세션에서 생성해야 유지된다)
\c analysis
CREATE TEMP TABLE _still_candidates (short_code text PRIMARY KEY);
\copy _still_candidates FROM '/tmp/esthetic_requalify_candidates.csv'

BEGIN;

\echo '=== 삭제 대상 (미분류 뷰티 ∩ 여전히 후보) ==='
SELECT count(*) AS to_delete
FROM content_analyses a
WHERE a.is_beauty IS TRUE
  AND a.main_category IS NULL
  AND EXISTS (SELECT 1 FROM _still_candidates c WHERE c.short_code = a.short_code);

\echo '=== 참고: 후보 밖이라 보존되는 미분류 뷰티 (삭제 안 함 — 데이터 유실 방지) ==='
SELECT count(*) AS kept_out_of_window
FROM content_analyses a
WHERE a.is_beauty IS TRUE
  AND a.main_category IS NULL
  AND NOT EXISTS (SELECT 1 FROM _still_candidates c WHERE c.short_code = a.short_code);

DELETE FROM content_analyses a
WHERE a.is_beauty IS TRUE
  AND a.main_category IS NULL
  AND EXISTS (SELECT 1 FROM _still_candidates c WHERE c.short_code = a.short_code);

\echo '=== 삭제 후 총계 ==='
SELECT count(*) AS remaining FROM content_analyses;

ROLLBACK;  -- 반영 시 COMMIT으로 변경
```

- [ ] **Step 2: 로컬 psql로 문법 검증 (실데이터 컨테이너, dry-run 그대로 ROLLBACK)**

```bash
docker cp analytics/ops/requalify_esthetic_candidates.sql crawler-postgres-1:/tmp/requalify.sql && docker exec crawler-postgres-1 psql -U crawler -d crawler -v ON_ERROR_STOP=1 -f /tmp/requalify.sql
```

Expected: 카운트 3개 출력 후 ROLLBACK — 에러 없이 종료(로컬 데이터 기준 건수는 참고만).
컨테이너 이름이 다르면 `PG_CONTAINER` 관례대로 해당 이름으로 치환. 로컬에 컨테이너가 없으면 이 스텝은 건너뛰고 커밋 메시지에 "문법 검증은 운영 dry-run 시점으로 이월" 명시.

- [ ] **Step 3: Commit**

```bash
git add analytics/ops/requalify_esthetic_candidates.sql
git commit -m "feat(analytics): 에스테틱 소급 재자격 ops SQL 동봉 — 후보 교집합 한정 삭제(dry-run 기본, 실행 보류)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 문서 갱신

**Files:**
- Modify: `DECISIONS.md` (맨 위에 결정 1행 추가)
- Modify: `docs/superpowers/specs/2026-08-09-esthetic-taxonomy-design.md` (상태 헤더 전환)

**Interfaces:**
- Consumes: Task 1·2의 커밋 결과(마이그레이션 파일명, ops 스크립트 경로)
- Produces: 없음 (기록용)

- [ ] **Step 1: DECISIONS.md 맨 위(테이블 첫 데이터 행)에 결정 추가**

기존 표 형식(`| 날짜 | 결정 | 링크 |`)을 따라 첫 행으로 삽입:

```markdown
| 2026-08-09 | **beauty_taxonomy 에스테틱 대분류 추가** — 게시물 단위 사각지대(디바이스·시술 후기가 is_beauty=true인데 어휘 밖이라 main_category=null) 해소. `esthetic`/'에스테틱'(main_order 7), 중분류 3(뷰티 디바이스 5·뷰티 툴 3·피부 시술·관리 6 — 경락 마사지 포함), 소분류 14 — 피부 축 한정(왁싱·반영구·네일·헤어·전문 화장품 제외, 사용자 확정). 어휘 단일 원천이라 additive 시드(V20260809063533) 하나로 프롬프트·sanitize·역유도·카테고리 믹스 뷰 자동 반영, 코드 무접촉. '필링 시술'은 클렌징 '필링'과 라벨 충돌 회피 명명. 기존 분석분 소급은 ops SQL(`analytics/ops/requalify_esthetic_candidates.sql`) 동봉만 — 정상 행 삭제라 후보 뷰 교집합으로 한정(유실 방지), 실행은 운영 건수 확인 후 별도 결정. celfit-front 필터 어휘 동기화는 별도 저장소 후속(백엔드 선행 안전) | [specs/2026-08-09-esthetic-taxonomy-design.md](docs/superpowers/specs/2026-08-09-esthetic-taxonomy-design.md) |
```

(DECISIONS.md 실제 표 헤더·컬럼 구성이 다르면 그 형식을 따른다 — 내용은 위 그대로.)

- [ ] **Step 2: 스펙 상태 헤더 전환**

`docs/superpowers/specs/2026-08-09-esthetic-taxonomy-design.md` 첫머리:

```markdown
> 상태: 🟢 활성 · ✅ 구현됨(2026-08-09 — 시드 V20260809063533·ops 동봉) · 소급 실행·프론트 동기화는 후속
```

- [ ] **Step 3: Commit**

```bash
git add DECISIONS.md docs/superpowers/specs/2026-08-09-esthetic-taxonomy-design.md
git commit -m "docs: 에스테틱 대분류 결정 기록·스펙 ✅ 전환

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 계획 밖 후속 작업 (머지 후)

- **celfit-front**: '에스테틱' + 중·소분류 라벨 14개를 필터 어휘에 표기 그대로 추가 (별도 저장소)
- **소급 실행 여부 결정**: 운영 DB에서 ops SQL dry-run으로 대상 건수 확인 → 실행 판단 (반드시 사용자 확인)
- **배포 후 표본 확인**: 어드민(8082 `/ui`)에서 분석 잡 트리거 → 에스테틱 콘텐츠가 `main_category='esthetic'`으로 저장되는지 확인
