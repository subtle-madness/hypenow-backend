package com.celfit.was.saved;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.saved_influencers·app.saved_contents 저장 CRUD. 인플루언서는 부분 갱신 upsert —
 * status·memo 각각 "미지정이면 기존 값 유지"가 계약이라 memo는 null 지정과 미지정을
 * 구분해야 해서 memoGiven 플래그를 별도로 받는다(NULL 지정 시 memo를 실제로 비우는 것도 허용).
 */
@Repository
public class SavedRepository {

	private final JdbcClient jdbcClient;

	public SavedRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * @param status    null이면 신규 기본값(reviewing) 또는 기존 값 유지
	 * @param memoGiven true면 memo(null 포함)로 갱신, false면 기존 값 유지
	 */
	public SavedInfluencer upsertInfluencer(long userId, String handle, String status, boolean memoGiven,
			String memo) {
		return jdbcClient.sql("""
				INSERT INTO app.saved_influencers (user_id, handle, status, memo)
				VALUES (:userId, :handle, COALESCE(:status, 'reviewing'), :memo)
				ON CONFLICT (user_id, handle) DO UPDATE
				SET status     = COALESCE(:status, app.saved_influencers.status),
				    memo       = CASE WHEN :memoGiven THEN :memo ELSE app.saved_influencers.memo END,
				    updated_at = now()
				RETURNING handle, status, memo, created_at, updated_at
				""")
				.param("userId", userId)
				.param("handle", handle)
				.param("status", status)
				.param("memo", memo)
				.param("memoGiven", memoGiven)
				.query(SavedInfluencer.class)
				.single();
	}

	public List<SavedInfluencer> findInfluencers(long userId) {
		return jdbcClient.sql("""
				SELECT handle, status, memo, created_at, updated_at
				FROM app.saved_influencers
				WHERE user_id = :userId
				ORDER BY updated_at DESC
				""")
				.param("userId", userId)
				.query(SavedInfluencer.class)
				.list();
	}

	/** 멱등 — 없는 행을 지워도 예외 없이 0건 삭제로 끝난다. */
	public void deleteInfluencer(long userId, String handle) {
		jdbcClient.sql("DELETE FROM app.saved_influencers WHERE user_id = :userId AND handle = :handle")
				.param("userId", userId)
				.param("handle", handle)
				.update();
	}

	/** 멱등 upsert — 이미 있으면 created_at을 유지한 채 그대로 반환한다. */
	public SavedContent upsertContent(long userId, String shortCode) {
		return jdbcClient.sql("""
				INSERT INTO app.saved_contents (user_id, short_code)
				VALUES (:userId, :shortCode)
				ON CONFLICT (user_id, short_code) DO UPDATE
				SET short_code = app.saved_contents.short_code
				RETURNING short_code, created_at
				""")
				.param("userId", userId)
				.param("shortCode", shortCode)
				.query(SavedContent.class)
				.single();
	}

	public List<SavedContent> findContents(long userId) {
		return jdbcClient.sql("""
				SELECT short_code, created_at
				FROM app.saved_contents
				WHERE user_id = :userId
				ORDER BY created_at DESC
				""")
				.param("userId", userId)
				.query(SavedContent.class)
				.list();
	}

	/** 멱등 — 없는 행을 지워도 예외 없이 0건 삭제로 끝난다. */
	public void deleteContent(long userId, String shortCode) {
		jdbcClient.sql("DELETE FROM app.saved_contents WHERE user_id = :userId AND short_code = :shortCode")
				.param("userId", userId)
				.param("shortCode", shortCode)
				.update();
	}
}
