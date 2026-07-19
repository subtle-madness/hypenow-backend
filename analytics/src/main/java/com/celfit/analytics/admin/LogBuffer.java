package com.celfit.analytics.admin;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.LoggerFactory;

/**
 * com.celfit.analytics 로거의 최근 로그를 메모리에 보관해 UI 실행 로그 패널에 노출.
 * 프로세스 재시작 시 사라지는 휘발성 뷰 — 영속 이력 테이블은 두지 않기로 결정(스펙 §2).
 * crawler LogBuffer와 의도된 중복 (§4-4 모듈 공유 금지).
 */
public class LogBuffer extends AppenderBase<ILoggingEvent> {

	public record Line(String time, String level, String logger, String message) {}

	static final int MAX_LINES = 200;
	private static final DateTimeFormatter TIME =
			DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	private final Deque<Line> lines = new ConcurrentLinkedDeque<>();

	public void register() {
		if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) return;
		setContext(ctx);
		start();
		ctx.getLogger("com.celfit.analytics").addAppender(this);
	}

	public void unregister() {
		if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) {
			ctx.getLogger("com.celfit.analytics").detachAppender(this);
		}
		stop();
	}

	@Override
	protected void append(ILoggingEvent event) {
		String message = event.getFormattedMessage();
		if (event.getThrowableProxy() != null) {
			message += " — " + event.getThrowableProxy().getClassName()
					+ ": " + event.getThrowableProxy().getMessage();
		}
		lines.addFirst(new Line(TIME.format(Instant.ofEpochMilli(event.getTimeStamp())),
				event.getLevel().toString(), shortLogger(event.getLoggerName()), message));
		while (lines.size() > MAX_LINES) lines.pollLast();
	}

	/** 최신순. */
	public List<Line> lines() {
		return List.copyOf(lines);
	}

	private static String shortLogger(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? name : name.substring(dot + 1);
	}
}
