package com.celfit.monitoring.store;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시딩(협업) 계정 등록(스펙 §6) — 브랜드가 등록한 시딩 인플루언서 계정. 판정 결과와 분리 저장하고
 * 조회 시 조인으로 계산한다(목록을 나중에 등록·수정해도 재판정 불필요). tombstone 없음(하드 삭제) —
 * 이 테이블은 재등록 시 자동 유도되는 값이 없어(태그·제외 문자열과 달리 유저가 직접 관리하는
 * 목록뿐) 되살릴 대상이 없다.
 */
@Repository
public class BrandSeededAccountRepository {

	private final JdbcTemplate db;

	public BrandSeededAccountRepository(JdbcTemplate db) {
		this.db = db;
	}

	public List<String> findUsernames(long brandId) {
		return db.queryForList(
				"SELECT username FROM brand_seeded_account WHERE brand_id = ? ORDER BY created_at, username",
				String.class, brandId);
	}

	public void add(long brandId, Collection<String> usernames) {
		for (String username : usernames) {
			db.update("INSERT INTO brand_seeded_account (brand_id, username) VALUES (?, ?) ON CONFLICT DO NOTHING",
					brandId, username);
		}
	}

	/** 전체 교체 — 목록에 없는 기존 행은 하드 삭제한다(tombstone 불필요 — 클래스 주석 참조). */
	@Transactional
	public void replace(long brandId, List<String> usernames) {
		Set<String> next = new LinkedHashSet<>(usernames);
		for (String existing : findUsernames(brandId)) {
			if (!next.contains(existing)) {
				db.update("DELETE FROM brand_seeded_account WHERE brand_id = ? AND username = ?",
						brandId, existing);
			}
		}
		add(brandId, next);
	}

	public void delete(long brandId, String username) {
		db.update("DELETE FROM brand_seeded_account WHERE brand_id = ? AND username = ?", brandId, username);
	}

	public void deleteAll(long brandId) {
		db.update("DELETE FROM brand_seeded_account WHERE brand_id = ?", brandId);
	}
}
