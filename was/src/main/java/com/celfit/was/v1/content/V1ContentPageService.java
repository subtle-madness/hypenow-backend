package com.celfit.was.v1.content;

import com.celfit.was.config.CacheConfig;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 6.1 목록의 공통 페이지 묶음(개인화 제외) — Redis 캐시 단위(스펙 §4). isSaved 오버레이는
 * 컨트롤러가 캐시 밖에서 얹는다 → 사용자 간 캐시 공유(개인화는 캐시 밖) + 저장 직후에도 항상 실시간 정확(스펙 §6).
 */
@Service
public class V1ContentPageService {

	private final V1ContentRepository repository;

	public V1ContentPageService(V1ContentRepository repository) {
		this.repository = repository;
	}

	@Cacheable(cacheNames = CacheConfig.CONTENT_RANKING, key = "#q.cacheKey()", sync = true)
	public ContentPage page(V1ContentQuery q) {
		return new ContentPage(repository.findCards(q), repository.countCards(q));
	}

	/** meta.distributors — 소형 조회라 캐시 없이 통과. 축은 요청이 결정(2026-09-01). */
	public List<Map<String, Object>> distributorOptions(String axis) {
		return repository.findDistributorOptions(axis);
	}

	/** 캐시에 실리는 페이지 묶음 — rows는 개인화 없는 공통 행. */
	public record ContentPage(List<ContentCardRow> rows, long total) {
	}
}
