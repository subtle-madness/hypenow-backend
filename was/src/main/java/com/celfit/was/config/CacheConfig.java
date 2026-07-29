package com.celfit.was.config;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * 조회 캐시(Redis) — TTL 백스톱만, 무효화 연동 없음(분석 데이터는 새벽 미러 후 하루 불변,
 * 스펙 specs/2026-07-28-redis-caching-design.md). 장애는 전면 fail-open: errorHandler가
 * 삼키고 DB 직행 — Redis는 SPOF가 아니다. 세션은 JDBC 유지(여기 안 탄다).
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

	public static final String CONTENT_RANKING = "content-ranking";
	public static final String INFLUENCER_DISCOVERY = "influencer-discovery";
	public static final String CONTENT_REPORT = "content-report";
	public static final String INFLUENCER_REPORT = "influencer-report";

	private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

	private final String cacheEpoch;

	public CacheConfig(ObjectProvider<BuildProperties> buildProperties) {
		BuildProperties bp = buildProperties.getIfAvailable();
		// getTime()은 build.time 제거 시 null — 그 경우 "dev" 폴백으로 기동은 지키되, 캐시 세대 무효화(배포별 prefix)는
		// 죽는다. 재현 빌드 등으로 time을 꺼야 하면 commit sha를 buildInfo additional로 넣고 여기서 읽도록 바꿀 것.
		this.cacheEpoch = (bp == null || bp.getTime() == null) ? "dev"
				: String.valueOf(bp.getTime().getEpochSecond());
	}

	@Bean
	public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
		// 주의: 캐시 값의 OffsetDateTime은 왕복 시 offset이 UTC로 정규화된다(instant는 보존) —
		// 캐시 DTO의 시간 필드는 instant 기반으로만 소비할 것.
		RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
				.computePrefixWith(name -> "hypenow:cache:" + cacheEpoch + ":" + name + ":")
				.serializeValuesWith(RedisSerializationContext.SerializationPair
						.fromSerializer(RedisSerializer.json()));
		// TTL 근거: 미러 KST 04:30~07:00 → 최악 stale 6h면 오전 중 자연 갱신(스펙 §4)
		return RedisCacheManager.builder(factory)
				.cacheDefaults(base.entryTtl(Duration.ofHours(1)))
				.withCacheConfiguration(CONTENT_RANKING, base.entryTtl(Duration.ofHours(1)))
				.withCacheConfiguration(INFLUENCER_DISCOVERY, base.entryTtl(Duration.ofHours(1)))
				.withCacheConfiguration(CONTENT_REPORT, base.entryTtl(Duration.ofHours(6)))
				.withCacheConfiguration(INFLUENCER_REPORT, base.entryTtl(Duration.ofHours(6)))
				.build();
	}

	@Override
	public CacheErrorHandler errorHandler() {
		return new CacheErrorHandler() {
			@Override
			public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
				warn("get", cache, key, e);
			}

			@Override
			public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
				warn("put", cache, key, e);
			}

			@Override
			public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
				warn("evict", cache, key, e);
			}

			@Override
			public void handleCacheClearError(RuntimeException e, Cache cache) {
				warn("clear", cache, null, e);
			}
		};
	}

	private static void warn(String op, Cache cache, Object key, RuntimeException e) {
		log.warn("캐시 {} 실패(fail-open, 무시) cache={} key={}", op, cache.getName(), key, e);
	}
}
