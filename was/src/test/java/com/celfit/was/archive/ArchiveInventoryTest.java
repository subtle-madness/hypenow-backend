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
 *
 * <p>"분류됨" 판정은 {@link ArchiveTables#CATALOG}(어떤 삭제 경로로든 아카이브되는 테이블 전부)
 * 기준이다 — 탈퇴 이관 순서인 ACCOUNT_DELETION_ORDER를 기준으로 쓰면 탈퇴와 무관한 개별 삭제
 * 경로 전용 테이블이 억지로 탈퇴 캐스케이드에 편입되거나 EXCLUDED에 거짓 사유로 등재되는 결과를
 * 낳는다.
 *
 * <p><b>EXCLUDED 사유 품질은 assertion으로 강제할 수 없다</b> — 성의 없는 사유("일단 제외" 등)도
 * 테스트는 통과시킨다. 이게 이 가드의 가장 현실적인 무력화 경로다(빨간 불을 끄는 가장 쉬운 길이
 * EXCLUDED 추가이므로). PR 리뷰어가 새로 추가되는 사유마다 "왜 삭제 경로가 없는지 / 왜 자산
 * 가치가 없는지"가 구체적으로 적혔는지 반드시 검토할 것.
 */
class ArchiveInventoryTest extends IntegrationTest {

	/** 아카이브하지 않는 테이블과 그 사유. 여기 추가할 때는 반드시 사유를 적을 것. */
	private static final Map<String, String> EXCLUDED = Map.ofEntries(
			Map.entry("flyway_schema_history", "Flyway 이력 테이블 — 우리 데이터가 아니다"),
			Map.entry("spring_session", "세션 토큰. 자산 가치 없음"),
			Map.entry("spring_session_attributes", "세션 속성. 자산 가치 없음"),
			Map.entry("gate_events", "삭제 경로 없음. FK도 없어 탈퇴에도 보존된다(V5 주석의 의도)"),
			Map.entry("app_setting", "was 런타임 설정값"),
			Map.entry("password_resets", "만료성 재설정 코드·토큰(SHA-256 해시, TTL 10분 이내) — 자산 가치 없음"),
			Map.entry("signup_codes", "삭제 경로 없음(used_by가 SET NULL로 끊길 뿐)"),
			Map.entry("signup_events", "90일 보존 배치가 삭제(트랙 A 09-03, SignupEventRetentionScheduler) "
					+ "— email + detail->>'userId' 보존으로 탈퇴 유저의 가명화 아카이브를 재식별할 수 있는 "
					+ "창은 그 90일 내로 한정된다. 비회원(가입 미완료) 이메일까지 무기한 보유할 근거가 "
					+ "없어 재식별 편의보다 개인정보 최소 보존을 택한 의도된 결정(설계 §4-4)"),
			Map.entry("inquiries", "삭제 경로 없음"),
			Map.entry("admin_audit_logs", "삭제 경로 없음. target_user_id에 FK가 없어 탈퇴에도 남는다"),
			Map.entry("encryption_keys", "시스템 암호화 키(DEK) 저장소. 사용자 스코프 데이터가 아니라 전역 키 관리용"));

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

		Set<String> archived = ArchiveTables.CATALOG.stream()
				.map(table -> table.qualifiedName().replace("app.", ""))
				.collect(Collectors.toSet());

		List<String> unclassified = actual.stream()
				.filter(name -> !archived.contains(name) && !EXCLUDED.containsKey(name))
				.toList();

		assertThat(unclassified)
				.as("""
						분류되지 않은 app 테이블이 있다: %s
						아카이브할 테이블이면 ArchiveTables에 ArchiveTable을 추가하고 CATALOG에 넣어라
						(탈퇴 시에도 이관해야 하면 ACCOUNT_DELETION_ORDER에도). 아카이브하지 않을
						테이블이면 ArchiveInventoryTest.EXCLUDED에 사유와 함께 등재하라.
						""".formatted(unclassified))
				.isEmpty();
	}

	/**
	 * ACCOUNT_DELETION_ORDER ⊆ CATALOG여야 한다 — 탈퇴가 이관하는 테이블은 당연히 카탈로그
	 * 소속이어야 한다. 반대 방향(CATALOG의 모든 원소가 ACCOUNT_DELETION_ORDER에도 있어야 한다)은
	 * 검사하지 않는다 — 탈퇴와 무관한 개별 삭제 경로 전용 테이블(CATALOG에는 있지만 탈퇴 캐스케이드
	 * 대상은 아닌 것)이 앞으로 생길 수 있다는 게 이 분리의 요지다.
	 */
	@Test
	void ACCOUNT_DELETION_ORDER는_CATALOG의_부분집합이어야_한다() {
		Set<String> catalog = ArchiveTables.CATALOG.stream()
				.map(ArchiveTable::qualifiedName)
				.collect(Collectors.toSet());

		List<String> notInCatalog = ArchiveTables.ACCOUNT_DELETION_ORDER.stream()
				.map(ArchiveTable::qualifiedName)
				.filter(name -> !catalog.contains(name))
				.toList();

		assertThat(notInCatalog)
				.as("""
						ACCOUNT_DELETION_ORDER에는 있지만 CATALOG에는 없는 테이블이 있다: %s
						탈퇴 시 이관하는 테이블은 카탈로그에도 있어야 한다. ArchiveTables.CATALOG에 추가하라.
						""".formatted(notInCatalog))
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
