# 대시보드 수동 RowMapper 응급 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `BrandReadRepository`의 대량 행 쿼리 3개를 수동 RowMapper로 교체해 성과 대시보드 조립 8.4초를 ~3.5초로 내린다.

**Architecture:** [설계](../specs/archive/2026-08-31-dashboard-manual-rowmapper-design.md) 참조. `JdbcClient.query(Class)`의 `SimplePropertyRowMapper`(행당 ~47µs, raw JDBC의 20배 실측)를 컬럼명 기반 수동 람다로 바꾼다. SQL·record·메서드 시그니처·소비자 전부 무변경 — 변경 파일은 리포지토리 1개 + 테스트 1개.

**Tech Stack:** Java 21, Spring Boot 4.1(`JdbcClient`), Testcontainers(PostgreSQL).

## Global Constraints

- 브랜치 `feat/perf-dashboard-manual-rowmapper`(develop 기준, 이 worktree에 이미 체크아웃됨), PR은 develop 대상.
- 커밋 메시지 한국어, prefix `feat(was):`/`test(was):`/`docs:`.
- 테스트는 Docker Desktop 기동 필요, `DOCKER_HOST` **미설정**이 정답(08-09 확인 — colima 아님).
- 단위 실행: `./gradlew :was:test --tests "com.celfit.was.monitoring.BrandReadRepositoryTest"`. 전체 `:was:test`는 마지막 태스크에서만.
- SQL·record 정의·메서드 시그니처는 절대 바꾸지 않는다(응급 범위 계약).

---

### Task 1: findAuthors 널 필드 매핑 특성화 테스트

기존 테스트 `게시자_프로필은_id와_username_두_경로로_조회된다`는 IG_B(널 필드 행)를 `findAuthorsByUsername`으로만 조회한다 — 이번에 바꾸는 `findAuthors` 경로로도 널 매핑을 못박는다. **행동 변경이 아니라 리팩터 대비 특성화 테스트라 지금도 통과해야 한다** (실패하면 현행 이해가 틀린 것이니 중단하고 보고).

**Files:**
- Modify: `was/src/test/java/com/celfit/was/monitoring/BrandReadRepositoryTest.java` (기존 테스트 `게시자_프로필은_id와_username_두_경로로_조회된다`, ~575행)

**Interfaces:**
- Consumes: `BrandReadRepository.findAuthors(Collection<String>)` → `List<AuthorRow>`, `AuthorRow(String igUserId, String username, String fullName, Long followers, String profilePicUrl, Boolean isVerified, String imageObjectPath)`
- Produces: 없음(테스트만)

- [ ] **Step 1: 기존 테스트에 findAuthors 널 행 검증 추가**

기존 테스트 메서드 안, `byId` 검증 블록 뒤에 추가(IG_B는 이미 시드돼 있다 — full_name·followers·profile_pic_url·is_verified NULL, image_object_path는 INSERT 컬럼 목록에 없어 NULL):

```java
		// 널 필드 행도 findAuthors 경로로 못박는다(2026-08-31 수동 매퍼 교체 대비) — 박싱 타입
		// 전부 null 보존, 원시형 없음.
		List<AuthorRow> nullFields = repository.findAuthors(List.of("IG_B"));
		assertThat(nullFields).hasSize(1);
		assertThat(nullFields.get(0).igUserId()).isEqualTo("IG_B");
		assertThat(nullFields.get(0).fullName()).isNull();
		assertThat(nullFields.get(0).followers()).isNull();
		assertThat(nullFields.get(0).profilePicUrl()).isNull();
		assertThat(nullFields.get(0).isVerified()).isNull();
		assertThat(nullFields.get(0).imageObjectPath()).isNull();
```

- [ ] **Step 2: 테스트 실행 — 통과 확인(특성화)**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.BrandReadRepositoryTest"`
Expected: PASS (현행 매퍼에서도 통과 — 이 테스트의 역할은 교체 후에도 같은 값이 나옴을 못박는 것)

- [ ] **Step 3: Commit**

```bash
git add was/src/test/java/com/celfit/was/monitoring/BrandReadRepositoryTest.java
git commit -m "test(was): findAuthors 널 필드 매핑 특성화 — 수동 매퍼 교체 대비"
```

---

### Task 2: findBrandPostIndex 수동 매퍼

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java` (`findBrandPostIndex`, ~148행 — `.query(BrandPostIndexRow.class)` 자리)

**Interfaces:**
- Consumes: `BrandPostIndexRow(String shortCode, OffsetDateTime takenAt, OffsetDateTime tagDetectedAt, OffsetDateTime directRegisteredAt, OffsetDateTime hashtagDetectedAt, OffsetDateTime unavailableAt, String rawAuthorUsername, String authorIgUserId, Boolean isPaidPartnership, boolean captionMarker, String contentType, String adVerdict, String authorUsername, String authorFullName, String authorProfilePicUrl, String authorImageObjectPath, Long authorFollowers)` — 정의 그대로, 수정 금지
- Produces: 시그니처 무변경 — 소비자(`BrandPostAssembler`·`BrandIndexCache`·`PerformanceContentAssembler`) 손대지 않음

- [ ] **Step 1: 매퍼 교체**

`findBrandPostIndex`의 `.query(BrandPostIndexRow.class)`를 다음으로 교체(SELECT 절 별칭과 1:1 — `raw_author_username`은 `t.author_username`의 별칭, `author_*`는 `author_profile` 조인 별칭):

```java
				.query((rs, i) -> new BrandPostIndexRow(
						rs.getString("short_code"),
						rs.getObject("taken_at", OffsetDateTime.class),
						rs.getObject("tag_detected_at", OffsetDateTime.class),
						rs.getObject("direct_registered_at", OffsetDateTime.class),
						rs.getObject("hashtag_detected_at", OffsetDateTime.class),
						rs.getObject("unavailable_at", OffsetDateTime.class),
						rs.getString("raw_author_username"),
						rs.getString("author_ig_user_id"),
						rs.getObject("is_paid_partnership", Boolean.class),
						rs.getBoolean("caption_marker"),
						rs.getString("content_type"),
						rs.getString("ad_verdict"),
						rs.getString("author_username"),
						rs.getString("author_full_name"),
						rs.getString("author_profile_pic_url"),
						rs.getString("author_image_object_path"),
						rs.getObject("author_followers", Long.class)))
```

- 널러블 박싱(`Boolean`·`Long`)은 `rs.getObject(col, 타입.class)` — 널 보존.
- 원시 `boolean`(`captionMarker`)만 `rs.getBoolean` — SQL 식 구조상 널 불가(`FALSE AND NULL = FALSE`).
- import 확인: `OffsetDateTime`은 이 파일에 이미 있다(record 정의가 쓴다).

- [ ] **Step 2: 메서드 javadoc에 근거 한 줄 추가**

기존 `findBrandPostIndex` javadoc 마지막 문단으로:

```java
	 * <p>매핑은 수동 람다다(2026-08-31) — 창 안 전 행(운영 1.6만 행대)을 싣는 쿼리라
	 * {@code query(Class)}의 이름 기반 리플렉션 매핑(행당 ~47µs 실측, raw 대비 20배)이 지배 비용이
	 * 된다. 조립 8.4초 분해는 2026-08-31 수동 RowMapper 설계 §1 참조.
```

- [ ] **Step 3: 테스트 실행**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.BrandReadRepositoryTest"`
Expected: PASS — 특히 `인덱스는_캡션_대신_마커_매치와_작성자_판정_컬럼을_준다`(작성자 조인 미스 → author 컬럼 전부 널)와 `인덱스_프로젝션은_브랜드_창_스코프로_판정_입력만_읽는다`(메타 미보강 → captionMarker false·contentType/adVerdict 널)가 널 의미론을 검증한다.

- [ ] **Step 4: Commit**

```bash
git add was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java
git commit -m "feat(was): findBrandPostIndex 수동 RowMapper — 행당 리플렉션 매핑 제거"
```

---

### Task 3: findLatestSnapshotsForBrand · findAuthors 수동 매퍼

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java` (`findLatestSnapshotsForBrand` ~204행, `findAuthors` ~278행)

**Interfaces:**
- Consumes: `LatestSnapshotRow(String shortCode, LocalDate capturedOn, String contentType, Long views, Long likes, boolean likesHidden, Long comments)` · `AuthorRow`(Task 1 참조) — 정의 그대로, 수정 금지
- Produces: 시그니처 무변경

- [ ] **Step 1: findLatestSnapshotsForBrand 매퍼 교체**

`.query(LatestSnapshotRow.class)`를 다음으로:

```java
				.query((rs, i) -> new LatestSnapshotRow(
						rs.getString("short_code"),
						rs.getObject("captured_on", LocalDate.class),
						rs.getString("content_type"),
						rs.getObject("views", Long.class),
						rs.getObject("likes", Long.class),
						rs.getBoolean("likes_hidden"),
						rs.getObject("comments", Long.class)))
```

원시 `likesHidden`은 `rs.getBoolean` — `brand_post_snapshot.likes_hidden`은 `NOT NULL DEFAULT false`(monitoring V20260806150000 DDL). javadoc 마지막 문단으로 추가:

```java
	 * <p>매핑은 수동 람다다(2026-08-31) — 게시물당 1행이라도 창 안 전 게시물(운영 1.5만 행대)을
	 * 싣는다. 근거는 {@link #findBrandPostIndex} 매핑 주석과 같다.
```

- [ ] **Step 2: findAuthors 매퍼 교체**

`.query(AuthorRow.class)`를 다음으로:

```java
				.query((rs, i) -> new AuthorRow(
						rs.getString("ig_user_id"),
						rs.getString("username"),
						rs.getString("full_name"),
						rs.getObject("followers", Long.class),
						rs.getString("profile_pic_url"),
						rs.getObject("is_verified", Boolean.class),
						rs.getString("image_object_path")))
```

javadoc이 없는 메서드라 새로 한 줄:

```java
	/** 게시자 프로필 배치 조회 — 매핑은 수동 람다(2026-08-31, 근거는 {@link #findBrandPostIndex} 참조). */
```

주의: `findAuthorsByUsername`도 같은 `AuthorRow`를 쓰지만 **손대지 않는다** — 폴백 경로라 행수가 작고(범위 계약: 실측된 3개만), DISTINCT ON 셀렉트 절이 같은 컬럼을 주므로 바꿀 필요도 없다.

- [ ] **Step 3: 테스트 실행**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.BrandReadRepositoryTest"`
Expected: PASS — `최신_스냅샷_지표는_브랜드_창_스코프다`(피드 views 널·likesHidden true)와 Task 1의 findAuthors 널 필드 검증이 널 의미론을 못박는다.

- [ ] **Step 4: Commit**

```bash
git add was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java
git commit -m "feat(was): findLatestSnapshotsForBrand·findAuthors 수동 RowMapper"
```

---

### Task 4: 전체 회귀 · 문서 정리 · PR

**Files:**
- Move: `docs/superpowers/plans/2026-08-31-dashboard-manual-rowmapper.md` → `docs/superpowers/plans/archive/`
- Modify: `docs/superpowers/specs/archive/2026-08-31-dashboard-manual-rowmapper-design.md` (상태 헤더 🟢 활성 → ✅ 구현됨)

**Interfaces:** 없음(검증·문서·PR)

- [ ] **Step 1: was 모듈 전체 테스트**

Run: `./gradlew :was:test`
Expected: PASS 전체. 실패가 있으면 원인 파악 전엔 다음 단계로 가지 않는다(특히 매퍼 교체 3곳과 무관해 보이는 실패도 보고).

- [ ] **Step 2: 스펙 상태 갱신 + 계획 아카이브**

```bash
sed -i '' 's/> 상태: 🟢 활성 (2026-08-31 작성)/> 상태: ✅ 구현됨 (2026-08-31 작성·구현)/' docs/superpowers/specs/archive/2026-08-31-dashboard-manual-rowmapper-design.md
git mv docs/superpowers/plans/2026-08-31-dashboard-manual-rowmapper.md docs/superpowers/plans/archive/
git add docs/superpowers/specs/archive/2026-08-31-dashboard-manual-rowmapper-design.md
git commit -m "docs: 수동 RowMapper 응급 구현 완료 — 스펙 상태 갱신·계획 아카이브"
```

- [ ] **Step 3: push + develop 대상 PR**

```bash
git push -u origin feat/perf-dashboard-manual-rowmapper
gh pr create --base develop --title "feat(was): 대시보드 대량 행 쿼리 3개 수동 RowMapper — 조립 8.4s→~3.5s" --body "$(cat <<'EOF'
## 무엇
`BrandReadRepository`의 `findBrandPostIndex`·`findLatestSnapshotsForBrand`·`findAuthors`를 `query(Class)` → 컬럼명 기반 수동 람다 매퍼로 교체. SQL·record·시그니처·소비자 무변경.

## 왜 (실측 — [설계 §1](docs/superpowers/specs/archive/2026-08-31-dashboard-manual-rowmapper-design.md))
성과 대시보드 조립 8.4초의 71%(~5.4s)가 `SimplePropertyRowMapper`의 행 매핑이다 — 행마다 camelCase `findColumn` 실패 예외(요청당 ~43만 개)·값당 ConversionService·리플렉션 생성으로 raw JDBC의 **20배**(로컬 47µs/행 vs 2µs/행). 커넥션 풀 대기(같은 요청 `findAccount` 14콜 8ms)·전송(80ms/12MB)·DB 고정비는 배제 확인.

## 기대 효과 (스테이징 유저 5, 브랜드 7연결 기준)
| 단계 | 전 | 후(기대) |
|---|---|---|
| findBrandPostIndex (5콜) | 5,727ms | ~1.9s |
| findLatestSnapshotsForBrand (5콜) | 1,174ms | ~0.25s |
| findAuthors (1콜) | 599ms | ~0.45s |
| **요청 전체** | **8.4s** | **~3.5s** |

브랜드 목록·인플루언서 표면(`BrandIndexCache` 경유 동일 쿼리)도 같이 수혜.

## 검증
- `:was:test` 전체 통과. 널 의미론은 기존 조인 미스·피드 NULL 테스트 + findAuthors 널 필드 특성화 테스트(신규)가 못박음.
- 머지 → 스테이징 배포 후 `docker logs deploy-test-was-1`의 `요청 단계` 로그로 전후 비교(설계 §5).

## 안 하는 것
findAuthors IN 6,633개 바인드 전개(~0.4s 잔존)·협찬 정규식(~1.35s)·기간 스코핑 — 후속(설계 §6).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: 배포 후 검증 안내(사용자에게)**

머지·스테이징 배포는 CD 경로(develop→staging)라 사용자 결정이다. 배포되면 유저 5로 대시보드를 열고:

```bash
ssh ubuntu@155.248.187.106 'docker logs deploy-test-was-1 2>&1 | grep "요청 단계" | grep performance-dashboard | tail -20'
```

기대: `findBrandPostIndex` ~1.9s(5콜)·`findLatestSnapshotsForBrand` ~0.25s. 스테이징은 Loki에 없다(alloy가 `test-*` drop) — 컨테이너 로그 직접 조회만 가능하고, 로그는 재배포 시 유실되니 배포 직후 측정한다.
