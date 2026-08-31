package com.celfit.monitoring.config;

import com.celfit.instagram.source.InstagramSource;
import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.service.BrandCollectService;
import com.celfit.monitoring.service.BrandHashtagCollectService;
import com.celfit.monitoring.service.BrandSnapshotWriter;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 브랜드 해시태그 수집 배선(2026-08-27 해시태그 직접 수집 설계 §2) — 구 감지 구조의 LLM 관련성
 * 판정({@code BrandMentionJudge}) 빈은 파이프라인과 함께 제거됐다. 남은 설정 값은 두 개다:
 *
 * <ul>
 *   <li>{@code max-pages} — 태그당 recent 열거 최대 페이지(구 구조에서 그대로).</li>
 *   <li>{@code post-limit} — 브랜드당 hashtag 성분 행 상한(설계 §0, 기본 1,000). tagged의
 *       {@code collection-post-limit}(2,000)과 <b>별도 카운터</b>다. 0 이하는 무제한
 *       (backfill-max-per-run·collection-post-limit 관용 일치).</li>
 * </ul>
 *
 * <p>구 {@code window-days}(90일 고정)는 폐기됐다 — 기간 컷은 이제 브랜드의 collectionMonths를
 * 그대로 쓴다({@code BrandCollectService.collectionCutoff}).
 */
@Configuration
public class BrandHashtagConfig {

	@Bean
	public BrandHashtagCollectService brandHashtagCollectService(InstagramSource hiker,
			BrandCallContext callContext, BrandHashtagRepository tags, TaggedPostRepository taggedPosts,
			BrandSnapshotWriter writer, BrandCollectService collect,
			@Value("${monitoring.brand.hashtag.max-pages:4}") int maxPages,
			@Value("${monitoring.brand.hashtag.post-limit:1000}") int postLimit) {
		return new BrandHashtagCollectService(hiker, callContext, tags, taggedPosts, writer, collect,
				maxPages, postLimit);
	}
}
