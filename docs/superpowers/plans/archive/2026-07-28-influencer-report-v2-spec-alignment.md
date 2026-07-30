# 인플루언서 리포트 v2 스펙 정렬 (6.22·6.23·6.24) — PR #149 증분 구현 계획

> 상태: ✅ 구현됨 (PR #149 머지, 2026-07-28 — 07-27 계획과 동일 PR)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 프론트 확정 스펙 6.22(발굴 리포트 v2)·6.23(유사 인플루언서 카드)·6.24(이메일 중복 확인)를 PR #149 브랜치(`feat/influencer-report-redesign`)에 얹어 머지 가능한 상태로 만든다.

**Architecture:** ① 프론트 실어댑터가 `/v1/influencers/{id}/ai-report`를 라이브 소비 중이므로(celfit-front `adapter.real.ts`) **v1 리포트 표면은 develop 원형으로 복원**하고, 6.22·6.23은 신설 패키지 `was/v2/influencer`에 병행 구현한다(6.5 폐기는 프론트 전환 후 별도 PR). ② 유효 팔로워는 스펙 7절 17번(6.21과 동일 소스·동일 값)에 따라 **실반응 산식(07-28 확정)을 공용 Java 유틸로 추출**하고, 발굴 목록(6.21)의 구 산식 SQL CTE를 걷어내 Java 계산으로 전환한다. ③ 브랜드 hover 엔드포인트(`/v1/brands/{brand}/influencers`)는 스펙에서 사라졌으므로 삭제하고 `ads.brands[].otherInfluencers` 인라인으로 흡수한다. analytics(V39·V40·LLM 5종·백필)는 무변경.

**Tech Stack:** Java 21 / Spring Boot 4.1 / JdbcClient / Testcontainers PostgreSQL / Jackson 3(`tools.jackson.*`).

**스펙 [확인 필요] 항목에 대한 백엔드 확정(프론트 전달, PR 본문에 포함):**
- 7절 20번(AI 문구 파이프라인): LLM 5종(tagline·traits·요약 3종)은 새벽 배치(analytics), headline은 was 사실값 템플릿. 갱신 주기는 신규 게시물 감지 시 재분석(쿨다운 규칙).
- 7절 21번(유사도 기준): 동일 주 카테고리 × traits Jaccard 내림차순 × 팔로워 근접. 최대 9명.
- 7절 22번(otherInfluencers 기준): 풀 내 같은 브랜드의 협찬(ad_type='sponsored') 게시물 보유 계정, 협업 수 내림차순 상위 5.
- 6.24 레이트리밋: IP당 분당 30회(기존 RateLimiter 재사용), 초과 시 429 RATE_LIMITED.
- 7절 17번(유효 팔로워, 07-28 사용자 확정): 산식은 실반응 공식(EffectiveFollowers 유틸) 현행 유지. **화면 라벨은 반드시 "유효 팔로워"** — "반응 팔로워" 등 대체 명칭 금지. **표기는 명수 주(主)·% 보조**(예: "유효 팔로워 340명 (3%)") — 중앙값이 3%대라 % 단독 표기는 초라해 보이지만 명수+근거 툴팁이 정직한 소구. 운영 분포(07-28 실측): 중앙값 3.3%·상위 10% 18%·최대 89.9%(100% 불가).

**스펙과의 의도적 차이(전부 스펙이 null·미정 여지를 둔 지점):**
- `adIntervalDays`: 광고 2건 이상이어도 전부 같은 날(스팬 0)이면 null("평균 0일 간격" 오해 방지).
- `adsSummary`: 광고 0건이면 null(스펙 그대로) — analytics가 "협찬 없음" 문구를 저장해도 서빙하지 않는다(파이프라인 무변경).
- headline 문구는 조사 회피 명사형("메디큐브 협업") — 스펙 예시("메디큐브와 협업")와 어투만 다름.

---

## 작업 환경 (Task 0)

기존 worktree `.worktrees/report-redesign`(브랜치 `feat/influencer-report-redesign`)에서 작업한다.

- [ ] **Step 1: worktree 최신화 + develop 머지**

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/report-redesign
git fetch origin
git status   # 깨끗해야 함 — 아니면 중단하고 사용자에게 보고
git merge origin/develop
```

충돌 예상 지점: `ARCHITECTURE.md`(§5·§7 — 양쪽 갱신분 모두 보존), 그 외 was/v1/influencer는 #151(발굴 목록)이 신규 파일만 추가해 충돌 없어야 정상. 머지 커밋 메시지는 기본값 유지.

- [ ] **Step 2: 이 계획 문서를 worktree로 복사**

```bash
cp /Users/woomin/Project/hypenow-backend/docs/superpowers/plans/2026-07-28-influencer-report-v2-spec-alignment.md \
   docs/superpowers/plans/
```

- [ ] **Step 3: 마이그레이션 번호 재확인** — develop 최신 analysis 마이그레이션이 V38이면 브랜치의 V39·V40 유지(현재 확인됨). V39+가 새로 들어와 있으면 재번호(V18 경합 전례).

```bash
ls analytics/src/main/resources/db/migration/analysis/ | sort -V | tail -5
```

---

### Task 1: v1 리포트 표면 복원 + 흡수된 표면 삭제

v1 재작성(breaking)을 develop 원형으로 되돌려 배포 결합을 끊고, 스펙에서 사라진 표면(v1 similar, 브랜드 hover 엔드포인트)을 삭제한다.

**Files:**
- Restore(develop 원형): `was/src/main/java/com/celfit/was/v1/influencer/InfluencerAiReport.java`, `V1InfluencerReportController.java`, `V1InfluencerReportRepository.java`, `V1InfluencerReportAssembler.java` + 테스트 `V1InfluencerReportAssemblerTest.java`, `V1InfluencerReportControllerTest.java`
- Delete: `was/src/main/java/com/celfit/was/v1/influencer/SimilarInfluencer.java`, `was/src/main/java/com/celfit/was/v1/brand/`(3파일), `was/src/test/java/com/celfit/was/v1/brand/V1BrandControllerTest.java`

- [ ] **Step 1: 복원·삭제 실행**

```bash
git checkout origin/develop -- \
  was/src/main/java/com/celfit/was/v1/influencer/InfluencerAiReport.java \
  was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportController.java \
  was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportRepository.java \
  was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerReportAssembler.java \
  was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerReportAssemblerTest.java \
  was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerReportControllerTest.java
git rm --ignore-unmatch was/src/main/java/com/celfit/was/v1/influencer/SimilarInfluencer.java
git rm -r --ignore-unmatch was/src/main/java/com/celfit/was/v1/brand was/src/test/java/com/celfit/was/v1/brand
```

- [ ] **Step 2: v1 리포트 테스트 통과 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerReport*"
```
Expected: PASS (develop과 동일 상태로 복귀). 컴파일 에러가 나면 브랜치 잔여 파일이 v1 신 DTO를 참조하는 것 — 그 참조 파일도 삭제 대상인지 확인(이 시점 기준 잔여 참조는 없어야 정상).

- [ ] **Step 3: 커밋**

```bash
git add -A
git commit -m "revert(was): v1 리포트 표면 develop 원형 복원 — 6.22는 /v2 병행 신설로 전환, v1 similar·브랜드 hover 표면 삭제"
```

---

### Task 2: 유효 팔로워 공용 유틸 `EffectiveFollowers`

07-28 확정 실반응 산식(#149 assembler에 있던 코드)을 v1 발굴 목록과 v2 리포트가 공용하는 유틸로 추출한다. Task 1에서 v1 assembler를 복원하며 이 코드가 사라졌으므로 여기서 신설한다.

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/influencer/EffectiveFollowers.java`
- Test: `was/src/test/java/com/celfit/was/v1/influencer/EffectiveFollowersTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.v1.influencer.EffectiveFollowers.Post;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 유효 팔로워 실반응 산식(07-28 확정) 계약 — 산식 정의는 EffectiveFollowers javadoc. */
class EffectiveFollowersTest {

	@Test
	void 기본_케이스는_평균_인정_반응_비율() {
		// followers 10000, 2게시물: (100+10)=110, (200+20)=220 — 앵커(39×(댓글+1)) 미달이라 그대로.
		// r = (110+220)/2/10000 = 0.0165, 지수 = max(1, 2×0.25) = 1 → 10000×0.0165 = 165
		Long result = EffectiveFollowers.estimate(10_000L,
				List.of(new Post(null, 100L, 10L), new Post(null, 200L, 20L)));
		assertThat(result).isEqualTo(165L);
	}

	@Test
	void 조회수가_팔로워를_넘으면_안분() {
		// views 40000 > followers 10000 → engaged 400×(10000/40000) = 100, 앵커 39×11=429 미달
		// r = 100/10000 = 0.01, 지수 1 → 100
		Long result = EffectiveFollowers.estimate(10_000L,
				List.of(new Post(40_000L, 390L, 10L)));
		assertThat(result).isEqualTo(100L);
	}

	@Test
	void 비정상_좋아요는_댓글_앵커로_컷() {
		// 좋아요 10000·댓글 1 → engaged 10001, 앵커 39×2=78 → 78 채택. r=78/10000, 지수 1 → 78
		Long result = EffectiveFollowers.estimate(10_000L,
				List.of(new Post(null, 10_000L, 1L)));
		assertThat(result).isEqualTo(78L);
	}

	@Test
	void 게시물_12개면_지수_3으로_확장() {
		// 12게시물 전부 r_post=0.01 → r=0.01, 지수 = 12×0.25 = 3 → 1-(1-0.01)^3 = 0.029701 → 297
		List<Post> posts = java.util.Collections.nCopies(12, new Post(null, 90L, 10L));
		assertThat(EffectiveFollowers.estimate(10_000L, posts)).isEqualTo(297L);
	}

	@Test
	void 근거_없으면_null() {
		assertThat(EffectiveFollowers.estimate(null, List.of(new Post(null, 1L, 1L)))).isNull();
		assertThat(EffectiveFollowers.estimate(0L, List.of(new Post(null, 1L, 1L)))).isNull();
		assertThat(EffectiveFollowers.estimate(10_000L, List.of())).isNull();
	}

	@Test
	void 음수_센티널은_0으로_클램프() {
		// likes -1(비공개 센티널) → 0, 댓글 10 → engaged 10, 앵커 429 미달. r=10/10000 → 10
		Long result = EffectiveFollowers.estimate(10_000L, List.of(new Post(null, -1L, 10L)));
		assertThat(result).isEqualTo(10L);
	}
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.influencer.EffectiveFollowersTest"
```
Expected: COMPILE FAIL (`EffectiveFollowers` 미존재)

- [ ] **Step 3: 구현** — #149 assembler에 있던 코드를 그대로 이관(산식·상수·주석 불변)

```java
package com.celfit.was.v1.influencer;

import java.util.List;

/**
 * 유효 팔로워 = 게시물당 평균 실반응 팔로워 수(07-28 확정 산식) — 6.21 발굴 카드와 6.22 리포트가
 * 공용하는 단일 원천(스펙 7절 17번: 동일 소스·동일 값). 피어와 무관한 절대 측정 —
 * "관측 불가한 값을 추정 확장하지 마라, 100%는 장치가 아니라 현실이 막아야 한다"(07-28 원칙).
 */
public final class EffectiveFollowers {

	/** 산식 입력 1게시물 — views는 피드면 null(3.6), likes -1은 비공개 센티널(0으로 클램프). */
	public record Post(Long views, Long likes, Long comments) {
	}

	/** 댓글 앵커 계수 — 모집단 38,474게시물의 좋아요:댓글 비율 상위 90% 경계(07-28 실측 39:1).
	 *  이 비율을 크게 벗어나는 좋아요는 팔로워 반응으로 보지 않는다(탐색탭 유입·구매성 좋아요 컷). */
	private static final double LIKES_PER_COMMENT_ANCHOR = 39.0;

	/** 중복 계수 — 게시물당 반응을 "윈도우 중 1회 이상 반응한 고유 팔로워"로 확장할 때의
	 *  실효 독립 기회 비율(지수 = 게시물 수 × 0.25, 12개면 3). 임의 설정(07-28 확정) —
	 *  댓글 작성자 수집이 재개되면 계정별 실측 중복률로 대체 예정. */
	private static final double DUPLICATION_FACTOR = 0.25;

	private EffectiveFollowers() {
	}

	/**
	 * 유효 팔로워 = 팔로워 × (1 − (1−r)^(n×중복계수)) — "최근 n개 중 1회 이상 반응한 고유 팔로워" 추정.
	 * 선형 ×n은 같은 팔로워를 거듭 세어 팔로워 초과 모순이 나므로, 이미 반응한 팔로워를 다시 세지
	 * 않는 포함-배제 형태를 쓴다(보통 계정에선 ×3과 사실상 동일, 반응률 높은 계정만 중복 차감).
	 * r = 게시물당 평균 인정 반응 ÷ 팔로워 —
	 *   인정 반응 = min( (좋아요+댓글) × min(1, 팔로워/조회수),   ← 릴스 바이럴 안분
	 *                    ANCHOR × (댓글+1) )                       ← 비정상 좋아요 컷
	 * 운영 실측(07-28, 6,321계정): 중앙값 3.4%·상위 1% 45.7%·최대 89.9% — 90% 이상 0계정,
	 * 100%는 셈법 자체로 불가(캡·클램프 없음). 근거 없으면 null.
	 */
	public static Long estimate(Long followers, List<Post> posts) {
		if (followers == null || followers <= 0 || posts.isEmpty()) {
			return null;
		}
		double sum = 0;
		for (Post p : posts) {
			long likes = p.likes() == null ? 0 : Math.max(p.likes(), 0);
			long comments = p.comments() == null ? 0 : Math.max(p.comments(), 0);
			double engaged = likes + comments;
			if (p.views() != null && p.views() > followers) {
				engaged = engaged * followers / (double) p.views(); // 도달 중 팔로워 비중으로 안분
			}
			sum += Math.min(engaged, LIKES_PER_COMMENT_ANCHOR * (comments + 1));
		}
		double r = Math.min(sum / posts.size() / followers, 1.0);
		// 게시물이 적으면(지수 < 1) 확장이 축소로 뒤집히므로 하한 1 — 최소한 게시물당 측정값은 보장
		double exponent = Math.max(1.0, posts.size() * DUPLICATION_FACTOR);
		return Math.round(followers * (1 - Math.pow(1 - r, exponent)));
	}
}
```

- [ ] **Step 4: 통과 확인 후 커밋**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.influencer.EffectiveFollowersTest"
git add was/src/main/java/com/celfit/was/v1/influencer/EffectiveFollowers.java \
        was/src/test/java/com/celfit/was/v1/influencer/EffectiveFollowersTest.java
git commit -m "feat(was): 유효 팔로워 실반응 산식 공용 유틸 추출 — 6.21·6.22 단일 원천(스펙 7절 17번)"
```

---

### Task 3: 발굴 목록(6.21) 유효 팔로워를 신 산식 Java 계산으로 전환

develop의 발굴 카드가 구 산식(피어 중앙값 ER SQL CTE)을 쓰고 있어 6.22와 값이 갈린다. CTE를 걷어내고 페이지 핸들의 시계열을 한 번 더 조회해 `EffectiveFollowers`로 계산한다(페이지 ≤ 24건이라 비용 무시 가능).

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryRepository.java`
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryAssembler.java`
- Modify: `was/src/main/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryController.java`
- Test: `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryAssemblerTest.java`, `V1InfluencerDiscoveryRepositoryTest.java`, `V1InfluencerDiscoveryControllerTest.java`

- [ ] **Step 1: 실패하는 assembler 테스트 추가**

`V1InfluencerDiscoveryAssemblerTest`에 추가(기존 픽스처의 `CardRow` 생성자에서 `effectiveFollowers` 인자 제거가 동반된다 — 기존 테스트도 새 시그니처로 함께 수정):

```java
	@Test
	void 유효_팔로워는_실반응_산식으로_계산() {
		// followers 10000, 2게시물 (100+10)·(200+20) → EffectiveFollowersTest 기본 케이스와 동일 = 165
		var rows = List.of(cardRow("a", 10_000L)); // 기존 헬퍼에서 effectiveFollowers 인자만 빠진 형태
		var engagements = List.of(
				new V1InfluencerDiscoveryRepository.EngagementRow("a", null, 100L, 10L),
				new V1InfluencerDiscoveryRepository.EngagementRow("a", null, 200L, 20L));
		var cards = assembler.toCards(rows, List.of(), List.of(), List.of(), engagements);
		assertThat(cards.get(0).effectiveFollowers()).isEqualTo(165L);
	}

	@Test
	void 시계열_없는_계정은_유효_팔로워_null() {
		var cards = assembler.toCards(List.of(cardRow("a", 10_000L)),
				List.of(), List.of(), List.of(), List.of());
		assertThat(cards.get(0).effectiveFollowers()).isNull();
	}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerDiscoveryAssemblerTest"
```
Expected: COMPILE FAIL (`EngagementRow` 미존재, `toCards` 시그니처 불일치)

- [ ] **Step 3: Repository 수정**

`V1InfluencerDiscoveryRepository`에서:

1. `EFFECTIVE_FOLLOWERS_CTES` 상수 전체 삭제.
2. `findCards`의 SELECT에서 `CASE WHEN su.avg_er_pct ... END AS effective_followers` 컬럼과 `LEFT JOIN peer b ... LEFT JOIN med m ... CROSS JOIN gmed g` 3줄 삭제, `EFFECTIVE_FOLLOWERS_CTES +` 접두 제거.
3. `CardRow` record에서 `Long effectiveFollowers` 컴포넌트 삭제.
4. 신규 메서드·record 추가:

```java
	/** 유효 팔로워 재료 — 페이지 핸들의 창 내 시계열(순서 무관, 산식이 평균이라). 계산은 Java(EffectiveFollowers). */
	public List<EngagementRow> findEngagements(List<String> handles) {
		if (handles.isEmpty()) {
			return List.of();
		}
		return jdbcClient.sql("""
				SELECT account_handle, views, likes, comments
				FROM account_content_series
				WHERE account_handle IN (:handles)
				""").param("handles", handles).query(EngagementRow.class).list();
	}

	public record EngagementRow(String accountHandle, Long views, Long likes, Long comments) {
	}
```

- [ ] **Step 4: Assembler 수정**

`toCards`에 `List<EngagementRow> engagements` 파라미터 추가, 핸들별 그룹핑 후 계산:

```java
	public List<InfluencerCard> toCards(List<CardRow> rows, List<ShareRow> shares,
			List<BrandRow> brands, List<ThumbRow> thumbs, List<EngagementRow> engagements) {
		// ... 기존 sharesBy/brandsBy/thumbsBy 그대로 ...
		Map<String, List<EngagementRow>> engagementsBy = engagements.stream()
				.collect(Collectors.groupingBy(EngagementRow::accountHandle));
		return rows.stream().map(r -> toCard(r,
				sharesBy.getOrDefault(r.handle(), List.of()),
				brandsBy.getOrDefault(r.handle(), List.of()),
				thumbsBy.getOrDefault(r.handle(), List.of()),
				engagementsBy.getOrDefault(r.handle(), List.of()))).toList();
	}
```

`toCard`는 `List<EngagementRow> engagements`를 받아 카드의 `effectiveFollowers` 자리에:

```java
				EffectiveFollowers.estimate(r.followers(), engagements.stream()
						.map(e -> new EffectiveFollowers.Post(e.views(), e.likes(), e.comments()))
						.toList()),
```

- [ ] **Step 5: Controller 배선** — `V1InfluencerDiscoveryController`에서 카드 조립 호출부에 `repository.findEngagements(handles)`를 추가로 넘긴다(기존 shares/brands/thumbs 보강 조회와 같은 자리, 같은 `handles` 목록 사용).

- [ ] **Step 6: 나머지 테스트 정렬**

- `V1InfluencerDiscoveryRepositoryTest`: 구 산식 SQL의 effective_followers 기대값 검증 케이스를 삭제(산식이 Java로 이동), `CardRow` 픽스처·컬럼 단언에서 effectiveFollowers 제거. `findEngagements` 검증 1건 추가(시드된 시계열 행이 핸들별로 반환되는지).
- `V1InfluencerDiscoveryControllerTest`: `toCards` 목/픽스처를 새 시그니처로 수정.

```bash
./gradlew :was:test --tests "com.celfit.was.v1.influencer.V1InfluencerDiscovery*"
```
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/influencer/ was/src/test/java/com/celfit/was/v1/influencer/
git commit -m "feat(was): 발굴 카드 유효 팔로워를 실반응 산식으로 전환 — 6.22와 동일 값 보장(구 CTE 제거)"
```

---

### Task 4: v2 DTO + Repository

**Files:**
- Create: `was/src/main/java/com/celfit/was/v2/influencer/InfluencerAiReportV2.java`
- Create: `was/src/main/java/com/celfit/was/v2/influencer/V2InfluencerReportRepository.java`
- Test: `was/src/test/java/com/celfit/was/v2/influencer/V2InfluencerReportRepositoryTest.java`

- [ ] **Step 1: DTO 작성** (필드 순서 = 스펙 6.22 표 순서)

```java
package com.celfit.was.v2.influencer;

import java.math.BigDecimal;
import java.util.List;

/**
 * 스펙 6.22 발굴 리포트 AI 분석 v2 — 프론트는 6.4를 페어 호출해 차트 막대·광고 스트립·게시물 카드를
 * 파생하므로 이 응답엔 게시물별 재료(bars)가 없다. tagline·perfSummary·contentSummary는 비-null
 * 계약 — 카피 미생성 계정은 컨트롤러가 404(리포트 미생성). sponsored·adsSummary는 광고 0건이면 null.
 */
public record InfluencerAiReportV2(String tagline, Long analyzedCount, Long totalPosts,
		Long effectiveFollowers, Activity activity,
		String perfSummary, String contentSummary, String adsSummary,
		StatSet overall, StatSet sponsored,
		List<TrendPoint> viewsTrend, List<TrendPoint> erTrend,
		ContentMix contentMix, Ads ads) {

	public record Activity(Long lastUploadDaysAgo, BigDecimal avgIntervalDays) {
	}

	/** overall·sponsored 공용 지표 세트. views.value는 조회수 공개 게시물 평균 — 세트 내 해당 게시물 없으면 null. */
	public record StatSet(MetricCell views, MetricCell er, MetricCell likes, MetricCell comments,
			BigDecimal viewsPerFollower, Long sampleCount) {
	}

	/** growthPct: 표본 올린 순 반분, 앞 구간 평균 대비 뒤 구간 평균 증감률(정수 %). 반분 불가(표본 2 미만)면 null. */
	public record MetricCell(BigDecimal value, Integer growthPct) {
	}

	/** date는 KST 달력 날짜 "YYYY-MM-DD"(스펙 3.4). */
	public record TrendPoint(String date, BigDecimal value) {
	}

	public record ContentMix(List<Category> categories, List<String> traits) {
		public record Category(String label, Long count) {
		}
	}

	/** 광고 0건이어도 항상 포함(sponsoredCount 0, 나머지 null·빈 배열). */
	public record Ads(Long sponsoredCount, Long adIntervalDays, Long lastAdDaysAgo, String headline,
			List<Brand> brands) {
		/** contentIds는 올린 순 short_code — 프론트가 6.4 recentContents와 조인. */
		public record Brand(String name, Long count, List<OtherInfluencer> otherInfluencers,
				List<String> contentIds) {
			/** id는 handle 그대로(6.4 확정 준용). */
			public record OtherInfluencer(String id, String handle) {
			}
		}
	}
}
```

- [ ] **Step 2: Repository 작성**

```java
package com.celfit.was.v2.influencer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 6.22·6.23 발굴 리포트 v2 조회 — analysis DB 미러 읽기 전용(분석 결과끼리 조인은 §4-4 허용). */
@Repository
public class V2InfluencerReportRepository {

	private final JdbcClient jdbcClient;

	public V2InfluencerReportRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** account_summaries 1행 — 없으면 empty → 404(인플루언서 없음). */
	public Optional<SummaryRow> findSummary(String handle) {
		return jdbcClient.sql("""
				SELECT followers, analyzed_count, posts_count, avg_views, views_per_follower,
				       avg_er_pct, avg_likes, avg_comments, last_posted_at, avg_interval_days
				FROM account_summaries
				WHERE handle = :h
				""").param("h", handle).query(SummaryRow.class).optional();
	}

	/** 신 스키마 카피 최신 1행 — perf_summary가 있는 행만(구 스키마 행은 "리포트 미생성" = 404).
	 *  tagline·perf·content는 잡 가드가 비-null 보장, ad_summary만 nullable(AdSituation.INSUFFICIENT). */
	public Optional<CopyRow> findLatestCopy(String handle) {
		return jdbcClient.sql("""
				SELECT tagline, traits::text AS traits_json, perf_summary, content_summary, ad_summary
				FROM account_analyses
				WHERE handle = :h AND perf_summary IS NOT NULL
				ORDER BY analyzed_at DESC
				LIMIT 1
				""").param("h", handle).query(CopyRow.class).optional();
	}

	/** 창 내 시계열 — 올린 순(posted_at ASC, 2차 short_code). 추이 2종·성장세·유효 팔로워·광고 간격 재료.
	 *  sponsored 정본은 캡션 분류(content_analyses.ad_type='sponsored') — v1 리포트와 동일 결정(07-27). */
	public List<SeriesRow> findSeries(String handle) {
		return jdbcClient.sql("""
				SELECT s.posted_at, s.content_type, s.views, s.likes, s.comments,
				       COALESCE(an.ad_type = 'sponsored', false) AS sponsored
				FROM account_content_series s
				LEFT JOIN content_analyses an ON an.short_code = s.short_code
				WHERE s.account_handle = :h
				ORDER BY s.posted_at, s.short_code
				""").param("h", handle).query(SeriesRow.class).list();
	}

	/** contentMix.categories — 창 내 콘텐츠 × 분석 대분류, label은 main_label 폴백 main_category. */
	public List<CategoryRow> findCategories(String handle) {
		return jdbcClient.sql("""
				SELECT COALESCE(t.main_label, an.main_category) AS label, count(*) AS cnt
				FROM account_content_series s
				JOIN content_analyses an ON an.short_code = s.short_code
				LEFT JOIN (SELECT DISTINCT main_value, main_label FROM beauty_taxonomy) t
				  ON t.main_value = an.main_category
				WHERE s.account_handle = :h AND an.main_category IS NOT NULL
				GROUP BY 1 ORDER BY cnt DESC, label
				""").param("h", handle).query(CategoryRow.class).list();
	}

	/**
	 * ads.brands — 이 계정의 브랜드별 협찬 게시물(contentIds, 올린 순)과 풀 내 같은 브랜드 협업 계정
	 * 상위 5(otherInfluencers, 협업 수 내림차순 — 스펙 7절 22번 백엔드 확정). pairs에서 (브랜드,게시물)
	 * DISTINCT — detected_brands 배열에 같은 브랜드가 중복 기재돼도 contentIds·cnt가 안 부풀도록
	 * (traits Jaccard DISTINCT 교훈과 동일). JSON 집계는 CopyRow.traitsJson과 같은 ::text 관용구.
	 */
	public List<BrandCollabRow> findBrandCollabs(String handle) {
		return jdbcClient.sql("""
				WITH pairs AS (
				  SELECT DISTINCT b->>'name' AS name, s.short_code, s.posted_at
				  FROM account_content_series s
				  JOIN content_analyses an ON an.short_code = s.short_code AND an.ad_type = 'sponsored'
				  CROSS JOIN LATERAL jsonb_array_elements(COALESCE(an.detected_brands, '[]'::jsonb)) b
				  WHERE s.account_handle = :h AND b->>'name' IS NOT NULL
				),
				mine AS (
				  SELECT name, count(*) AS cnt,
				         jsonb_agg(short_code ORDER BY posted_at)::text AS content_ids_json
				  FROM pairs GROUP BY name
				)
				SELECT m.name, m.cnt, m.content_ids_json,
				       COALESCE((SELECT jsonb_agg(o.handle ORDER BY o.cnt DESC, o.last_at DESC)
				                 FROM (SELECT s2.account_handle AS handle, count(*) AS cnt,
				                              max(s2.posted_at) AS last_at
				                       FROM account_content_series s2
				                       JOIN content_analyses an2 ON an2.short_code = s2.short_code
				                                                AND an2.ad_type = 'sponsored'
				                       CROSS JOIN LATERAL jsonb_array_elements(
				                                          COALESCE(an2.detected_brands, '[]'::jsonb)) b2
				                       WHERE b2->>'name' = m.name AND s2.account_handle <> :h
				                       GROUP BY 1 ORDER BY cnt DESC, last_at DESC LIMIT 5) o),
				                '[]'::jsonb)::text AS others_json
				FROM mine m
				ORDER BY m.cnt DESC, m.name
				""").param("h", handle).query(BrandCollabRow.class).list();
	}

	/** 유사 인플루언서 핸들 — 동일 주 카테고리 × traits Jaccard 교집합 내림차순 × 팔로워 근접,
	 *  상위 9(스펙 6.23 서버 고정). overlap은 DISTINCT(중복 traits 부풀림 방지). 카드 조립은
	 *  발굴 목록(6.21) 표면 재사용 — 기준 계정이 풀에 없으면 빈 목록. */
	public List<String> findSimilarHandles(String handle) {
		return jdbcClient.sql("""
				WITH me AS (
				  SELECT p.peer_category, ac.followers, la.traits
				  FROM account_peer_stats p
				  JOIN accounts ac ON ac.handle = p.handle
				  JOIN LATERAL (SELECT traits FROM account_analyses
				                WHERE handle = p.handle ORDER BY analyzed_at DESC LIMIT 1) la ON true
				  WHERE p.handle = :h
				)
				SELECT c.handle
				FROM account_peer_stats c
				JOIN me ON c.peer_category = me.peer_category
				JOIN accounts ac ON ac.handle = c.handle
				JOIN LATERAL (SELECT traits FROM account_analyses
				              WHERE handle = c.handle ORDER BY analyzed_at DESC LIMIT 1) la ON true
				WHERE c.handle <> :h
				ORDER BY (SELECT count(DISTINCT t.value) FROM jsonb_array_elements_text(la.traits) t
				           WHERE t.value IN (SELECT value FROM jsonb_array_elements_text(me.traits))) DESC,
				         abs(ac.followers - me.followers) ASC
				LIMIT 9
				""").param("h", handle).query(String.class).list();
	}

	public record SummaryRow(Long followers, Long analyzedCount, Long postsCount, Long avgViews,
			BigDecimal viewsPerFollower, BigDecimal avgErPct, Long avgLikes, Long avgComments,
			OffsetDateTime lastPostedAt, BigDecimal avgIntervalDays) {
	}

	public record CopyRow(String tagline, String traitsJson, String perfSummary,
			String contentSummary, String adSummary) {
	}

	public record SeriesRow(OffsetDateTime postedAt, String contentType, Long views, Long likes,
			Long comments, Boolean sponsored) {
	}

	public record CategoryRow(String label, Long cnt) {
	}

	public record BrandCollabRow(String name, Long cnt, String contentIdsJson, String othersJson) {
	}
}
```

- [ ] **Step 3: Repository DB 테스트 작성** — 구성(Testcontainers·마이그레이션 로드·시드 헬퍼)은 기존 `was/src/test/java/com/celfit/was/v1/influencer/V1InfluencerDiscoveryRepositoryTest.java` 상단을 그대로 복제해 시작한다(analysis 마이그레이션 적용 방식 포함). 검증할 계약:

```java
	@Test
	void 구_스키마_카피는_리포트_미생성으로_취급() {
		// perf_summary NULL 행만 있는 계정 → findLatestCopy empty
		// perf_summary 있는 구행 + NULL 신행이 섞이면 → perf_summary 있는 행 중 최신을 반환
	}

	@Test
	void 브랜드_협업은_중복_기재를_한_번만_센다() {
		// 한 게시물의 detected_brands에 같은 브랜드 2회 기재 → cnt 1, contentIds 1개
		// contentIds는 posted_at 올린 순, others는 협업 수 내림차순 최대 5 + 자기 자신 제외
	}

	@Test
	void 유사_핸들은_교집합_내림차순_최대_9() {
		// 동일 카테고리 후보 중 traits 교집합 큰 순 → 팔로워 근접 순, 기준 계정 제외
	}
```

각 케이스의 시드는 discovery 테스트의 INSERT 관용구를 따르되 `account_analyses`(traits jsonb, perf_summary)·`content_analyses`(ad_type, detected_brands jsonb)를 채운다. 정확한 컬럼 제약은 V3·V30·V40 DDL을 열어 확인.

- [ ] **Step 4: 통과 확인 후 커밋**

```bash
./gradlew :was:test --tests "com.celfit.was.v2.influencer.V2InfluencerReportRepositoryTest"
git add was/src/main/java/com/celfit/was/v2/ was/src/test/java/com/celfit/was/v2/
git commit -m "feat(was): 리포트 v2 DTO·조회 — 신 스키마 카피 게이트, 브랜드 협업 인라인(otherInfluencers·contentIds), 유사 핸들 9"
```

---

### Task 5: v2 Assembler + 단위 테스트

**Files:**
- Create: `was/src/main/java/com/celfit/was/v2/influencer/V2InfluencerReportAssembler.java`
- Test: `was/src/test/java/com/celfit/was/v2/influencer/V2InfluencerReportAssemblerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.was.v2.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.v2.influencer.V2InfluencerReportRepository.BrandCollabRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.CategoryRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.CopyRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.SeriesRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.SummaryRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 스펙 6.22 조립 계약 — 산식 정의는 각 메서드 javadoc. */
class V2InfluencerReportAssemblerTest {

	// 기준 시각 고정: 2026-07-28T00:00Z
	private final Clock clock = Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC);
	private final V2InfluencerReportAssembler assembler =
			new V2InfluencerReportAssembler(clock, new ObjectMapper());

	private SummaryRow summary() {
		return new SummaryRow(10_000L, 12L, 214L, 52_000L, new BigDecimal("5.2"),
				new BigDecimal("3.14"), 1_500L, 80L,
				OffsetDateTime.parse("2026-07-23T00:00:00Z"), new BigDecimal("6.2"));
	}

	private CopyRow copy() {
		return new CopyRow("태그라인", "[\"정보형\",\"스킨케어\"]", "성과 요약", "콘텐츠 요약", "광고 요약");
	}

	private SeriesRow row(String at, Long views, long likes, long comments, boolean sp) {
		return new SeriesRow(OffsetDateTime.parse(at), views == null ? "feed" : "reels",
				views, likes, comments, sp);
	}

	@Test
	void 요약_3종은_톱레벨이고_광고_없으면_adsSummary_null() {
		var noAds = List.of(row("2026-07-01T00:00:00Z", 1000L, 100, 10, false));
		var r = assembler.toReport(summary(), copy(), noAds, List.of(), List.of());
		assertThat(r.perfSummary()).isEqualTo("성과 요약");
		assertThat(r.contentSummary()).isEqualTo("콘텐츠 요약");
		assertThat(r.adsSummary()).isNull();     // 광고 0건 — 저장된 문구가 있어도 서빙 안 함(스펙 6.22)
		assertThat(r.sponsored()).isNull();
		assertThat(r.ads().sponsoredCount()).isZero(); // ads 블록 자체는 항상 존재

		var withAds = List.of(row("2026-07-01T00:00:00Z", 1000L, 100, 10, true));
		assertThat(assembler.toReport(summary(), copy(), withAds, List.of(), List.of())
				.adsSummary()).isEqualTo("광고 요약");
	}

	@Test
	void overall_세트는_summary_값과_series_성장세() {
		// views 앞절반(2건) 평균 10000, 뒤절반 평균 15000 → +50%
		var series = List.of(
				row("2026-07-01T00:00:00Z", 8_000L, 100, 10, false),
				row("2026-07-02T00:00:00Z", 12_000L, 100, 10, false),
				row("2026-07-03T00:00:00Z", 14_000L, 100, 10, false),
				row("2026-07-04T00:00:00Z", 16_000L, 100, 10, false));
		var overall = assembler.toReport(summary(), copy(), series, List.of(), List.of()).overall();
		assertThat(overall.views().value()).isEqualByComparingTo("52000");
		assertThat(overall.views().growthPct()).isEqualTo(50);
		assertThat(overall.er().value()).isEqualByComparingTo("3.1"); // 소수 1자리(HALF_UP)
		assertThat(overall.viewsPerFollower()).isEqualByComparingTo("5.2");
		assertThat(overall.sampleCount()).isEqualTo(12L); // analyzedCount
	}

	@Test
	void sponsored_세트는_광고만_재계산() {
		var series = List.of(
				row("2026-07-01T00:00:00Z", 10_000L, 100, 10, false),
				row("2026-07-02T00:00:00Z", 6_000L, 300, 30, true),
				row("2026-07-03T00:00:00Z", 4_000L, 400, 40, true));
		var sp = assembler.toReport(summary(), copy(), series, List.of(), List.of()).sponsored();
		assertThat(sp.views().value()).isEqualByComparingTo("5000");      // (6000+4000)/2
		assertThat(sp.er().value()).isEqualByComparingTo("3.9");          // avg(330,440)×100/10000 = 3.85 → 3.9
		assertThat(sp.viewsPerFollower()).isEqualByComparingTo("0.5");    // 5000/10000
		assertThat(sp.sampleCount()).isEqualTo(2L);
		assertThat(sp.views().growthPct()).isEqualTo(-33);                // 앞 6000 vs 뒤 4000 → -33%
	}

	@Test
	void viewsTrend는_조회수_공개만_올린_순_최대_8_그리고_2개_미만이면_빈배열() {
		// 피드(views null) 제외, 릴스 10건 → 최신 8건만, 올린 순 유지
		var series = new java.util.ArrayList<SeriesRow>();
		series.add(row("2026-06-30T00:00:00Z", null, 50, 5, false)); // 피드 — 제외
		for (int i = 1; i <= 10; i++) {
			series.add(row("2026-07-%02dT00:00:00Z".formatted(i), 1_000L * i, 100, 10, false));
		}
		var trend = assembler.toReport(summary(), copy(), series, List.of(), List.of()).viewsTrend();
		assertThat(trend).hasSize(8);
		assertThat(trend.get(0).date()).isEqualTo("2026-07-03"); // 앞 2건 잘림(KST +9h 주의: 00Z=09KST 동일 날짜)
		assertThat(trend.get(7).value()).isEqualByComparingTo("10000");

		var one = List.of(row("2026-07-01T00:00:00Z", 1_000L, 100, 10, false));
		assertThat(assembler.toReport(summary(), copy(), one, List.of(), List.of()).viewsTrend())
				.isEmpty(); // 1건 → 추이 불가
	}

	@Test
	void erTrend는_전체_게시물_게시물당_참여율() {
		// (100+10)×100/10000 = 1.1
		var series = List.of(row("2026-07-01T00:00:00Z", null, 100, 10, false));
		var trend = assembler.toReport(summary(), copy(), series, List.of(), List.of()).erTrend();
		assertThat(trend).hasSize(1);
		assertThat(trend.get(0).value()).isEqualByComparingTo("1.1");
	}

	@Test
	void ads는_정수_간격과_사실값_헤드라인_브랜드_중첩() {
		// 광고 3건: 07-01·07-05·07-09 → 스팬 8일/2 = 4일, 마지막 광고 19일 전(기준 07-28)
		var series = List.of(
				row("2026-07-01T00:00:00Z", 1_000L, 100, 10, true),
				row("2026-07-05T00:00:00Z", 1_000L, 100, 10, true),
				row("2026-07-09T00:00:00Z", 1_000L, 100, 10, true));
		var collabs = List.of(new BrandCollabRow("브랜드A", 2L,
				"[\"c1\", \"c2\"]", "[\"other1\", \"other2\"]"));
		var ads = assembler.toReport(summary(), copy(), series, List.of(), collabs).ads();
		assertThat(ads.sponsoredCount()).isEqualTo(3L);
		assertThat(ads.adIntervalDays()).isEqualTo(4L);   // 정수(스펙: 소수 없음)
		assertThat(ads.lastAdDaysAgo()).isEqualTo(19L);
		assertThat(ads.headline()).isEqualTo("최근 19일 전 브랜드A 협업 · 평균 4일 간격으로 광고 진행");
		assertThat(ads.brands().get(0).otherInfluencers().get(0).id()).isEqualTo("other1");
		assertThat(ads.brands().get(0).otherInfluencers().get(0).handle()).isEqualTo("other1");
		assertThat(ads.brands().get(0).contentIds()).containsExactly("c1", "c2");
	}

	@Test
	void 유효_팔로워와_activity() {
		var series = List.of(
				row("2026-07-01T00:00:00Z", null, 100L, 10L, false),
				row("2026-07-02T00:00:00Z", null, 200L, 20L, false));
		var r = assembler.toReport(summary(), copy(), series, List.of(), List.of());
		assertThat(r.effectiveFollowers()).isEqualTo(165L); // EffectiveFollowersTest 기본 케이스와 동일
		assertThat(r.activity().lastUploadDaysAgo()).isEqualTo(5L);
		assertThat(r.activity().avgIntervalDays()).isEqualByComparingTo("6.2");
	}

	@Test
	void contentMix와_traits() {
		var r = assembler.toReport(summary(), copy(), List.of(),
				List.of(new CategoryRow("메이크업", 7L)), List.of());
		assertThat(r.contentMix().categories().get(0).label()).isEqualTo("메이크업");
		assertThat(r.contentMix().traits()).containsExactly("정보형", "스킨케어");
		assertThat(r.tagline()).isEqualTo("태그라인");
		assertThat(r.analyzedCount()).isEqualTo(12L);
		assertThat(r.totalPosts()).isEqualTo(214L);
	}
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v2.influencer.V2InfluencerReportAssemblerTest"
```
Expected: COMPILE FAIL

- [ ] **Step 3: Assembler 구현**

```java
package com.celfit.was.v2.influencer;

import com.celfit.was.v1.influencer.EffectiveFollowers;
import com.celfit.was.v2.influencer.InfluencerAiReportV2.Ads;
import com.celfit.was.v2.influencer.InfluencerAiReportV2.Activity;
import com.celfit.was.v2.influencer.InfluencerAiReportV2.ContentMix;
import com.celfit.was.v2.influencer.InfluencerAiReportV2.MetricCell;
import com.celfit.was.v2.influencer.InfluencerAiReportV2.StatSet;
import com.celfit.was.v2.influencer.InfluencerAiReportV2.TrendPoint;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.BrandCollabRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.CategoryRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.CopyRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.SeriesRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.SummaryRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 미러 행 → InfluencerAiReportV2(스펙 6.22) 순수 변환. copy는 비-null 전제(컨트롤러가 미생성 404).
 * 성장세·유효 팔로워·광고 간격·헤드라인은 알고리즘 산출(LLM 아님) — v1 리포트(07-27)와 동일 결정.
 */
@Component
public class V2InfluencerReportAssembler {

	private static final int VIEWS_TREND_MAX = 8;
	private static final int ER_TREND_MAX = 12;
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};

	private final Clock clock;
	private final ObjectMapper objectMapper;

	public V2InfluencerReportAssembler(Clock clock, ObjectMapper objectMapper) {
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	public InfluencerAiReportV2 toReport(SummaryRow summary, CopyRow copy, List<SeriesRow> series,
			List<CategoryRow> categories, List<BrandCollabRow> brandCollabs) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		List<SeriesRow> sponsored = series.stream()
				.filter(s -> Boolean.TRUE.equals(s.sponsored())).toList();
		return new InfluencerAiReportV2(
				copy.tagline(),
				summary.analyzedCount(),
				summary.postsCount(),
				EffectiveFollowers.estimate(summary.followers(), series.stream()
						.map(s -> new EffectiveFollowers.Post(s.views(), s.likes(), s.comments()))
						.toList()),
				new Activity(daysSince(summary.lastPostedAt(), now), summary.avgIntervalDays()),
				copy.perfSummary(),
				copy.contentSummary(),
				// 광고 0건이면 null(스펙 6.22) — analytics가 "협찬 없음" 문구를 저장해도 서빙하지 않는다
				sponsored.isEmpty() ? null : copy.adSummary(),
				overallSet(summary, series),
				sponsoredSet(sponsored, summary.followers()),
				viewsTrend(series),
				erTrend(series, summary.followers()),
				new ContentMix(categories.stream()
						.map(c -> new ContentMix.Category(c.label(), c.cnt())).toList(),
						traits(copy)),
				ads(sponsored, brandCollabs, now));
	}

	/** overall — 값은 summary(SQL 집계), 성장세는 series 전체. sampleCount는 analyzedCount(창 크기). */
	private StatSet overallSet(SummaryRow summary, List<SeriesRow> series) {
		return new StatSet(
				new MetricCell(toBigDecimal(summary.avgViews()),
						growthPct(mapToDouble(series, s -> s.views() == null ? null : s.views().doubleValue()))),
				new MetricCell(scale1(summary.avgErPct()),
						growthPct(mapToDouble(series, V2InfluencerReportAssembler::erProxy))),
				new MetricCell(toBigDecimal(summary.avgLikes()),
						growthPct(mapToDouble(series, s -> s.likes() == null ? null : s.likes().doubleValue()))),
				new MetricCell(toBigDecimal(summary.avgComments()),
						growthPct(mapToDouble(series, s -> s.comments() == null ? null : s.comments().doubleValue()))),
				scale1(summary.viewsPerFollower()),
				summary.analyzedCount());
	}

	/** sponsored — 광고 0건이면 세트 자체가 null. 값은 series 재계산(summary는 전체 집계라 못 씀). */
	private StatSet sponsoredSet(List<SeriesRow> sponsored, Long followers) {
		if (sponsored.isEmpty()) {
			return null;
		}
		BigDecimal avgViews = avgPositive(sponsored.stream().map(SeriesRow::views).toList());
		return new StatSet(
				new MetricCell(avgViews,
						growthPct(mapToDouble(sponsored, s -> s.views() == null ? null : s.views().doubleValue()))),
				new MetricCell(adEr(sponsored, followers),
						growthPct(mapToDouble(sponsored, V2InfluencerReportAssembler::erProxy))),
				new MetricCell(avg(sponsored.stream().map(SeriesRow::likes).toList()),
						growthPct(mapToDouble(sponsored, s -> s.likes() == null ? null : s.likes().doubleValue()))),
				new MetricCell(avg(sponsored.stream().map(SeriesRow::comments).toList()),
						growthPct(mapToDouble(sponsored, s -> s.comments() == null ? null : s.comments().doubleValue()))),
				viewsPerFollower(avgViews, followers),
				(long) sponsored.size());
	}

	/** viewsTrend — 조회수 공개(views>0, 피드는 항상 null이라 자연 제외)만 올린 순, 최신 8개.
	 *  2개 미만이면 빈 배열(화면 "추이 데이터 부족" 빈 상태). */
	private List<TrendPoint> viewsTrend(List<SeriesRow> series) {
		List<SeriesRow> open = series.stream()
				.filter(s -> s.views() != null && s.views() > 0).toList();
		if (open.size() < 2) {
			return List.of();
		}
		List<SeriesRow> latest = open.size() > VIEWS_TREND_MAX
				? open.subList(open.size() - VIEWS_TREND_MAX, open.size()) : open;
		return latest.stream()
				.map(s -> new TrendPoint(kstDate(s.postedAt()), BigDecimal.valueOf(s.views())))
				.toList();
	}

	/** erTrend — 최근 게시물 전체 올린 순 최대 12개, 게시물당 (좋아요+댓글)×100/팔로워 소수 1.
	 *  팔로워 근거 없으면 빈 배열. 음수 센티널(likes -1)은 0 클램프. */
	private List<TrendPoint> erTrend(List<SeriesRow> series, Long followers) {
		if (followers == null || followers <= 0) {
			return List.of();
		}
		return series.stream().limit(ER_TREND_MAX).map(s -> {
			long likes = s.likes() == null ? 0 : Math.max(s.likes(), 0);
			long comments = s.comments() == null ? 0 : Math.max(s.comments(), 0);
			BigDecimal er = BigDecimal.valueOf((likes + comments) * 100.0 / followers)
					.setScale(1, RoundingMode.HALF_UP);
			return new TrendPoint(kstDate(s.postedAt()), er);
		}).toList();
	}

	private Ads ads(List<SeriesRow> sponsored, List<BrandCollabRow> collabs, OffsetDateTime now) {
		OffsetDateTime lastAd = sponsored.stream().map(SeriesRow::postedAt)
				.max(Comparator.naturalOrder()).orElse(null);
		Long lastAdDaysAgo = daysSince(lastAd, now);
		Long adIntervalDays = adIntervalDays(sponsored);
		String topBrand = collabs.isEmpty() ? null : collabs.get(0).name();
		return new Ads((long) sponsored.size(), adIntervalDays, lastAdDaysAgo,
				headline(lastAdDaysAgo, adIntervalDays, topBrand),
				collabs.stream().map(this::brand).toList());
	}

	private Ads.Brand brand(BrandCollabRow r) {
		List<String> otherHandles = jsonStringList(r.othersJson());
		return new Ads.Brand(r.name(), r.cnt(),
				otherHandles.stream().map(h -> new Ads.Brand.OtherInfluencer(h, h)).toList(),
				jsonStringList(r.contentIdsJson()));
	}

	/** 스팬일수/(건수-1) 반올림 정수(스펙: 소수 없음). 광고 2건 미만·전부 같은 날(스팬 0)이면 null
	 *  — "평균 0일 간격" 오해 방지(v1 리포트와 동일 결정). */
	static Long adIntervalDays(List<SeriesRow> sponsored) {
		if (sponsored.size() < 2) {
			return null;
		}
		OffsetDateTime min = sponsored.stream().map(SeriesRow::postedAt)
				.min(Comparator.naturalOrder()).orElseThrow();
		OffsetDateTime max = sponsored.stream().map(SeriesRow::postedAt)
				.max(Comparator.naturalOrder()).orElseThrow();
		long spanDays = ChronoUnit.DAYS.between(min, max);
		if (spanDays == 0) {
			return null;
		}
		return Math.round((double) spanDays / (sponsored.size() - 1));
	}

	/** 광고 헤드라인 — 사실값 템플릿(LLM 아님). 조사 회피 명사형. 광고 이력 없으면 null. */
	static String headline(Long lastAdDaysAgo, Long adIntervalDays, String topBrand) {
		if (lastAdDaysAgo == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append(lastAdDaysAgo == 0 ? "오늘" : "최근 " + lastAdDaysAgo + "일 전");
		sb.append(topBrand != null ? " " + topBrand + " 협업" : " 광고 게시");
		if (adIntervalDays != null) {
			sb.append(" · 평균 ").append(adIntervalDays).append("일 간격으로 광고 진행");
		}
		return sb.toString();
	}

	/**
	 * 성장세: 올린 순 앞절반(floor(n/2)) vs 뒤절반, 각 절반에서 값>0만 평균 —
	 * 10_account_detail trend CTE와 같은 경계·필터. 근거 부족(절반 비었음)이면 null.
	 * 스펙 각주("최근 N개를 반으로 나눠 앞 구간 대비 뒤 구간 비교")와 동일 정의.
	 */
	static Integer growthPct(List<Double> valuesInOrder) {
		int n = valuesInOrder.size();
		if (n < 2) {
			return null;
		}
		double olderSum = 0, newerSum = 0;
		int olderN = 0, newerN = 0;
		for (int i = 0; i < n; i++) {
			double v = valuesInOrder.get(i) == null ? 0 : valuesInOrder.get(i);
			if (v <= 0) {
				continue;
			}
			if (i < n / 2) {
				olderSum += v;
				olderN++;
			} else {
				newerSum += v;
				newerN++;
			}
		}
		if (olderN == 0 || newerN == 0) {
			return null;
		}
		return (int) Math.round(((newerSum / newerN) / (olderSum / olderN) - 1) * 100);
	}

	/** er 대용값 = likes+comments(팔로워 상수이므로 증감률은 실제 ER 증감률과 동일). 둘 다 null이면 null. */
	private static Double erProxy(SeriesRow s) {
		if (s.likes() == null && s.comments() == null) {
			return null;
		}
		long likes = s.likes() == null ? 0 : s.likes();
		long comments = s.comments() == null ? 0 : s.comments();
		return (double) (likes + comments);
	}

	private static <T> List<Double> mapToDouble(List<T> rows, Function<T, Double> f) {
		return rows.stream().map(f).toList();
	}

	/** views>0인 표본만 평균(반올림). 표본 없으면 null(세트 내 조회수 공개 게시물 없음). */
	private static BigDecimal avgPositive(List<Long> values) {
		List<Long> positive = values.stream().filter(v -> v != null && v > 0).toList();
		if (positive.isEmpty()) {
			return null;
		}
		return BigDecimal.valueOf(Math.round(
				positive.stream().mapToLong(Long::longValue).average().orElseThrow()));
	}

	/** null 아닌 값 전부 평균(반올림). 표본 없으면 null. */
	private static BigDecimal avg(List<Long> values) {
		List<Long> nonNull = values.stream().filter(Objects::nonNull).toList();
		if (nonNull.isEmpty()) {
			return null;
		}
		return BigDecimal.valueOf(Math.round(
				nonNull.stream().mapToLong(Long::longValue).average().orElseThrow()));
	}

	/** 광고 er = avg(likes+comments)×100/followers, 소수 1(HALF_UP). followers 근거 없으면 null. */
	private static BigDecimal adEr(List<SeriesRow> sponsored, Long followers) {
		if (followers == null || followers <= 0 || sponsored.isEmpty()) {
			return null;
		}
		double sum = 0;
		for (SeriesRow s : sponsored) {
			sum += (s.likes() == null ? 0 : s.likes()) + (s.comments() == null ? 0 : s.comments());
		}
		return BigDecimal.valueOf(sum / sponsored.size() * 100 / followers)
				.setScale(1, RoundingMode.HALF_UP);
	}

	/** 평균 조회수 ÷ 팔로워 소수 1(스펙 StatSet.viewsPerFollower). 근거 없으면 null. */
	private static BigDecimal viewsPerFollower(BigDecimal avgViews, Long followers) {
		if (avgViews == null || followers == null || followers <= 0) {
			return null;
		}
		return avgViews.divide(BigDecimal.valueOf(followers), 1, RoundingMode.HALF_UP);
	}

	private static BigDecimal toBigDecimal(Long value) {
		return value == null ? null : BigDecimal.valueOf(value);
	}

	private static BigDecimal scale1(BigDecimal v) {
		return v == null ? null : v.setScale(1, RoundingMode.HALF_UP);
	}

	/** 경과일 = 24시간 단위 경과 수(캘린더 날짜 경계 아님) — 기존 표면과 동일 시맨틱. */
	private Long daysSince(OffsetDateTime moment, OffsetDateTime now) {
		return moment == null ? null : ChronoUnit.DAYS.between(moment, now);
	}

	/** KST 달력 날짜 "YYYY-MM-DD"(스펙 3.4). */
	private String kstDate(OffsetDateTime at) {
		return at == null ? null : at.atZoneSameInstant(KST).toLocalDate().toString();
	}

	private List<String> traits(CopyRow copy) {
		return copy.traitsJson() == null ? List.of()
				: objectMapper.readValue(copy.traitsJson(), STRING_LIST);
	}

	private List<String> jsonStringList(String json) {
		return json == null ? List.of() : objectMapper.readValue(json, STRING_LIST);
	}
}
```

- [ ] **Step 4: 통과 확인 후 커밋**

```bash
./gradlew :was:test --tests "com.celfit.was.v2.influencer.V2InfluencerReportAssemblerTest"
git add was/src/main/java/com/celfit/was/v2/ was/src/test/java/com/celfit/was/v2/
git commit -m "feat(was): 리포트 v2 조립 — StatSet 2세트·추이 2종·브랜드 중첩·정수 광고 간격 (스펙 6.22)"
```

---

### Task 6: v2 Controller + SecurityConfig

**Files:**
- Create: `was/src/main/java/com/celfit/was/v2/influencer/V2InfluencerReportController.java`
- Modify: `was/src/main/java/com/celfit/was/config/SecurityConfig.java` (permitAll 화이트리스트 1줄)
- Test: `was/src/test/java/com/celfit/was/v2/influencer/V2InfluencerReportControllerTest.java`

- [ ] **Step 1: 실패하는 컨트롤러 테스트 작성** — 구성은 v1 `V1InfluencerReportControllerTest`(@WebMvcTest) 관용구를 따른다. `V1InfluencerDiscoveryRepository`·`V1InfluencerDiscoveryAssembler`도 @MockitoBean/실빈으로 배선.

```java
	@Test
	void 카피_미생성이면_404_리포트_미생성() throws Exception {
		given(repository.findSummary("haeun.log")).willReturn(Optional.of(fullSummary()));
		given(repository.findLatestCopy("haeun.log")).willReturn(Optional.empty());
		mockMvc.perform(get("/v2/influencers/haeun.log/ai-report"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 리포트_v2_응답_구조() throws Exception {
		// fullSummary()·copy()·series 픽스처는 Task 5 테스트와 동일 값 재사용
		mockMvc.perform(get("/v2/influencers/haeun.log/ai-report"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.perfSummary").value("성과 요약"))
				.andExpect(jsonPath("$.data.overall.views.value").isNumber())
				.andExpect(jsonPath("$.data.overall.sampleCount").value(12))
				.andExpect(jsonPath("$.data.ads.sponsoredCount").isNumber())
				.andExpect(jsonPath("$.data.stats").doesNotExist())   // v1 구조 부재 확인
				.andExpect(jsonPath("$.data.chart").doesNotExist());
	}

	@Test
	void 유사_인플루언서는_6_21_카드를_유사도_순으로() throws Exception {
		given(repository.findSummary("haeun.log")).willReturn(Optional.of(fullSummary()));
		given(repository.findSimilarHandles("haeun.log")).willReturn(List.of("b", "a"));
		// discoveryRepository.findCardsByHandles가 ["a","b"] 순으로 돌려줘도 응답은 유사도 순 ["b","a"]
		mockMvc.perform(get("/v2/influencers/haeun.log/similar"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].id").value("b"))
				.andExpect(jsonPath("$.data[1].id").value("a"));
	}

	@Test
	void 유사_기준_계정_없으면_404() throws Exception {
		given(repository.findSummary("ghost")).willReturn(Optional.empty());
		mockMvc.perform(get("/v2/influencers/ghost/similar"))
				.andExpect(status().isNotFound());
	}
```

(v2는 Public/Optional이라 `.with(user(...))` 불필요 — 시큐리티 규칙도 이 테스트에서 함께 검증된다.)

- [ ] **Step 2: 컨트롤러 구현**

```java
package com.celfit.was.v2.influencer;

import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.influencer.InfluencerCard;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryAssembler;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 스펙 6.22·6.23 발굴 리포트 v2 — influencerId는 handle 그대로(6.4와 동일 설계).
 * 6.5(v1)는 기존 패널이 소비 중이라 병존 — 프론트 v2 전환 후 별도 PR로 폐기.
 * 인증은 둘 다 공개(SecurityConfig permitAll) — 잠금 표현은 프론트 처리(스펙 7절 15번).
 */
@RestController
public class V2InfluencerReportController {

	private final V2InfluencerReportRepository repository;
	private final V2InfluencerReportAssembler assembler;
	private final V1InfluencerDiscoveryRepository discoveryRepository;
	private final V1InfluencerDiscoveryAssembler discoveryAssembler;

	public V2InfluencerReportController(V2InfluencerReportRepository repository,
			V2InfluencerReportAssembler assembler,
			V1InfluencerDiscoveryRepository discoveryRepository,
			V1InfluencerDiscoveryAssembler discoveryAssembler) {
		this.repository = repository;
		this.assembler = assembler;
		this.discoveryRepository = discoveryRepository;
		this.discoveryAssembler = discoveryAssembler;
	}

	@GetMapping("/v2/influencers/{influencerId}/ai-report")
	public ApiResponse<InfluencerAiReportV2> aiReport(@PathVariable String influencerId) {
		var summary = repository.findSummary(influencerId)
				.orElseThrow(() -> V1ApiException.notFound("인플루언서를 찾을 수 없습니다."));
		// 신 스키마 카피 없으면 "리포트 미생성" — tagline·요약 3종이 비-null 계약이라 부분 응답 불가(스펙 6.22 에러)
		var copy = repository.findLatestCopy(influencerId)
				.orElseThrow(() -> V1ApiException.notFound("리포트가 아직 생성되지 않았습니다."));
		return ApiResponse.ok(assembler.toReport(summary, copy,
				repository.findSeries(influencerId),
				repository.findCategories(influencerId),
				repository.findBrandCollabs(influencerId)));
	}

	/** 6.23 — 응답은 6.21 InfluencerCard 재사용, 서버 고정 최대 9(유사도 내림차순). */
	@GetMapping("/v2/influencers/{influencerId}/similar")
	public ApiResponse<List<InfluencerCard>> similar(@PathVariable String influencerId) {
		if (repository.findSummary(influencerId).isEmpty()) {
			throw V1ApiException.notFound("인플루언서를 찾을 수 없습니다.");
		}
		List<String> handles = repository.findSimilarHandles(influencerId);
		List<InfluencerCard> cards = discoveryAssembler.toCards(
				discoveryRepository.findCardsByHandles(handles),
				discoveryRepository.findShares(handles),
				discoveryRepository.findBrands(handles),
				discoveryRepository.findThumbs(handles),
				discoveryRepository.findEngagements(handles));
		// 카드 조회는 순서 비보장 — 유사도 순(handles) 복원
		Map<String, InfluencerCard> byId = cards.stream()
				.collect(Collectors.toMap(InfluencerCard::id, Function.identity()));
		return ApiResponse.ok(handles.stream().map(byId::get).filter(Objects::nonNull).toList());
	}
}
```

- [ ] **Step 3: `findCardsByHandles` 추가** — `V1InfluencerDiscoveryRepository`에서 `build()`의 `fromJoins` 문자열을 상수 `FROM_JOINS`로 승격해 공유하고:

```java
	/** 핸들 목록 카드 일괄 조회(6.23 유사 카드 재사용) — 필터·정렬 없음, 순서는 호출부가 복원. */
	public List<CardRow> findCardsByHandles(List<String> handles) {
		if (handles.isEmpty()) {
			return List.of();
		}
		return jdbcClient.sql("""
				SELECT a.handle, a.display_name,
				       COALESCE('/img/' || ip.object_path, a.profile_image_url) AS profile_image_url,
				       a.followers, su.posts_count, su.follows_count, su.biography, cp.tagline,
				       su.views_per_follower, su.avg_er_pct, su.avg_views, su.avg_likes,
				       su.avg_comments, COALESCE(sp.cnt, 0) AS sponsored_count
				""" + FROM_JOINS + """

				WHERE a.handle IN (:handles)
				""").param("handles", handles).query(CardRow.class).list();
	}
```

- [ ] **Step 4: SecurityConfig 화이트리스트** — `/v1/influencers` permitAll 줄 바로 아래 추가:

```java
						.requestMatchers(HttpMethod.GET, "/v2/influencers/*/ai-report",
								"/v2/influencers/*/similar").permitAll() // 발굴 리포트 v2(스펙 6.22·6.23) — 잠금 표현은 프론트(7절 15번)
```

- [ ] **Step 5: 통과 확인 후 커밋**

```bash
./gradlew :was:test --tests "com.celfit.was.v2.influencer.*" --tests "com.celfit.was.v1.influencer.V1InfluencerDiscovery*"
git add was/src/main/java/com/celfit/was/ was/src/test/java/com/celfit/was/
git commit -m "feat(was): 리포트 v2 컨트롤러 — /v2 ai-report(미생성 404)·similar(6.21 카드 재사용, 최대 9) + 공개 인증"
```

---

### Task 7: 이메일 중복 확인 `POST /v1/auth/email-availability` (스펙 6.24)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/account/V1AuthController.java`
- Create: `was/src/main/java/com/celfit/was/v1/account/EmailAvailabilityRequest.java`, `EmailAvailabilityResponse.java`
- Test: `was/src/test/java/com/celfit/was/v1/account/V1AuthControllerTest.java` (케이스 추가)

- [ ] **Step 1: 실패하는 테스트 추가** — 기존 V1AuthControllerTest의 목 구성 관용구(csrf 포함)를 따른다. rateLimiter 목은 기본 false를 돌려주므로 `given(rateLimiter.tryAcquire(anyString(), anyInt())).willReturn(true)` 스텁 필수.

```java
	@Test
	void 이메일_사용_가능() throws Exception {
		given(rateLimiter.tryAcquire(anyString(), anyInt())).willReturn(true);
		given(userRepository.findByEmail("new@example.com")).willReturn(Optional.empty());
		mockMvc.perform(post("/v1/auth/email-availability").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"new@example.com\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.available").value(true));
	}

	@Test
	void 이메일_이미_가입() throws Exception {
		given(rateLimiter.tryAcquire(anyString(), anyInt())).willReturn(true);
		given(userRepository.findByEmail("dup@example.com"))
				.willReturn(Optional.of(existingUser())); // 기존 테스트의 AppUser 픽스처 재사용
		mockMvc.perform(post("/v1/auth/email-availability").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"dup@example.com\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.available").value(false));
	}

	@Test
	void 이메일_형식_위반은_400() throws Exception {
		given(rateLimiter.tryAcquire(anyString(), anyInt())).willReturn(true);
		mockMvc.perform(post("/v1/auth/email-availability").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"not-an-email\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 레이트리밋_초과는_429() throws Exception {
		given(rateLimiter.tryAcquire(anyString(), anyInt())).willReturn(false);
		mockMvc.perform(post("/v1/auth/email-availability").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"new@example.com\"}"))
				.andExpect(status().isTooManyRequests());
	}
```

주의: SignupValidator가 실빈이 아니라 목이면 `requireEmail`의 예외 스텁이 필요하다 — 기존 테스트가 validator를 어떻게 배선했는지 먼저 확인하고 그 방식을 따른다(목이면 `willThrow(V1ApiException.validation(...))` 스텁).

- [ ] **Step 2: 실패 확인**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.account.V1AuthControllerTest"
```
Expected: FAIL (404 — 엔드포인트 미존재)

- [ ] **Step 3: 구현**

`EmailAvailabilityRequest.java`:

```java
package com.celfit.was.v1.account;

/** 스펙 6.24 이메일 중복 확인 요청. */
public record EmailAvailabilityRequest(String email) {
}
```

`EmailAvailabilityResponse.java`:

```java
package com.celfit.was.v1.account;

/** 스펙 6.24 — available: true = 가입 가능, false = 이미 가입된 이메일. */
public record EmailAvailabilityResponse(boolean available) {
}
```

`V1AuthController`에 추가:

```java
	/** 이메일 중복 확인 상한(분당·IP) — 디바운스 500ms 전제라 가입(10회)보다 느슨, 열거 남용은 차단. */
	private static final int EMAIL_AVAILABILITY_PER_MINUTE = 30;

	/**
	 * 가입 전 이메일 중복 확인(스펙 6.24) — 위저드 2스텝 디바운스 호출. 비교 기준은 6.15 가입과 동일
	 * (UserRepository가 lower 정규화). 존재 여부 노출은 6.15의 409와 동일 수준이라 추가 마스킹 없음.
	 */
	@PostMapping("/v1/auth/email-availability")
	public ApiResponse<EmailAvailabilityResponse> emailAvailability(
			@RequestBody EmailAvailabilityRequest request, HttpServletRequest httpRequest) {
		if (!rateLimiter.tryAcquire("email-availability:" + httpRequest.getRemoteAddr(),
				EMAIL_AVAILABILITY_PER_MINUTE)) {
			throw V1ApiException.rateLimited();
		}
		signupValidator.requireEmail(request.email());
		return ApiResponse.ok(new EmailAvailabilityResponse(
				userRepository.findByEmail(request.email()).isEmpty()));
	}
```

(`findByEmail`이 내부에서 `normalizeEmail`을 타는지 확인 — 안 타면 `UserRepository.normalizeEmail(request.email())`로 감싼다. `V1ApiException.rateLimited()`가 없으면 기존 signup의 429 던지는 관용구를 그대로 쓴다.)

- [ ] **Step 4: 통과 확인 후 커밋**

```bash
./gradlew :was:test --tests "com.celfit.was.v1.account.V1AuthControllerTest"
git add was/src/main/java/com/celfit/was/v1/account/ was/src/test/java/com/celfit/was/v1/account/
git commit -m "feat(was): 가입 전 이메일 중복 확인 POST /v1/auth/email-availability (스펙 6.24, IP 분당 30회)"
```

---

### Task 8: 전체 검증 · 문서 · PR 갱신

- [ ] **Step 1: 전체 테스트** (colima/Docker 필요 — Testcontainers)

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL. 실패 시 해당 테스트를 고치고 재실행(특히 v1 복원과 v2 신설의 연쇄 — `FlywaySchemaTest`·discovery 계열).

- [ ] **Step 2: ARCHITECTURE.md 갱신**

- §5 리포트 개편 트랙 행의 설명에 "v2 스펙 정렬(6.22·6.23·6.24) 포함" 반영.
- §7 결정 기록 append(4줄): ① 6.22는 /v2 병행 신설, v1(6.5)은 프론트 전환까지 보존 후 별도 PR 폐기 ② 유효 팔로워는 실반응 산식 단일 원천(`EffectiveFollowers`) — 6.21 구 CTE 폐기 ③ 브랜드 hover 엔드포인트는 ads.brands 인라인(otherInfluencers·contentIds)으로 흡수 ④ 피어 퍼센타일(topPct)은 v2 계약에서 제외(V39 뷰는 유사 후보 풀·내부 재료로 유지).

- [ ] **Step 3: 계획 문서 상태 갱신 + 커밋**

이 문서와 `2026-07-27-influencer-report-redesign-backend.md`의 상태 헤더를 `✅ 구현됨`으로 갱신(아카이브 이동은 머지 후).

```bash
git add ARCHITECTURE.md docs/superpowers/plans/
git commit -m "docs: 리포트 v2 스펙 정렬 결정 기록 + 구현 계획"
```

- [ ] **Step 4: push + PR #149 본문 갱신**

```bash
git push origin feat/influencer-report-redesign
```

`gh pr edit 149 --body`로 본문을 갱신 — 기존 요약에 추가할 내용:

```
## v2 스펙 정렬 (07-28 프론트 스펙 6.22·6.23·6.24 반영)
- v1(6.5) 리포트는 develop 원형 보존(프론트 라이브 소비 중) — 6.22·6.23은 /v2 병행 신설, 프론트 전환 후 v1 폐기 PR 별도
- 6.22: 요약 3종 톱레벨, overall/sponsored StatSet(topPct 계약 제외), viewsTrend/erTrend, ads.brands 인라인(otherInfluencers·contentIds) — 브랜드 hover 엔드포인트 삭제
- 6.23: 응답 = 6.21 InfluencerCard 재사용, 서버 고정 최대 9
- 6.24: POST /v1/auth/email-availability (IP 분당 30회)
- 유효 팔로워: 실반응 산식 단일 원천(EffectiveFollowers) — 발굴 목록(6.21) 구 CTE 제거, 6.22와 동일 값 보장(스펙 7절 17번)

## 프론트 전달([확인 필요] 답변)
- 7절 20번: LLM 5종(새벽 배치·신규 게시물 감지 시 재분석) + headline은 사실값 템플릿(명사형 어투)
- 7절 21번: 유사도 = 동일 주 카테고리 × traits Jaccard × 팔로워 근접
- 7절 22번: otherInfluencers = 같은 브랜드 협찬 게시물 보유 계정, 협업 수 내림차순 상위 5
- 6.24 레이트리밋: IP 분당 30회 429
- v2 ai-report는 카피 백필 완료 전 계정에서 404(리포트 미생성) — 백필은 analytics 배포 후 새벽 배치 자연 진행
```

- [ ] **Step 5: 사용자 확인 대기** — 머지·배포는 사용자 승인 후(배포 런북 아래).

---

## 배포 런북 (머지 후 — 07-27 계획서 런북 대체)

1. **analytics 배포** — V39·V40 자동 적용, 새벽 배치부터 신 카피 백필(`perf_summary IS NULL` 자연 재대상, 전 계정 ~1,000콜 분산).
2. **was 배포** — v1 보존이라 **프론트 전환과 무관하게 즉시 가능**(v2는 카피 백필 완료 계정부터 200, 나머지 404).
3. 스팟 체크: 백필된 계정 1건 `GET /v2/influencers/{h}/ai-report`(요약 3종·overall/sponsored·viewsTrend·ads.brands 중첩), `GET /v2/influencers/{h}/similar`(카드 9), `POST /v1/auth/email-availability`.
4. 프론트 v2 전환 완료 통지 후: v1 리포트 표면(6.5) 폐기 PR 별도(구 카피 5컬럼 서빙 코드 제거 포함).

## 리스크·주의

- **머지 충돌**: Task 0의 develop 머지에서 ARCHITECTURE.md만 손충돌 예상. was 코드 충돌이 나면 범위 재확인.
- **V39 유지 근거**: topPct가 계약에서 빠졌지만 뷰는 유사 후보 풀(peer_category)·향후 LLM 내부 재료로 유지 — 축소는 YAGNI.
- **v1 열화(의도됨)**: analytics 배포 후 신 카피 행이 최신이 되면 v1 리포트의 구 카피 필드(summary 등)가 null로 서빙 — 기존 패널은 빈 문구 섹션으로 열화하나 구조는 유지(프론트 전환 전 과도기, 07-27 계획서에서 이미 알린 리스크).
- **6.24 CSRF**: POST 공개 표면이지만 기존 SPA CSRF 관용구(XSRF-TOKEN 쿠키 왕복)에 자동 포함 — 프론트 http 클라이언트가 이미 처리(signup-code/verify와 동일).
- **유사 카드의 유효 팔로워**: 6.23 카드도 `EffectiveFollowers` 경유라 6.21·6.22와 자동 일치.
