package com.celfit.was.v2.influencer;

import com.celfit.was.config.CacheConfig;
import com.celfit.was.v1.common.V1ApiException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 6.22 발굴 리포트 v2 조립 — 핸들 단일 키 Redis 캐시(TTL 6h, v1 V1InfluencerReportService와 같은
 * 등급·관용구). 404(인플루언서 없음·카피 미생성)는 예외라 캐시에 안 실린다.
 *
 * 09-03 도입 근거: v2는 v1과 병존하며 만들어질 때 캐시 층 없이 컨트롤러가 리포지토리를 직접
 * 불렀고, 응답 헤더도 no-store라 어느 계층에도 캐시가 없었다(프론트 09-01 실측: 같은 계정 연속
 * 2회가 8,018ms·7,678ms). 지연 본체(findBrandCollabs 브랜드별 전체 스캔)는 별도 PR(#734)로
 * 수리했고, 이 캐시는 그 위에 얹는 부하 대책 — 리포트 재료(미러·계정 카피)는 새벽 배치 후 하루
 * 불변이라 TTL 백스톱만으로 충분하다(2026-07-28 Redis 캐싱 설계와 동일 판단, 무효화 연동 없음).
 *
 * 주의: 어셈블러가 "며칠 전"류 상대값(lastUploadDaysAgo·lastAdDaysAgo)을 조립 시각 기준으로
 * 계산하므로 캐시 히트 응답은 최대 TTL만큼 낡을 수 있다 — v1도 같은 계약.
 */
@Service
public class V2InfluencerReportService {

	private final V2InfluencerReportRepository repository;
	private final V2InfluencerReportAssembler assembler;

	public V2InfluencerReportService(V2InfluencerReportRepository repository,
			V2InfluencerReportAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@Cacheable(cacheNames = CacheConfig.INFLUENCER_REPORT_V2, key = "#influencerId", sync = true)
	public InfluencerAiReportV2 report(String influencerId) {
		var summary = repository.findSummary(influencerId)
				.orElseThrow(() -> V1ApiException.notFound("인플루언서를 찾을 수 없습니다."));
		// 신 스키마 카피 없으면 "리포트 미생성" — tagline·요약 3종이 비-null 계약이라 부분 응답 불가(스펙 6.22 에러)
		var copy = repository.findLatestCopy(influencerId)
				.orElseThrow(() -> V1ApiException.notFound("리포트가 아직 생성되지 않았습니다."));
		return assembler.toReport(summary, copy,
				repository.findSeries(influencerId),
				repository.findCategories(influencerId),
				repository.findBrandCollabs(influencerId));
	}
}
