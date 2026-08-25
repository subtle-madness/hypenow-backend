package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.mockito.Mockito.verifyNoInteractions;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawMediaPageRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.CategoryClass;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawMediaPage;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BeautyJobTest {

    static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    RawProfileRepository rawProfiles = mock(RawProfileRepository.class);
    RawMediaPageRepository rawMediaPages = mock(RawMediaPageRepository.class);
    BeautyJudge judge = mock(BeautyJudge.class);
    SettingsService settings = mock(SettingsService.class);
    // 실객체 주입 — execute()가 콜백을 즉시 실행하므로 배치 단위 트랜잭션 래핑을 그대로 재현한다.
    TransactionTemplate txTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));

    JobStopFlag stopFlag = new JobStopFlag();

    BeautyJob job = new BeautyJob(influencers, rawProfiles, rawMediaPages, judge, settings, stopFlag,
            java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC), txTemplate);

    @BeforeEach
    void wireSavePassthrough() {
        // 판정 적용이 이제 명시 save(detached merge)를 거치므로, 세터 결과 어서션이 save 이후에도
        // 그대로 성립하는지 확인하기 위한 passthrough.
        when(influencers.save(any(Influencer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(settings.beautyBatchLimit()).thenReturn(500);
    }

    static Influencer qualified(Long id, String username) {
        Influencer inf = new Influencer(username);
        inf.setId(id);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        return inf;
    }

    static RawProfile legacyProfile(Long influencerId, String fullName, String bio) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fullName", fullName);
        payload.put("biography", bio);
        return new RawProfile(influencerId, null, RawSource.LEGACY_ENVELOPE, payload, Instant.EPOCH);
    }

    /** 프로필에 캡션이 없는 소스(HIKER_MOBILE·DATALIKERS)의 폴백 재료 — 릴스 페이지 원형. */
    static RawMediaPage clipsPage(Long influencerId, String... captions) {
        List<Object> items = new ArrayList<>();
        for (String c : captions) {
            items.add(Map.of("media", Map.of("caption", Map.of("text", c))));
        }
        Map<String, Object> payload = Map.of("response", Map.of("items", items));
        return new RawMediaPage(influencerId, null, RawSource.HIKER_V2_CLIPS, payload, Instant.EPOCH);
    }

    @Test
    void 중지_요청이_있으면_판정_배치를_실행하지_않는다() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "메이크업", "코덕")));
        stopFlag.request(JobName.BEAUTY);

        job.run(TriggerType.MANUAL, false);

        verify(judge, never()).judge(any());
        assertThat(a.getBeauty()).isNull();
    }

    @Test
    void 판정_결과를_beauty_필드에_저장한다() {
        Influencer a = qualified(1L, "a");
        Influencer b = qualified(2L, "b");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class))).thenReturn(List.of(a, b));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "메이크업", "코덕")));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(2L))
                .thenReturn(Optional.of(legacyProfile(2L, "여행", "여행기")));
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, "메이크업 중심", null, null, null, null),
                new BeautyJudge.Verdict("b", BeautyClass.NOT_BEAUTY, "여행 계정", null, null, null, null)));

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.judgedBeauty()).isEqualTo(1);
        assertThat(s.judgedNotBeauty()).isEqualTo(1);
        assertThat(a.getBeauty()).isTrue();
        assertThat(a.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
        assertThat(a.getBeautyReason()).isEqualTo("메이크업 중심");
        assertThat(b.getBeauty()).isFalse();
        // 판정 시각 기록 — rejudge가 오래된 판정(실패 배치)부터 재시도하는 기준
        assertThat(a.getBeautyJudgedAt()).isEqualTo(NOW);
        assertThat(b.getBeautyJudgedAt()).isEqualTo(NOW);
    }

    @Test
    void 회사_판정은_beauty_company에_저장된다() {
        Influencer a = qualified(1L, "brand_acc");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "에텔랑화장품", "화장품 브랜드")));
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("brand_acc", BeautyClass.COMPANY, "화장품 브랜드 공식 계정", null, null, null, null)));

        job.run(TriggerType.MANUAL, false);

        assertThat(a.getBeauty()).isTrue();
        assertThat(a.getBeautyCompany()).isTrue();
    }

    @Test
    void 양축_판정은_뷰티와_FnB에_모두_적용되고_저장은_계정당_한_번이다() {
        // 2축 판정(스펙 2026-08-23 §2) — 한 응답으로 두 축을 각각 적용한다.
        Influencer a = qualified(1L, "a");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fullName", "이름");
        payload.put("biography", "bio");
        payload.put("latestPosts", List.of(Map.of("caption", "쿠션 발색"), Map.of("caption", "오늘의 레시피")));
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L)).thenReturn(Optional.of(
                new RawProfile(1L, null, RawSource.LEGACY_ENVELOPE, payload, Instant.EPOCH)));
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict(
                "a", BeautyClass.INFLUENCER, "뷰티 리뷰", "CAPTION",
                CategoryClass.INFLUENCER, "레시피 다수", "CAPTION")));

        var s = job.run(TriggerType.MANUAL, false);

        // 뷰티 축 — 기존 동작 그대로
        assertThat(a.getBeautyClass()).isEqualTo(BeautyClass.INFLUENCER);
        assertThat(a.getBeauty()).isTrue();
        assertThat(a.getBeautyBasis()).isEqualTo("CAPTION");
        assertThat(a.getBeautyJudgedAt()).isEqualTo(NOW);
        assertThat(a.getBeautyCaptionCount()).isEqualTo((short) 2);
        assertThat(s.judgedBeauty()).isEqualTo(1);
        // F&B 축 — classifyFnb가 파생 boolean까지 fnb_class와 일치시킨다
        assertThat(a.getFnbClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(a.getFnb()).isTrue();
        assertThat(a.getFnbCompany()).isFalse();
        assertThat(a.getFnbSource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
        assertThat(a.getFnbReason()).isEqualTo("레시피 다수");
        assertThat(a.getFnbBasis()).isEqualTo("CAPTION");
        assertThat(a.getFnbJudgedAt()).isEqualTo(NOW);
        assertThat(a.getFnbCaptionCount()).isEqualTo((short) 2);
        // 두 축을 적용해도 save는 계정당 1회 — 축별 중복 저장이 아니다
        verify(influencers, times(1)).save(a);
    }

    @Test
    void 뷰티축이_무응답이면_뷰티_필드는_건드리지_않고_FnB만_적용한다() {
        // 모델이 beauty 축만 무효값·누락으로 낸 경우 — 그 축은 미판정으로 남아 다음 실행에 재시도되고,
        // 유효한 fnb 축은 버리지 않는다(파서가 축별 null로 넘긴다).
        Influencer a = qualified(1L, "cafe_acc");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "카페", "성수동 카페")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict(
                "cafe_acc", null, null, null,
                CategoryClass.SERVICE, "카페 업장 공식 계정", "BIO")));

        var s = job.run(TriggerType.MANUAL, false);

        // 뷰티 축은 미판정 유지 — 어떤 필드도 쓰이지 않는다
        assertThat(a.getBeautyClass()).isNull();
        assertThat(a.getBeauty()).isNull();
        assertThat(a.getBeautySource()).isNull();
        assertThat(a.getBeautyJudgedAt()).isNull();
        assertThat(a.getBeautyCaptionCount()).isNull();
        assertThat(s.judgedBeauty() + s.judgedService() + s.judgedForeign() + s.judgedNotBeauty()).isZero();
        // F&B 축만 적용 — SERVICE는 파생 fnb=false(타깃 아님)
        assertThat(a.getFnbClass()).isEqualTo(CategoryClass.SERVICE);
        assertThat(a.getFnb()).isFalse();
        assertThat(a.getFnbCompany()).isFalse();
        assertThat(a.getFnbReason()).isEqualTo("카페 업장 공식 계정");
        assertThat(a.getFnbBasis()).isEqualTo("BIO");
        assertThat(a.getFnbJudgedAt()).isEqualTo(NOW);
        assertThat(a.getFnbCaptionCount()).isEqualTo((short) 0);
        verify(influencers, times(1)).save(a);
    }

    @Test
    void 백필_대상은_F앤B_축만_적용하고_뷰티_판정을_보존한다() {
        // F&B 백필(스펙 2026-08-23 §3) — 뷰티 판정이 이미 있는 계정을 F&B 축만 채우려고 부른다.
        // 모델이 뷰티 축을 같이 내도 무시해야 한다(MANUAL 판정이 덮이면 수동 교정이 날아간다).
        Influencer inf = qualified(1L, "kept");
        inf.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_MANUAL, "수동", null);
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of());
        when(influencers.findFnbBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(inf));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("kept",
                BeautyClass.NOT_BEAUTY, "모델이 딴소리", "BIO",
                CategoryClass.INFLUENCER, "레시피 계정", "CAPTION")));

        BeautyJob.Summary s = job.run(TriggerType.MANUAL, false);

        // 뷰티 축은 그대로 (MANUAL INFLUENCER 보존 — 모델의 NOT_BEAUTY 무시)
        assertThat(inf.getBeautyClass()).isEqualTo(BeautyClass.INFLUENCER);
        assertThat(inf.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_MANUAL);
        assertThat(inf.getBeautyReason()).isEqualTo("수동");
        assertThat(inf.getBeautyJudgedAt()).isNull();
        // F&B 축만 적용
        assertThat(inf.getFnbClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(inf.getFnb()).isTrue();
        assertThat(s.fnbApplied()).isEqualTo(1);
        assertThat(s.fnbPositive()).isEqualTo(1);
        assertThat(s.judgedBeauty()).isZero();
    }

    @Test
    void 신규_판정은_두_축을_모두_적용한다() {
        Influencer inf = qualified(2L, "fresh");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(inf));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(2L))
                .thenReturn(Optional.of(legacyProfile(2L, "이름", "bio")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("fresh",
                BeautyClass.NOT_BEAUTY, "뷰티 아님", "CAPTION",
                CategoryClass.INFLUENCER, "요리 계정", "CAPTION")));

        BeautyJob.Summary s = job.run(TriggerType.MANUAL, false);

        assertThat(inf.getBeautyClass()).isEqualTo(BeautyClass.NOT_BEAUTY);
        assertThat(inf.getFnbClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(s.judgedNotBeauty()).isEqualTo(1);
        assertThat(s.fnbApplied()).isEqualTo(1);
    }

    @Test
    void 백필_계정에_F앤B축이_무응답이면_저장도_카운터도_건드리지_않는다() {
        // 이월 Minor (a) — 백필 마스크로 뷰티 축을 버리는데 F&B 축까지 무응답이면 적용할 게 0개다.
        // 그래도 save·진행 카운터·계정별 로그가 돌면 "판정했다"는 흔적만 남아 진척을 오독한다.
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(BeautyJob.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            Influencer inf = qualified(1L, "bf_nofnb");
            inf.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_MANUAL, "수동", null);
            when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                    .thenReturn(List.of());
            when(influencers.findFnbBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                    .thenReturn(List.of(inf));
            when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                    .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
            when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                    .thenReturn(Optional.empty());
            when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("bf_nofnb",
                    BeautyClass.NOT_BEAUTY, "모델이 딴소리", "BIO", null, null, null)));

            BeautyJob.Summary s = job.run(TriggerType.MANUAL, false);

            // 뷰티 판정 보존 + F&B 미판정 유지 — 아무것도 안 바뀌었으니 save도 없다
            assertThat(inf.getBeautyClass()).isEqualTo(BeautyClass.INFLUENCER);
            assertThat(inf.getFnbClass()).isNull();
            verify(influencers, never()).save(any(Influencer.class));
            assertThat(s.fnbApplied()).isZero();
            assertThat(s.judgedBeauty() + s.judgedService() + s.judgedForeign() + s.judgedNotBeauty())
                    .isZero();
            assertThat(appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage))
                    .noneMatch(m -> m.contains("뷰티 판정 (") && m.contains("bf_nofnb"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void 백필은_신규가_쓴_만큼만_남은_한도로_호출된다() {
        // 이월 Minor (b) — 백필이 limit 전체로 조회되면 한 실행이 배치 한도의 2배까지 부풀 수 있다.
        when(settings.beautyBatchLimit()).thenReturn(10);
        Influencer fresh = qualified(1L, "new1");
        Influencer bf = qualified(2L, "bf1");
        bf.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(fresh));
        when(influencers.findFnbBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(bf));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(anyLong()))
                .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(judge.judge(any())).thenReturn(List.of());

        job.run(TriggerType.MANUAL, false);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(influencers).findFnbBackfillTargets(eq(InfluencerStatus.QUALIFIED), page.capture());
        assertThat(page.getValue()).isEqualTo(PageRequest.of(0, 9));  // 한도 10 − 신규 1
    }

    @Test
    void 백필은_신규가_한도를_다_채우면_호출되지_않는다() {
        // 신규(미판정)가 배치 한도를 다 쓰면 백필은 다음 실행 몫 — 한도를 넘겨 부풀리지 않는다
        when(settings.beautyBatchLimit()).thenReturn(1);
        Influencer fresh = qualified(3L, "only_new");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(fresh));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(3L))
                .thenReturn(Optional.of(legacyProfile(3L, "이름", "bio")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("only_new",
                BeautyClass.NOT_BEAUTY, "r", "BIO", CategoryClass.NONE, "r", "BIO")));

        job.run(TriggerType.MANUAL, false);

        verify(influencers, never()).findFnbBackfillTargets(any(), any());
    }

    @Test
    void 백필_계정의_로그는_보존_표기와_FnB_사유를_남긴다() {
        // 이월 Minor (a) — 뷰티 축을 적용하지 않은 건에 뷰티 사유가 찍히면 판정이 덮인 것으로 오독된다
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(BeautyJob.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            Influencer inf = qualified(1L, "bfacc");
            inf.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_MANUAL, "수동", null);
            when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                    .thenReturn(List.of());
            when(influencers.findFnbBackfillTargets(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                    .thenReturn(List.of(inf));
            when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                    .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
            when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                    .thenReturn(Optional.empty());
            when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("bfacc",
                    BeautyClass.NOT_BEAUTY, "모델이 딴소리", "BIO",
                    CategoryClass.SERVICE, "카페 업장", "BIO")));

            job.run(TriggerType.MANUAL, false);

            List<String> msgs = appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(msgs).anyMatch(m -> m.contains("bfacc")
                    && m.contains("뷰티 판정 보존")            // 덮지 않았음을 명시
                    && m.contains("F&B(매장·서비스)")          // 실제 적용된 축의 라벨
                    && m.contains("카페 업장")                 // 사유도 적용된 축의 것
                    && !m.contains("모델이 딴소리"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void 축_부분_무응답_건수를_경고로_남긴다() {
        // 이월 Minor (b) — 한 축만 무효인 응답이 늘어나면 프롬프트·파서를 의심해야 하는데,
        // 지금까지는 계정별 로그에만 흔적이 남아 배치 단위 추세가 보이지 않았다.
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(BeautyJob.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            Influencer a = qualified(1L, "acc1");
            Influencer b = qualified(2L, "acc2");
            when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                    .thenReturn(List.of(a, b));
            when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(anyLong()))
                    .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
            when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                    .thenReturn(Optional.empty());
            when(judge.judge(any())).thenReturn(List.of(
                    new BeautyJudge.Verdict("acc1", null, null, null,
                            CategoryClass.NONE, "비F&B", "BIO"),          // 뷰티축만 무효
                    new BeautyJudge.Verdict("acc2", BeautyClass.NOT_BEAUTY, "r", "BIO",
                            null, null, null)));                          // F&B축만 무효

            job.run(TriggerType.MANUAL, false);

            List<String> warns = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(warns).anyMatch(m -> m.contains("축 부분 무응답")
                    && m.contains("뷰티축 1건") && m.contains("F&B축 1건"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void 시작_요약과_계정별_판정_로그를_남긴다() {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(BeautyJob.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            Influencer a = qualified(1L, "a");
            Influencer b = qualified(2L, "b");
            when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                    .thenReturn(List.of(a, b));
            when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                    .thenReturn(Optional.of(legacyProfile(1L, "메이크업", "코덕")));
            when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(2L))
                    .thenReturn(Optional.of(legacyProfile(2L, "여행", "여행기")));
            when(judge.judge(any())).thenReturn(List.of(
                    new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, "메이크업 중심", null, null, null, null),
                    new BeautyJudge.Verdict("b", BeautyClass.NOT_BEAUTY, "여행 계정", null, null, null, null)));

            job.run(TriggerType.MANUAL, false);

            List<String> msgs = appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(msgs).anyMatch(m -> m.contains("뷰티 판정 시작") && m.contains("2명"));
            assertThat(msgs).anyMatch(m -> m.contains("(1/2) a — 뷰티") && m.contains("메이크업 중심"));
            assertThat(msgs).anyMatch(m -> m.contains("(2/2) b — 비뷰티") && m.contains("여행 계정"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void 응답에서_누락된_계정을_경고로_남긴다() {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(BeautyJob.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            Influencer a = qualified(1L, "acc1");
            Influencer b = qualified(2L, "acc2");
            when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any()))
                    .thenReturn(List.of(a, b));
            when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(anyLong()))
                    .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
            when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                    .thenReturn(Optional.empty());
            when(judge.judge(any())).thenReturn(List.of(
                    new BeautyJudge.Verdict("acc1", BeautyClass.INFLUENCER, "이유", "BIO", null, null, null)));

            job.run(TriggerType.MANUAL, false);

            List<String> warns = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(warns).anyMatch(m -> m.contains("누락") && m.contains("acc2"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void 응답_중복을_경고로_남긴다() {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(BeautyJob.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            Influencer a = qualified(1L, "acc1");
            when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any()))
                    .thenReturn(List.of(a));
            when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(anyLong()))
                    .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
            when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(anyLong(), any()))
                    .thenReturn(Optional.empty());
            when(judge.judge(any())).thenReturn(List.of(
                    new BeautyJudge.Verdict("acc1", BeautyClass.INFLUENCER, "이유", "BIO", null, null, null),
                    new BeautyJudge.Verdict("acc1", BeautyClass.NOT_BEAUTY, "다른 이유", "BIO", null, null, null)));

            job.run(TriggerType.MANUAL, false);

            List<String> warns = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(warns).anyMatch(m -> m.contains("중복") && m.contains("acc1"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void 카드에_최근_캡션을_개수_제한과_길이_절단으로_담는다() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class))).thenReturn(List.of(a));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fullName", "이름");
        payload.put("latestPosts", List.of(
                Map.of("caption", "긴캡션".repeat(50)),  // 150자 → CAPTION_MAX_CHARS로 절단
                Map.of("caption", "둘"), Map.of("caption", "셋"), Map.of("caption", "넷"),
                Map.of("caption", "다섯"), Map.of("caption", "여섯"), Map.of("caption", "일곱")));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L)).thenReturn(Optional.of(
                new RawProfile(1L, null, RawSource.LEGACY_ENVELOPE, payload, Instant.EPOCH)));
        when(judge.judge(any())).thenAnswer(inv -> {
            List<BeautyJudge.ProfileCard> cards = inv.getArgument(0);
            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).captions()).containsExactly(
                    "긴캡션".repeat(50).substring(0, BeautyJob.CAPTION_MAX_CHARS), "둘", "셋", "넷", "다섯");
            return List.of(new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, "ok", null, null, null, null));
        });

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.judgedBeauty()).isEqualTo(1);
    }

    @Test
    void 캡션_절단이_이모지_서로게이트_쌍을_반쪽으로_자르지_않는다() {
        // 절단 경계(100번째 문자)가 이모지(UTF-16 서로게이트 쌍, 2문자) 한가운데면 substring이
        // high surrogate 반쪽만 남겨 깨진 문자열이 되고, Anthropic API가 요청 JSON을 400으로 거부한다
        // (운영 실측 2026-07-21: "no low surrogate in string" — 배치 10개 중 9개 실패).
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class))).thenReturn(List.of(a));
        String caption = "가".repeat(BeautyJob.CAPTION_MAX_CHARS - 1) + "💄뒤";  // 99자 + 쌍(2자) + 1자
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fullName", "이름");
        payload.put("latestPosts", List.of(Map.of("caption", caption)));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L)).thenReturn(Optional.of(
                new RawProfile(1L, null, RawSource.LEGACY_ENVELOPE, payload, Instant.EPOCH)));
        when(judge.judge(any())).thenAnswer(inv -> {
            List<BeautyJudge.ProfileCard> cards = inv.getArgument(0);
            String trimmed = cards.get(0).captions().get(0);
            // 쌍을 통째로 버리고 99자에서 끊는다 — 반쪽 서로게이트가 남으면 안 된다
            assertThat(trimmed).isEqualTo("가".repeat(BeautyJob.CAPTION_MAX_CHARS - 1));
            assertThat(Character.isHighSurrogate(trimmed.charAt(trimmed.length() - 1))).isFalse();
            return List.of(new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, "ok", null, null, null, null));
        });

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.judgedBeauty()).isEqualTo(1);
    }

    @Test
    void raw_profile이_없으면_스킵하고_beauty는_NULL_유지() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class))).thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L)).thenReturn(Optional.empty());

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.skippedNoProfile()).isEqualTo(1);
        assertThat(a.getBeauty()).isNull();
    }

    @Test
    void 배치_실패는_격리되고_해당_계정은_NULL로_남아_재시도된다() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class))).thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "x", "y")));
        when(judge.judge(any())).thenThrow(new ApifyException("CLI 실패"));

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.failedBatches()).isEqualTo(1);
        assertThat(a.getBeauty()).isNull();
    }

    @Test
    void 응답이_지어낸_username은_무시한다() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class))).thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "x", "y")));
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("ghost", BeautyClass.INFLUENCER, "?", null, null, null, null)));

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.judgedBeauty()).isZero();
        assertThat(a.getBeauty()).isNull();
    }

    @Test
    void 배치_한도만큼만_선정하고_rejudge는_남은_한도만_채운다() {
        when(settings.beautyBatchLimit()).thenReturn(3);
        Influencer a = qualified(1L, "a");
        Influencer b = qualified(2L, "b");
        Influencer c = qualified(3L, "c");
        // 미판정 쿼리가 한도 3으로 호출되고, rejudge 쿼리는 남은 한도 2만 요청한다
        when(influencers.findByStatusAndBeautyIsNull(
                InfluencerStatus.QUALIFIED, PageRequest.of(0, 3, Sort.by("id")))).thenReturn(List.of(a));
        when(influencers.findRejudgeTargets(
                InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE,
                PageRequest.of(0, 2))).thenReturn(List.of(b, c));  // 정렬은 쿼리(오래된 판정 우선) 몫
        for (long id = 1; id <= 3; id++) {
            when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(id))
                    .thenReturn(Optional.of(legacyProfile(id, "이름", "bio")));
        }
        when(judge.judge(any())).thenAnswer(inv -> {
            List<BeautyJudge.ProfileCard> cards = inv.getArgument(0);
            assertThat(cards).hasSize(3);
            return cards.stream().map(cd -> new BeautyJudge.Verdict(cd.username(), BeautyClass.INFLUENCER, "ok", null, null, null, null)).toList();
        });

        var s = job.run(TriggerType.MANUAL, true);

        assertThat(s.judgedBeauty()).isEqualTo(3);
    }

    @Test
    void 미판정만으로_한도를_다_채우면_rejudge_쿼리를_호출하지_않는다() {
        when(settings.beautyBatchLimit()).thenReturn(1);
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, "ok", null, null, null, null)));

        job.run(TriggerType.MANUAL, true);

        verify(influencers, never()).findRejudgeTargets(any(), any(), any());
    }

    @Test
    void rejudge는_재료_갱신된_비뷰티_선정_쿼리를_CLAUDE_판정분으로만_호출한다() {
        // 비뷰티·재료 갱신·MANUAL 제외 조건 자체는 쿼리 몫 — BeautySelectionIntegrationTest가 고정한다.
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class))).thenReturn(List.of());
        when(influencers.findRejudgeTargets(
                eq(InfluencerStatus.QUALIFIED), eq(Influencer.BEAUTY_SOURCE_CLAUDE), any(Pageable.class)))
                .thenReturn(List.of());

        job.run(TriggerType.MANUAL, true);

        verify(influencers).findRejudgeTargets(
                eq(InfluencerStatus.QUALIFIED), eq(Influencer.BEAUTY_SOURCE_CLAUDE), any(Pageable.class));
    }

    @Test
    void rejudge가_유사_발굴_이력을_초기화하지_않는다() {
        // similar_processed_at은 SIMILAR 시드 소진 표식 — 재판정이 이걸 지우면 판정 전후로
        // similar를 다시 돌릴 때 같은 시드가 또 수확된다. 재판정은 beauty 3필드만 갱신해야 한다.
        Influencer a = qualified(1L, "a");
        a.setBeauty(true);
        a.setBeautySource(Influencer.BEAUTY_SOURCE_CLAUDE);
        Instant harvested = Instant.parse("2026-07-10T00:00:00Z");
        a.setSimilarProcessedAt(harvested);
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of());
        when(influencers.findRejudgeTargets(
                eq(InfluencerStatus.QUALIFIED), eq(Influencer.BEAUTY_SOURCE_CLAUDE), any(Pageable.class)))
                .thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "메이크업", "코덕")));
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, "재판정", null, null, null, null)));

        job.run(TriggerType.MANUAL, true);

        assertThat(a.getSimilarProcessedAt()).isEqualTo(harvested);
    }

    @Test
    void 두_선정_쿼리에_같은_인플루언서가_겹치면_한_번만_판정한다() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class))).thenReturn(List.of(a));
        when(influencers.findRejudgeTargets(
                eq(InfluencerStatus.QUALIFIED), eq(Influencer.BEAUTY_SOURCE_CLAUDE), any(Pageable.class)))
                .thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "메이크업", "코덕")));
        when(judge.judge(any())).thenAnswer(inv -> {
            List<BeautyJudge.ProfileCard> cards = inv.getArgument(0);
            assertThat(cards).hasSize(1);
            return List.of(new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, "메이크업", null, null, null, null));
        });

        var s = job.run(TriggerType.MANUAL, true);

        assertThat(s.judgedBeauty()).isEqualTo(1);
    }

    @Test
    void 한_응답_안에서_같은_username이_중복되면_첫_값만_적용하고_카운터도_한_번만_센다() {
        // acc1에 대해 서로 다른 두 판정(INFLUENCER → NOT_BEAUTY)이 온 상황 — 첫 값 채택 규칙 검증.
        Influencer a = qualified(1L, "acc1");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("acc1", BeautyClass.INFLUENCER, "첫 판정", null, null, null, null),
                new BeautyJudge.Verdict("acc1", BeautyClass.NOT_BEAUTY, "두번째 판정", null, null, null, null)));

        var s = job.run(TriggerType.MANUAL, false);

        // 검증 A: 카운터 합계가 실제 판정 계정 수(1)와 일치 — 중복분이 이중 계상되지 않는다
        assertThat(s.judgedBeauty() + s.judgedService() + s.judgedForeign() + s.judgedNotBeauty()).isEqualTo(1);
        // 검증 B: 첫 값(INFLUENCER)이 채택된다 — beauty_class와 카운터 둘 다
        assertThat(s.judgedBeauty()).isEqualTo(1);
        assertThat(s.judgedNotBeauty()).isEqualTo(0);
        assertThat(a.getBeautyClass()).isEqualTo(BeautyClass.INFLUENCER);
        assertThat(a.getBeautyReason()).isEqualTo("첫 판정");
    }

    @Test
    void 판정_결과가_beauty_class와_파생_boolean으로_저장되고_Summary가_구분_집계한다() {
        Influencer inf1 = qualified(1L, "inf1");
        Influencer com1 = qualified(2L, "com1");
        Influencer svc1 = qualified(3L, "svc1");
        Influencer no1 = qualified(4L, "no1");
        Influencer for1 = qualified(5L, "for1");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any(Pageable.class)))
                .thenReturn(List.of(inf1, com1, svc1, no1, for1));
        for (long id = 1; id <= 5; id++) {
            when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(id))
                    .thenReturn(Optional.of(legacyProfile(id, "이름", "bio")));
        }
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("inf1", BeautyClass.INFLUENCER, "메이크업 크리에이터", null, null, null, null),
                new BeautyJudge.Verdict("com1", BeautyClass.COMPANY, "화장품 브랜드", null, null, null, null),
                new BeautyJudge.Verdict("svc1", BeautyClass.BEAUTY_SERVICE, "피부과 시술 홍보", null, null, null, null),
                new BeautyJudge.Verdict("no1", BeautyClass.NOT_BEAUTY, "여행 계정", null, null, null, null),
                new BeautyJudge.Verdict("for1", BeautyClass.FOREIGN_INFLUENCER, "영어 뷰티 콘텐츠", null, null, null, null)));

        BeautyJob.Summary s = job.run(TriggerType.MANUAL, false);

        assertThat(s.judgedBeauty()).isEqualTo(2);      // INFLUENCER + COMPANY
        assertThat(s.judgedService()).isEqualTo(1);     // BEAUTY_SERVICE
        assertThat(s.judgedForeign()).isEqualTo(1);     // FOREIGN_INFLUENCER
        assertThat(s.judgedNotBeauty()).isEqualTo(1);   // NOT_BEAUTY

        // FOREIGN_INFLUENCER — beauty_class 원본 저장 + beauty=false 파생(수집·시드 자동 제외)
        assertThat(for1.getBeautyClass()).isEqualTo(BeautyClass.FOREIGN_INFLUENCER);
        assertThat(for1.getBeauty()).isFalse();
        assertThat(for1.getBeautyCompany()).isFalse();

        // BEAUTY_SERVICE — beauty_class 원본 저장 + beauty=false 파생(수집·시드 자동 제외)
        assertThat(svc1.getBeautyClass()).isEqualTo(BeautyClass.BEAUTY_SERVICE);
        assertThat(svc1.getBeauty()).isFalse();
        assertThat(svc1.getBeautyCompany()).isFalse();
        assertThat(svc1.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
        assertThat(svc1.getBeautyJudgedAt()).isEqualTo(NOW);
        // 인플루언서·회사도 파생 boolean이 기존 규칙과 동일
        assertThat(inf1.getBeauty()).isTrue();
        assertThat(inf1.getBeautyCompany()).isFalse();
        assertThat(com1.getBeauty()).isTrue();
        assertThat(com1.getBeautyCompany()).isTrue();
        assertThat(no1.getBeauty()).isFalse();
    }

    @Test
    void 프로필에_캡션이_없으면_릴스_페이지_캡션을_쓴다() {
        Influencer inf = qualified(1L, "acc1");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any()))
                .thenReturn(List.of(inf));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(1L, RawSource.HIKER_V2_CLIPS))
                .thenReturn(Optional.of(clipsPage(1L, "스킨케어 루틴", "쿠션 발색")));
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("acc1", BeautyClass.INFLUENCER, "뷰티 캡션", "CAPTION", null, null, null)));

        job.run(TriggerType.MANUAL, false);

        ArgumentCaptor<List<BeautyJudge.ProfileCard>> cards = ArgumentCaptor.forClass(List.class);
        verify(judge).judge(cards.capture());
        assertThat(cards.getValue().getFirst().captions())
                .containsExactly("스킨케어 루틴", "쿠션 발색");
        assertThat(inf.getBeautyCaptionCount()).isEqualTo((short) 2);
    }

    @Test
    void 프로필_캡션이_있으면_릴스_페이지를_조회하지_않는다() {
        Influencer inf = qualified(1L, "acc1");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fullName", "이름");
        payload.put("biography", "bio");
        payload.put("latestPosts", List.of(Map.of("caption", "프로필 캡션")));
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any()))
                .thenReturn(List.of(inf));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L)).thenReturn(Optional.of(
                new RawProfile(1L, null, RawSource.LEGACY_ENVELOPE, payload, Instant.EPOCH)));
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("acc1", BeautyClass.INFLUENCER, "이유", "CAPTION", null, null, null)));

        job.run(TriggerType.MANUAL, false);

        verifyNoInteractions(rawMediaPages);
        assertThat(inf.getBeautyCaptionCount()).isEqualTo((short) 1);
    }

    @Test
    void 캡션을_어디서도_못_구하면_0으로_기록한다() {
        Influencer inf = qualified(1L, "acc1");
        when(influencers.findByStatusAndBeautyIsNull(eq(InfluencerStatus.QUALIFIED), any()))
                .thenReturn(List.of(inf));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "이름", "bio")));
        when(rawMediaPages.findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(1L, RawSource.HIKER_V2_CLIPS))
                .thenReturn(Optional.empty());
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("acc1", BeautyClass.INFLUENCER, "이유", "CATEGORY_ONLY", null, null, null)));

        job.run(TriggerType.MANUAL, false);

        assertThat(inf.getBeautyCaptionCount()).isEqualTo((short) 0);
    }
}
