package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.in.TriggerJobUseCase;
import com.celfit.crawler.crawling.application.port.in.TriggerJobUseCase.StopResult;
import com.celfit.crawler.crawling.application.port.in.TriggerJobUseCase.TriggerResult;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class UiJobControllerTest {

    TriggerJobUseCase jobService = mock(TriggerJobUseCase.class);
    UiJobController controller = new UiJobController(jobService);

    @Test
    void 실행_중인_잡의_중지_요청은_요청_접수_메시지를_남긴다() {
        when(jobService.stop(JobName.COLLECT)).thenReturn(StopResult.STOP_REQUESTED);
        var ra = new RedirectAttributesModelMap();

        String view = controller.stop(JobName.COLLECT, ra);

        assertThat(view).isEqualTo("redirect:/ui");
        assertThat(ra.getFlashAttributes().get("message").toString()).contains("중지 요청");
    }

    @Test
    void 실행_중이_아닌_잡의_중지는_실행_중_아님_메시지를_남긴다() {
        when(jobService.stop(JobName.REELS)).thenReturn(StopResult.NOT_RUNNING);
        var ra = new RedirectAttributesModelMap();

        controller.stop(JobName.REELS, ra);

        assertThat(ra.getFlashAttributes().get("message").toString()).contains("실행 중이 아닙니다");
    }

    @Test
    void 잡_트리거는_대시보드로_리다이렉트하고_시작_메시지를_남긴다() {
        when(jobService.trigger(JobName.COLLECT, TriggerType.MANUAL)).thenReturn(TriggerResult.ACCEPTED);
        var ra = new RedirectAttributesModelMap();

        String view = controller.collect(ra);

        assertThat(view).isEqualTo("redirect:/ui");
        assertThat(ra.getFlashAttributes().get("message").toString()).contains("실행 시작");
    }
}
