package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * app.brand_hashtag_tags 조회 계층 통합 테스트(2026-08-19 설계, 상호작용 사용자 스코프 개정) — 유저
 * 스코프 태그 CRUD·브랜드 합집합·삭제 시맨틱(다른 유저 소유 판정)을 실 컨테이너 왕복으로 검증한다.
 */
class BrandHashtagTagRepositoryTest extends IntegrationTest {

	@Autowired
	BrandHashtagTagRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	long userId;
	long otherUserId;
	// brand_id는 테스트마다 고유해야 한다 — 통합 테스트가 컨테이너를 공유하고 롤백이 없다.
	long brandId;

	@BeforeEach
	void 유저_시드() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "hashtag-tag-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		otherUserId = jdbcClient
				.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "hashtag-tag-other-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		brandId = System.nanoTime();   // monitoring 논리 참조 — 실제 brand_account 행 불필요(FK 없음).
	}

	@Test
	void addTags_이후_findByUserAndBrand로_조회된다() {
		repository.addTags(userId, brandId, List.of("리즈다", "lizda"));

		assertThat(repository.findByUserAndBrand(userId, brandId)).containsExactlyInAnyOrder("리즈다", "lizda");
	}

	@Test
	void addTags는_이미_있으면_멱등이다() {
		repository.addTags(userId, brandId, List.of("리즈다"));
		repository.addTags(userId, brandId, List.of("리즈다"));

		assertThat(repository.findByUserAndBrand(userId, brandId)).containsExactly("리즈다");
	}

	@Test
	void replaceTags는_이_유저의_태그만_바꾼다() {
		repository.addTags(userId, brandId, List.of("옛태그"));
		repository.addTags(otherUserId, brandId, List.of("남의태그"));

		repository.replaceTags(userId, brandId, List.of("새태그"));

		assertThat(repository.findByUserAndBrand(userId, brandId)).containsExactly("새태그");
		assertThat(repository.findByUserAndBrand(otherUserId, brandId)).containsExactly("남의태그");
	}

	@Test
	void unionByBrand는_연결_유저_전체_태그의_합집합이다() {
		repository.addTags(userId, brandId, List.of("공통태그", "내태그"));
		repository.addTags(otherUserId, brandId, List.of("공통태그", "남의태그"));

		assertThat(repository.unionByBrand(brandId)).containsExactlyInAnyOrder("공통태그", "내태그", "남의태그");
	}

	/**
	 * 삭제 시맨틱(요구사항, 08-19) — hasOtherRegistrant와 같은 패턴. 다른 유저가 같은 태그를 갖고
	 * 있으면 내 삭제가 그 사실을 바꾸지 않아야 한다(monitoring 반영 여부는 호출부가 이 결과로 판단).
	 */
	@Test
	void hasOtherUserWithTag는_다른_유저_소유만_본다() {
		repository.addTags(userId, brandId, List.of("공유태그"));
		repository.addTags(otherUserId, brandId, List.of("공유태그"));

		assertThat(repository.hasOtherUserWithTag(brandId, "공유태그", userId)).isTrue();

		repository.deleteTag(otherUserId, brandId, "공유태그");

		assertThat(repository.hasOtherUserWithTag(brandId, "공유태그", userId)).isFalse();
	}

	@Test
	void deleteAllTags는_이_유저의_모든_태그를_지운다() {
		repository.addTags(userId, brandId, List.of("태그1", "태그2"));
		repository.addTags(otherUserId, brandId, List.of("남의태그"));

		repository.deleteAllTags(userId, brandId);

		assertThat(repository.findByUserAndBrand(userId, brandId)).isEmpty();
		assertThat(repository.findByUserAndBrand(otherUserId, brandId)).containsExactly("남의태그");
	}
}
