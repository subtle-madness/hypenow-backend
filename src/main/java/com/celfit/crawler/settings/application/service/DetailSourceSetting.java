package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.domain.DetailSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 상세 수집 소스 토글. 타입별 키(detail.reels.source/detail.feed.source), 기본 릴스=HIKER·피드=SELF. */
@Service
public class DetailSourceSetting {

    static final String REELS_KEY = "detail.reels.source";
    static final String FEED_KEY = "detail.feed.source";

    private final AppSettingRepository settings;

    public DetailSourceSetting(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public DetailSource sourceFor(ContentType type) {
        String key = type == ContentType.REELS ? REELS_KEY : FEED_KEY;
        DetailSource dflt = type == ContentType.REELS ? DetailSource.HIKER : DetailSource.SELF;
        return settings.findById(key).map(AppSetting::getValue).map(v -> parse(v, dflt)).orElse(dflt);
    }

    @Transactional
    public void update(DetailSource reels, DetailSource feed) {
        settings.save(new AppSetting(REELS_KEY, reels.name()));
        settings.save(new AppSetting(FEED_KEY, feed.name()));
    }

    private DetailSource parse(String value, DetailSource dflt) {
        try {
            return DetailSource.valueOf(value);
        } catch (IllegalArgumentException e) {
            return dflt;
        }
    }
}
