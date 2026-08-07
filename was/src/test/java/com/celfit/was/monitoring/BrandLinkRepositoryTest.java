package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 브랜드 연결 저장 계층(2026-08-07 스펙 §3-1) — app.brand_monitorings·app.brand_direct_posts와
 * users.instgram_account_name 접점을 실 컨테이너 왕복으로 검증한다. 유니크 제약(활성 연결 1개)은
 * 서비스 계층 방어의 최후 보루라 DB에서 실제로 터지는지까지 확인한다.
 */
class BrandLinkRepositoryTest extends IntegrationTest {

	@Autowired
	BrandLinkRepository repository;
	@Autowired
	BrandDirectPostRepository directPostRepository;
	@Autowired
	MonitoringItemRepository itemRepository;
	@Autowired
	JdbcClient jdbcClient;

	long userId;
	// brand_id는 테스트마다 고유해야 한다 — 통합 테스트가 컨테이너를 공유하고 롤백이 없어서
	// 고정 상수(100L 등)를 쓰면 countActiveByBrand가 다른 테스트가 남긴 활성 연결까지 센다.
	long brandA;
	long brandB;

	@BeforeEach
	void 유저_시드() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "brand-link-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		brandA = userId * 10;
		brandB = userId * 10 + 1;
	}

	@Test
	void 활성_연결은_사용자당_하나다() {
		repository.saveInstagramAccountName(userId, "lizda_official");
		repository.insertLink(userId, brandA, "lizda_official");
		assertThat(repository.findActiveByUser(userId)).isPresent();

		// 활성 중복은 유니크 위반
		assertThatThrownBy(() -> repository.insertLink(userId, brandB, "other"))
				.isInstanceOf(DuplicateKeyException.class);

		// soft-delete 후 재삽입 가능(재등록 경로)
		assertThat(repository.softDeleteActiveLink(userId)).isTrue();
		repository.insertLink(userId, brandA, "lizda_official");
		assertThat(repository.countActiveByBrand(brandA)).isEqualTo(1);
	}

	@Test
	void findActiveByUser는_soft_delete된_연결을_보지_않는다() {
		long id = repository.insertLink(userId, brandA, "lizda_official");

		BrandLinkRow row = repository.findActiveByUser(userId).orElseThrow();
		assertThat(row.id()).isEqualTo(id);
		assertThat(row.userId()).isEqualTo(userId);
		assertThat(row.brandId()).isEqualTo(brandA);
		assertThat(row.username()).isEqualTo("lizda_official");
		assertThat(row.createdAt()).isNotNull();
		assertThat(row.deletedAt()).isNull();

		assertThat(repository.softDeleteActiveLink(userId)).isTrue();

		assertThat(repository.findActiveByUser(userId)).isEmpty();
		// 이미 해제된 뒤의 중복 호출은 갱신 행이 없어 false — 멱등 판정 지점.
		assertThat(repository.softDeleteActiveLink(userId)).isFalse();
	}

	@Test
	void findActiveByUserAndBrand는_소유하지_않은_브랜드에_비어있다() {
		repository.insertLink(userId, brandA, "lizda_official");

		assertThat(repository.findActiveByUserAndBrand(userId, brandA)).isPresent();
		assertThat(repository.findActiveByUserAndBrand(userId, brandB)).isEmpty();

		repository.softDeleteActiveLink(userId);
		assertThat(repository.findActiveByUserAndBrand(userId, brandA)).isEmpty();
	}

	@Test
	void countActiveByBrand는_활성_연결만_센다() {
		long other = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "brand-link-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		repository.insertLink(userId, brandA, "lizda_official");
		repository.insertLink(other, brandA, "lizda_official");
		assertThat(repository.countActiveByBrand(brandA)).isEqualTo(2);

		repository.softDeleteActiveLink(other);

		assertThat(repository.countActiveByBrand(brandA)).isEqualTo(1);
		assertThat(repository.countActiveByBrand(brandB)).isZero();
	}

	@Test
	void instagramAccountNameForUpdate는_미저장이면_null_저장했으면_그_값이다() {
		assertThat(repository.instagramAccountNameForUpdate(userId)).isNull();

		repository.saveInstagramAccountName(userId, "lizda_official");

		assertThat(repository.instagramAccountNameForUpdate(userId)).isEqualTo("lizda_official");
	}

	@Test
	void instagramAccountNameForUpdate는_존재하지_않는_유저면_예외() {
		// 인증 전제상 도달 불가 — 도달하면 전제가 깨진 것이라 조용한 null 대신 예외로 드러낸다.
		assertThatThrownBy(() -> repository.instagramAccountNameForUpdate(999_999L))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void direct_매핑은_유저_shortcode당_하나이고_재삽입은_무해하다() {
		long itemId = 아이템_시드("abc123");
		long otherItemId = 아이템_시드("zzz999");

		directPostRepository.upsert(userId, brandA, "abc123", itemId);
		// 같은 (user, shortCode) 재삽입은 ON CONFLICT DO NOTHING — 예외 없이 기존 행 유지.
		directPostRepository.upsert(userId, brandA, "abc123", otherItemId);

		assertThat(directPostRepository.findByUser(userId))
				.containsExactly(new BrandDirectPostRepository.Row(userId, brandA, "abc123", itemId));
		assertThat(directPostRepository.shortCodesByUser(userId)).containsExactly("abc123");
	}

	@Test
	void direct_매핑_조회는_유저_스코프다() {
		long other = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "brand-link-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		directPostRepository.upsert(userId, brandA, "abc123", 아이템_시드("abc123"));
		directPostRepository.upsert(userId, brandB, "def456", 아이템_시드("def456"));

		assertThat(directPostRepository.shortCodesByUser(userId)).containsExactlyInAnyOrder("abc123", "def456");
		assertThat(directPostRepository.shortCodesByUser(other)).isEmpty();
		assertThat(directPostRepository.findByUser(other)).isEmpty();
	}

	private long 아이템_시드(String shortCode) {
		return itemRepository.insertPending(userId, "url", UUID.randomUUID(), null, shortCode,
				"https://instagram.com/p/" + shortCode, null, 30, LocalDate.of(2026, 8, 7));
	}
}
