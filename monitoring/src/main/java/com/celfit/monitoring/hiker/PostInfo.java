package com.celfit.monitoring.hiker;

/**
 * 게시물 스냅샷 원재료 — 6지표(좋아요·댓글·조회·저장·공유·리포스트).
 * 취득 불가 지표는 null이다: 조회·저장·공유는 릴스 전용이고, 피드·캐러셀 응답에는 키 자체가 없다(findings §2).
 * 릴스의 저장·리포스트는 세션 복권(콜 단위 전부/전무, 존재율 ~30-45% — findings §2 결론 5)이라
 * null이 "취득 불가"가 아니라 "이 콜이 꽝"일 수 있다 — 스윕이 clips 재시도로 보강한다(08-04).
 * takenAt은 taken_at(epoch seconds) — 핀 고정 게시물 때문에 배열 순서를 믿을 수 없어 재정렬 기준으로 쓴다.
 * 응답 원문은 나르지 않는다 — 감사용 원형 적재는 전송 계층(RecordingHikerHttp)이 콜 단위로 남긴다.
 * 과거에 원문(페이지 body 전체, TAGGED 평균 859KB)을 rawJson 필드로 실었다가 소비처 없이 힙만
 * 잠식해 OOM을 냈다(08-12 운영 — 365일 백필 × 무제한 enrich 큐가 브랜드당 ~150MB를 상주시킴).
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
 *
 * <p>sharesHidden은 게시자의 공유 횟수 숨김 관측 여부다 — share_count_disabled 토글이거나,
 * 좋아요 숨김(IG 앱 문구 "좋아요 수 및 공유 횟수는 회원님만" — 좋아요 숨김이 공유 노출도 함께
 * 끈다, 08-05 실측: lvcd=true 10게시물 전원 reshare_count 영구 부재 vs 제공 31게시물 전원 false).
 * 숨김 게시물은 저장·리포스트 재시도 판정에서 공유 항을 빼고(헛 재시도 방지), 소진 시
 * 공유 0 간주 대상에서도 제외한다(숨김은 0이 아니라 비공개).
 *
 * <p>videoUrl·videoDuration·isPaidPartnership은 브랜드 was 계약 필드(2026-08-07 스펙 §3-2 —
 * brand_post_meta 표시 메타)다. 전부 같은 media 노드에서 추가 콜 0으로 뽑는다:
 * videoUrl은 video_versions[0].url(썸네일과 마찬가지로 CDN 서명 만료가 있어 스윕마다 갱신 대상),
 * videoDuration은 초 단위 실수 — 둘 다 릴스·비디오에만 실린다(피드·캐러셀은 키 부재 → null).
 * isPaidPartnership이 Boolean인 건 <b>키 부재(null = 판정 unknown)와 관측된 false(비협찬)</b>를
 * 구분하기 위해서다 — 응답 셰이프에 따라 키가 통째로 없는 경로가 있다(태그 열거 합성 픽스처 기준).
 */
public record PostInfo(String shortCode, String username, String ownerFullName, String ownerProfilePicUrl,
		String ownerUserId, String contentType, String caption, String thumbnailUrl,
		Long takenAt, Long likes, Long comments, Long views, Long fbPlays, Long saves,
		Long shares, Long reposts, String videoUrl, Double videoDuration, Boolean isPaidPartnership,
		boolean viewsTrusted, boolean likesHidden, boolean sharesHidden) {

	/** 재시도 콜에서 얻은 FB 몫만 갈아끼운 사본 — 나머지 지표는 원 콜 값을 유지한다. */
	public PostInfo withFbPlays(Long newFbPlays) {
		return new PostInfo(shortCode, username, ownerFullName, ownerProfilePicUrl, ownerUserId, contentType,
				caption, thumbnailUrl, takenAt, likes, comments, views, newFbPlays, saves, shares, reposts,
				videoUrl, videoDuration, isPaidPartnership,
				viewsTrusted, likesHidden, sharesHidden);
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
				videoUrl, videoDuration, isPaidPartnership,
				viewsTrusted, likesHidden, sharesHidden);
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
				// 표시 메타도 지표와 같은 non-null 우선 — 캡션·썸네일과 동일 취급(응답 셰이프에 따라
				// 한쪽에만 실리는 필드라 정본이 폴백을 null로 덮으면 방금 관측한 값을 유실한다).
				coalesce(videoUrl, fallback.videoUrl),
				coalesce(videoDuration, fallback.videoDuration),
				coalesce(isPaidPartnership, fallback.isPaidPartnership),
				viewsFromFallback ? fallback.viewsTrusted : viewsTrusted,
				likesHidden,
				// 숨김 플래그는 어느 쪽 응답에서든 관측되면 참 — 값과 달리 켜짐이 정보다.
				sharesHidden || fallback.sharesHidden);
	}

	private static <T> T coalesce(T primary, T secondary) {
		return primary != null ? primary : secondary;
	}
}
