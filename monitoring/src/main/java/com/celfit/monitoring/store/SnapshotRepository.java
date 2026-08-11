package com.celfit.monitoring.store;

import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 스냅샷 테이블 접점 — 관측 대상 단위로 하루 1행(캠페인 수와 무관).
 * 같은 날 재수집은 덮어쓴다(마지막 관측이 그날의 값).
 */
@Repository
public class SnapshotRepository {

	private final JdbcTemplate db;

	public SnapshotRepository(JdbcTemplate db) {
		this.db = db;
	}

	public void upsertProfile(String username, LocalDate on, ProfileInfo p) {
		db.update("""
				INSERT INTO profile_snapshot (username, captured_on, followers, following, media_count)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (username, captured_on) DO UPDATE SET
				  followers=EXCLUDED.followers, following=EXCLUDED.following,
				  media_count=EXCLUDED.media_count""",
				username, on, p.followers(), p.following(), p.mediaCount());
	}

	/**
	 * 스냅샷은 지표만 담는다 — takenAt·캡션 같은 게시물 속성은 저장 대상이 아니다.
	 *
	 * <p>views는 **화면 합산값**(IG 몫 + FB 몫)으로 조립해 저장한다(findings §2 결론 4).
	 * FB 몫이 이번 콜에 안 실렸으면(IG 전용 세션, {@code fbPlays()==null}) 직전 관측 fb_plays를
	 * 캐리포워드한다 — FB 몫은 실측상 며칠 단위로 거의 정적이라 오차가 작고, 이 규칙 덕에 세션
	 * 복불복에도 views 시계열이 역행하지 않는다. 관측된 0은 캐리포워드가 아니라 0으로 덮는다.
	 * views가 null(피드·클립 보강 실패)이면 fb와 무관하게 null 유지 — 비공개 오탐 방지 계약 그대로.
	 */
	public void upsertPost(LocalDate on, PostInfo p) {
		Long fb = p.fbPlays() != null ? p.fbPlays() : latestFbPlays(p.shortCode(), on);
		Long views = p.views() == null ? null : p.views() + (fb != null ? fb : 0);
		if (p.fbPlays() != null) {
			// 역전파 — fb를 처음 관측하면 이전의 미관측 행에도 소급 적용한다. 안 하면 fb 관측 시작일에
			// 시계열이 +fb만큼 점프해 성과 추이가 "증가"로 오표시된다(FB 몫은 실측상 정적이라 소급이
			// 근사적으로 정확). fb_plays가 이미 있는 행(관측·캐리포워드)은 건드리지 않는다 — 이중 가산 금지.
			// 대부분의 콜에서 0행 UPDATE(이미 전파됨)라 비용은 무시 가능.
			db.update("""
					UPDATE post_snapshot SET fb_plays = ?, views = views + ?
					WHERE short_code = ? AND captured_on < ? AND fb_plays IS NULL AND views IS NOT NULL""",
					p.fbPlays(), p.fbPlays(), p.shortCode(), on);
		}
		db.update("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type,
				                           likes, likes_hidden, comments, views, fb_plays,
				                           saves, shares, shares_hidden, reposts)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (short_code, captured_on) DO UPDATE SET
				  likes=EXCLUDED.likes, likes_hidden=EXCLUDED.likes_hidden,
				  comments=EXCLUDED.comments, views=EXCLUDED.views,
				  fb_plays=EXCLUDED.fb_plays,
				  saves=EXCLUDED.saves, shares=EXCLUDED.shares,
				  shares_hidden=EXCLUDED.shares_hidden, reposts=EXCLUDED.reposts""",
				p.username(), p.shortCode(), on, p.contentType(),
				p.likes(), p.likesHidden(), p.comments(), views, fb,
				p.saves(), p.shares(), p.sharesHidden(), p.reposts());
	}

	/**
	 * FB 몫 관측 이력이 있는 코드들(최초 1회 재시도 판정용, CollectService) — 이력이 있으면 캐리포워드가
	 * 가능하므로 재시도 대상이 아니다. 계정 열거 12건을 한 번에 묻는 배치 조회(IN절)라 수집당 1쿼리다.
	 */
	public Set<String> codesWithFbObserved(Collection<String> codes) {
		if (codes.isEmpty()) {
			return Set.of();
		}
		String placeholders = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
		return new HashSet<>(db.queryForList(
				"SELECT DISTINCT short_code FROM post_snapshot WHERE fb_plays IS NOT NULL AND short_code IN ("
						+ placeholders + ")",
				String.class, codes.toArray()));
	}

	/**
	 * 리포스트 0 캐리 대상 코드(08-05) — 양수 관측 이력이 전무하고, 오늘 이전 마지막 행이 0으로
	 * 끝난(=전일 0 간주 또는 관측 0) 게시물. 구조적으로 키가 안 오는 게시물(리포스트 0·공유 미상)이
	 * 매일 재시도 상한까지 헛돌지 않도록, 재시도 진입 전에 이 판정으로 즉시 0을 기록한다.
	 * 나중에 실제 값이 생기면 키가 오기 시작하고 양수 관측이 이력에 남아 이 판정이 자동 해제된다.
	 */
	public Set<String> codesWithRepostsZeroCarry(Collection<String> codes, LocalDate today) {
		return zeroCarryCodes(codes, "reposts", today);
	}

	/** 공유 0 캐리 대상 코드 — 규칙은 리포스트와 동형(호출부가 숨김 게시물을 미리 제외한다). */
	public Set<String> codesWithSharesZeroCarry(Collection<String> codes, LocalDate today) {
		return zeroCarryCodes(codes, "shares", today);
	}

	/** column은 이 클래스의 상수 호출("reposts"/"shares")만 — 외부 입력이 아니라 SQL 조립이 안전하다. */
	private Set<String> zeroCarryCodes(Collection<String> codes, String column, LocalDate today) {
		if (codes.isEmpty()) {
			return Set.of();
		}
		String placeholders = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
		Object[] args = new Object[codes.size() + 1];
		int i = 0;
		for (String code : codes) {
			args[i++] = code;
		}
		args[i] = today;
		return new HashSet<>(db.queryForList("""
				SELECT short_code FROM post_snapshot
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
				SELECT fb_plays FROM post_snapshot
				WHERE short_code = ? AND captured_on <= ? AND fb_plays IS NOT NULL
				ORDER BY captured_on DESC LIMIT 1""",
				(rs, i) -> rs.getObject("fb_plays", Long.class), shortCode, on)
				.stream().findFirst().orElse(null);
	}

	/**
	 * 팔로워 1회 수집(트랙 II 후속) — 해당 계정에 profile_snapshot 행이 하나라도 있는지 판정.
	 * POST 등록분만 있는 계정은 계정 갈래를 영구히 안 타 이 행이 계속 없다 — DailySweepJob이
	 * 이 메서드로 "아직 안 채워졌으면 1회만" 여부를 판단한다.
	 */
	public boolean hasProfileSnapshot(String username) {
		Boolean exists = db.queryForObject(
				"SELECT EXISTS(SELECT 1 FROM profile_snapshot WHERE username = ?)", Boolean.class, username);
		return Boolean.TRUE.equals(exists);
	}

	/**
	 * 직전 스냅샷 — 지표 비공개 판정 기준. 호출 시점이 그날 upsert **직전**이므로 당일 행이 있다면
	 * 그게 바로 "직전 관측"이다(오늘 두 번째 이후 수집) — **당일 포함**(`<=`)으로 조회해야 한다.
	 * `<`로 당일을 건너뛰면 같은 날 두 번째 수집이 어제 값과 다시 비교돼 이미 적재한 METRICS_HIDDEN을
	 * 또 적재한다(리뷰 I1 — 오늘 값이 아직 없는 그날 첫 수집에서는 당일 행 자체가 없으니 결과가 같다).
	 */
	public Optional<PostMetrics> findLatestPostUpTo(String shortCode, LocalDate on) {
		return db.query("""
				SELECT content_type, likes, comments, views, saves, shares, reposts
				FROM post_snapshot
				WHERE short_code = ? AND captured_on <= ?
				ORDER BY captured_on DESC LIMIT 1""",
				(rs, i) -> new PostMetrics(rs.getString("content_type"),
						rs.getObject("likes", Long.class), rs.getObject("comments", Long.class),
						rs.getObject("views", Long.class), rs.getObject("saves", Long.class),
						rs.getObject("shares", Long.class), rs.getObject("reposts", Long.class)),
				shortCode, on)
				.stream().findFirst();
	}
}
