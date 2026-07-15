package com.celfit.was.config;

import java.time.Duration;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * 세션 쿠키 직렬화기 — server.servlet.session.cookie.* 를 명시적으로 매핑한다(Boot의
 * SessionAutoConfiguration 임베디드 경로와 같은 매핑).
 *
 * 명시 빈을 두는 이유(실측): Boot 4의 SessionAutoConfiguration은 임베디드 웹서버 컨텍스트에서만
 * 이 프로퍼티들을 CookieSerializer에 입히고, mock 서블릿 환경(MockMvc 통합 테스트)은
 * @ConditionalOnWarDeployment 로 분류돼 프로퍼티가 무시된 기본 SESSION 쿠키가 나간다.
 * 두 경로 모두 @ConditionalOnMissingBean 이라 이 빈이 항상 이기고, 배포 형태와 무관하게
 * 스펙 4절 계약(hypenow-session·HttpOnly·SameSite=Lax·Max-Age 30일)이 한 곳에 고정된다.
 */
@Configuration
public class SessionConfig {

	@Bean
	public DefaultCookieSerializer cookieSerializer(ServerProperties serverProperties) {
		Cookie cookie = serverProperties.getServlet().getSession().getCookie();
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		PropertyMapper map = PropertyMapper.get(); // Boot 4 PropertyMapper는 null 소스를 기본으로 건너뛴다
		map.from(cookie::getName).to(serializer::setCookieName);
		map.from(cookie::getHttpOnly).to(serializer::setUseHttpOnlyCookie);
		map.from(cookie::getSecure).to(serializer::setUseSecureCookie);
		map.from(cookie::getPath).to(serializer::setCookiePath);
		map.from(cookie::getDomain).to(serializer::setDomainName);
		map.from(cookie::getMaxAge).asInt(Duration::getSeconds).to(serializer::setCookieMaxAge);
		map.from(cookie::getSameSite).as(Cookie.SameSite::attributeValue).to(serializer::setSameSite);
		return serializer;
	}
}
