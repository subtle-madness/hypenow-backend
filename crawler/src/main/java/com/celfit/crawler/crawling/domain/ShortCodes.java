package com.celfit.crawler.crawling.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShortCodes {

    private static final Pattern URL = Pattern.compile("instagram\\.com/(?:p|reel)/([A-Za-z0-9_-]+)");

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
