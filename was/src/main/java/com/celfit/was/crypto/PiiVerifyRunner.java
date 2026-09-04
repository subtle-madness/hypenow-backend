package com.celfit.was.crypto;

import com.celfit.was.auth.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * PII 정합 검증(트랙 A PR 2 게이트) — 평문 vs decrypt(enc), bidx vs HMAC(normalize(평문))를 전 행 대조.
 * 아무것도 바꾸지 않는다. 불일치 행은 id만 로그(평문·암호문 금지 — password_resets는 PK가 email이라
 * md5(email) 앞 8자를 대체 식별자로 로그한다). --crypto.verify=true 기동 시 1회.
 * 읽기 전환 배포 직전 0건 확인용 — 불일치 행은 읽기 전환 후 로그인 불가·오표시가 되므로 백필 재실행
 * (해당 행 enc를 NULL로 되돌린 뒤 --crypto.backfill=true)으로 먼저 고친다.
 *
 * <p>합계>0이면 로그 등급을 ERROR로 올린다(기본 INFO). {@code crypto.verify.fail-fast=true}
 * (기본 false, 운영·스테이징 배포 게이트 스크립트 전용 — deploy/README.md §6-3)를 함께 주면
 * 합계>0일 때 {@link SpringApplication#exit}로 비정상 종료(코드 1)해 사람이 로그를 읽지 않아도
 * 스크립트가 종료코드만으로 게이트 통과 여부를 판별할 수 있게 한다. 종료 여부 판정
 * ({@link #shouldExitAbnormally})은 순수 함수로 분리해 ApplicationContext 없이 단위 테스트한다 —
 * 실제 종료 동작({@link #exitAbnormally})은 통합 테스트에서 호출하면 테스트 JVM이 죽으므로
 * failFast=false 경로만 통합 테스트로 검증한다.
 */
@Component
@ConditionalOnProperty(name = "crypto.verify", havingValue = "true")
public class PiiVerifyRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(PiiVerifyRunner.class);

	public record VerifyReport(Map<String, Integer> encMismatch, Map<String, Integer> bidxMismatch) {
		public int total() {
			return encMismatch.values().stream().mapToInt(Integer::intValue).sum()
					+ bidxMismatch.values().stream().mapToInt(Integer::intValue).sum();
		}
	}

	/**
	 * password_resets는 PK가 email이라 그대로 id로 조회하면 WARN 로그(table+id)에 평문 이메일이
	 * 노출된다 — md5(email) 앞 8자를 로그 전용 비식별 대체 식별자로 쓴다(대조 자체는 email 컬럼으로).
	 * 패키지-프라이빗으로 노출해 테스트가 "email AS id로 노출하지 않는다"를 코드로 고정한다.
	 */
	static final String PASSWORD_RESETS_SQL =
			"SELECT left(md5(email), 8) AS id, email, email_enc, email_bidx FROM app.password_resets";

	private final JdbcClient jdbc;
	private final FieldCipher cipher;
	private final boolean failFast;
	private final ConfigurableApplicationContext context;

	public PiiVerifyRunner(JdbcClient jdbc, FieldCipher cipher,
			@Value("${crypto.verify.fail-fast:false}") boolean failFast,
			ConfigurableApplicationContext context) {
		this.jdbc = jdbc;
		this.cipher = cipher;
		this.failFast = failFast;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) {
		VerifyReport r = verifyAll();
		int total = r.total();
		if (total > 0) {
			log.error("PII 정합 검증 실패 — enc 불일치={}, bidx 불일치={}, 합계={}", r.encMismatch(), r.bidxMismatch(), total);
		} else {
			log.info("PII 정합 검증 — enc 불일치={}, bidx 불일치={}, 합계={}", r.encMismatch(), r.bidxMismatch(), total);
		}
		if (shouldExitAbnormally(failFast, total)) {
			exitAbnormally();
		}
	}

	/**
	 * fail-fast 종료 여부 결정만 담당하는 순수 함수 — ApplicationContext 없이 단위 테스트 대상.
	 * {@link #run}이 이 함수와 {@link #exitAbnormally}를 분리 호출하므로, failFast=false 경로는
	 * 통합 테스트에서 {@code context}에 null을 넣고 {@link #run}을 그대로 호출해도 안전하다.
	 */
	static boolean shouldExitAbnormally(boolean failFast, int total) {
		return failFast && total > 0;
	}

	/** 종료 액션 자체 — 배포 게이트 스크립트가 종료코드로 합계>0을 판별할 수 있게 코드 1로 종료한다. */
	private void exitAbnormally() {
		int code = SpringApplication.exit(context, () -> 1);
		System.exit(code);
	}

	public VerifyReport verifyAll() {
		Map<String, Integer> enc = new LinkedHashMap<>();
		Map<String, Integer> bidx = new LinkedHashMap<>();
		verifyTable("users", "SELECT id, email, name, nickname, phone_number, email_enc, name_enc, nickname_enc, phone_number_enc, email_bidx FROM app.users",
				List.of("email", "name", "nickname", "phone_number"), true, true, enc, bidx);
		verifyTable("inquiries", "SELECT id, name, email, organization, message, name_enc, email_enc, organization_enc, message_enc FROM app.inquiries",
				List.of("name", "email", "organization", "message"), false, false, enc, bidx);
		verifyTable("password_resets", PASSWORD_RESETS_SQL,
				List.of("email"), true, true, enc, bidx);
		verifyTable("signup_events", "SELECT id, email, ip, email_enc, ip_enc, email_bidx FROM app.signup_events",
				List.of("email", "ip"), true, true, enc, bidx);
		return new VerifyReport(enc, bidx);
	}

	/** columns의 각 c에 대해 decrypt(c_enc)==c 검사; hasBidx면 email_bidx==blindIndex(normalize(email)) 검사. */
	private void verifyTable(String table, String sql, List<String> columns, boolean hasBidx, boolean normalizeEmail,
			Map<String, Integer> enc, Map<String, Integer> bidx) {
		int encBad = 0;
		int bidxBad = 0;
		for (Map<String, Object> row : jdbc.sql(sql).query().listOfRows()) {
			boolean rowEncBad = false;
			for (String c : columns) {
				String plain = (String) row.get(c);
				String token = (String) row.get(c + "_enc");
				String dec;
				try {
					dec = cipher.decrypt(token);
				} catch (IllegalStateException e) {
					dec = null;
				}
				if (!Objects.equals(plain, dec)) {
					rowEncBad = true;
				}
			}
			if (rowEncBad) {
				encBad++;
				log.warn("PII enc 불일치 — table={}, id={}", table, row.get("id"));
			}
			if (hasBidx) {
				String email = (String) row.get("email");
				String expected = cipher.blindIndex(normalizeEmail ? UserRepository.normalizeEmail(email) : email);
				if (!Objects.equals(expected, row.get("email_bidx"))) {
					bidxBad++;
					log.warn("PII bidx 불일치 — table={}, id={}", table, row.get("id"));
				}
			}
		}
		enc.put(table, encBad);
		bidx.put(table, bidxBad);
	}
}
