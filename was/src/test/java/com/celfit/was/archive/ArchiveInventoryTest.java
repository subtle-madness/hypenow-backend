package com.celfit.was.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * app 스키마의 모든 테이블은 아카이브 대상이거나, 사유가 적힌 제외 대상이거나 둘 중 하나여야 한다.
 * 새 테이블을 만들면 이 테스트가 깨진다 — 의도적이다(트랙 NN).
 */
class ArchiveInventoryTest extends IntegrationTest {

	/** 아카이브하지 않는 테이블과 그 사유. 여기 추가할 때는 반드시 사유를 적을 것. */
	private static final Map<String, String> EXCLUDED = Map.ofEntries(
			Map.entry("flyway_schema_history", "Flyway 이력 테이블 — 우리 데이터가 아니다"),
			Map.entry("spring_session", "세션 토큰. 자산 가치 없음"),
			Map.entry("spring_session_attributes", "세션 속성. 자산 가치 없음"),
			Map.entry("gate_events", "삭제 경로 없음. FK도 없어 탈퇴에도 보존된다(V5 주석의 의도)"),
			Map.entry("app_setting", "was 런타임 설정값"),
			Map.entry("email_verifications", "만료성 인증 코드"),
			Map.entry("signup_codes", "삭제 경로 없음(used_by가 SET NULL로 끊길 뿐)"),
			Map.entry("signup_events", "삭제 경로 없음. 단 email + detail->>'userId'를 보존해 "
					+ "탈퇴 유저의 가명화 아카이브를 재식별할 수 있다(설계 §4-4)"),
			Map.entry("inquiries", "삭제 경로 없음"),
			Map.entry("admin_audit_logs", "삭제 경로 없음. target_user_id에 FK가 없어 탈퇴에도 남는다"));

	@Autowired
	JdbcClient jdbcClient;

	@Test
	void app_스키마의_모든_테이블은_분류돼_있어야_한다() {
		List<String> actual = jdbcClient.sql("""
						SELECT table_name FROM information_schema.tables
						 WHERE table_schema = 'app' AND table_type = 'BASE TABLE'
						""")
				.query(String.class)
				.list();

		Set<String> archived = ArchiveTables.ACCOUNT_DELETION_ORDER.stream()
				.map(table -> table.qualifiedName().replace("app.", ""))
				.collect(Collectors.toSet());

		List<String> unclassified = actual.stream()
				.filter(name -> !archived.contains(name) && !EXCLUDED.containsKey(name))
				.toList();

		assertThat(unclassified)
				.as("""
						분류되지 않은 app 테이블이 있다: %s
						탈퇴 시 아카이브할 테이블이면 ArchiveTables에 ArchiveTable을 추가하고
						ACCOUNT_DELETION_ORDER에 넣어라. 아카이브하지 않을 테이블이면
						ArchiveInventoryTest.EXCLUDED에 사유와 함께 등재하라.
						""".formatted(unclassified))
				.isEmpty();
	}

	@Test
	void 가명화_대상_컬럼은_실제로_users에_존재해야_한다() {
		List<String> actual = jdbcClient.sql("""
						SELECT column_name FROM information_schema.columns
						 WHERE table_schema = 'app' AND table_name = 'users'
						""")
				.query(String.class)
				.list();

		List<String> missing = ArchiveTables.USERS.omitColumns().stream()
				.filter(column -> !actual.contains(column))
				.toList();

		assertThat(missing)
				.as("""
						가명화 대상으로 등재됐지만 app.users에 없는 컬럼이 있다: %s
						`to_jsonb(t) - '없는컬럼'`은 에러 없이 no-op라 이 오타는 런타임에 드러나지 않는다 —
						개인정보가 payload에 그대로 남는다. 컬럼명이 바뀌었으면 USER_PII를 갱신하라.
						""".formatted(missing))
				.isEmpty();
	}

	@Test
	void 제외_목록에_죽은_항목이_없어야_한다() {
		List<String> actual = jdbcClient.sql("""
						SELECT table_name FROM information_schema.tables
						 WHERE table_schema = 'app' AND table_type = 'BASE TABLE'
						""")
				.query(String.class)
				.list();

		List<String> stale = EXCLUDED.keySet().stream().filter(name -> !actual.contains(name)).toList();

		assertThat(stale)
				.as("EXCLUDED에 이미 없어진 테이블이 남아 있다: %s — 목록에서 지워라".formatted(stale))
				.isEmpty();
	}
}
