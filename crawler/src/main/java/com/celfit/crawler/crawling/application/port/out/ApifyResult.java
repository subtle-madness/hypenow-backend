package com.celfit.crawler.crawling.application.port.out;

import java.util.List;
import java.util.Map;

public record ApifyResult(String runId, List<Map<String, Object>> items) {}
