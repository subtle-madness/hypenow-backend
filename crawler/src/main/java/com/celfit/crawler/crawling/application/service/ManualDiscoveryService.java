package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.InfluencerDiscoveryRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerDiscovery;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import java.time.Clock;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수동 발굴 등록 — 크롬 익스텐션 등 외부에서 username 하나를 발굴 단계(DISCOVERED)로 넣는다.
 * 신규만 생성하고 기존 계정은 건드리지 않는다(반복 클릭으로 discovery 이력이 쌓이는 것 방지).
 * 이후 판정·뷰티판정은 기존 QualifyJob·BeautyJob이 그대로 이어받는다.
 */
@Service
public class ManualDiscoveryService {

    /** 발굴 출처 스냅샷 — influencer_discovery.keyword에 남는 값. */
    public static final String MANUAL_KEYWORD = "수동:크롬";

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._]{1,30}$");

    public record Result(String username, boolean created, InfluencerStatus status, BeautyClass beautyClass) {}

    private final InfluencerRepository influencers;
    private final InfluencerDiscoveryRepository discoveries;
    private final Clock clock;

    public ManualDiscoveryService(InfluencerRepository influencers,
                                  InfluencerDiscoveryRepository discoveries, Clock clock) {
        this.influencers = influencers;
        this.discoveries = discoveries;
        this.clock = clock;
    }

    @Transactional
    public Result register(String rawUsername) {
        String username = normalize(rawUsername);
        var existing = influencers.findByUsername(username);
        if (existing.isPresent()) {
            Influencer inf = existing.get();
            return new Result(username, false, inf.getStatus(), inf.getBeautyClass());
        }
        Influencer inf = influencers.save(new Influencer(username));
        discoveries.save(new InfluencerDiscovery(inf.getId(), MANUAL_KEYWORD, null, clock.instant()));
        return new Result(username, true, inf.getStatus(), inf.getBeautyClass());
    }

    /** 앞뒤 공백·@ 제거 후 소문자화. 인스타 username 형식(영숫자·._ 1~30자)이 아니면 예외. */
    private static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("username이 비어 있음");
        }
        String u = raw.trim().toLowerCase();
        if (u.startsWith("@")) {
            u = u.substring(1);
        }
        if (!USERNAME.matcher(u).matches()) {
            throw new IllegalArgumentException("username 형식 불량: " + raw);
        }
        return u;
    }
}
