package com.celfit.monitoring.image;

import com.celfit.instagram.source.AuthorInfo;
import com.celfit.instagram.source.InstagramSource;
import com.celfit.instagram.source.ProfileInfo;
import com.celfit.monitoring.store.AuthorProfileRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 만료된 인스타 CDN 프로필 이미지 재수집 백필(2026-08-25, 어드민 수동 트리거 전용 — 상시 스케줄
 * 아님) — 아카이브 잡들({@link AuthorProfileImageArchiveJob}·{@link HashtagPostAuthorImageArchiveJob})은
 * CDN 서명이 살아있는 동안만 원본을 미러할 수 있다. author_profile은 30일 stale + 재등장 때만
 * 재조회되고, 재등장하지 않는 게시자는 profile_pic_url이 영영 만료 상태로 남아 아카이브가
 * 영구히 skip한다(운영 실측 08-25: author_profile 만료분 7,302건, RELEVANT 해시태그 게시자 만료분
 * 477명 — 그중 375명은 author_profile 행 자체가 없음, 합집합 7,759명). 이 잡은 그 잔존분을 Hiker
 * 재조회로 "만료 아닌 URL"로 갱신만 하고, 실제 다운로드·업로드는 뒤이어 각 아카이브 잡의 자체
 * run()에 맡긴다(같은 실행 안에서 즉시 수거 — {@link #run(int)} 참고) — 재구현을 피하고 배치
 * 상한·만료 필터·건 단위 격리 계약을 그대로 재사용하기 위함이다.
 *
 * <p><b>Phase A(author_profile)</b>: 만료 행은 igUserId로 {@code /v2/user/by/id} 재조회 →
 * {@link AuthorProfileRepository#upsert}로 profile_pic_url·fetched_at을 갱신한다.
 * <b>Phase B(brand_hashtag_post, RELEVANT만)</b>: 만료 작성자마다 먼저 author_profile의 미만료
 * URL을 찾아 재사용하고(Phase A가 방금 갱신한 것 포함, Hiker 호출 0), 없으면 {@code
 * /v2/user/by/username} 재조회 결과로 그 작성자의 미아카이브 행 전부를 갱신한다. 비공개
 * 계정({@link com.celfit.instagram.source.PrivateAccountException})은 깨진 이미지보다 없는 편이
 * 낫다(프론트 이니셜 placeholder)는 판단으로 조용히 skip한다.
 *
 * <p>{@code limit}은 이 실행에서 두 phase가 함께 쓰는 <b>Hiker 호출 총량 상한</b>이다(다운로드
 * 상한이 아니라 재조회 콜 상한 — 08-25 배치 상한 폐지로 다운로드·업로드 자체엔 이제 상한이 없다).
 * Phase A를 먼저 소모하고 남은 예산을 Phase B가 쓴다 — 소진 후 잔여는 deferred로 이월(다음 트리거가 재시도).
 */
public class AuthorImageBackfillJob {

	private static final Logger log = LoggerFactory.getLogger(AuthorImageBackfillJob.class);

	private final JdbcTemplate db;
	private final InstagramSource hiker;
	private final AuthorProfileRepository authorProfileRepo;
	private final AuthorProfileImageArchiveJob authorArchiveJob;
	private final HashtagPostAuthorImageArchiveJob hashtagArchiveJob;

	public AuthorImageBackfillJob(JdbcTemplate db, InstagramSource hiker,
			AuthorProfileRepository authorProfileRepo, AuthorProfileImageArchiveJob authorArchiveJob,
			HashtagPostAuthorImageArchiveJob hashtagArchiveJob) {
		this.db = db;
		this.hiker = hiker;
		this.authorProfileRepo = authorProfileRepo;
		this.authorArchiveJob = authorArchiveJob;
		this.hashtagArchiveJob = hashtagArchiveJob;
	}

	/** Phase별 재조회/재사용/실패/이월 요약. */
	public record PhaseAResult(int refreshed, int failed, int deferred) {}

	public record PhaseBResult(int refreshed, int reused, int failed, int deferred) {}

	public record Result(PhaseAResult authorProfile, PhaseBResult hashtagAuthor) {}

	public Result run(int limit) {
		long nowEpoch = Instant.now().getEpochSecond();
		int[] callsUsed = {0};   // 두 phase가 공유하는 Hiker 호출 예산 소모량(배열로 감싸 람다·메서드 간 전달 없이 순차 갱신)

		PhaseAResult a = runAuthorProfilePhase(limit, callsUsed, nowEpoch);
		PhaseBResult b = runHashtagAuthorPhase(limit, callsUsed, nowEpoch);

		log.info("게시자 프로필 CDN 백필 완료 — 재조회 {}건 / 실패 {}건{}", a.refreshed(), a.failed(),
				a.deferred() > 0 ? ", 잔여 " + a.deferred() + "건 이월" : "");
		log.info("해시태그 작성자 CDN 백필 완료 — 재조회 {}건 / 재사용 {}건 / 실패 {}건{}",
				b.refreshed(), b.reused(), b.failed(),
				b.deferred() > 0 ? ", 잔여 " + b.deferred() + "건 이월" : "");

		// 백필이 갱신한 신선 URL을 같은 실행 안에서 바로 수거 — 각 잡의 만료 필터·건 단위 격리는
		// 그대로 존중한다(재구현하지 않는다). 08-25 배치 상한 폐지로 이제 매 실행이 대기분 전량을 처리한다.
		log.info("백필 직후 즉시 아카이브 수거 시작");
		authorArchiveJob.run();
		hashtagArchiveJob.run();

		return new Result(a, b);
	}

	private record AuthorCandidate(String igUserId, String profilePicUrl) {}

	private PhaseAResult runAuthorProfilePhase(int limit, int[] callsUsed, long nowEpoch) {
		List<AuthorCandidate> candidates = db.query("""
				SELECT ig_user_id, profile_pic_url FROM author_profile
				WHERE image_object_path IS NULL AND profile_pic_url LIKE 'http%'
				""", (rs, i) -> new AuthorCandidate(rs.getString("ig_user_id"), rs.getString("profile_pic_url")));

		int refreshed = 0;
		int failed = 0;
		int deferred = 0;
		for (AuthorCandidate c : candidates) {
			if (!isExpired(c.profilePicUrl(), nowEpoch)) {
				continue;   // 아직 살아있는 URL — 이 백필의 대상이 아니다(정상 아카이브 스윕이 처리).
			}
			if (callsUsed[0] >= limit) {
				deferred++;   // 예산 소진 — 다음 트리거로 이월.
				continue;
			}
			callsUsed[0]++;
			try {
				AuthorInfo info = hiker.fetchAuthorProfile(c.igUserId());
				authorProfileRepo.upsert(info);   // profile_pic_url·fetched_at 갱신 → 다음 아카이브 스윕이 수거
				refreshed++;
			} catch (RuntimeException e) {
				// 건 단위 격리 — 계정 삭제·응답 셰이프 이상 등은 이 건만 skip하고 나머지는 계속 처리.
				log.warn("게시자 프로필 CDN 재조회 실패 — igUserId={}", c.igUserId(), e);
				failed++;
			}
		}
		return new PhaseAResult(refreshed, failed, deferred);
	}

	private record HashtagAuthorCandidate(String authorUsername, String profilePicUrl) {}

	private PhaseBResult runHashtagAuthorPhase(int limit, int[] callsUsed, long nowEpoch) {
		// HashtagPostAuthorImageArchiveJob과 동일한 DISTINCT ON 후보 산정 — RELEVANT·미아카이브만.
		List<HashtagAuthorCandidate> candidates = db.query("""
				SELECT DISTINCT ON (author_username)
				       author_username, author_profile_pic_url
				FROM brand_hashtag_post
				WHERE verdict = 'RELEVANT' AND author_image_object_path IS NULL
				  AND author_profile_pic_url LIKE 'http%'
				ORDER BY author_username, first_seen_at DESC
				""", (rs, i) -> new HashtagAuthorCandidate(rs.getString("author_username"),
				rs.getString("author_profile_pic_url")));

		int refreshed = 0;
		int reused = 0;
		int failed = 0;
		int deferred = 0;
		for (HashtagAuthorCandidate c : candidates) {
			if (!isExpired(c.profilePicUrl(), nowEpoch)) {
				continue;   // 아직 살아있는 URL — 정상 아카이브 스윕이 처리.
			}

			String freshUrl = freshAuthorProfileUrl(c.authorUsername(), nowEpoch);
			if (freshUrl != null) {
				// author_profile이 이미 살아있는 URL을 들고 있다(Phase A가 방금 갱신한 것 포함) — Hiker 호출 없이 재사용.
				updateHashtagAuthorUrl(c.authorUsername(), freshUrl);
				reused++;
				continue;
			}

			if (callsUsed[0] >= limit) {
				deferred++;   // 예산 소진 — 다음 트리거로 이월.
				continue;
			}
			callsUsed[0]++;
			try {
				ProfileInfo info = hiker.fetchProfile(c.authorUsername());
				if (info.profilePicUrl() != null) {
					updateHashtagAuthorUrl(c.authorUsername(), info.profilePicUrl());
				}
				refreshed++;
			} catch (RuntimeException e) {
				// 비공개 계정·수집 실패 모두 여기로 — 깨진 이미지보다 없는 편이 낫다(프론트 이니셜 placeholder).
				log.warn("해시태그 작성자 CDN 재조회 실패 — authorUsername={}", c.authorUsername(), e);
				failed++;
			}
		}
		return new PhaseBResult(refreshed, reused, failed, deferred);
	}

	/** author_profile에서 그 username이 든, 확정 만료는 아닌(미상 포함) profile_pic_url 하나. 없으면 null. */
	private String freshAuthorProfileUrl(String username, long nowEpoch) {
		List<String> urls = db.query("""
				SELECT profile_pic_url FROM author_profile
				WHERE username = ? AND profile_pic_url LIKE 'http%'
				""", (rs, i) -> rs.getString(1), username);
		for (String url : urls) {
			if (!isExpired(url, nowEpoch)) {
				return url;
			}
		}
		return null;
	}

	/** 그 작성자의 미아카이브(author_image_object_path IS NULL) 행 전부에 신선 URL을 채운다. */
	private void updateHashtagAuthorUrl(String authorUsername, String freshUrl) {
		db.update("""
				UPDATE brand_hashtag_post
				   SET author_profile_pic_url = ?
				 WHERE author_username = ? AND author_image_object_path IS NULL
				""", freshUrl, authorUsername);
	}

	/** CdnExpiry의 비대칭 규칙(만료 미상은 만료로 취급하지 않음)을 그대로 따른다. */
	private static boolean isExpired(String url, long nowEpoch) {
		Long oe = CdnExpiry.expiryEpoch(url);
		return oe != null && oe <= nowEpoch;
	}
}
