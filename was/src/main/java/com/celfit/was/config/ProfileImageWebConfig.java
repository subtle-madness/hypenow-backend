package com.celfit.was.config;

import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 프로필 이미지 정적 서빙(스펙 6.13) — ProfileImageStore가 저장한 `was.profile-image.dir`를
 * `/profile-images/**`로 노출한다. URL의 `?v=` 쿼리는 캐시 무효화용이라 매핑에는 영향 없다.
 */
@Configuration
public class ProfileImageWebConfig implements WebMvcConfigurer {

	private final String dir;

	public ProfileImageWebConfig(@Value("${was.profile-image.dir:./data/profile-images}") String dir) {
		this.dir = dir;
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		String location = Paths.get(dir).toAbsolutePath().normalize().toUri().toString();
		if (!location.endsWith("/")) {
			location = location + "/"; // 디렉토리가 아직 없으면 toUri가 슬래시를 안 붙인다
		}
		registry.addResourceHandler("/profile-images/**").addResourceLocations(location);
	}
}
