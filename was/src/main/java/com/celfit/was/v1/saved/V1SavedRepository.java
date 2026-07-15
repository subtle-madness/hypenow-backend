package com.celfit.was.v1.saved;

import com.celfit.was.v1.content.ContentCard;
import com.celfit.was.v1.content.ContentCardRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * /v1 저장 2종(스펙 6.6~6.11) 리포지토리. app 쓰기·읽기와 analysis 미러 읽기를 <b>각각 별도 쿼리</b>로
 * 수행한다 — app 스키마와 analysis 테이블을 SQL 조인하지 않는다(§4-4). 조합은 컨트롤러·어셈블러 몫.
 * 구 saved.SavedRepository와 병존하며 같은 테이블을 다루되, v1은 memo만 upsert하고 status는 손대지 않는다.
 */
@Repository
public class V1SavedRepository {

	private final JdbcClient jdbcClient;

	public V1SavedRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	// --- app.saved_contents (서비스 데이터 쓰기·읽기) ---

	/**
	 * memo upsert(스펙 6.7) — 이미 있으면 memo만 EXCLUDED로 갱신, created_at은 유지한다.
	 * inserted는 (xmax = 0) — 이번 문장이 INSERT면 true(201), UPDATE면 false(200)를 가른다.
	 */
	public ContentSave upsertContent(long userId, String shortCode, String memo) {
		return jdbcClient.sql("""
				INSERT INTO app.saved_contents (user_id, short_code, memo)
				VALUES (:userId, :shortCode, :memo)
				ON CONFLICT (user_id, short_code) DO UPDATE
				SET memo = EXCLUDED.memo
				RETURNING short_code, memo, created_at, (xmax = 0) AS inserted
				""")
				.param("userId", userId)
				.param("shortCode", shortCode)
				.param("memo", memo)
				.query(ContentSave.class)
				.single();
	}

	/** 최근 저장 순(created_at DESC) — 목록은 전량 반환(스펙 3.3, 페이지네이션 없음). */
	public List<SavedContentRow> findSavedContents(long userId) {
		return jdbcClient.sql("""
				SELECT short_code, memo, created_at
				FROM app.saved_contents
				WHERE user_id = :userId
				ORDER BY created_at DESC, short_code
				""")
				.param("userId", userId)
				.query(SavedContentRow.class)
				.list();
	}

	/** 멱등 — 없는 행을 지워도 예외 없이 0건 삭제로 끝난다(스펙 6.8). */
	public void deleteContent(long userId, String shortCode) {
		jdbcClient.sql("DELETE FROM app.saved_contents WHERE user_id = :userId AND short_code = :shortCode")
				.param("userId", userId)
				.param("shortCode", shortCode)
				.update();
	}

	// --- app.saved_influencers (서비스 데이터 쓰기·읽기) ---

	/**
	 * memo upsert(스펙 6.10) — status는 건드리지 않는다: 신규 행은 테이블 DEFAULT('reviewing'),
	 * 기존 행은 값 유지. v1 응답에는 status를 노출하지 않는다(구 /api/saved 전용 필드).
	 */
	public InfluencerSave upsertInfluencer(long userId, String handle, String memo) {
		return jdbcClient.sql("""
				INSERT INTO app.saved_influencers (user_id, handle, memo)
				VALUES (:userId, :handle, :memo)
				ON CONFLICT (user_id, handle) DO UPDATE
				SET memo = EXCLUDED.memo, updated_at = now()
				RETURNING handle, memo, created_at, (xmax = 0) AS inserted
				""")
				.param("userId", userId)
				.param("handle", handle)
				.param("memo", memo)
				.query(InfluencerSave.class)
				.single();
	}

	/** 최근 저장 순 — savedAt은 최초 저장 시각이라 created_at DESC(테이블엔 updated_at도 있으나 정렬 기준 아님). */
	public List<SavedInfluencerRow> findSavedInfluencers(long userId) {
		return jdbcClient.sql("""
				SELECT handle, memo, created_at
				FROM app.saved_influencers
				WHERE user_id = :userId
				ORDER BY created_at DESC, handle
				""")
				.param("userId", userId)
				.query(SavedInfluencerRow.class)
				.list();
	}

	/** 멱등 — 없는 행을 지워도 0건 삭제(스펙 6.11). 구 saved.SavedRepository와 동일 SQL(별 bean이라 재구현). */
	public void deleteInfluencer(long userId, String handle) {
		jdbcClient.sql("DELETE FROM app.saved_influencers WHERE user_id = :userId AND handle = :handle")
				.param("userId", userId)
				.param("handle", handle)
				.update();
	}

	// --- analysis 미러 읽기 (읽기 전용, app과 별도 쿼리) ---

	/**
	 * 카드 1건 — contents ⋈ content_analyses ⋈ accounts. POST 저장 시 존재 확인을 겸한다:
	 * 분석 완료 콘텐츠만 카드가 되므로 empty면 404(GET 목록의 미분석 제외 정책과 동일한 기준).
	 */
	public Optional<ContentCardRow> findCard(String shortCode) {
		return jdbcClient.sql(ContentCardRow.SELECT + """

				FROM contents c
				JOIN content_analyses an ON an.short_code = c.short_code
				JOIN accounts a ON a.handle = c.account_handle
				WHERE c.short_code = :sc
				""")
				.param("sc", shortCode)
				.query(ContentCardRow.class)
				.optional();
	}

	/** 저장된 short_code 묶음의 카드 일괄 조회 — 순서 보장 없음(조합 시 저장 순서로 재정렬). */
	public List<ContentCardRow> findCards(List<String> shortCodes) {
		return jdbcClient.sql(ContentCardRow.SELECT + """

				FROM contents c
				JOIN content_analyses an ON an.short_code = c.short_code
				JOIN accounts a ON a.handle = c.account_handle
				WHERE c.short_code IN (:codes)
				""")
				.param("codes", shortCodes)
				.query(ContentCardRow.class)
				.list();
	}

	/** accounts 프로필 1건(id=handle) — POST 존재 확인 겸 응답 프로필. 없으면 404. */
	public Optional<ContentCard.Influencer> findInfluencer(String handle) {
		return jdbcClient.sql("""
				SELECT handle, display_name, profile_image_url, followers
				FROM accounts
				WHERE handle = :h
				""")
				.param("h", handle)
				.query((rs, i) -> new ContentCard.Influencer(rs.getString("handle"), rs.getString("handle"),
						rs.getString("display_name"), rs.getString("profile_image_url"),
						rs.getObject("followers", Long.class)))
				.optional();
	}

	/** 저장된 handle 묶음의 프로필 일괄 조회 — 부재분은 결과에서 빠지고, 조합 시 handle-only로 채운다. */
	public List<ContentCard.Influencer> findInfluencers(List<String> handles) {
		return jdbcClient.sql("""
				SELECT handle, display_name, profile_image_url, followers
				FROM accounts
				WHERE handle IN (:handles)
				""")
				.param("handles", handles)
				.query((rs, i) -> new ContentCard.Influencer(rs.getString("handle"), rs.getString("handle"),
						rs.getString("display_name"), rs.getString("profile_image_url"),
						rs.getObject("followers", Long.class)))
				.list();
	}

	// --- app 저장 행 형태 ---

	public record ContentSave(String shortCode, String memo, OffsetDateTime createdAt, boolean inserted) {
	}

	public record SavedContentRow(String shortCode, String memo, OffsetDateTime createdAt) {
	}

	public record InfluencerSave(String handle, String memo, OffsetDateTime createdAt, boolean inserted) {
	}

	public record SavedInfluencerRow(String handle, String memo, OffsetDateTime createdAt) {
	}
}
