package com.celfit.was.v1.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.celfit.was.v1.account.SessionService.SessionView;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;

/** SessionService 단위 검증 — 세션 저장소는 인터페이스 목, 세션은 MapSession 실물로 조립한다. */
class SessionServiceTest {

	private static final String EMAIL = "user@example.com";

	@SuppressWarnings("unchecked")
	private final FindByIndexNameSessionRepository<MapSession> repository =
			mock(FindByIndexNameSessionRepository.class);

	private SessionService service;

	@BeforeEach
	void setUp() {
		service = new SessionService(repository);
	}

	private MapSession session(String id, Instant creationTime, String browser, String os) {
		MapSession session = new MapSession(id);
		session.setCreationTime(creationTime);
		if (browser != null) {
			session.setAttribute("session.browser", browser);
		}
		if (os != null) {
			session.setAttribute("session.os", os);
		}
		return session;
	}

	@Test
	void alias는_결정적이고_hex_16자며_원본_id를_노출하지_않는다() {
		String alias = SessionService.alias("real-session-id");

		assertThat(alias).isEqualTo(SessionService.alias("real-session-id")); // 결정성 — 목록·삭제가 같은 값을 본다
		assertThat(alias).hasSize(16).matches("[0-9a-f]{16}");
		assertThat(alias).isNotEqualTo("real-session-id");
		assertThat(SessionService.alias("other-session-id")).isNotEqualTo(alias);
	}

	@Test
	void listSessions는_loginAt_내림차순에_current_판정과_alias_id를_담는다() {
		Map<String, MapSession> sessions = new LinkedHashMap<>();
		sessions.put("old-id", session("old-id", Instant.parse("2026-07-01T00:00:00Z"), "Chrome", "Mac OS X"));
		sessions.put("new-id", session("new-id", Instant.parse("2026-07-15T09:00:00Z"), "Safari", "iOS"));
		given(repository.findByPrincipalName(EMAIL)).willReturn(sessions);

		List<SessionView> views = service.listSessions(EMAIL, "old-id");

		assertThat(views).hasSize(2);
		assertThat(views.get(0).id()).isEqualTo(SessionService.alias("new-id")); // 최근 로그인 먼저
		assertThat(views.get(0).browser()).isEqualTo("Safari");
		assertThat(views.get(0).os()).isEqualTo("iOS");
		assertThat(views.get(0).loginAt()).isEqualTo("2026-07-15T09:00:00Z");
		assertThat(views.get(0).current()).isFalse();
		assertThat(views.get(1).id()).isEqualTo(SessionService.alias("old-id"));
		assertThat(views.get(1).current()).isTrue();
	}

	@Test
	void listSessions는_UA_attribute가_없으면_기타로_채운다() {
		given(repository.findByPrincipalName(EMAIL)).willReturn(
				Map.of("bare-id", session("bare-id", Instant.parse("2026-07-10T00:00:00Z"), null, null)));

		List<SessionView> views = service.listSessions(EMAIL, "bare-id");

		assertThat(views.get(0).browser()).isEqualTo("기타");
		assertThat(views.get(0).os()).isEqualTo("기타");
	}

	@Test
	void deleteByAlias는_매칭되면_실_id로_삭제하고_true다() {
		given(repository.findByPrincipalName(EMAIL)).willReturn(
				Map.of("target-id", session("target-id", Instant.now(), "Chrome", "Windows")));

		boolean deleted = service.deleteByAlias(EMAIL, SessionService.alias("target-id"));

		assertThat(deleted).isTrue();
		then(repository).should().deleteById("target-id");
	}

	@Test
	void deleteByAlias는_본인_목록에_없으면_false고_아무것도_지우지_않는다() {
		given(repository.findByPrincipalName(EMAIL)).willReturn(
				Map.of("mine-id", session("mine-id", Instant.now(), "Chrome", "Windows")));

		boolean deleted = service.deleteByAlias(EMAIL, SessionService.alias("someone-elses-id"));

		assertThat(deleted).isFalse();
		then(repository).should(never()).deleteById(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void deleteOthers는_현재_세션만_남기고_지운다() {
		Map<String, MapSession> sessions = new LinkedHashMap<>();
		sessions.put("current-id", session("current-id", Instant.now(), "Chrome", "Mac OS X"));
		sessions.put("other-1", session("other-1", Instant.now(), "Safari", "iOS"));
		sessions.put("other-2", session("other-2", Instant.now(), "Edge", "Windows"));
		given(repository.findByPrincipalName(EMAIL)).willReturn(sessions);

		service.deleteOthers(EMAIL, "current-id");

		then(repository).should().deleteById("other-1");
		then(repository).should().deleteById("other-2");
		then(repository).should(never()).deleteById("current-id");
	}

	@Test
	void deleteAll은_전_세션을_지운다() {
		Map<String, MapSession> sessions = new LinkedHashMap<>();
		sessions.put("id-1", session("id-1", Instant.now(), null, null));
		sessions.put("id-2", session("id-2", Instant.now(), null, null));
		given(repository.findByPrincipalName(EMAIL)).willReturn(sessions);

		service.deleteAll(EMAIL);

		then(repository).should().deleteById("id-1");
		then(repository).should().deleteById("id-2");
	}
}
