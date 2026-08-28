package com.celfit.was.monitoring;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.ad_disclosure_notices — 광고 미표기 판정 알림을 <b>게시물당 1회</b>로 묶는 이력(설계 §8).
 * 사전·프롬프트 갱신 후 리셋·재판정이 돌면 같은 게시물이 다음 주에 다시 NOT_DISCLOSED로 잡히는데,
 * 이 이력이 없으면 사용자가 같은 게시물을 매주 다시 통보받는다.
 *
 * <p>행이 주(notified_week)를 함께 들고 있는 이유: 이력을 "있다/없다"로만 두면 잡이 이번 주
 * 후보를 기록한 직후 같은 주 따라잡기 틱이 그 기록에 걸려 방금 만든 알림 항목을 스스로
 * 지워 버린다. "이번 주가 아닌 주에 이미 알린 것"만 걸러내면 그 자기무효화가 사라진다.
 */
@Repository
public class AdDisclosureNoticeRepository {

	private final JdbcClient jdbcClient;

	public AdDisclosureNoticeRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 후보 중 <b>이번 주가 아닌</b> 주에 이미 알린 shortCode 집합 — 호출부가 후보에서 뺀다. */
	public Set<String> findNotifiedInOtherWeek(long userId, Collection<String> shortCodes, LocalDate weekStart) {
		if (shortCodes.isEmpty()) {
			return Set.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return new LinkedHashSet<>(jdbcClient.sql("""
				SELECT short_code FROM app.ad_disclosure_notices
				WHERE user_id = :userId AND short_code IN (:shortCodes) AND notified_week <> :weekStart
				""")
				.param("userId", userId)
				.param("shortCodes", shortCodes)
				.param("weekStart", weekStart)
				.query(String.class)
				.list());
	}

	/** 이번 주 알림 대상 기록 — 이미 있으면 최초 주를 보존한다(같은 주 재실행 멱등). */
	public void markNotified(long userId, Collection<String> shortCodes, LocalDate weekStart) {
		for (String shortCode : shortCodes) {
			jdbcClient.sql("""
					INSERT INTO app.ad_disclosure_notices (user_id, short_code, notified_week)
					VALUES (:userId, :shortCode, :weekStart)
					ON CONFLICT (user_id, short_code) DO NOTHING
					""")
					.param("userId", userId)
					.param("shortCode", shortCode)
					.param("weekStart", weekStart)
					.update();
		}
	}
}
