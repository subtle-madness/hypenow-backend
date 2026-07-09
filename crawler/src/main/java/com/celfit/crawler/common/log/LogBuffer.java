package com.celfit.crawler.common.log;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * com.celfit.crawler 로거의 최근 로그를 메모리에 보관해 UI 실행 로그 패널에 노출.
 * 프로세스 재시작 시 사라지는 휘발성 뷰 — 영속 이력은 crawl_run이 담당.
 */
@Component
public class LogBuffer extends AppenderBase<ILoggingEvent> {

    public record Line(String time, String level, String logger, String message) {}

    static final int MAX_LINES = 200;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Deque<Line> lines = new ConcurrentLinkedDeque<>();

    @PostConstruct
    void register() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) return;
        setContext(ctx);
        start();
        ctx.getLogger("com.celfit.crawler").addAppender(this);
    }

    @PreDestroy
    void unregister() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) {
            ctx.getLogger("com.celfit.crawler").detachAppender(this);
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
