package com.celfit.was.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 프론트(celfit-front.vercel.app)가 별도 오리진이라 CORS 허용이 필요하다. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final String[] allowedOrigins;

	public WebConfig(@Value("${was.cors.allowed-origins}") String[] allowedOrigins) {
		this.allowedOrigins = allowedOrigins;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOrigins(allowedOrigins)
				.allowedMethods("GET");
	}
}
