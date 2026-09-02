package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.account.RateLimiter;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * 질의 API 계약 슬라이스 검증(FE 변경요청서 2026-08-28 §3·§5·§6·§7·§8) - 에이전트·후속질문 생성기는
 * mock이고, 여기서 보는 것은 요청 검증(accountIds·text)·소유 검증·대화 배선(생성/재사용/불일치)·
 * 프리셋 폴백·상한 429·로그 적재·응답 형태다. 킬 스위치 404는 별도 클래스({@link V1BrandAiMessagesDisabledTest}).
 *
 * <p>실행기는 동기 실행기(Runnable::run)로 갈아끼운다 - 슬라이스 테스트에서 별도 스레드로 넘기면
 * 타이밍 의존 플레이키가 생기고, 여기서 검증하려는 것은 비동기 배선이 아니라 계약이다.
 */
@WebMvcTest(controllers = V1BrandAiMessagesController.class,
		properties = {"was.cors.allowed-origins=http://localhost:3000", "monitoring.enabled=true",
				"monitoring.brand.ai.enabled=true"})
@Import({V1ExceptionAdvice.class, SecurityConfig.class,
		V1BrandAiMessagesControllerTest.TestSupportConfig.class})
class V1BrandAiMessagesControllerTest {

	@TestConfiguration
	static class TestSupportConfig {

		/** F2(2026-08-30 리뷰) 풀 거절 재현용 스위치 - true면 다음 execute 호출이
		 * {@link RejectedExecutionException}을 던진다. 기본은 동기 실행(Runnable::run)이라 대부분의
		 * 테스트는 그대로 결정론을 유지한다. */
		static final AtomicBoolean REJECT_NEXT = new AtomicBoolean(false);

		@Bean("brandAiChatExecutor")
		Executor brandAiChatExecutor() {
			return runnable -> {
				if (REJECT_NEXT.get()) {
					throw new RejectedExecutionException("테스트 강제 거절");
				}
				runnable.run();
			};
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}

	@Autowired
	MockMvc mockMvc;
	@MockitoBean
	BrandAiAgent agent;
	@MockitoBean
	AiChatQuota quota;
	@MockitoBean
	AiChatLogRepository logRepository;
	@MockitoBean
	AiConversationRepository conversationRepository;
	@MockitoBean
	BrandAiFollowUpGenerator followUpGenerator;
	@MockitoBean
	BrandLinkRepository linkRepository;
	@MockitoBean
	RateLimiter rateLimiter;
	@MockitoBean
	AppSettingRepository settingRepository;

	private static final long USER_ID = 7L;
	private static final long BRAND_ID = 45L;

	@BeforeEach
	void defaults() {
		// Mockito boolean 기본값이 false라 명시적으로 열어 주지 않으면 모든 테스트가 429가 된다
		given(rateLimiter.tryAcquire(anyString(), anyInt())).willReturn(true);
		// Optional 기본 스텁은 null이라 명시하지 않으면 perMinuteLimit()에서 NPE가 난다 - 기본값 폴백 경로로 통일
		given(settingRepository.findValue(anyString())).willReturn(Optional.empty());
		given(linkRepository.findActiveByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Optional.of(link()));
		given(logRepository.findByConversation(anyLong())).willReturn(List.of());
		given(followUpGenerator.generate(anyString(), anyString(), anyLong())).willReturn(List.of());
	}

	@AfterEach
	void resetExecutorSwitch() {
		// 테스트 간 상태 누수 방지 - 풀 거절 테스트가 스위치를 켠 채로 남기면 뒤따르는 테스트가 전부 429가 된다.
		TestSupportConfig.REJECT_NEXT.set(false);
	}

	private static BrandLinkRow link() {
		return new BrandLinkRow(1L, USER_ID, BRAND_ID, "brand", "own", 12, OffsetDateTime.now(), null);
	}

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(USER_ID, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	private static BrandAiAgent.AgentOutcome okOutcome(String answer) {
		return new BrandAiAgent.AgentOutcome(answer, List.of(), List.of(), List.of(), 100, 20, BRAND_ID,
				AiChatLogEntry.OUTCOME_OK, true, null);
	}

	private static BrandAiAgent.AgentOutcome limitReachedOutcome(String answer, String limitReached) {
		return new BrandAiAgent.AgentOutcome(answer, List.of(), List.of(), List.of(), 100, 20, BRAND_ID,
				AiChatLogEntry.OUTCOME_OK, true, limitReached);
	}

	private static String body(String accountId, String text) {
		return "{\"accountIds\":[\"" + accountId + "\"],\"text\":\"" + text + "\"}";
	}

	@Test
	void 신규_대화를_만들고_messageId를_돌려준다() throws Exception {
		given(conversationRepository.create(eq(USER_ID), eq(BRAND_ID), anyString())).willReturn(123L);
		given(agent.run(eq(USER_ID), any(), eq(BRAND_ID), any(), anyString(), any())).willReturn(okOutcome("3건이에요"));
		given(logRepository.insert(any())).willReturn(456L);
		given(followUpGenerator.generate(anyString(), anyString(), anyLong()))
				.willReturn(List.of(new AiMessagesResponse.FollowUp("더 볼까요?", "deepen"),
						new AiMessagesResponse.FollowUp("DM 초안 만들까요?", "action")));

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("45", "알려줘")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.conversationId").value("123"))
				.andExpect(jsonPath("$.data.messageId").value("456"))
				.andExpect(jsonPath("$.data.content").value("3건이에요"))
				.andExpect(jsonPath("$.data.followUps.length()").value(2))
				.andExpect(jsonPath("$.data.followUps[0].kind").value("deepen"))
				.andExpect(jsonPath("$.data.followUps[1].kind").value("action"));

		then(conversationRepository).should(times(1)).touch(123L);
		ArgumentCaptor<AiChatLogEntry> captor = ArgumentCaptor.forClass(AiChatLogEntry.class);
		then(logRepository).should(times(1)).insert(captor.capture());
		assertThat(captor.getValue().conversationId()).isEqualTo(123L);
		assertThat(captor.getValue().question()).isEqualTo("알려줘");
		assertThat(captor.getValue().outcome()).isEqualTo(AiChatLogEntry.OUTCOME_OK);
	}

	@Test
	void 기존_대화를_이어가면_새_대화를_만들지_않는다() throws Exception {
		given(conversationRepository.findOwnedActive(123L, USER_ID))
				.willReturn(Optional.of(new AiConversationRepository.ConversationRow(123L, BRAND_ID, "제목",
						OffsetDateTime.now())));
		given(agent.run(eq(USER_ID), any(), eq(BRAND_ID), any(), anyString(), any())).willReturn(okOutcome("답변"));
		given(logRepository.insert(any())).willReturn(789L);

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"conversationId\":\"123\",\"accountIds\":[\"45\"],\"text\":\"이어서\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.conversationId").value("123"));

		then(conversationRepository).should(times(0)).create(anyLong(), anyLong(), anyString());
	}

	@Test
	void 삭제된_대화는_404다() throws Exception {
		given(conversationRepository.findOwnedActive(999L, USER_ID)).willReturn(Optional.empty());

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"conversationId\":\"999\",\"accountIds\":[\"45\"],\"text\":\"이어서\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void 대화의_브랜드와_요청_브랜드가_다르면_409다() throws Exception {
		given(conversationRepository.findOwnedActive(123L, USER_ID))
				.willReturn(Optional.of(new AiConversationRepository.ConversationRow(123L, 999L, "제목",
						OffsetDateTime.now())));

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"conversationId\":\"123\",\"accountIds\":[\"45\"],\"text\":\"이어서\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("CONVERSATION_SCOPE_MISMATCH"));

		// F10(2026-08-30 리뷰) - 409로 끝난 요청은 분당 버킷을 소모하지 않는다(대화 참조 검증이
		// rate limiter보다 먼저 온다).
		then(rateLimiter).should(never()).tryAcquire(anyString(), anyInt());
	}

	/** F10(2026-08-30 리뷰) - 404(미소유 브랜드)도 마찬가지로 분당 버킷을 건드리지 않는다. */
	@Test
	void 미소유_브랜드_404도_분당_버킷을_소모하지_않는다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(USER_ID, 999L)).willReturn(Optional.empty());

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("999", "알려줘")))
				.andExpect(status().isNotFound());

		then(rateLimiter).should(never()).tryAcquire(anyString(), anyInt());
	}

	@Test
	void accountIds가_비면_400이다() throws Exception {
		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"accountIds\":[],\"text\":\"알려줘\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void accountIds가_2개면_400이다() throws Exception {
		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountIds\":[\"45\",\"46\"],\"text\":\"알려줘\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void accountIds가_비숫자면_400이다() throws Exception {
		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("abc", "알려줘")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 미소유_브랜드는_404다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(USER_ID, 999L)).willReturn(Optional.empty());

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("999", "알려줘")))
				.andExpect(status().isNotFound());
	}

	@Test
	void text가_비면_400이다() throws Exception {
		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("45", "")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 미등록_presetId는_무시되고_자유_질의로_처리된다() throws Exception {
		given(conversationRepository.create(eq(USER_ID), eq(BRAND_ID), anyString())).willReturn(123L);
		given(agent.run(eq(USER_ID), any(), eq(BRAND_ID), any(), anyString(), any())).willReturn(okOutcome("답변"));
		given(logRepository.insert(any())).willReturn(1L);

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountIds\":[\"45\"],\"presetId\":\"no_such_preset\",\"text\":\"알려줘\"}"))
				.andExpect(status().isOk());

		ArgumentCaptor<AiChatLogEntry> captor = ArgumentCaptor.forClass(AiChatLogEntry.class);
		then(logRepository).should(times(1)).insert(captor.capture());
		// presetId는 미등록이어도 로그엔 원본 그대로 남는다(계측 정본) - 지시문만 빈 문자열로 폴백한다.
		assertThat(captor.getValue().presetId()).isEqualTo("no_such_preset");
	}

	/** verified 플랜 선실행 주입(스펙 §6) - 플랜을 보유한 presetId면 컨트롤러가 그 플랜을
	 * {@link BrandAiPresets#planFor}로 조회해 agent.run에 그대로 실어 보낸다. */
	@Test
	void presetId가_플랜을_가지면_agent에_플랜이_전달된다() throws Exception {
		given(conversationRepository.create(eq(USER_ID), eq(BRAND_ID), anyString())).willReturn(123L);
		given(agent.run(eq(USER_ID), any(), eq(BRAND_ID), any(), anyString(), any())).willReturn(okOutcome("답변"));
		given(logRepository.insert(any())).willReturn(1L);

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountIds\":[\"45\"],\"presetId\":\"efficient_influencers\",\"text\":\"알려줘\"}"))
				.andExpect(status().isOk());

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<BrandAiPresets.PlannedCall>> planCaptor = ArgumentCaptor.forClass(List.class);
		then(agent).should(times(1)).run(eq(USER_ID), any(), eq(BRAND_ID), any(), anyString(), planCaptor.capture());
		assertThat(planCaptor.getValue()).isEqualTo(BrandAiPresets.planFor("efficient_influencers"));
		assertThat(planCaptor.getValue()).isNotEmpty();
	}

	@Test
	void 일일_상한_초과는_429다() throws Exception {
		// FE 계약(변경요청서 §9.1)에 맞춰 코드는 분당 한도와 동일한 RATE_LIMITED로 통일한다
		willThrow(new V1ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
				"RATE_LIMITED", "오늘 질문 가능 횟수(30회)를 모두 사용했어요.")).given(quota).requireWithinDailyLimit(anyLong());

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("45", "알려줘")))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
	}

	/** 이력 복원은 최근 6행(질문+답변 쌍)만 - 그보다 오래된 행은 에이전트에 전달되지 않는다(설계 §요구). */
	@Test
	void 이력은_최근_6행만_에이전트에_전달된다() throws Exception {
		given(conversationRepository.findOwnedActive(123L, USER_ID))
				.willReturn(Optional.of(new AiConversationRepository.ConversationRow(123L, BRAND_ID, "제목",
						OffsetDateTime.now())));
		List<AiChatLogRepository.ConversationMessageRow> rows = new java.util.ArrayList<>();
		ObjectMapper om = new ObjectMapper();
		for (int i = 1; i <= 8; i++) {
			rows.add(new AiChatLogRepository.ConversationMessageRow("질문" + i, "답변" + i, null,
					om.createArrayNode(), om.createArrayNode(), OffsetDateTime.now()));
		}
		given(logRepository.findByConversation(123L)).willReturn(rows);
		given(agent.run(eq(USER_ID), any(), eq(BRAND_ID), any(), anyString(), any())).willReturn(okOutcome("최종 답변"));
		given(logRepository.insert(any())).willReturn(1L);

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"conversationId\":\"123\",\"accountIds\":[\"45\"],\"text\":\"새 질문\"}"))
				.andExpect(status().isOk());

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<AiChatMessage>> contentsCaptor = ArgumentCaptor.forClass(List.class);
		then(agent).should(times(1)).run(eq(USER_ID), contentsCaptor.capture(), eq(BRAND_ID), any(), anyString(),
				any());
		List<AiChatMessage> contents = contentsCaptor.getValue();
		// 8행 중 최근 6행(질문+답변 쌍 12건) + 새 질문 1건 = 13건. 가장 오래된 질문1·질문2는 빠진다.
		assertThat(contents).hasSize(13);
		assertThat(contents.stream().map(AiChatMessage::content)).doesNotContain("질문1", "질문2")
				.contains("질문3", "질문8", "답변8", "새 질문");
	}

	@Test
	void LLM_실패는_502_재시도_안내이고_실패도_로그로_남는다() throws Exception {
		given(conversationRepository.create(eq(USER_ID), eq(BRAND_ID), anyString())).willReturn(123L);
		given(agent.run(eq(USER_ID), any(), eq(BRAND_ID), any(), anyString(), any()))
				.willThrow(new IllegalStateException("Vertex HTTP 500"));

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("45", "알려줘")))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));

		ArgumentCaptor<AiChatLogEntry> captor = ArgumentCaptor.forClass(AiChatLogEntry.class);
		then(logRepository).should(times(1)).insert(captor.capture());
		assertThat(captor.getValue().outcome()).isEqualTo(AiChatLogEntry.OUTCOME_LLM_FAILED);
		assertThat(captor.getValue().answer()).isNull();
		assertThat(captor.getValue().conversationId()).isEqualTo(123L);
		then(conversationRepository).should(times(1)).touch(123L);
	}

	/**
	 * F2(2026-08-30 리뷰) - 실행 풀이 접수 자체를 거절하면(동시 실행 상한 초과) 429 BUSY 응답만 돌아가고,
	 * 로그도 대화도 전혀 만들어지지 않는다 - 예전엔 대화를 먼저 만들고 풀에 제출해서, 거절되면 빈
	 * 대화가 고아로 남는 사고가 있었다.
	 */
	@Test
	void 실행_풀_거절은_로그와_대화를_전혀_만들지_않는다() throws Exception {
		TestSupportConfig.REJECT_NEXT.set(true);

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("45", "알려줘")))
				.andExpect(status().isTooManyRequests());

		then(conversationRepository).should(times(0)).create(anyLong(), anyLong(), anyString());
		then(conversationRepository).should(times(0)).touch(anyLong());
		then(logRepository).should(times(0)).insert(any());
	}

	/** F2 - 기존 대화를 이어가는 요청이 풀에서 거절돼도 그 대화의 touch조차 남기지 않는다. */
	@Test
	void 실행_풀_거절은_기존_대화도_건드리지_않는다() throws Exception {
		given(conversationRepository.findOwnedActive(123L, USER_ID))
				.willReturn(Optional.of(new AiConversationRepository.ConversationRow(123L, BRAND_ID, "제목",
						OffsetDateTime.now())));
		TestSupportConfig.REJECT_NEXT.set(true);

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"conversationId\":\"123\",\"accountIds\":[\"45\"],\"text\":\"이어서\"}"))
				.andExpect(status().isTooManyRequests());

		then(conversationRepository).should(times(0)).touch(anyLong());
		then(logRepository).should(times(0)).insert(any());
	}

	// --- SSE 협상(T4, FE 변경요청서 §3.2) ---

	/** perform() 결과가 실제로 비동기 처리에 들어갔을 때만 asyncDispatch로 마무리한다 - 이 슬라이스의
	 * 실행기가 동기(Runnable::run)라 SseEmitter가 컨트롤러 메서드 반환 전에 이미 완료될 수 있고, 그
	 * 경우 스프링이 async 상태로 전이하지 않을 수 있다(둘 다 유효한 경로). */
	private MvcResult completeAsyncIfStarted(MvcResult result) throws Exception {
		if (result.getRequest().isAsyncStarted()) {
			return mockMvc.perform(asyncDispatch(result)).andReturn();
		}
		return result;
	}

	@Test
	void SSE_요청은_meta_status_delta_done_순서로_이벤트를_보낸다() throws Exception {
		given(conversationRepository.create(eq(USER_ID), eq(BRAND_ID), anyString())).willReturn(321L);
		given(agent.run(eq(USER_ID), any(), eq(BRAND_ID), any(), anyString(), any(),
				any(BrandAiAgent.StreamListener.class), any())).willAnswer(invocation -> {
					BrandAiAgent.StreamListener listener = invocation.getArgument(6);
					listener.onThinking(1);
					listener.onToolCall("search_posts", 1);
					listener.onThinking(2);
					listener.onWriting();
					listener.onAnswerDelta("안녕하세요");
					return new BrandAiAgent.AgentOutcome("안녕하세요", List.of(), List.of(), List.of(), 10, 5, BRAND_ID,
							AiChatLogEntry.OUTCOME_OK, true, null);
				});
		given(logRepository.insert(any())).willReturn(999L);

		MvcResult started = mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal()))
						.with(csrf()).accept(MediaType.TEXT_EVENT_STREAM)
						.contentType(MediaType.APPLICATION_JSON).content(body("45", "안녕")))
				.andReturn();
		MvcResult result = completeAsyncIfStarted(started);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(body).contains("event:meta", "\"conversationId\":\"321\"")
				.contains("event:status", "search_posts")
				.contains("\"stage\":\"thinking\"", "\"label\":\"생각하는 중\"")
				.contains("\"stage\":\"tool\"", "\"label\":\"캡션 검색하는 중\"")
				.contains("\"stage\":\"writing\"", "\"label\":\"답변 정리하는 중\"")
				.contains("event:delta", "안녕하세요")
				.contains("event:done", "\"messageId\":\"999\"");
		// meta가 가장 먼저, done이 가장 나중에 나와야 한다.
		assertThat(body.indexOf("event:meta")).isLessThan(body.indexOf("event:status"));
		assertThat(body.indexOf("event:status")).isLessThan(body.indexOf("event:delta"));
		assertThat(body.indexOf("event:delta")).isLessThan(body.indexOf("event:done"));
		// thinking → tool → thinking → writing 순서(2026-09-02 진행 상태 확장).
		assertThat(body.indexOf("\"stage\":\"thinking\",\"index\":1"))
				.isLessThan(body.indexOf("\"stage\":\"tool\""));
		assertThat(body.indexOf("\"stage\":\"tool\""))
				.isLessThan(body.indexOf("\"stage\":\"thinking\",\"index\":2"));
		assertThat(body.indexOf("\"stage\":\"thinking\",\"index\":2"))
				.isLessThan(body.indexOf("\"stage\":\"writing\""));
		then(conversationRepository).should(times(1)).touch(321L);
	}

	/** SSE를 열기 전 검증 실패(여기선 미소유 브랜드 404)는 event-stream이 아니라 기존 JSON envelope
	 * 그대로 돌아온다(T4) - Accept: text/event-stream이어도 마찬가지다. */
	@Test
	void SSE_요청도_사전_검증_실패는_기존_JSON_에러_응답이다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(USER_ID, 999L)).willReturn(Optional.empty());

		MvcResult started = mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal()))
						.with(csrf()).accept(MediaType.TEXT_EVENT_STREAM)
						.contentType(MediaType.APPLICATION_JSON).content(body("999", "알려줘")))
				.andReturn();
		MvcResult result = completeAsyncIfStarted(started);

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
		assertThat(result.getResponse().getContentAsString()).contains("\"success\":false");
	}

	/** 에이전트 실행 자체가 예외로 실패하면 error 이벤트가 나가고 실패도 로그에 남는다(T4). */
	@Test
	void SSE_에이전트_실패는_error_이벤트를_보내고_로그에_남는다() throws Exception {
		given(conversationRepository.create(eq(USER_ID), eq(BRAND_ID), anyString())).willReturn(321L);
		given(agent.run(eq(USER_ID), any(), eq(BRAND_ID), any(), anyString(), any(),
				any(BrandAiAgent.StreamListener.class), any()))
				.willThrow(new IllegalStateException("Vertex HTTP 500"));

		MvcResult started = mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal()))
						.with(csrf()).accept(MediaType.TEXT_EVENT_STREAM)
						.contentType(MediaType.APPLICATION_JSON).content(body("45", "알려줘")))
				.andReturn();
		MvcResult result = completeAsyncIfStarted(started);

		String body = result.getResponse().getContentAsString();
		assertThat(body).contains("event:error", "AI_UNAVAILABLE");
		ArgumentCaptor<AiChatLogEntry> captor = ArgumentCaptor.forClass(AiChatLogEntry.class);
		then(logRepository).should(times(1)).insert(captor.capture());
		assertThat(captor.getValue().outcome()).isEqualTo(AiChatLogEntry.OUTCOME_LLM_FAILED);
		assertThat(captor.getValue().answer()).isNull();
	}

	/** 기존 JSON 경로(Accept 미지정 또는 application/json)는 SSE 도입 후에도 그대로 완결 응답이다 -
	 * 회귀 확인용. */
	@Test
	void Accept_미지정_요청은_여전히_완결_JSON_응답이다() throws Exception {
		given(conversationRepository.create(eq(USER_ID), eq(BRAND_ID), anyString())).willReturn(1L);
		given(agent.run(eq(USER_ID), any(), eq(BRAND_ID), any(), anyString(), any())).willReturn(okOutcome("답변"));
		given(logRepository.insert(any())).willReturn(2L);

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("45", "알려줘")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.content").value("답변"));

		then(agent).should(times(0)).run(anyLong(), any(), anyLong(), any(), anyString(), any(),
				any(BrandAiAgent.StreamListener.class), any());
	}

	// --- limitReached 구조 고지(Task 7, 스펙 §5) ---

	/** 예산 소진으로 강제 답변이 일어나면 그 원인을 응답 필드로 노출한다(FE additive). */
	@Test
	void 강제_답변이면_응답에_limitReached가_실린다() throws Exception {
		given(conversationRepository.create(eq(USER_ID), eq(BRAND_ID), anyString())).willReturn(123L);
		given(agent.run(eq(USER_ID), any(), eq(BRAND_ID), any(), anyString(), any()))
				.willReturn(limitReachedOutcome("예산 안에서 답변드려요", "budget"));
		given(logRepository.insert(any())).willReturn(456L);

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("45", "알려줘")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.limitReached").value("budget"));
	}

	/** 정상 완료면 limitReached는 null이다(값 자체가 "강제 답변 아님"의 신호). */
	@Test
	void 정상_완료면_limitReached가_null이다() throws Exception {
		given(conversationRepository.create(eq(USER_ID), eq(BRAND_ID), anyString())).willReturn(123L);
		given(agent.run(eq(USER_ID), any(), eq(BRAND_ID), any(), anyString(), any())).willReturn(okOutcome("답변"));
		given(logRepository.insert(any())).willReturn(456L);

		mockMvc.perform(post("/v1/brand-monitoring/ai/messages").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("45", "알려줘")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.limitReached").doesNotExist());
	}
}
