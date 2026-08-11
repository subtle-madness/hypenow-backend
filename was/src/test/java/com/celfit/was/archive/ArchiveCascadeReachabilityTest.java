package com.celfit.was.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 설계 단계에서 monitoring_registration_entries를 놓친 이유가 직접 FK만 봤기 때문이다(간접
 * CASCADE — users → monitoring_registrations → monitoring_registration_entries). 이 테스트는
 * pg_constraint를 재귀로 훑어 그런 사고를 CI가 잡게 한다.
 *
 * <p>삭제 뿌리가 3개라 요구사항이 다르다:
 * <ul>
 *   <li>app.users — 탈퇴 이관 대상. CASCADE로 재귀 도달하는 테이블 전부가
 *       {@link ArchiveTables#ACCOUNT_DELETION_ORDER}에 있어야 한다.
 *   <li>app.monitoring_campaigns, app.monitoring_items — 자기 행 하나만 아카이브하고 삭제하는
 *       경로(Task 5·6)라 CASCADE 자식이 생기는 순간 그 자식은 아카이브 없이 사라진다. 그래서
 *       CASCADE 자식이 0개여야 한다.
 * </ul>
 */
class ArchiveCascadeReachabilityTest extends IntegrationTest {

	@Autowired
	JdbcClient jdbcClient;

	/** rootQualifiedName에서 ON DELETE CASCADE로 재귀 도달하는 테이블 전체(스키마 한정, 중복 없음). */
	private List<String> cascadeDescendants(String rootQualifiedName) {
		return jdbcClient.sql("""
						WITH RECURSIVE cascaded AS (
						    SELECT c.conrelid AS rel
						      FROM pg_constraint c
						     WHERE c.contype = 'f' AND c.confdeltype = 'c' AND c.confrelid = :root::regclass
						    UNION
						    SELECT c.conrelid
						      FROM pg_constraint c
						      JOIN cascaded p ON c.confrelid = p.rel
						     WHERE c.contype = 'f' AND c.confdeltype = 'c'
						)
						SELECT DISTINCT rel::regclass::text FROM cascaded
						""")
				.param("root", rootQualifiedName)
				.query(String.class)
				.list()
				.stream()
				// regclass::text는 search_path에 있는 스키마(public 등)면 접두사를 생략한다.
				// 이 프로젝트 테이블은 전부 app 소속이라 접두사 없는 이름은 app.으로 보정한다.
				.map(name -> name.contains(".") ? name : "app." + name)
				.toList();
	}

	@Test
	void users에서_CASCADE로_재귀_도달하는_테이블은_전부_ACCOUNT_DELETION_ORDER에_있다() {
		List<String> descendants = cascadeDescendants("app.users");

		Set<String> archived = ArchiveTables.ACCOUNT_DELETION_ORDER.stream()
				.map(ArchiveTable::qualifiedName)
				.collect(Collectors.toSet());

		List<String> missing = descendants.stream().filter(name -> !archived.contains(name)).toList();

		assertThat(missing)
				.as("""
						app.users에서 CASCADE로 재귀 도달하지만 ACCOUNT_DELETION_ORDER에 없는 테이블이 있다: %s
						탈퇴 시 이 테이블의 행은 아카이브 없이 CASCADE로 통째로 삭제된다. ArchiveTables에
						ArchiveTable을 추가하고 ACCOUNT_DELETION_ORDER 및 탈퇴 삭제 경로에 배선하라.
						""".formatted(missing))
				.isEmpty();
	}

	/**
	 * 간접 CASCADE(users → monitoring_registrations → monitoring_registration_entries)가 실제로
	 * 탐지되는지 확인하는 양성 대조군이다. 이게 없으면 위 테스트의 "missing이 비었다"가
	 * "재귀 쿼리 자체가 아무것도 못 찾는 상태"와 구분되지 않는다.
	 */
	@Test
	void 간접_CASCADE인_registration_entries가_재귀로_탐지된다() {
		List<String> descendants = cascadeDescendants("app.users");

		assertThat(descendants).contains("app.monitoring_registration_entries");
	}

	@Test
	void monitoring_campaigns는_CASCADE_자식이_없어야_한다() {
		List<String> descendants = cascadeDescendants("app.monitoring_campaigns");

		assertThat(descendants)
				.as("""
						app.monitoring_campaigns에 CASCADE 자식이 생겼다: %s
						CampaignRepository.delete는 캠페인 행 하나만 아카이브하고 삭제한다(items는
						SET NULL로 풀릴 뿐이라는 전제) — 이 자식들은 아카이브 없이 함께 사라진다.
						그 자식도 아카이브하도록 삭제 경로를 고치거나, FK를 CASCADE가 아닌 것(SET NULL
						등)으로 바꿔라.
						""".formatted(descendants))
				.isEmpty();
	}

	@Test
	void monitoring_items는_CASCADE_자식이_없어야_한다() {
		List<String> descendants = cascadeDescendants("app.monitoring_items");

		assertThat(descendants)
				.as("""
						app.monitoring_items에 CASCADE 자식이 생겼다: %s
						MonitoringItemRepository의 롤백 삭제 경로는 item 행 하나만 아카이브하고
						삭제한다 — 이 자식들은 아카이브 없이 함께 사라진다. 그 자식도 아카이브하도록
						삭제 경로를 고치거나, FK를 CASCADE가 아닌 것(SET NULL 등)으로 바꿔라.
						""".formatted(descendants))
				.isEmpty();
	}
}
