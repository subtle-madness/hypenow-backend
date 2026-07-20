package com.celfit.was.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입 코드 일괄 적재(설계 2026-07-20) — 검증을 먼저 전부 통과시킨 뒤 삽입(부분 저장 없음).
 * channel은 코드 접두사(첫 '-' 앞)에서 유도, 접두사 없으면 400. 중복은 리포지토리 ON CONFLICT가 스킵.
 */
@Service
public class AdminSignupCodeService {

	private static final int MAX_BATCH = 500;
	// 접두사·서픽스 모두 non-empty, 공백/추가 '-' 불가 — 접두사 없는 코드(-XXXX, XXXX) 거부
	private static final Pattern CODE = Pattern.compile("^[^\\s-]+-[^\\s-]+$");

	private final AdminSignupCodeRepository repository;

	public AdminSignupCodeService(AdminSignupCodeRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public SignupCodeCreateResponse create(SignupCodeCreateRequest request) {
		List<String> raw = request == null ? null : request.codes();
		if (raw == null || raw.isEmpty()) {
			throw new AdminApiException(400, "codes가 비어 있습니다.");
		}
		if (raw.size() > MAX_BATCH) {
			throw new AdminApiException(400, "배치 최대 " + MAX_BATCH + "개입니다.");
		}
		record CodeChannel(String code, String channel) {
		}
		List<CodeChannel> parsed = new ArrayList<>(raw.size());
		for (String r : raw) {
			String code = r == null ? "" : r.trim();
			if (code.isEmpty()) {
				throw new AdminApiException(400, "빈 코드가 포함돼 있습니다.");
			}
			if (!CODE.matcher(code).matches()) {
				throw new AdminApiException(400, "접두사 없는 코드입니다: " + code);
			}
			parsed.add(new CodeChannel(code, code.substring(0, code.indexOf('-'))));
		}
		int inserted = 0;
		for (CodeChannel cc : parsed) {
			inserted += repository.insert(cc.code(), cc.channel());
		}
		return new SignupCodeCreateResponse(inserted, raw.size() - inserted);
	}
}
