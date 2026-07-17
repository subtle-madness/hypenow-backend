package com.celfit.was.v1.common;

import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 로그인 유저의 저장 셋 일괄 조회 — P1 Optional 응답의 개인화 마킹 재료 (비로그인이면 호출하지 않음). */
@Component
public class SavedLookup {

	private final JdbcClient jdbcClient;

	public SavedLookup(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Set<String> savedShortCodes(long userId) {
		return Set.copyOf(jdbcClient.sql(
				"SELECT short_code FROM app.saved_contents WHERE user_id = :u")
				.param("u", userId).query(String.class).list());
	}

	public boolean isInfluencerSaved(long userId, String handle) {
		return jdbcClient.sql(
				"SELECT count(*) FROM app.saved_influencers WHERE user_id = :u AND handle = :h")
				.param("u", userId).param("h", handle).query(Long.class).single() > 0;
	}
}
