package com.celfit.was.v1.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/**
 * 세션 목록·무효화(스펙 6.14) — Spring Session의 principal_name(=userId 문자열, 트랙 A 09-03 —
 * 전환기엔 구 email-principal 세션이 공존하며 만료로 소거) 인덱스로 본인 세션만 다룬다.
 *
 * 노출 id는 실 세션 id가 아니라 **alias(sha-256 hex 앞 16자)** — XSS로 목록 응답을 읽혀도
 * 세션 쿠키 값을 복원할 수 없어 세션 탈취로 이어지지 않는다. 삭제도 alias를 본인 목록에서
 * 역매칭해 실 id를 찾는 방식이라 alias가 곧 권한 경계다.
 */
@Service
public class SessionService {

	private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

	public SessionService(FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
		this.sessionRepository = sessionRepository;
	}

	/** 스펙 6.14 응답 1행 — loginAt은 세션 생성 시각(ISO Z), browser/os는 로그인 시 UA attribute(Task 1 규약). */
	public record SessionView(String id, String browser, String os, String loginAt, boolean current) {
	}

	/** 현재 세션 포함 전체, loginAt 내림차순(최근 로그인 먼저). */
	public List<SessionView> listSessions(String principalName, String currentSessionId) {
		return sessionRepository.findByPrincipalName(principalName).values().stream()
				.sorted(Comparator.comparing(Session::getCreationTime).reversed())
				.map(session -> new SessionView(
						alias(session.getId()),
						attributeOrDefault(session, "session.browser"),
						attributeOrDefault(session, "session.os"),
						session.getCreationTime().toString(),
						session.getId().equals(currentSessionId)))
				.toList();
	}

	/**
	 * alias가 본인 세션과 매칭되면 삭제 후 true, 아니면 false(호출부가 404로 은닉 — 스펙의 타 유저 403은
	 * alias 방식에선 존재 여부 자체가 불명이라 구분할 수 없다). 현재 세션을 지우면 로그아웃과 동일 효과.
	 */
	public boolean deleteByAlias(String principalName, String alias) {
		for (String sessionId : sessionRepository.findByPrincipalName(principalName).keySet()) {
			if (alias(sessionId).equals(alias)) {
				sessionRepository.deleteById(sessionId);
				return true;
			}
		}
		return false;
	}

	/** 비밀번호 변경용(스펙 6.13) — 현재 세션만 남기고 전부 무효화. */
	public void deleteOthers(String principalName, String currentSessionId) {
		for (String sessionId : sessionRepository.findByPrincipalName(principalName).keySet()) {
			if (!sessionId.equals(currentSessionId)) {
				sessionRepository.deleteById(sessionId);
			}
		}
	}

	/** 탈퇴용(스펙 6.13) — 본인 세션 전부 무효화. */
	public void deleteAll(String principalName) {
		for (String sessionId : sessionRepository.findByPrincipalName(principalName).keySet()) {
			sessionRepository.deleteById(sessionId);
		}
	}

	/** 세션 id 노출용 alias — sha-256 hex 앞 16자(결정적·역산 불가). */
	public static String alias(String sessionId) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(sessionId.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 미지원 JVM은 없다", e);
		}
	}

	private static String attributeOrDefault(Session session, String name) {
		Object value = session.getAttribute(name);
		return value == null ? "기타" : value.toString();
	}
}
