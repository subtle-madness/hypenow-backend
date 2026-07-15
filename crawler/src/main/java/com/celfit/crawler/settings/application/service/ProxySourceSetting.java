package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.domain.ProxySource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 인스타 자체크롤 프록시 경로 토글. app_setting 키 proxy.source, 없거나 이상하면 APIFY(기존 배선 유지). */
@Service
public class ProxySourceSetting {

    static final String KEY = "proxy.source";

    private final AppSettingRepository settings;

    public ProxySourceSetting(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public ProxySource current() {
        return settings.findById(KEY).map(AppSetting::getValue).map(this::parse).orElse(ProxySource.APIFY);
    }

    @Transactional
    public void update(ProxySource source) {
        settings.save(new AppSetting(KEY, source.name()));
    }

    private ProxySource parse(String value) {
        try {
            return ProxySource.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ProxySource.APIFY;
        }
    }
}
