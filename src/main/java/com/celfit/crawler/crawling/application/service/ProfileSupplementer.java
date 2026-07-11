package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.settings.application.service.ProfileSupplementSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 부족한 베이스(SELF·HIKER_MOBILE)에만 HikerAPI 보충을 각각 독립 적용. */
@Service
public class ProfileSupplementer {

    private static final Logger log = LoggerFactory.getLogger(ProfileSupplementer.class);
    private static final Set<ProfileSource> DEFICIENT = Set.of(ProfileSource.SELF, ProfileSource.HIKER_MOBILE);

    private final HikerMediasSupplement medias;
    private final HikerSuggestedSupplement suggested;
    private final ProfileSupplementSetting setting;

    public ProfileSupplementer(HikerMediasSupplement medias, HikerSuggestedSupplement suggested,
                               ProfileSupplementSetting setting) {
        this.medias = medias;
        this.suggested = suggested;
        this.setting = setting;
    }

    public CrawlExecutor.Execution apply(CrawlExecutor.Execution ex, ProfileSource source) {
        if (!DEFICIENT.contains(source)) return ex;
        boolean posts = setting.postsEnabled();
        boolean related = setting.relatedEnabled();
        if (!posts && !related) return ex;
        for (var item : ex.items()) {
            if (posts) {
                try { medias.enrich(item); }
                catch (RuntimeException e) { log.warn("posts 보충 실패 {}: {}", item.get("username"), e.getMessage()); }
            }
            if (related) {
                try { suggested.enrich(item); }
                catch (RuntimeException e) { log.warn("related 보충 실패 {}: {}", item.get("username"), e.getMessage()); }
            }
        }
        return ex;
    }
}
