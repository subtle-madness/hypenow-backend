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
import java.util.ArrayList;
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
 *
 * <p>청크 안에서 파싱(DB 무접촉)과 쓰기(트랜잭션 안)를 분리한다 — 파싱 실패를 트랜잭션 안에서
 * 삼키면, SQL 레벨 실패가 그 트랜잭션을 aborted 상태로 만들어도 예외가 호출자에 전달되지 않고
 * Postgres가 COMMIT을 조용히 ROLLBACK으로 치환한다(응답 태그만 보고는 구분 불가). 그러면 이미
 * 성공한 캡션까지 그 청크 전체가 유실되는데 워터마크는 무조건 전진해 재실행으로도 복구 불가가
 * 된다. 그래서 형태 불일치만 안전하게 건너뛰는 파싱을 트랜잭션 밖에서 먼저 끝내고, 쓰기는
 * 예외를 삼키지 않는 트랜잭션 안에서 워터마크 저장과 함께 원자적으로 수행한다.
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

            // 파싱은 트랜잭션 밖에서 — DB를 만지지 않으므로 형태 불일치인 페이지만 안전하게 건너뛴다.
            // (트랜잭션 안에서 삼키면 SQL 실패 시 Postgres가 커밋을 조용히 ROLLBACK으로 치환해
            //  그 청크에서 이미 성공한 캡션까지 유실되고, 워터마크는 전진해 영구 복구 불가가 된다.)
            List<PageItems> parsed = new ArrayList<>();
            for (RawMediaPage page : chunk) {
                parseInto(parsed, page.getPayload(), page.getSource(), page.getCapturedAt());
            }
            long last = chunk.get(chunk.size() - 1).getId();
            // 쓰기는 트랜잭션 안에서 — SQL 실패는 삼키지 않는다(청크 롤백 + 워터마크 미전진 =
            // 요란한 실패로 드러나고 재실행이 그 청크를 다시 처리한다).
            Integer n = txTemplate.execute(status -> {
                int c = 0;
                for (PageItems p : parsed) {
                    c += captionUpserter.upsert(p.items(), p.source(), p.capturedAt());
                }
                saveWatermark(MEDIA_WATERMARK, last);   // 캡션과 워터마크를 원자적으로
                return c;
            });
            captions += n == null ? 0 : n;
            pages += chunk.size();
            cursor = last;
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

            List<PageItems> parsed = new ArrayList<>();
            for (RawProfile p : chunk) {
                parseInto(parsed, p.getPayload(), RawSource.SELF_GQL, p.getCapturedAt());
            }
            long last = chunk.get(chunk.size() - 1).getId();
            Integer n = txTemplate.execute(status -> {
                int c = 0;
                for (PageItems p : parsed) {
                    c += captionUpserter.upsert(p.items(), p.source(), p.capturedAt());
                }
                saveWatermark(PROFILE_WATERMARK, last);   // 캡션과 워터마크를 원자적으로
                return c;
            });
            captions += n == null ? 0 : n;
            pages += chunk.size();
            cursor = last;
            log.info("캡션 백필(profile) — 누계 페이지 {}건 / 캡션 {}건 (cursor={})",
                    pages, captions, cursor);
        }
        return new Stats(pages, captions);
    }

    /** 트랜잭션 밖에서 파싱한 페이지 1건의 결과. */
    private record PageItems(List<MediaItemExtractor.MediaItem> items, RawSource source,
                             Instant capturedAt) {}

    /**
     * 페이지 1건 파싱 — DB를 만지지 않는다. 형태 불일치·파싱 실패는 그 페이지만 건너뛴다
     * (원형은 raw 테이블에 남아 있으니 유실이 아니다). 빈 결과는 담지 않는다.
     */
    private void parseInto(List<PageItems> out, Map<String, Object> payload, RawSource source,
                           Instant capturedAt) {
        if (payload == null) return;
        try {
            var items = MediaItemExtractor.extract(payload, source);
            if (!items.isEmpty()) out.add(new PageItems(items, source, capturedAt));
        } catch (RuntimeException e) {
            log.warn("캡션 백필 페이지 파싱 실패 — 건너뜀 (source={}): {}", source, e.toString());
        }
    }

    private long watermark(String key) {
        return settings.findById(key).map(AppSetting::getValue).map(Long::parseLong).orElse(0L);
    }

    private void saveWatermark(String key, long value) {
        settings.save(new AppSetting(key, Long.toString(value)));
    }
}
