package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.domain.DiscoverSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 발굴 소스 토글. app_setting 키 discover.source, 없거나 이상하면 HIKER. */
@Service
public class DiscoverSourceSetting {

    static final String KEY = "discover.source";

    private final AppSettingRepository settings;

    public DiscoverSourceSetting(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public DiscoverSource current() {
        return settings.findById(KEY).map(AppSetting::getValue).map(this::parse).orElse(DiscoverSource.HIKER);
    }

    @Transactional
    public void update(DiscoverSource source) {
        settings.save(new AppSetting(KEY, source.name()));
    }

    /** 테스트·마이그레이션용: 검증 없이 원문 저장. current()가 파싱 실패 시 HIKER 폴백. */
    @Transactional
    public void updateRaw(String value) {
        settings.save(new AppSetting(KEY, value));
    }

    private DiscoverSource parse(String value) {
        try {
            return DiscoverSource.valueOf(value);
        } catch (IllegalArgumentException e) {
            return DiscoverSource.HIKER;
        }
    }
}
