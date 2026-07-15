package com.celfit.was.saved;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.UserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Flyway(AppFlywayConfig)가 app 스키마를 실제로 생성한 위에서 검증 — DDL 하드코딩 없음. */
class SavedRepositoryTest extends IntegrationTest {

	@Autowired
	SavedRepository repository;

	@Autowired
	UserRepository userRepository;

	long userId;

	@BeforeEach
	void setUpUser() {
		// saved_influencers·saved_contents는 user_id FK가 있어 매 테스트 새 사용자를 만든다(이메일 UNIQUE라 겹치지 않게).
		AppUser user = userRepository.insert("saved-" + System.nanoTime() + "@example.com", "hashed");
		userId = user.id();
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
	void memo만_지정하면_status는_유지되고_updated_at은_갱신된다() throws InterruptedException {
		SavedInfluencer first = repository.upsertInfluencer(userId, "alpha", "collaborating", false, null);
		Thread.sleep(5);

		SavedInfluencer updated = repository.upsertInfluencer(userId, "alpha", null, true, "메모 변경");

		assertThat(updated.status()).isEqualTo("collaborating");
		assertThat(updated.memo()).isEqualTo("메모 변경");
		assertThat(updated.updatedAt()).isAfter(first.updatedAt());
	}

	@Test
	void 목록은_updated_at_내림차순이다() throws InterruptedException {
		repository.upsertInfluencer(userId, "alpha", null, false, null);
		Thread.sleep(5);
		repository.upsertInfluencer(userId, "beta", null, false, null);

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
	void 콘텐츠_upsert는_멱등이며_created_at을_유지한다() {
		SavedContent first = repository.upsertContent(userId, "h1");

		SavedContent again = repository.upsertContent(userId, "h1");

		assertThat(again.shortCode()).isEqualTo("h1");
		assertThat(again.createdAt()).isEqualTo(first.createdAt());
	}

	@Test
	void 콘텐츠_목록은_created_at_내림차순이고_delete는_멱등이다() throws InterruptedException {
		repository.upsertContent(userId, "h1");
		Thread.sleep(5);
		repository.upsertContent(userId, "h2");

		List<SavedContent> items = repository.findContents(userId);
		assertThat(items).extracting(SavedContent::shortCode).containsExactly("h2", "h1");

		repository.deleteContent(userId, "h1");
		repository.deleteContent(userId, "h1"); // 두 번째 삭제도 예외 없이 끝난다

		assertThat(repository.findContents(userId)).extracting(SavedContent::shortCode).containsExactly("h2");
	}
}
