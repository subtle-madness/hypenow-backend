package com.celfit.crawler.apify;

public class ApifyException extends RuntimeException {
    public ApifyException(String message) {
        super(message);
    }

    public ApifyException(String message, Throwable cause) {
        super(message, cause);
    }
}
