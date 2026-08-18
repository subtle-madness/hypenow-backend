package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.TestDb;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BrandSeededAccountRepositoryTest {

	JdbcTemplate db;
	BrandSeededAccountRepository repo;
	long brandId;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		repo = new BrandSeededAccountRepository(db);
		brandId = db.queryForObject(
				"INSERT INTO brand_account (username, ig_user_id) VALUES ('cclime_official', '99') RETURNING id",
				Long.class);
	}

	@Test
	void 추가와_조회() {
		repo.add(brandId, List.of("influencer1", "influencer2"));
		assertThat(repo.findUsernames(brandId)).containsExactlyInAnyOrder("influencer1", "influencer2");
	}

	@Test
	void 추가는_멱등() {
		repo.add(brandId, List.of("influencer1"));
		repo.add(brandId, List.of("influencer1"));
		assertThat(repo.findUsernames(brandId)).containsExactly("influencer1");
	}

	@Test
	void 전체_교체() {
		repo.add(brandId, List.of("influencer1", "influencer2"));
		repo.replace(brandId, List.of("influencer2", "influencer3"));
		assertThat(repo.findUsernames(brandId)).containsExactlyInAnyOrder("influencer2", "influencer3");
	}

	@Test
	void 단건_삭제() {
		repo.add(brandId, List.of("influencer1", "influencer2"));
		repo.delete(brandId, "influencer1");
		assertThat(repo.findUsernames(brandId)).containsExactly("influencer2");
	}

	@Test
	void 전체_삭제() {
		repo.add(brandId, List.of("influencer1", "influencer2"));
		repo.deleteAll(brandId);
		assertThat(repo.findUsernames(brandId)).isEmpty();
	}
}
