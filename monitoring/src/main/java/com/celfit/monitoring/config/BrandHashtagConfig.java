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
 *   <li>{@code post-limit} — 해시태그 감시 세트 크기(2026-09-02 감시 세트 2,000 설계 §1, 기본 2,000).
 *       구 "편입 하드스톱"이 아니라 롤링 세트다 — 신규는 항상 편입되고, 게시일 최신 postLimit개
 *       밖은 동결된다(설계 §2). tagged의 {@code collection-post-limit}(2,000)과 <b>별도 카운터</b>고,
 *       2단계 재수집({@code BrandDirectCollectService})이 같은 키를 읽는다. 0 이하는 무제한
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
			@Value("${monitoring.brand.hashtag.post-limit:2000}") int postLimit) {
		return new BrandHashtagCollectService(hiker, callContext, tags, taggedPosts, writer, collect,
				maxPages, postLimit);
	}
}
