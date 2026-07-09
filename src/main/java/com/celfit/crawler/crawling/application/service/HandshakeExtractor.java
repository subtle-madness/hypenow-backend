package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HandshakeExtractor {

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static final Pattern LSD = Pattern.compile("\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"");

    public static long mediaIdFromShortCode(String sc) {
        long n = 0;
        for (int i = 0; i < sc.length(); i++) {
            int idx = ALPHABET.indexOf(sc.charAt(i));
            if (idx < 0) throw new ApifyException("shortCode 문자 이상: " + sc);
            n = n * 64 + idx;
        }
        return n;
    }

    public static String lsdFrom(String html) {
        Matcher m = LSD.matcher(html);
        if (!m.find()) throw new ApifyException("lsd 토큰 추출 실패");
        return m.group(1);
    }

    private HandshakeExtractor() {}
}
