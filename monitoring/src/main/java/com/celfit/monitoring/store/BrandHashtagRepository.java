package com.celfit.monitoring.store;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 해시태그 감지 저장(스펙 2026-08-11) — 태그·제외 문자열·판정 게시물 3테이블.
 * 게시물은 필터 도달 전량(SELF·DIRECT_TAGGED 포함) 저장 — 조기 종료·dedup·재판정 재료.
 */
@Repository
public class BrandHashtagRepository {

	private final JdbcTemplate db;

	public BrandHashtagRepository(JdbcTemplate db) {
		this.db = db;
	}

	/** 활성 태그만(deleted_at IS NULL) — 스윕·시드 판정 재료. 유저가 지운 태그는 여기서 빠진다. */
	public List<String> findTags(long brandId) {
		return db.queryForList(
				"SELECT tag FROM brand_hashtag WHERE brand_id = ? AND deleted_at IS NULL ORDER BY created_at, tag",
				String.class, brandId);
	}

	/**
	 * 등록·replay 자동 시드 전용 — ON CONFLICT DO NOTHING이라 유저가 지운(tombstone, deleted_at
	 * 채워짐) 태그는 되살아나지 않는다. 되살리려면 {@link #replaceTags}(재활성 UPSERT)를 쓸 것.
	 */
	public void insertTags(long brandId, Collection<String> tags) {
		for (String tag : tags) {
			db.update("INSERT INTO brand_hashtag (brand_id, tag) VALUES (?, ?) ON CONFLICT DO NOTHING",
					brandId, tag);
		}
	}

	/**
	 * 태그 셋 전체 교체(유저 관리 API, 2026-08-12) — tombstone 의미론. 새 목록에 없는 기존 활성
	 * 태그는 deleted_at을 채워 비활성화(행은 남아 자동 시드가 못 되살림), 새 목록의 태그는
	 * UPSERT로 삽입하거나 tombstone을 해제(deleted_at = NULL)해 재활성한다.
	 */
	@Transactional
	public void replaceTags(long brandId, List<String> tags) {
		Set<String> newTags = new LinkedHashSet<>(tags);
		for (String existing : findTags(brandId)) {
			if (!newTags.contains(existing)) {
				db.update("UPDATE brand_hashtag SET deleted_at = now() WHERE brand_id = ? AND tag = ?",
						brandId, existing);
			}
		}
		for (String tag : newTags) {
			db.update("""
					INSERT INTO brand_hashtag (brand_id, tag) VALUES (?, ?)
					ON CONFLICT (brand_id, tag) DO UPDATE SET deleted_at = NULL""",
					brandId, tag);
		}
	}

	public List<String> findExclusionTerms(long brandId) {
		return db.queryForList(
				"SELECT term FROM brand_hashtag_exclusion WHERE brand_id = ? ORDER BY created_at, term",
				String.class, brandId);
	}

	public void insertDefaultExclusion(long brandId, String term) {
		db.update("INSERT INTO brand_hashtag_exclusion (brand_id, term) VALUES (?, ?) ON CONFLICT DO NOTHING",
				brandId, term);
	}

	/** 전체 교체(PUT 계약) — 삭제 후 재삽입을 한 트랜잭션으로. */
	@Transactional
	public void replaceExclusionTerms(long brandId, List<String> terms) {
		db.update("DELETE FROM brand_hashtag_exclusion WHERE brand_id = ?", brandId);
		for (String term : terms) {
			db.update("INSERT INTO brand_hashtag_exclusion (brand_id, term) VALUES (?, ?) ON CONFLICT DO NOTHING",
					brandId, term);
		}
	}

	/** 페이지 단위 기존 코드 조회 — 조기 종료·스킵 판정 재료. 빈 입력은 선처리(IN ()은 SQL 오류). */
	public Set<String> existingCodes(long brandId, List<String> codes) {
		if (codes.isEmpty()) {
			return Set.of();
		}
		String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
		Object[] args = new Object[codes.size() + 1];
		args[0] = brandId;
		for (int i = 0; i < codes.size(); i++) {
			args[i + 1] = codes.get(i);
		}
		return new HashSet<>(db.queryForList(
				"SELECT short_code FROM brand_hashtag_post WHERE brand_id = ? AND short_code IN ("
						+ placeholders + ")",
				String.class, args));
	}

	/** 발견 게시물 삽입 필드 묶음 — BrandSnapshotRepository.upsertPost(LocalDate, PostInfo) 등과 정합. */
	public record HashtagPostInsert(long brandId, String matchedTag, String shortCode, String authorUsername,
			String authorFullName, String authorProfilePicUrl, OffsetDateTime takenAt, String caption,
			String contentType, String thumbnailUrl, Long likes, Long comments,
			String verdict, String verdictSource) {
	}

	public void insertPost(HashtagPostInsert post) {
		db.update("""
				INSERT INTO brand_hashtag_post (brand_id, short_code, matched_tag, author_username,
				    author_full_name, author_profile_pic_url, taken_at, caption, content_type,
				    thumbnail_url, likes, comments, verdict, verdict_source)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (brand_id, short_code) DO NOTHING""",
				post.brandId(), post.shortCode(), post.matchedTag(), post.authorUsername(),
				post.authorFullName(), post.authorProfilePicUrl(), post.takenAt(),
				post.caption() != null ? post.caption() : "", post.contentType(), post.thumbnailUrl(),
				post.likes(), post.comments(), post.verdict(), post.verdictSource());
	}
}
