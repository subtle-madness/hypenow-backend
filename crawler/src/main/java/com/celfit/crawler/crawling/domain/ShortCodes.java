package com.celfit.crawler.crawling.domain;

import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShortCodes {

    private static final Pattern URL = Pattern.compile("instagram\\.com/(?:p|reel)/([A-Za-z0-9_-]+)");

    /** IG shortCode의 base64url 알파벳(pk 인코딩용). */
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    /** shortCode를 숫자 media pk로 디코드(비로그인 GraphQL 변수 media_id용). 예: DakcjkOuiZi → 3937397563614439010. */
    public static String mediaId(String shortCode) {
        BigInteger id = BigInteger.ZERO;
        for (int i = 0; i < shortCode.length(); i++) {
            int v = B64.indexOf(shortCode.charAt(i));
            if (v < 0) throw new IllegalArgumentException("shortCode에 잘못된 문자: " + shortCode);
            id = id.multiply(BigInteger.valueOf(64)).add(BigInteger.valueOf(v));
        }
        return id.toString();
    }

    public static String postUrl(String shortCode) {
        return "https://www.instagram.com/p/" + shortCode + "/";
    }

    public static String reelUrl(String shortCode) {
        return "https://www.instagram.com/reel/" + shortCode + "/";
    }

    public static Optional<String> fromUrl(String url) {
        if (url == null) return Optional.empty();
        Matcher m = URL.matcher(url);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private ShortCodes() {}
}
