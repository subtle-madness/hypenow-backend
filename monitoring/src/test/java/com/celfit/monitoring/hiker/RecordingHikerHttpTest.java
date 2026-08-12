package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.store.RawPayloadRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordingHikerHttpTest {

	/** save 인자만 붙잡는 페이크 — DB 없이 kind·subject 판정을 검증한다. */
	private static final class CapturingRepo extends RawPayloadRepository {
		record Saved(String kind, String subject) {}
		final List<Saved> saved = new ArrayList<>();

		CapturingRepo() {
			super(null);
		}

		@Override
		public void save(String kind, String subject, int httpStatus, String payloadJson) {
			saved.add(new Saved(kind, subject));
		}
	}

	/**
	 * 단건 경로가 /v2/media/info/by/code로 이전(08-04)돼도 감사 적재는 kind=POST·subject=code를
	 * 유지해야 한다 — kind='POST' 필터 기반 백필·포렌식(V20260803064353 등)이 새 행을 계속 보도록.
	 * share 해소(/v2/media/info/by/url, kind=MEDIA_INFO)와 프리픽스가 겹치므로 판정 분리도 함께 본다.
	 */
	@Test
	void 단건_신_경로도_kind_POST_subject_code로_적재된다() {
		CapturingRepo repo = new CapturingRepo();
		RecordingHikerHttp http = new RecordingHikerHttp(path -> "{\"status\":\"ok\"}", repo);

		http.get("/v2/media/info/by/code?code=Xx1");
		http.get("/v2/media/info/by/url?url=https%3A%2F%2Fexample");

		assertThat(repo.saved).containsExactly(
				new CapturingRepo.Saved("POST", "Xx1"),
				new CapturingRepo.Saved("MEDIA_INFO", "https://example"));
	}

	/**
	 * 브랜드 태그 모니터링 신규 경로 2종 — 태그 열거(/v2/user/tag/medias)와 게시자 프로필
	 * (/v2/user/by/id)의 kind·subject 판정. 태그 열거가 기존 열거(/v2/user/medias)의 POSTS로
	 * 오분류되지 않는지도 함께 본다(프리픽스가 다른 경로라 startsWith 충돌은 없지만 계약으로 고정).
	 */
	@Test
	void 태그_열거와_게시자_프로필은_전용_kind로_적재된다() {
		CapturingRepo repo = new CapturingRepo();
		RecordingHikerHttp http = new RecordingHikerHttp(path -> "{\"status\":\"ok\"}", repo);

		http.get("/v2/user/tag/medias?user_id=123");
		http.get("/v2/user/by/id?id=456");
		http.get("/v2/user/medias?user_id=789");

		assertThat(repo.saved).containsExactly(
				new CapturingRepo.Saved("TAGGED", "123"),
				new CapturingRepo.Saved("PROFILE_BY_ID", "456"),
				new CapturingRepo.Saved("POSTS", "789"));
	}
}
