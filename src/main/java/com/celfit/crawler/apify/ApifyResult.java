package com.celfit.crawler.apify;

import java.util.List;
import java.util.Map;

public record ApifyResult(String runId, List<Map<String, Object>> items) {}
