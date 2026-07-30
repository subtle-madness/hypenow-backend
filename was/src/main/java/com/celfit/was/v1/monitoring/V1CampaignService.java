package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.v1.common.V1ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 캠페인 CRUD 비즈니스 로직(스펙 6.25 Campaign, 6.31). 이름 정규화·필드 검증, PATCH의
 * "키 없음=유지, 키 있고 null=해제" 의미론, resolveOrCreate(등록 접수 태스크 6.27이 사용할 예정 —
 * 현재는 public화·단위 검증만)를 담당한다. 필드 값은 컨트롤러가 JSON 본문에서 뽑은 원시 Object를
 * 그대로 받아 여기서 파싱·검증한다(budget의 정수/소수 구분은 타입 정보가 살아있는 시점에만 가능하다 —
 * 미리 Long으로 변환해 버리면 12.5가 조용히 12로 잘려 400을 낼 기회를 잃는다).
 */
@Service
public class V1CampaignService {

	private static final int MAX_DESCRIPTION_LENGTH = 200;
	private static final int MAX_BRAND_LENGTH = 30;
	private static final String CAMPAIGN_NAME_EXISTS = "CAMPAIGN_NAME_EXISTS";
	private static final String NAME_EXISTS_MESSAGE = "같은 이름의 캠페인이 이미 있어요.";

	private final CampaignRepository repository;

	public V1CampaignService(CampaignRepository repository) {
		this.repository = repository;
	}

	/** 전량 반환(스펙 1.4) — 정렬은 repository.findByUser가 보장(createdAt ASC, id ASC). */
	public List<CampaignRow> list(long userId) {
		return repository.findByUser(userId);
	}

	public CampaignRow create(long userId, Object rawName, Object rawDescription, Object rawStartDate,
			Object rawEndDate, Object rawBrand, Object rawBudget) {
		String name = CampaignName.normalize(toStringOrNull(rawName));
		String description = validateDescription(toStringOrNull(rawDescription));
		String brand = validateBrand(toStringOrNull(rawBrand));
		LocalDate startDate = parseDate(rawStartDate, "startDate");
		LocalDate endDate = parseDate(rawEndDate, "endDate");
		validateDateRange(startDate, endDate);
		Long budget = parseBudget(rawBudget);

		try {
			return repository.insert(userId, name, description, startDate, endDate, brand, budget);
		} catch (DuplicateKeyException e) {
			throw V1ApiException.conflict(CAMPAIGN_NAME_EXISTS, NAME_EXISTS_MESSAGE);
		}
	}

	/**
	 * PATCH — 키 없음=유지, 키 있고 null=해제(스펙 6.31). name만 예외로 해제 불가(null이면 400).
	 * 동시 수정은 last-write-wins이며 버전 충돌 검사를 하지 않는다(스펙 그대로).
	 */
	public CampaignRow patch(long userId, long campaignId, Map<String, Object> body) {
		CampaignRow existing = repository.findByIdAndUser(campaignId, userId)
				.orElseThrow(() -> V1ApiException.notFound("캠페인을 찾을 수 없습니다."));

		String name = existing.name();
		if (body.containsKey("name")) {
			Object raw = body.get("name");
			// name만 해제 불가 — CampaignName.normalize의 빈 값 메시지와 통일해 null·빈 문자열이
			// 유저에게 같은 문구로 보이게 한다("캠페인 이름을 입력해 주세요.").
			if (raw == null) {
				throw V1ApiException.validation("캠페인 이름을 입력해 주세요.");
			}
			name = CampaignName.normalize(raw.toString());
		}

		String description = existing.description();
		if (body.containsKey("description")) {
			description = validateDescription(toStringOrNull(body.get("description")));
		}

		String brand = existing.brand();
		if (body.containsKey("brand")) {
			brand = validateBrand(toStringOrNull(body.get("brand")));
		}

		LocalDate startDate = existing.startDate();
		if (body.containsKey("startDate")) {
			startDate = parseDate(body.get("startDate"), "startDate");
		}
		LocalDate endDate = existing.endDate();
		if (body.containsKey("endDate")) {
			endDate = parseDate(body.get("endDate"), "endDate");
		}
		validateDateRange(startDate, endDate);

		Long budget = existing.budget();
		if (body.containsKey("budget")) {
			budget = parseBudget(body.get("budget"));
		}

		try {
			return repository.update(campaignId, name, description, startDate, endDate, brand, budget);
		} catch (DuplicateKeyException e) {
			throw V1ApiException.conflict(CAMPAIGN_NAME_EXISTS, NAME_EXISTS_MESSAGE);
		}
	}

	/**
	 * 이름만으로 캠페인을 해석한다 — 동명 캠페인이 있으면 그 행을 재사용하고, 없으면 이름만으로 새로
	 * 만든다. 등록 접수(6.27, 후속 태스크)가 "캠페인명 텍스트 입력"을 캠페인 행에 연결할 때 쓴다.
	 */
	public Resolved resolveOrCreate(long userId, String rawName) {
		String name = CampaignName.normalize(rawName);
		return repository.findByNameAndUser(name, userId)
				.map(row -> new Resolved(row, false))
				.orElseGet(() -> new Resolved(repository.insert(userId, name, null, null, null, null, null), true));
	}

	/** 소유 검증 → 삭제. FK ON DELETE SET NULL이 소속 추적 행의 campaign_id를 해제한다(캐스케이드 삭제 아님). */
	public void delete(long userId, long campaignId) {
		// 반환값은 소유 검증(존재 + 이 유저 소유)에만 쓰고 버린다 — 실제 삭제는 아래 repository.delete.
		CampaignRow unused = repository.findByIdAndUser(campaignId, userId)
				.orElseThrow(() -> V1ApiException.notFound("캠페인을 찾을 수 없습니다."));
		repository.delete(campaignId);
	}

	private static String validateDescription(String description) {
		if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
			throw V1ApiException.validation("설명은 200자 이하여야 해요.");
		}
		return description;
	}

	private static String validateBrand(String brand) {
		if (brand != null && brand.length() > MAX_BRAND_LENGTH) {
			throw V1ApiException.validation("브랜드명은 30자 이하여야 해요.");
		}
		return brand;
	}

	/** 둘 다 있을 때만 start ≤ end 검증(한쪽만 null은 관대하게 허용, 스펙 6.31). */
	private static void validateDateRange(LocalDate startDate, LocalDate endDate) {
		if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
			throw V1ApiException.validation("시작일은 종료일보다 늦을 수 없어요.");
		}
	}

	private static LocalDate parseDate(Object raw, String field) {
		if (raw == null) {
			return null;
		}
		try {
			return LocalDate.parse(raw.toString());
		} catch (DateTimeParseException e) {
			throw V1ApiException.validation(field + " 형식이 올바르지 않아요.");
		}
	}

	/**
	 * budget: null 또는 0 이상 정수만 허용한다. Map&lt;String,Object&gt; 역직렬화에서 JSON 정수는
	 * Integer/Long, 소수는 Double로 들어오므로 타입으로 소수 여부를 구분한다(12.5 같은 값은 여기서 400).
	 */
	private static Long parseBudget(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof Integer || raw instanceof Long) {
			long value = ((Number) raw).longValue();
			if (value < 0) {
				throw V1ApiException.validation("예산은 0 이상이어야 해요.");
			}
			return value;
		}
		throw V1ApiException.validation("예산은 0 이상의 정수로 입력해 주세요.");
	}

	private static String toStringOrNull(Object raw) {
		return raw == null ? null : raw.toString();
	}

	/** resolveOrCreate 결과 — 생성 여부 포함(등록 접수 태스크가 신규/기존 분기에 사용할 예정). */
	public record Resolved(CampaignRow row, boolean created) {
	}
}
