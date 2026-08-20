package com.celfit.was.monitoring;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 사용자 스코프 해시태그 태그 원장(2026-08-19 설계, 상호작용 사용자 스코프 개정) —
 * app.brand_hashtag_tags. monitoring DB의 brand_hashtag(브랜드 단위)는 "누가 그 태그를 추가했는지"를
 * 모른다 — 이 테이블이 "이 유저가 이 브랜드에 등록한 태그"를 was 쪽에 보존해, 태그 관리 API의
 * 조회·수정이 유저 스코프로 성립하게 한다. monitoring에는 여전히 연결 유저 전체 태그의 합집합만
 * 반영한다({@link #unionByBrand}) — 감지 데이터 자체(스윕 대상)는 브랜드 공유가 정책이다.
 *
 * <p>monitoring이 물리적으로 다른 DB라 SQL 조인 불가 — 조합은 was 코드({@code V1BrandAccountService})에서.
 */
@Repository
public class BrandHashtagTagRepository {

	private final JdbcClient jdbcClient;

	public BrandHashtagTagRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 이 유저가 이 브랜드에 등록한 태그 전체 — 태그 관리 API GET의 정본(2026-08-19부터 유저 스코프). */
	public Set<String> findByUserAndBrand(long userId, long brandId) {
		return new LinkedHashSet<>(jdbcClient.sql("""
				SELECT tag FROM app.brand_hashtag_tags WHERE user_id = :userId AND brand_id = :brandId
				ORDER BY tag ASC
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.query(String.class)
				.list());
	}

	/** 이 브랜드에 연결된 전체 유저 태그의 합집합 — monitoring 동기화(스윕 대상) 계산 재료. */
	public Set<String> unionByBrand(long brandId) {
		return new LinkedHashSet<>(jdbcClient.sql("""
				SELECT DISTINCT tag FROM app.brand_hashtag_tags WHERE brand_id = :brandId ORDER BY tag ASC
				""")
				.param("brandId", brandId)
				.query(String.class)
				.list());
	}

	/**
	 * 이 브랜드에 원장 행이 하나라도 있는지(최초 시딩 판정, {@code V1BrandAccountService#ensureSeeded}
	 * 전용) — 이 기능 출시 이전부터 monitoring이 이미 갖고 있던 브랜드 단위 태그는 아무 유저에게도
	 * 귀속돼 있지 않다. 원장이 완전히 비어 있으면(=이 브랜드에서 태그 관리 API를 아직 아무도 안 건드림)
	 * 최초 조작 유저가 monitoring의 현재 태그 전체를 물려받는다(정책 §정지조건 밖 — was 자체 완결
	 * 시딩, 백필 마이그레이션 잡 불필요).
	 */
	public boolean existsForBrand(long brandId) {
		Boolean exists = jdbcClient.sql("""
				SELECT EXISTS (SELECT 1 FROM app.brand_hashtag_tags WHERE brand_id = :brandId)
				""")
				.param("brandId", brandId)
				.query(Boolean.class)
				.single();
		return Boolean.TRUE.equals(exists);
	}

	/**
	 * 이 태그를 excludingUserId 말고 다른 유저도 갖고 있는지(삭제 시맨틱, 08-19) —
	 * {@code BrandDirectPostRepository#hasOtherRegistrant}와 같은 패턴. 있으면 monitoring 삭제 호출을
	 * 건너뛴다 — 내 삭제가 다른 유저의 스윕 대상에서 태그를 빼면 안 된다.
	 */
	public boolean hasOtherUserWithTag(long brandId, String tag, long excludingUserId) {
		Boolean exists = jdbcClient.sql("""
				SELECT EXISTS (
				    SELECT 1 FROM app.brand_hashtag_tags
				    WHERE brand_id = :brandId AND tag = :tag AND user_id <> :userId
				)
				""")
				.param("brandId", brandId)
				.param("tag", tag)
				.param("userId", excludingUserId)
				.query(Boolean.class)
				.single();
		return Boolean.TRUE.equals(exists);
	}

	/** 태그 추가(멱등) — 이미 있으면 무시. 빈 목록은 no-op. */
	public void addTags(long userId, long brandId, List<String> tags) {
		for (String tag : tags) {
			jdbcClient.sql("""
					INSERT INTO app.brand_hashtag_tags (user_id, brand_id, tag)
					VALUES (:userId, :brandId, :tag)
					ON CONFLICT (user_id, brand_id, tag) DO NOTHING
					""")
					.param("userId", userId)
					.param("brandId", brandId)
					.param("tag", tag)
					.update();
		}
	}

	/** 이 유저의 이 브랜드 태그 전체를 주어진 집합으로 교체(PUT 계약) — 내 태그만 바꾼다(다른 유저 무관). */
	public void replaceTags(long userId, long brandId, List<String> tags) {
		jdbcClient.sql("DELETE FROM app.brand_hashtag_tags WHERE user_id = :userId AND brand_id = :brandId")
				.param("userId", userId)
				.param("brandId", brandId)
				.update();
		addTags(userId, brandId, tags);
	}

	/** 단건 삭제(멱등 — 없어도 무해). */
	public void deleteTag(long userId, long brandId, String tag) {
		jdbcClient.sql("""
				DELETE FROM app.brand_hashtag_tags WHERE user_id = :userId AND brand_id = :brandId AND tag = :tag
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.param("tag", tag)
				.update();
	}

	/** 이 유저의 이 브랜드 태그 전체 삭제. */
	public void deleteAllTags(long userId, long brandId) {
		jdbcClient.sql("DELETE FROM app.brand_hashtag_tags WHERE user_id = :userId AND brand_id = :brandId")
				.param("userId", userId)
				.param("brandId", brandId)
				.update();
	}
}
