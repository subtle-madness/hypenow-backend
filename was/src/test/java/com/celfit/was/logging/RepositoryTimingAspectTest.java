package com.celfit.was.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * 포인트컷 {@code bean(*Repository)} 위빙 검증 — 이름이 Repository로 끝나는 빈의 호출이
 * {@link RequestStageTimings}에 "클래스단순명.메서드명"으로 기록되는지 본다(DB 불필요 —
 * 운영에선 Boot의 AopAutoConfiguration이 aspectjweaver 존재로 같은 프록시를 켠다).
 */
class RepositoryTimingAspectTest {

	static class FakeThingRepository {
		String load() {
			return "ok";
		}
	}

	static class NotARepo {
		String load() {
			return "ok";
		}
	}

	@Configuration
	@EnableAspectJAutoProxy
	static class Config {
		@Bean
		RepositoryTimingAspect repositoryTimingAspect() {
			return new RepositoryTimingAspect();
		}

		@Bean
		FakeThingRepository fakeThingRepository() {
			return new FakeThingRepository();
		}

		@Bean
		NotARepo notARepo() {
			return new NotARepo();
		}
	}

	@Test
	void Repository_빈_호출이_단계로_기록된다() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Config.class)) {
			RequestStageTimings.begin();
			ctx.getBean(FakeThingRepository.class).load();
			ctx.getBean(NotARepo.class).load();
			Map<String, long[]> stages = RequestStageTimings.end();

			assertThat(stages).containsOnlyKeys("FakeThingRepository.load");
			assertThat(stages.get("FakeThingRepository.load")[1]).isEqualTo(1);
		}
	}
}
