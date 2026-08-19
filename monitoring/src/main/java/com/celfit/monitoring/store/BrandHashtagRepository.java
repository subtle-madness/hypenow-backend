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
 * 해시태그 감지 저장(스펙 2026-08-11) — 태그·판정 게시물 저장(2026-08-17: 제외 문자열 기능이
 * 폐기되며 관리 메서드도 함께 걷어냈다 — {@code brand_hashtag_exclusion} 테이블 자체는
 * expand-contract 원칙상 이번 릴리스에서 DROP하지 않고 남아 있지만, 여기서는 더 이상 읽지도
 * 쓰지도 않는다). 게시물은 필터 도달 전량(SELF·DIRECT_TAGGED 포함) 저장 — 조기 종료·dedup·
 * 재판정 재료.
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
	 * UPSERT로 삽입하거나 tombstone을 해제(deleted_at = NULL)해 재활성한다. 빈 목록도 허용
	 * (전체 비활성화 — 단건 API 도입 후 정당한 상태, PUT 자체의 하한 가드는 폐지됨).
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
			upsertTag(brandId, tag);
		}
	}

	/** 단건·다건 명시적 추가(POST 계약) — tombstone 재활성 UPSERT(replaceTags의 삽입 구문과 동일 의미론). */
	public void addTags(long brandId, Collection<String> tags) {
		for (String tag : tags) {
			upsertTag(brandId, tag);
		}
	}

	/** 단건 삭제(tombstone, DELETE {tag} 계약) — 없어도 무해(대상 0행이면 UPDATE도 0행). */
	public void deleteTag(long brandId, String tag) {
		db.update("UPDATE brand_hashtag SET deleted_at = now() WHERE brand_id = ? AND tag = ? AND deleted_at IS NULL",
				brandId, tag);
	}

	/** 활성 전체 삭제(tombstone, DELETE 전체 계약) — 브랜드 단위로 해시태그 감지를 일시 중지하는 것과 같다. */
	public void deleteAllTags(long brandId) {
		db.update("UPDATE brand_hashtag SET deleted_at = now() WHERE brand_id = ? AND deleted_at IS NULL", brandId);
	}

	private void upsertTag(long brandId, String tag) {
		db.update("""
				INSERT INTO brand_hashtag (brand_id, tag) VALUES (?, ?)
				ON CONFLICT (brand_id, tag) DO UPDATE SET deleted_at = NULL""",
				brandId, tag);
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

	/**
	 * 매칭 태그 전체 기록(2026-08-19, was 사용자 스코프 필터 지원) — 이 (brand, shortcode)가 이 태그의
	 * recent 열거 스트림에도 나타났다는 사실을 추가한다. FK가 brand_hashtag_post(brand_id,
	 * short_code)를 향하므로 그 행이 먼저 있어야 한다 — 호출부는 신규 저장 직후(processNew) 또는
	 * 이미 저장된 행을 다른 태그가 다시 만났을 때(sweepTag의 existing 분기)만 부른다. 멱등
	 * (ON CONFLICT DO NOTHING) — 같은 (brand, shortcode, tag)를 여러 스윕이 반복 기록해도 안전하다.
	 */
	public void recordTagMatch(long brandId, String shortCode, String tag) {
		db.update("""
				INSERT INTO brand_hashtag_post_matched_tags (brand_id, short_code, tag)
				VALUES (?, ?, ?)
				ON CONFLICT DO NOTHING""",
				brandId, shortCode, tag);
	}

	/** {@link #recordTagMatch} 배치판 — sweepTag의 "이미 존재하는" 코드 묶음 전용. */
	public void recordTagMatches(long brandId, Collection<String> shortCodes, String tag) {
		for (String shortCode : shortCodes) {
			recordTagMatch(brandId, shortCode, tag);
		}
	}
}
