package com.celfit.was.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc 최소 구성 — /v1 표면만 자동 문서화한다(springdoc.paths-to-match).
 * 정본은 프론트 API 스펙 문서이고 Swagger는 실제 서버 시그니처 확인용 보조 문서 —
 * 그래서 컨트롤러에 @Operation 류(오퍼레이션 summary·description 서술) 어노테이션은 달지 않는다
 * (자동 스캔만). 예외: PATCH/POST 바디가 {@code Map<String,Object>}라 스키마가 자동으로 비는
 * 컨트롤러는 {@code @RequestBody(content=@Content(schema=@Schema(implementation=...)))}로 문서
 * 전용 스키마 record를 붙이고(예: CampaignRequestDocs), 응답 DTO의 상태값 String 필드는
 * {@code @Schema(allowableValues=...)}로 허용값만 표기한다(07-30, 프론트 요청 A-2) — 둘 다 필드
 * 스키마 노출용이라 오퍼레이션 서술과는 다른 층이며, 런타임 타입·역직렬화는 그대로 둔다.
 * 전 환경 노출하되 접근은 admin 게이트가 통제한다(SecurityConfig 스웨거 체인 — 07-19 결정).
 */
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI hypenowOpenApi() {
		return new OpenAPI().info(new Info()
				.title("hypenow API")
				.version("v1")
				.description("hypenow 백엔드 /v1 표면 자동 문서. 정본은 프론트 API 스펙 문서 — 여기는 보조 참조용."));
	}
}
