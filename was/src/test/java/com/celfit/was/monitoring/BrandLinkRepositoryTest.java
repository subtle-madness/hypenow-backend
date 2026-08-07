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
 * 브랜드 연결 저장 계층(2026-08-07 스펙 §3-1, 08-07 다계정 개정) — app.brand_monitorings·
 * app.brand_direct_posts 접점을 실 컨테이너 왕복으로 검증한다. 유니크 제약(유저·브랜드당 활성 1개)은
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
	void 활성_연결은_유저_브랜드당_하나고_다른_브랜드는_추가_연결된다() {
		repository.insertLink(userId, brandA, "lizda_official");
		// 다계정(08-07 개정) — 다른 브랜드 연결은 정상 경로다.
		repository.insertLink(userId, brandB, "other_brand");
		assertThat(repository.findAllActiveByUser(userId)).hasSize(2);

		// 같은 (유저, 브랜드) 활성 중복만 유니크 위반
		assertThatThrownBy(() -> repository.insertLink(userId, brandA, "lizda_official"))
				.isInstanceOf(DuplicateKeyException.class);

		// soft-delete 후 재삽입 가능(재연결 경로)
		assertThat(repository.softDeleteLink(userId, brandA)).isTrue();
		repository.insertLink(userId, brandA, "lizda_official");
		assertThat(repository.countActiveByBrand(brandA)).isEqualTo(1);
	}

	@Test
	void findAllActiveByUser는_soft_delete된_연결을_보지_않고_생성_순으로_돌려준다() {
		long first = repository.insertLink(userId, brandA, "lizda_official");
		long second = repository.insertLink(userId, brandB, "other_brand");

		assertThat(repository.findAllActiveByUser(userId)).extracting(BrandLinkRow::id)
				.containsExactly(first, second);
		BrandLinkRow row = repository.findAllActiveByUser(userId).get(0);
		assertThat(row.userId()).isEqualTo(userId);
		assertThat(row.brandId()).isEqualTo(brandA);
		assertThat(row.username()).isEqualTo("lizda_official");
		assertThat(row.createdAt()).isNotNull();
		assertThat(row.deletedAt()).isNull();

		assertThat(repository.softDeleteLink(userId, brandA)).isTrue();

		assertThat(repository.findAllActiveByUser(userId)).extracting(BrandLinkRow::id)
				.containsExactly(second);
		// 이미 해제된 뒤의 중복 호출은 갱신 행이 없어 false — 멱등 판정 지점.
		assertThat(repository.softDeleteLink(userId, brandA)).isFalse();
	}

	@Test
	void findActiveByUserAndBrand는_소유하지_않은_브랜드에_비어있다() {
		repository.insertLink(userId, brandA, "lizda_official");

		assertThat(repository.findActiveByUserAndBrand(userId, brandA)).isPresent();
		assertThat(repository.findActiveByUserAndBrand(userId, brandB)).isEmpty();

		repository.softDeleteLink(userId, brandA);
		assertThat(repository.findActiveByUserAndBrand(userId, brandA)).isEmpty();
	}

	@Test
	void softDeleteAllActiveByUser는_유저의_활성_연결_전부를_해제한다() {
		repository.insertLink(userId, brandA, "lizda_official");
		repository.insertLink(userId, brandB, "other_brand");

		assertThat(repository.softDeleteAllActiveByUser(userId)).isEqualTo(2);

		assertThat(repository.findAllActiveByUser(userId)).isEmpty();
		assertThat(repository.softDeleteAllActiveByUser(userId)).isZero();
	}

	@Test
	void countActiveByBrand는_활성_연결만_센다() {
		long other = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "brand-link-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		repository.insertLink(userId, brandA, "lizda_official");
		repository.insertLink(other, brandA, "lizda_official");
		assertThat(repository.countActiveByBrand(brandA)).isEqualTo(2);

		repository.softDeleteLink(other, brandA);

		assertThat(repository.countActiveByBrand(brandA)).isEqualTo(1);
		assertThat(repository.countActiveByBrand(brandB)).isZero();
	}

	@Test
	void lockUser는_존재하지_않는_유저면_예외() {
		// 인증 전제상 도달 불가 — 도달하면 전제가 깨진 것이라 조용히 넘기지 않고 예외로 드러낸다.
		repository.lockUser(userId);   // 존재하는 유저는 예외 없음
		assertThatThrownBy(() -> repository.lockUser(999_999_999L))
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
