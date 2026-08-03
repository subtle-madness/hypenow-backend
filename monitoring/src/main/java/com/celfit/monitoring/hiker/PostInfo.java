package com.celfit.monitoring.hiker;

/**
 * 게시물 스냅샷 원재료 — 6지표(좋아요·댓글·조회·저장·공유·리포스트).
 * 취득 불가 지표는 null이다: 조회·저장·공유는 릴스 전용이고, 피드·캐러셀 응답에는 키 자체가 없다(findings §2).
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
 * <p>views는 **IG 몫**(ig_play_count)이다 — Hiker가 콜마다 다른 세션을 태워 합산 play_count는
 * 콜 간 역행한다(findings §2 결론 4). fbPlays는 FB 교차게시 몫으로, **null=이 응답에 fb 키 부재
 * (FB를 못 보는 세션), 0=관측된 0** — 이 구분이 저장 시 캐리포워드·최초 1회 재시도 판정 기준이라
 * 뭉개면 안 된다. 화면 합산값(views + fb)은 저장 계층(SnapshotRepository)이 조립한다.
 */
public record PostInfo(String shortCode, String username, String ownerFullName, String ownerProfilePicUrl,
		String contentType, String caption, String thumbnailUrl,
		Long takenAt, Long likes, Long comments, Long views, Long fbPlays, Long saves,
		Long shares, Long reposts, String rawJson, boolean viewsTrusted) {

	/** 재시도 콜에서 얻은 FB 몫만 갈아끼운 사본 — 나머지 지표는 원 콜 값을 유지한다. */
	public PostInfo withFbPlays(Long newFbPlays) {
		return new PostInfo(shortCode, username, ownerFullName, ownerProfilePicUrl, contentType, caption,
				thumbnailUrl, takenAt, likes, comments, views, newFbPlays, saves, shares, reposts,
				rawJson, viewsTrusted);
	}
}
