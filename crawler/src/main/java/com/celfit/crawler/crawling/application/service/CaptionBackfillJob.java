package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.application.service.ContentCaptionUpserter;
import com.celfit.crawler.crawling.application.port.out.RawMediaPageRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawMediaPage;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 캡션 소급 적재 — 이미 저장된 raw 원형(raw_media_page·raw_profile)을 훑어 캡션을 채운다.
 * 인스타 API를 호출하지 않으므로 재크롤 비용이 0이다.
 *
 * <p>페이지 주도로 스캔한다(content 주도 아님) — 페이지 1건이 캡션 약 12건을 내놓으므로,
 * content별로 원형을 역방향 조회(LATERAL 탐색)하는 것보다 훨씬 싸다.
 *
 * <p>파싱은 MediaItemExtractor 하나만 쓴다 — 라이브 수집과 같은 파서라 로직이 갈라지지 않는다.
 *
 * <p>재개는 app_setting 워터마크(페이지 id)로 한다. upsert가 멱등이라 중복 실행도 안전하고,
 * 청크 1개 = 트랜잭션 1개라 중간에 죽어도 처리한 만큼은 커밋돼 있다(BeautyJob과 같은 경계).
 */
@Service
public class CaptionBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(CaptionBackfillJob.class);

    /** 페이지 1건이 jsonb 수십~수백 KB다 — 청크를 크게 잡으면 힙이 위험하다. */
    static final int PAGE_CHUNK = 200;

    static final String MEDIA_WATERMARK = "caption.backfill.media-page-id";
    static final String PROFILE_WATERMARK = "caption.backfill.profile-id";

    /** 처리한 페이지 수와 적재 시도한 캡션 수. */
    public record Stats(int pages, int captions) {}

    private final RawMediaPageRepository mediaPages;
    private final RawProfileRepository profiles;
    private final ContentCaptionUpserter captionUpserter;
    private final AppSettingRepository settings;
    private final TransactionTemplate txTemplate;
    private final JobStopFlag stopFlag;

    public CaptionBackfillJob(RawMediaPageRepository mediaPages, RawProfileRepository profiles,
                              ContentCaptionUpserter captionUpserter, AppSettingRepository settings,
                              TransactionTemplate txTemplate, JobStopFlag stopFlag) {
        this.mediaPages = mediaPages;
        this.profiles = profiles;
        this.captionUpserter = captionUpserter;
        this.settings = settings;
        this.txTemplate = txTemplate;
        this.stopFlag = stopFlag;
    }

    public Stats run(TriggerType trigger) {
        log.info("캡션 백필 시작 (trigger={}) — raw 원형에서 소급 적재, 인스타 호출 없음", trigger);
        Stats media = backfillMediaPages();
        Stats profile = backfillProfiles();
        Stats total = new Stats(media.pages() + profile.pages(),
                media.captions() + profile.captions());
        log.info("캡션 백필 완료 — 페이지 {}건 처리, 캡션 {}건 적재", total.pages(), total.captions());
        return total;
    }

    /** raw_media_page: HIKER_V2_CLIPS·HIKER_V1_MEDIAS·HIKER_GQL_MEDIAS 전부 — source는 행에서 읽는다. */
    private Stats backfillMediaPages() {
        int pages = 0, captions = 0;
        long cursor = watermark(MEDIA_WATERMARK);
        while (!stopFlag.isRequested(JobName.CAPTION_BACKFILL)) {
            List<RawMediaPage> chunk =
                    mediaPages.findByIdGreaterThanOrderById(cursor, PageRequest.of(0, PAGE_CHUNK));
            if (chunk.isEmpty()) break;
            long last = chunk.get(chunk.size() - 1).getId();
            Integer n = txTemplate.execute(status -> {
                int c = 0;
                for (var page : chunk) {
                    c += upsertPage(page.getPayload(), page.getSource(), page.getCapturedAt());
                }
                return c;
            });
            captions += n == null ? 0 : n;
            pages += chunk.size();
            cursor = last;
            saveWatermark(MEDIA_WATERMARK, cursor);
            log.info("캡션 백필(media_page) — 누계 페이지 {}건 / 캡션 {}건 (cursor={})",
                    pages, captions, cursor);
        }
        return new Stats(pages, captions);
    }

    /** raw_profile: 내장 타임라인을 담는 SELF_GQL만 — 다른 source엔 게시물 배열이 없다. */
    private Stats backfillProfiles() {
        int pages = 0, captions = 0;
        long cursor = watermark(PROFILE_WATERMARK);
        while (!stopFlag.isRequested(JobName.CAPTION_BACKFILL)) {
            List<RawProfile> chunk = profiles.findBySourceAndIdGreaterThanOrderById(
                    RawSource.SELF_GQL, cursor, PageRequest.of(0, PAGE_CHUNK));
            if (chunk.isEmpty()) break;
            long last = chunk.get(chunk.size() - 1).getId();
            Integer n = txTemplate.execute(status -> {
                int c = 0;
                for (var p : chunk) {
                    c += upsertPage(p.getPayload(), RawSource.SELF_GQL, p.getCapturedAt());
                }
                return c;
            });
            captions += n == null ? 0 : n;
            pages += chunk.size();
            cursor = last;
            saveWatermark(PROFILE_WATERMARK, cursor);
            log.info("캡션 백필(profile) — 누계 페이지 {}건 / 캡션 {}건 (cursor={})",
                    pages, captions, cursor);
        }
        return new Stats(pages, captions);
    }

    /** 페이지 1건 처리 — 파싱 실패·형태 불일치는 그 페이지만 건너뛴다(원형은 남아 있으니 유실 아님). */
    private int upsertPage(Map<String, Object> payload, RawSource source, Instant capturedAt) {
        if (payload == null) return 0;
        try {
            return captionUpserter.upsert(
                    MediaItemExtractor.extract(payload, source), source, capturedAt);
        } catch (RuntimeException e) {
            log.warn("캡션 백필 페이지 건너뜀 (source={}): {}", source, e.toString());
            return 0;
        }
    }

    private long watermark(String key) {
        return settings.findById(key).map(AppSetting::getValue).map(Long::parseLong).orElse(0L);
    }

    private void saveWatermark(String key, long value) {
        settings.save(new AppSetting(key, Long.toString(value)));
    }
}
