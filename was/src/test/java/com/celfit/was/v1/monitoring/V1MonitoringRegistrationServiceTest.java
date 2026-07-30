package com.celfit.was.v1.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.RegistrationEntryRow;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.monitoring.RegistrationRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/**
 * V1MonitoringRegistrationService — 등록 접수(6.27) 동기 구간의 중복 규칙·순서 보존·pending 행
 * 생성·share 링크 처리·실행기 트리거를 DB 왕복으로 검증(구조 검증 400 케이스는 컨트롤러 테스트가 맡는다).
 */
class V1MonitoringRegistrationServiceTest extends IntegrationTest {

	@Autowired
	V1MonitoringRegistrationService service;
	@Autowired
	MonitoringItemRepository itemRepository;
	@Autowired
	RegistrationRepository registrationRepository;
	@Autowired
	JdbcClient jdbcClient;
	@MockitoBean
	RegistrationExecutor executor;

	private final ObjectMapper objectMapper = new ObjectMapper();

	long userId;

	@BeforeEach
	void 유저_시드() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-reg-svc-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	private static Map<String, Object> body(List<String> posts, List<String> accounts, Map<String, Object> keywords,
			int trackingDays) {
		Map<String, Object> map = new HashMap<>();
		map.put("posts", posts);
		map.put("accounts", accounts);
		if (keywords != null) {
			map.put("keywords", keywords);
		}
		map.put("trackingDays", trackingDays);
		return map;
	}

	private static Map<String, Object> keywords(List<String> and, List<String> or, List<String> exclude) {
		Map<String, Object> map = new HashMap<>();
		map.put("and", and);
		map.put("or", or);
		map.put("exclude", exclude);
		return map;
	}

	private List<RegistrationEntryRow> entriesOf(String registrationId) {
		long id = Long.parseLong(registrationId);
		return registrationRepository.findRecentByUser(userId, 50).stream()
				.filter(row -> row.id() == id)
				.findFirst()
				.map(RegistrationRow::entries)
				.orElseThrow();
	}

	@Test
	void 진행_중_행과_shortcode가_겹치면_duplicate다() {
		itemRepository.insertPending(userId, "url", UUID.randomUUID(), null, "ABC123",
				"https://www.instagram.com/p/ABC123/", null, 30, LocalDate.now());

		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of("https://www.instagram.com/p/ABC123/"), List.of(), null, 14));

		assertThat(response.items()).isEmpty();
		List<RegistrationEntryRow> entries = entriesOf(response.registrationId());
		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).result()).isEqualTo("duplicate");
		assertThat(entries.get(0).reasonCode()).isEqualTo("duplicate");
	}

	@Test
	void account_핸들이_진행_중_행과_겹치면_duplicate다() {
		itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep_official", null,
				"""
				{"and":[],"or":[],"exclude":[]}
				""", 30, LocalDate.now());

		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of(), List.of("GlowDeep_Official"), keywords(List.of("A"), List.of(), List.of()), 14));

		assertThat(response.items()).isEmpty();
		List<RegistrationEntryRow> entries = entriesOf(response.registrationId());
		assertThat(entries.get(0).result()).isEqualTo("duplicate");
	}

	@Test
	void url_모드_행의_input_value는_account_중복_판정_모수에서_제외된다() {
		// url 모드 행의 input_value가 우연히 같은 문자열이어도(shortcode) account 모드 중복 판정과는 무관
		itemRepository.insertPending(userId, "url", UUID.randomUUID(), null, "glowdeep_official",
				"https://www.instagram.com/p/glowdeep_official/", null, 30, LocalDate.now());

		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of(), List.of("glowdeep_official"), keywords(List.of("A"), List.of(), List.of()), 14));

		assertThat(response.items()).hasSize(1);
		assertThat(entriesOf(response.registrationId()).get(0).result()).isEqualTo("pending");
	}

	@Test
	void 같은_요청_안의_상호_중복은_뒤_항목만_duplicate다() {
		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of("https://www.instagram.com/p/ABC123/", "https://www.instagram.com/reel/ABC123/"),
						List.of(), null, 14));

		assertThat(response.items()).hasSize(1);
		List<RegistrationEntryRow> entries = entriesOf(response.registrationId());
		assertThat(entries).extracting(RegistrationEntryRow::result).containsExactly("pending", "duplicate");
	}

	@Test
	void 같은_요청_안의_계정_핸들_상호_중복도_뒤_항목만_duplicate다() {
		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of(), List.of("GlowDeep", "glowdeep"), keywords(List.of("A"), List.of(), List.of()), 14));

		List<RegistrationEntryRow> entries = entriesOf(response.registrationId());
		assertThat(entries).extracting(RegistrationEntryRow::result).containsExactly("pending", "duplicate");
	}

	@Test
	void canceled_행과는_중복이_아니다() {
		long canceledItemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), null, "ABC123",
				"https://www.instagram.com/p/ABC123/", null, 30, LocalDate.now());
		itemRepository.markCanceled(canceledItemId, "tracking", OffsetDateTime.now());

		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of("https://www.instagram.com/p/ABC123/"), List.of(), null, 14));

		assertThat(response.items()).hasSize(1);
		assertThat(entriesOf(response.registrationId()).get(0).result()).isEqualTo("pending");
	}

	@Test
	void entry는_posts_먼저_accounts_다음_요청_순서대로_생성된다() {
		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of("https://www.instagram.com/p/POSTA/", "https://www.instagram.com/p/POSTB/"),
						List.of("acct1", "acct2"), keywords(List.of("A"), List.of(), List.of()), 14));

		List<RegistrationEntryRow> entries = entriesOf(response.registrationId());
		assertThat(entries).extracting(RegistrationEntryRow::input)
				.containsExactly("https://www.instagram.com/p/POSTA/", "https://www.instagram.com/p/POSTB/",
						"acct1", "acct2");
		assertThat(entries).extracting(RegistrationEntryRow::kind)
				.containsExactly("post", "post", "account", "account");
		assertThat(entries).extracting(RegistrationEntryRow::seq).containsExactly(0, 1, 2, 3);
	}

	@Test
	void pending_행_생성값_url_모드_source_url() {
		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of("https://www.instagram.com/reel/POSTX/?utm_source=x"), List.of(), null, 21));

		TrackingItemResponse item = response.items().get(0);
		MonitoringItemRow row = itemRepository.findByIdAndUser(Long.parseLong(item.id()), userId).orElseThrow();
		assertThat(row.mode()).isEqualTo("url");
		assertThat(row.inputValue()).isEqualTo("POSTX");
		assertThat(row.sourceUrl()).isEqualTo("https://www.instagram.com/reel/POSTX/");
		assertThat(row.trackingDays()).isEqualTo(21);
		assertThat(item.sourceUrl()).isEqualTo("https://www.instagram.com/reel/POSTX/");
		assertThat(item.status()).isEqualTo("collecting");
	}

	@Test
	void pending_행_생성값_account_모드_keywords_json() {
		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of(), List.of("glowdeep.official"),
						keywords(List.of("글로우딥"), List.of("A", "B"), List.of("나눔")), 14));

		TrackingItemResponse item = response.items().get(0);
		MonitoringItemRow row = itemRepository.findByIdAndUser(Long.parseLong(item.id()), userId).orElseThrow();
		assertThat(row.mode()).isEqualTo("account");
		assertThat(row.inputValue()).isEqualTo("glowdeep.official");

		Map<?, ?> json = objectMapper.readValue(row.keywords(), Map.class);
		assertThat((List<String>) json.get("and")).containsExactly("글로우딥");
		assertThat((List<String>) json.get("or")).containsExactly("A", "B");
		assertThat((List<String>) json.get("exclude")).containsExactly("나눔");

		assertThat(item.status()).isEqualTo("detecting");
		assertThat(item.keywords().and()).containsExactly("글로우딥");
		assertThat(item.keywords().or()).containsExactly("A", "B");
		assertThat(item.keywords().exclude()).containsExactly("나눔");
	}

	@Test
	void share_링크는_entry만_pending이고_행은_생성되지_않는다() {
		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of("https://www.instagram.com/share/xyz123/"), List.of(), null, 14));

		assertThat(response.items()).isEmpty();
		List<RegistrationEntryRow> entries = entriesOf(response.registrationId());
		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).result()).isEqualTo("pending");
		assertThat(entries.get(0).itemId()).isNull();
		assertThat(itemRepository.findByUser(userId)).isEmpty();
	}

	@Test
	void 등록_접수_후_executor_submit이_호출된다() {
		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of("https://www.instagram.com/p/ABC999/"), List.of(), null, 14));

		then(executor).should().submit(Long.parseLong(response.registrationId()));
	}

	@Test
	void 전_항목_실패해도_등록은_성공하고_빈_items를_반환한다() {
		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of("not-a-url"), List.of(), null, 14));

		assertThat(response.items()).isEmpty();
		assertThat(entriesOf(response.registrationId()).get(0).result()).isEqualTo("failed");
		then(executor).should().submit(anyLong());
	}
}
