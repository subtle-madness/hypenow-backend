package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.settings.application.service.ProfileSupplementSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 부족한 베이스(SELF·HIKER_MOBILE)에만 related(HikerAPI suggested) 보충을 적용.
 * item은 소스 원형(SELF_GQL 또는 HIKER_MOBILE 형태)이므로 userId는 ProfileExtractor로 추출한다.
 */
@Service
public class ProfileSupplementer {

    private static final Logger log = LoggerFactory.getLogger(ProfileSupplementer.class);
    private static final Set<ProfileSource> DEFICIENT =
            Set.of(ProfileSource.SELF, ProfileSource.HIKER_MOBILE, ProfileSource.SELF_HIKER_FALLBACK);

    private final HikerSuggestedSupplement suggested;
    private final ProfileSupplementSetting setting;

    public ProfileSupplementer(HikerSuggestedSupplement suggested, ProfileSupplementSetting setting) {
        this.suggested = suggested;
        this.setting = setting;
    }

    public CrawlExecutor.Execution apply(CrawlExecutor.Execution ex, ProfileSource source) {
        if (!DEFICIENT.contains(source)) return ex;
        if (!setting.relatedEnabled()) return ex;
        // SELF 계열(SELF·SELF_HIKER_FALLBACK)의 기본 원형은 SELF_GQL — 폴백 혼합분은 아이템별 감지
        RawSource base = source == ProfileSource.HIKER_MOBILE ? RawSource.HIKER_MOBILE : RawSource.SELF_GQL;
        for (Map<String, Object> item : ex.items()) {
            String uid = ProfileExtractor.userId(item, ProfileExtractor.detect(item, base));
            try { suggested.enrich(item, uid); }
            catch (RuntimeException e) { log.warn("related 보충 실패 {}: {}", uid, e.getMessage()); }
        }
        return ex;
    }
}
