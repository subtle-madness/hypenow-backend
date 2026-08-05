package com.celfit.monitoring.hiker;

/**
 * 게시물 스냅샷 원재료 — 6지표(좋아요·댓글·조회·저장·공유·리포스트).
 * 취득 불가 지표는 null이다: 조회·저장·공유는 릴스 전용이고, 피드·캐러셀 응답에는 키 자체가 없다(findings §2).
 * 릴스의 저장·리포스트는 세션 복권(콜 단위 전부/전무, 존재율 ~30-45% — findings §2 결론 5)이라
 * null이 "취득 불가"가 아니라 "이 콜이 꽝"일 수 있다 — 스윕이 clips 재시도로 보강한다(08-04).
 * takenAt은 taken_at(epoch seconds) — 핀 고정 게시물 때문에 배열 순서를 믿을 수 없어 재정렬 기준으로 쓴다.
 * rawJson은 이 게시물만이 아니라 **응답 body 전체**다(열거면 그 페이지의 12건 전부).
 * 그래서 감사용 원형 적재는 여기서 하지 않는다 — 전송 계층(RecordingHikerHttp)이 콜 단위로 남긴다.
 *
 * <p>viewsTrusted는 "views가 null인 게 진짜 부재인가"를 하류에 알린다: 열거 경로의 조회수는
 * /v2/user/clips 보강으로만 채워지는데 그 보강은 실패해도 스윕을 계속한다(조용히 null).
 * 이 플래그가 없으면 보강 실패가 "조회수 비공개 전환"으로 오탐돼 알람이 나간다(스펙 §3-2).
 *
 * <p>thumbnailUrl은 post_meta 표시 메타용(계약 §3 post_meta) — image_versions2.candidates[0].url.
 * 인스타 CDN 서명 만료가 있어 스윕마다 갱신 대상이다(썸네일 자체는 스냅샷 지표가 아니다).
 *
 * <p>ownerFullName·ownerProfilePicUrl은 **단건 응답(/v2/media/by/code)에만** 실값이 실려 온다
 * (user.full_name·user.profile_pic_url). 계정 열거 경로(/v2/user/medias)에서도 같은 노드라 파싱은
 * 되지만 소비하지 않는다 — 응답 셰이프에 따라 둘 다 null일 수 있다(트랙 II).
 *
 * <p>ownerUserId는 소유 계정의 IG pk(user.pk, 제로 콜)다 — POST 등록만 있는 계정은 스윕이 열거를
 * 안 돌아 clips 재시도(저장·리포스트 세션 복권 보강)에 쓸 user_id가 없는데, 단건 응답의 이 값이
 * 유일한 공급원이다. 셰이프에 따라 null일 수 있다(그러면 재시도를 건너뛴다).
 *
 * <p>views는 **IG 몫**(ig_play_count)이다 — Hiker가 콜마다 다른 세션을 태워 합산 play_count는
 * 콜 간 역행한다(findings §2 결론 4). fbPlays는 FB 교차게시 몫으로, **null=이 응답에 fb 키 부재
 * (FB를 못 보는 세션), 0=관측된 0** — 이 구분이 저장 시 캐리포워드·최초 1회 재시도 판정 기준이라
 * 뭉개면 안 된다. 화면 합산값(views + fb)은 저장 계층(SnapshotRepository)이 조립한다.
 *
 * <p>likesHidden은 게시자의 좋아요 수 숨김(like_and_view_counts_disabled) 관측 여부다.
 * 숨김이면 likes는 null인데, "숨김"과 "그날 수집 실패(행 부재)"를 FE가 구분해 표시해야 해서
 * null로 뭉개지 않고 플래그를 스냅샷까지 관통시킨다(운영 실측 08-03).
 */
public record PostInfo(String shortCode, String username, String ownerFullName, String ownerProfilePicUrl,
		String ownerUserId, String contentType, String caption, String thumbnailUrl,
		Long takenAt, Long likes, Long comments, Long views, Long fbPlays, Long saves,
		Long shares, Long reposts, String rawJson, boolean viewsTrusted, boolean likesHidden) {

	/** 재시도 콜에서 얻은 FB 몫만 갈아끼운 사본 — 나머지 지표는 원 콜 값을 유지한다. */
	public PostInfo withFbPlays(Long newFbPlays) {
		return new PostInfo(shortCode, username, ownerFullName, ownerProfilePicUrl, ownerUserId, contentType,
				caption, thumbnailUrl, takenAt, likes, comments, views, newFbPlays, saves, shares, reposts,
				rawJson, viewsTrusted, likesHidden);
	}

	/**
	 * clips 재시도 관측에서 저장·공유·리포스트만 채운 사본 — 이미 있는 값은 유지한다(non-null 우선).
	 * 재생수는 건드리지 않는다: views·fbPlays는 캐리포워드·역전파 체계(08-03)가 따로 관리하는 지표라
	 * 재시도 관측으로 덮으면 그 체계와 두 군데서 경합한다.
	 */
	public PostInfo mergedMetrics(Long newSaves, Long newShares, Long newReposts) {
		return new PostInfo(shortCode, username, ownerFullName, ownerProfilePicUrl, ownerUserId, contentType,
				caption, thumbnailUrl, takenAt, likes, comments, views, fbPlays,
				coalesce(saves, newSaves), coalesce(shares, newShares), coalesce(reposts, newReposts),
				rawJson, viewsTrusted, likesHidden);
	}

	/**
	 * 이 응답(단건, 정본)에 빠진 값을 같은 스윕의 다른 관측(열거)으로 채운 사본 — 지표별 non-null 우선.
	 * 세션이 저장·공유·리포스트 키를 실었다 뺐다 하는데(운영 채움율 11~58% 요동, 08-04 실측) 같은
	 * 시각에도 응답 경로별로 다르게 걸리므로, 정본이 폴백을 null로 덮으면 방금 관측한 값을 유실한다.
	 *
	 * <p>likes만 예외로 정본의 숨김 판정을 따른다 — 숨김 게시물의 likes는 마스킹값이라 null로 비웠는데
	 * (레코드 주석 참조), 폴백의 값으로 coalesce하면 비워둔 마스킹값이 되살아난다.
	 * views는 값과 viewsTrusted 플래그가 한 몸이라 같은 쪽에서 함께 가져온다.
	 */
	public PostInfo mergedWith(PostInfo fallback) {
		boolean viewsFromFallback = views == null && fallback.views != null;
		return new PostInfo(shortCode, username,
				coalesce(ownerFullName, fallback.ownerFullName),
				coalesce(ownerProfilePicUrl, fallback.ownerProfilePicUrl),
				coalesce(ownerUserId, fallback.ownerUserId),
				contentType,
				coalesce(caption, fallback.caption),
				coalesce(thumbnailUrl, fallback.thumbnailUrl),
				coalesce(takenAt, fallback.takenAt),
				likesHidden ? null : coalesce(likes, fallback.likes),
				coalesce(comments, fallback.comments),
				viewsFromFallback ? fallback.views : views,
				coalesce(fbPlays, fallback.fbPlays),
				coalesce(saves, fallback.saves),
				coalesce(shares, fallback.shares),
				coalesce(reposts, fallback.reposts),
				rawJson,
				viewsFromFallback ? fallback.viewsTrusted : viewsTrusted,
				likesHidden);
	}

	private static <T> T coalesce(T primary, T secondary) {
		return primary != null ? primary : secondary;
	}
}
