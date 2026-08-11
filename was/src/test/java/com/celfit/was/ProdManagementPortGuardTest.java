package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * 운영 프로파일의 액추에이터 관리 포트 분리 회귀 가드 — `application-prod.yml`의
 * `management.server.port`가 존재하고 메인 포트(8081)와 다른지 스냅샷 파싱으로 확인한다.
 *
 * <p>이 한 줄이 사라지면 액추에이터가 메인 포트 8081로 되돌아온다. 8081은 Caddy가 프록시하는
 * 유일한 공개 표면이고 SecurityConfig의 액추에이터 체인은 permitAll이라(관리 포트가 도커 네트워크
 * 내부 전용이라는 전제에 기대는 설정), 곧바로 인터넷에 `/actuator/**`가 열린다 —
 * env·heapdump·prometheus 지표까지. 주석은 지워져도 빌드가 통과하지만 테스트는 통과하지 않는다.
 * 같은 취지의 선례가 {@code ProdForwardedHeadersTest}(forward-headers-strategy 가드).
 *
 * <p>Spring 컨텍스트를 띄우지 않는다 — 검증 대상은 런타임 동작이 아니라 설정 파일 자체이고,
 * 컨텍스트 기동(Testcontainers 포함)에 의존하면 가드가 느려지고 다른 이유로 깨진다.
 */
class ProdManagementPortGuardTest {

	/** application.yml의 `server.port` — Caddy가 프록시하는 공개 표면. */
	private static final String MAIN_PORT = "8081";

	private static Properties load(String yaml) {
		YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
		factory.setResources(new ClassPathResource(yaml));
		Properties properties = factory.getObject();
		assertThat(properties).as("%s를 클래스패스에서 읽지 못했다", yaml).isNotNull();
		return properties;
	}

	@Test
	void 운영_프로파일은_관리_포트를_분리한다() {
		Properties prod = load("application-prod.yml");

		String managementPort = prod.getProperty("management.server.port");

		assertThat(managementPort)
				.as("application-prod.yml에 management.server.port가 없다 — 액추에이터가 공개 포트 %s로 돌아온다"
						+ "(permitAll 체인이라 /actuator/**가 인터넷에 열림)", MAIN_PORT)
				.isNotNull()
				.isNotBlank();
		assertThat(managementPort)
				.as("관리 포트가 메인 포트와 같으면 분리가 무의미하다 — Caddy가 프록시하는 표면에 액추에이터가 실린다")
				.isNotEqualTo(MAIN_PORT);
	}

	@Test
	void 메인_포트_전제가_유지된다() {
		// 위 가드는 "관리 포트 != 메인 포트" 비교라 메인 포트 상수가 실제 설정과 어긋나면 조용히 헐거워진다.
		assertThat(load("application.yml").getProperty("server.port")).isEqualTo(MAIN_PORT);
	}
}
