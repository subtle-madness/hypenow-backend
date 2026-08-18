package com.celfit.monitoring.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 레거시(캠페인) 이력을 브랜드 전용 게시물 테이블로 복사(2026-08-18 direct 통합 §4-3, import 모드
 * 전용) — 같은 DB 안이므로 순수 SQL이다. 이관 잡·{@code POST /api/brands/{brandId}/direct-posts}의
 * {@code importLegacyHistory=true} 경로가 쓴다. <b>레거시 원본은 절대 지우지 않는다</b> — 같은
 * 게시물이 다른 유저의 개인 캠페인에서 여전히 추적 중일 수 있다. 전부 {@code ON CONFLICT DO NOTHING}
 * 이라 비파괴·재실행 안전(멱등)하다.
 *
 * <p><b>컬럼 동형성 대조 결과(설계 R9, 구현 시 실제 Flyway DDL로 대조 — 로컬 postgres 컨테이너에
 * monitoring 전용 DB가 없어 마이그레이션 파일 기준으로 확인)</b>:
 * <ul>
 * <li>{@code post_snapshot}(V1__core_tables.sql + V20260803043516 fb_plays + V20260803125200
 * likes_hidden + V20260805054500 shares_hidden) ↔ {@code brand_post_snapshot}(V20260806150000) —
 * 컬럼 완전 동형(username·short_code·captured_on·content_type·likes·likes_hidden·comments·views·
 * fb_plays·saves·shares·shares_hidden·reposts). 차이 없음.</li>
 * <li>{@code post_meta}(V5__p1_surfaces.sql + V20260801064345 이미지 아카이브 3컬럼) ↔
 * {@code brand_post_meta}(V20260806150000 + V20260807130000 영상·협찬 3컬럼 + V20260812021500
 * 이미지 아카이브 3컬럼) — 공통 7컬럼(short_code·username·content_type·uploaded_at·caption·
 * thumbnail_url·first_seen_at) + 이미지 아카이브 3컬럼(image_object_path·image_source_name·
 * image_archived_at)은 동형. <b>브랜드 전용 3컬럼(video_url·video_duration·is_paid_partnership)은
 * 레거시에 없다</b> — 복사 INSERT 목록에서 빼고 NULL로 둔다(직후의 collectAndEnrich가 단건 콜로
 * 채운다, ON CONFLICT DO NOTHING이라 복사가 먼저여도 upsert가 나중에 덮는다).</li>
 * <li>{@code post_comment}(V4__p2_surfaces.sql) ↔ {@code brand_post_comment}(V20260806150000) —
 * 컬럼 완전 동형(short_code·id·author·body·like_count·commented_at·owner_reply_text). 차이 없음.</li>
 * </ul>
 */
@Repository
public class BrandLegacyHistoryCopier {

	private final JdbcTemplate db;

	public BrandLegacyHistoryCopier(JdbcTemplate db) {
		this.db = db;
	}

	@Transactional
	public void copy(String shortCode) {
		db.update("""
				INSERT INTO brand_post_snapshot (username, short_code, captured_on, content_type, likes, likes_hidden,
				                                 comments, views, fb_plays, saves, shares, shares_hidden, reposts)
				SELECT username, short_code, captured_on, content_type, likes, likes_hidden,
				       comments, views, fb_plays, saves, shares, shares_hidden, reposts
				  FROM post_snapshot WHERE short_code = ?
				ON CONFLICT (short_code, captured_on) DO NOTHING""", shortCode);

		// post_meta의 SELECT 목록은 INSERT 목록과 1:1로 맞춘다(설계 문서 초안의 SELECT 목록은
		// post_snapshot 블록을 잘못 복사해 붙인 것이었다 — 위 컬럼 대조 결과대로 바로잡음, R9).
		db.update("""
				INSERT INTO brand_post_meta (short_code, username, content_type, uploaded_at, caption, thumbnail_url,
				                             first_seen_at, image_object_path, image_source_name, image_archived_at)
				SELECT short_code, username, content_type, uploaded_at, caption, thumbnail_url,
				       first_seen_at, image_object_path, image_source_name, image_archived_at
				  FROM post_meta WHERE short_code = ?
				ON CONFLICT (short_code) DO NOTHING""", shortCode);

		db.update("""
				INSERT INTO brand_post_comment (short_code, id, author, body, like_count, commented_at, owner_reply_text)
				SELECT short_code, id, author, body, like_count, commented_at, owner_reply_text
				  FROM post_comment WHERE short_code = ?
				ON CONFLICT (short_code, id) DO NOTHING""", shortCode);
	}
}
