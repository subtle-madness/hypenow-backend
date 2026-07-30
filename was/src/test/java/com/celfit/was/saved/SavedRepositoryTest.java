package com.celfit.was.saved;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Flyway(AppFlywayConfig)가 app 스키마를 실제로 생성한 위에서 검증 — DDL 하드코딩 없음. */
class SavedRepositoryTest extends IntegrationTest {

	@Autowired
	SavedRepository repository;

	@Autowired
	UserRepository userRepository;

	@Autowired
	JdbcClient jdbcClient;

	long userId;

	@BeforeEach
	void setUpUser() {
		// saved_influencers·saved_contents는 user_id FK가 있어 매 테스트 새 사용자를 만든다(이메일 UNIQUE라 겹치지 않게).
		AppUser user = userRepository.insert("saved-" + System.nanoTime() + "@example.com", "hashed");
		userId = user.id();
	}

	/**
	 * 시각 의존 단언을 위해 저장 시각을 과거로 명시 고정한다. Thread.sleep으로 몇 ms 벌리는 방식은
	 * 쓰지 않는다 — sleep은 단조 시계를 쓰지만 now()는 벽시계라, 컨테이너 벽시계가 뒤로 튀면
	 * (이 머신 실측: 약 118ms 역행이 주기적으로 발생) 나중 저장이 먼저 저장보다 이른 시각으로 박혀
	 * 정렬이 뒤집힌다. 1시간 마진이면 그 요동에 안 흔들린다.
	 */
	private OffsetDateTime 인플루언서_갱신시각_과거로(String handle) {
		return jdbcClient.sql("""
				UPDATE app.saved_influencers SET updated_at = now() - interval '1 hour'
				WHERE user_id = :userId AND handle = :handle
				RETURNING updated_at
				""")
				.param("userId", userId)
				.param("handle", handle)
				.query(OffsetDateTime.class)
				.single();
	}

	/** 위와 같은 이유로 콘텐츠 저장 시각을 과거로 고정한다. */
	private void 콘텐츠_저장시각_과거로(String shortCode) {
		jdbcClient.sql("""
				UPDATE app.saved_contents SET created_at = now() - interval '1 hour'
				WHERE user_id = :userId AND short_code = :shortCode
				""")
				.param("userId", userId)
				.param("shortCode", shortCode)
				.update();
	}

	@Test
	void 신규_저장은_기본값_reviewing_memo_null이다() {
		SavedInfluencer saved = repository.upsertInfluencer(userId, "alpha", null, false, null);

		assertThat(saved.handle()).isEqualTo("alpha");
		assertThat(saved.status()).isEqualTo("reviewing");
		assertThat(saved.memo()).isNull();
	}

	@Test
	void status만_지정하면_memo는_유지된다() {
		repository.upsertInfluencer(userId, "alpha", null, true, "첫 메모");

		SavedInfluencer updated = repository.upsertInfluencer(userId, "alpha", "contact_planned", false, null);

		assertThat(updated.status()).isEqualTo("contact_planned");
		assertThat(updated.memo()).isEqualTo("첫 메모");
	}

	@Test
	void memo만_지정하면_status는_유지되고_updated_at은_갱신된다() {
		repository.upsertInfluencer(userId, "alpha", "collaborating", false, null);
		OffsetDateTime 갱신_전 = 인플루언서_갱신시각_과거로("alpha");

		SavedInfluencer updated = repository.upsertInfluencer(userId, "alpha", null, true, "메모 변경");

		assertThat(updated.status()).isEqualTo("collaborating");
		assertThat(updated.memo()).isEqualTo("메모 변경");
		assertThat(updated.updatedAt()).isAfter(갱신_전);
	}

	@Test
	void 목록은_updated_at_내림차순이다() {
		repository.upsertInfluencer(userId, "alpha", null, false, null);
		repository.upsertInfluencer(userId, "beta", null, false, null);
		인플루언서_갱신시각_과거로("alpha");

		List<SavedInfluencer> items = repository.findInfluencers(userId);

		assertThat(items).extracting(SavedInfluencer::handle).containsExactly("beta", "alpha");
	}

	@Test
	void delete_인플루언서는_멱등이다() {
		repository.upsertInfluencer(userId, "alpha", null, false, null);

		repository.deleteInfluencer(userId, "alpha");
		repository.deleteInfluencer(userId, "alpha"); // 두 번째 삭제도 예외 없이 끝난다

		assertThat(repository.findInfluencers(userId)).isEmpty();
	}

	@Test
	void 콘텐츠_저장_시각이_같으면_short_code_오름차순으로_결정적이다() {
		// 한 문장 = 한 트랜잭션이라 now()가 두 행에 똑같이 박힌다(완전 동률 강제).
		// 물리 저장 순서(h2 먼저)를 기대 순서(h1 먼저)와 어긋나게 넣어야 타이브레이크가 실제로 도는지 보인다.
		jdbcClient.sql("""
				INSERT INTO app.saved_contents (user_id, short_code)
				VALUES (:userId, 'h2'), (:userId, 'h1')
				""")
				.param("userId", userId)
				.update();

		assertThat(repository.findContents(userId)).extracting(SavedContent::shortCode)
				.containsExactly("h1", "h2");
	}

	@Test
	void 인플루언서_갱신_시각이_같으면_handle_오름차순으로_결정적이다() {
		jdbcClient.sql("""
				INSERT INTO app.saved_influencers (user_id, handle)
				VALUES (:userId, 'beta'), (:userId, 'alpha')
				""")
				.param("userId", userId)
				.update();

		assertThat(repository.findInfluencers(userId)).extracting(SavedInfluencer::handle)
				.containsExactly("alpha", "beta");
	}

	@Test
	void 콘텐츠_upsert는_멱등이며_created_at을_유지한다() {
		SavedContent first = repository.upsertContent(userId, "h1");

		SavedContent again = repository.upsertContent(userId, "h1");

		assertThat(again.shortCode()).isEqualTo("h1");
		assertThat(again.createdAt()).isEqualTo(first.createdAt());
	}

	@Test
	void 콘텐츠_목록은_created_at_내림차순이고_delete는_멱등이다() {
		repository.upsertContent(userId, "h1");
		repository.upsertContent(userId, "h2");
		콘텐츠_저장시각_과거로("h1");

		List<SavedContent> items = repository.findContents(userId);
		assertThat(items).extracting(SavedContent::shortCode).containsExactly("h2", "h1");

		repository.deleteContent(userId, "h1");
		repository.deleteContent(userId, "h1"); // 두 번째 삭제도 예외 없이 끝난다

		assertThat(repository.findContents(userId)).extracting(SavedContent::shortCode).containsExactly("h2");
	}
}
