package com.celfit.instagram.source.self;

/**
 * DataImpulse 프록시 URL 조작 유틸(하니스 with_country 이식). 국가 라우팅은 username에 매개변수
 * 블록(__ 시작, ; 구분)으로 실린다: exit IP를 country로 고정하려면 __cr.<country>를 붙인다.
 * URI 파서 대신 scheme://userinfo@hostport를 수동 분해 — 프록시 password의 @·: 등 특수문자가
 * URI.create를 깨뜨릴 수 있어서다.
 */
public final class ProxyUrls {

	private ProxyUrls() {}

	/** exit IP를 country(예: "kr")로 고정한 프록시 URL. */
	public static String withCountry(String proxyUrl, String country) {
		int sep = proxyUrl.indexOf("://");
		String scheme = proxyUrl.substring(0, sep);
		String rest = proxyUrl.substring(sep + 3);
		int at = rest.lastIndexOf('@');
		String userinfo = at < 0 ? "" : rest.substring(0, at);
		String hostport = at < 0 ? rest : rest.substring(at + 1);
		int colon = userinfo.indexOf(':');
		String user = colon < 0 ? userinfo : userinfo.substring(0, colon);
		String pass = colon < 0 ? null : userinfo.substring(colon + 1);
		String newUser = user.contains("__") ? user + ";cr." + country : user + "__cr." + country;
		String newUserinfo = pass == null ? newUser : newUser + ":" + pass;
		return scheme + "://" + newUserinfo + "@" + hostport;
	}
}
