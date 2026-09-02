package com.celfit.monitoring.store;

import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.ProfileInfo;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * brand_post_snapshot·brand_profile_snapshot 접점 — 게시물/브랜드 계정 전역 하루 1행.
 * 캠페인 {@link SnapshotRepository}와 동형 규칙(fb 캐리포워드·역전파·0 캐리)을 브랜드 전용
 * 테이블에 이식한 것 — 전면 전용 스키마 결정(08-06)으로 캠페인 테이블을 건드리지 않는다.
 */
@Repository
public class BrandSnapshotRepository {

	private final JdbcTemplate db;

	public BrandSnapshotRepository(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * views는 화면 합산값(IG 몫 + FB 몫)으로 조립해 저장 — 규칙은 SnapshotRepository.upsertPost와
	 * 동일(캐리포워드·첫 관측 역전파 포함, findings §2 결론 4). 태그 열거도 세션에 따라 fb_* 키가
	 * 실렸다 빠지는 같은 소스라 규칙이 그대로 적용된다.
	 *
	 * <p><b>같은 날 재수집 시 null 관측의 덮어쓰기 보호</b> — SnapshotRepository.upsertPost 수정과
	 * 동형(이 메서드가 그 메서드를 그대로 이식한 것이라 같은 결함을 그대로 물려받고 있었다). self
	 * 단건(embed)은 saves·shares·reposts를 구조적으로 항상 null 반환하는데, 같은 날 Hiker가 먼저
	 * 채운 값 위에 self가 재수집하면 EXCLUDED가 무조건 이겨 null로 덮이던 결함을 막는다.
	 * saves·shares·reposts·comments는 EXCLUDED가 null이면 기존값을 유지한다(COALESCE, fb_plays와
	 * 동일 원칙). likes·shares는 숨김 플래그(likes_hidden·shares_hidden)와 얽혀 있어, EXCLUDED가
	 * (값 null + hidden=false)인 행만 "미확정"으로 보고 값·hidden 플래그를 함께 보존하고, 진짜
	 * 숨김 관측(hidden=true)은 정상적으로 덮는다.
	 */
	public void upsertPost(LocalDate on, PostInfo p) {
		Long fb = p.fbPlays() != null ? p.fbPlays() : latestFbPlays(p.shortCode(), on);
		Long views = p.views() == null ? null : p.views() + (fb != null ? fb : 0);
		if (p.fbPlays() != null) {
			db.update("""
					UPDATE brand_post_snapshot SET fb_plays = ?, views = views + ?
					WHERE short_code = ? AND captured_on < ? AND fb_plays IS NULL AND views IS NOT NULL""",
					p.fbPlays(), p.fbPlays(), p.shortCode(), on);
		}
		db.update("""
				INSERT INTO brand_post_snapshot (username, short_code, captured_on, content_type,
				                                 likes, likes_hidden, comments, views, fb_plays,
				                                 saves, shares, shares_hidden, reposts)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (short_code, captured_on) DO UPDATE SET
				  likes = CASE WHEN EXCLUDED.likes IS NULL AND EXCLUDED.likes_hidden = false
				               THEN brand_post_snapshot.likes ELSE EXCLUDED.likes END,
				  likes_hidden = CASE WHEN EXCLUDED.likes IS NULL AND EXCLUDED.likes_hidden = false
				               THEN brand_post_snapshot.likes_hidden ELSE EXCLUDED.likes_hidden END,
				  comments = COALESCE(EXCLUDED.comments, brand_post_snapshot.comments),
				  views=EXCLUDED.views,
				  fb_plays=EXCLUDED.fb_plays,
				  saves = COALESCE(EXCLUDED.saves, brand_post_snapshot.saves),
				  shares = CASE WHEN EXCLUDED.shares IS NULL AND EXCLUDED.shares_hidden = false
				               THEN brand_post_snapshot.shares ELSE EXCLUDED.shares END,
				  shares_hidden = CASE WHEN EXCLUDED.shares IS NULL AND EXCLUDED.shares_hidden = false
				               THEN brand_post_snapshot.shares_hidden ELSE EXCLUDED.shares_hidden END,
				  reposts = COALESCE(EXCLUDED.reposts, brand_post_snapshot.reposts)""",
				p.username(), p.shortCode(), on, p.contentType(),
				p.likes(), p.likesHidden(), p.comments(), views, fb,
				p.saves(), p.shares(), p.sharesHidden(), p.reposts());
	}

	/** 브랜드 계정 프로필 추이 — 매일 스윕의 프로필 1콜을 일 1행으로(profile_snapshot 동형). */
	public void upsertBrandProfile(String username, LocalDate on, ProfileInfo p) {
		db.update("""
				INSERT INTO brand_profile_snapshot (username, captured_on, followers, following, media_count)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (username, captured_on) DO UPDATE SET
				  followers=EXCLUDED.followers, following=EXCLUDED.following,
				  media_count=EXCLUDED.media_count""",
				username, on, p.followers(), p.following(), p.mediaCount());
	}

	/** 리포스트 0 캐리 대상 코드 — 규칙은 SnapshotRepository와 동형(DECISIONS 08-05). */
	public Set<String> codesWithRepostsZeroCarry(Collection<String> codes, LocalDate today) {
		return zeroCarryCodes(codes, "reposts", today);
	}

	/** 공유 0 캐리 대상 코드 — 호출부가 숨김 게시물을 미리 제외한다. */
	public Set<String> codesWithSharesZeroCarry(Collection<String> codes, LocalDate today) {
		return zeroCarryCodes(codes, "shares", today);
	}

	/** column은 이 클래스의 상수 호출("reposts"/"shares")만 — 외부 입력이 아니라 SQL 조립이 안전하다. */
	private Set<String> zeroCarryCodes(Collection<String> codes, String column, LocalDate today) {
		if (codes.isEmpty()) {
			return Set.of();
		}
		String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
		Object[] args = new Object[codes.size() + 1];
		int i = 0;
		for (String code : codes) {
			args[i++] = code;
		}
		args[i] = today;
		return new HashSet<>(db.queryForList("""
				SELECT short_code FROM brand_post_snapshot
				WHERE short_code IN (%s)
				GROUP BY short_code
				HAVING bool_or(%s > 0) IS NOT TRUE
				   AND (array_agg(%s ORDER BY captured_on DESC) FILTER (WHERE captured_on < ?))[1] = 0"""
				.formatted(placeholders, column, column),
				String.class, args));
	}

	/** 직전 관측 FB 몫 — 당일 포함(<=, 같은 날 재수집도 이어받는다). 관측 이력이 없으면 null. */
	private Long latestFbPlays(String shortCode, LocalDate on) {
		return db.query("""
				SELECT fb_plays FROM brand_post_snapshot
				WHERE short_code = ? AND captured_on <= ? AND fb_plays IS NOT NULL
				ORDER BY captured_on DESC LIMIT 1""",
				(rs, i) -> rs.getObject("fb_plays", Long.class), shortCode, on)
				.stream().findFirst().orElse(null);
	}
}
