package com.celfit.was.v1.content;

import com.celfit.was.config.CacheConfig;
import com.celfit.was.v1.common.V1ApiException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** 6.3 리포트 조립 — 단일 리소스 키 Redis 캐시(TTL 6h, 스펙 §4). 404는 예외라 캐시에 안 실린다. */
@Service
public class V1ContentReportService {

	private final V1ContentReportRepository repository;
	private final V1ContentReportAssembler assembler;

	public V1ContentReportService(V1ContentReportRepository repository,
			V1ContentReportAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@Cacheable(cacheNames = CacheConfig.CONTENT_REPORT, key = "#contentId", sync = true)
	public ContentAiReport report(String contentId) {
		var report = repository.findReport(contentId)
				.orElseThrow(() -> V1ApiException.notFound("콘텐츠를 찾을 수 없습니다."));
		// 카테고리 맥락은 대분류가 있을 때만 집계한다 (미분류면 비교 모수 자체가 정의되지 않음).
		var categoryContext = report.mainCategory() == null ? null
				: repository.findCategoryContext(report.mainCategory(), report.views());
		return assembler.toReport(report,
				repository.findRecentReels(report.accountHandle()),
				categoryContext,
				repository.countByCategory(contentId),
				repository.findComments(contentId));
	}
}
