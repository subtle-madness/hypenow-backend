package com.celfit.was.v1.notices;

import com.celfit.was.notices.NoticeRepository;
import com.celfit.was.notices.NoticeRepository.ItemInput;
import com.celfit.was.v1.common.V1ApiException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 업데이트 소식 쓰기(POST·PATCH·DELETE /v1/admin/notices) — 검증을 전량 선행한 뒤(all-or-nothing,
 * NotificationSettingsService 관례) 파싱·쓰기를 트랜잭션으로 묶는다. 응답 조립은 컨트롤러가 재조회 후
 * {@link NoticeResponse#from}으로 한다.
 */
@Service
public class NoticeAdminService {

	private static final Set<String> TAGS = Set.of("new", "changed", "improved", "fixed");
	private static final ZoneOffset KST = ZoneOffset.ofHours(9);

	private final NoticeRepository repository;

	public NoticeAdminService(NoticeRepository repository) {
		this.repository = repository;
	}

	/** notices RETURNING id → NoticeRepository가 스스로 트랜잭션을 지므로 여기서는 검증·파싱만 감싼다. */
	public long create(NoticeUpsertRequest request) {
		validate(request);
		OffsetDateTime publishedAt = parsePublishedAt(request.publishedAt());
		return repository.insert(request.title(), publishedAt, toItemInputs(request.items()));
	}

	/** UPDATE 0행(대상 부재)이면 404. */
	@Transactional
	public void update(long id, NoticeUpsertRequest request) {
		validate(request);
		OffsetDateTime publishedAt = parsePublishedAt(request.publishedAt());
		boolean updated = repository.update(id, request.title(), publishedAt, toItemInputs(request.items()));
		if (!updated) {
			throw V1ApiException.notFound("소식을 찾을 수 없습니다.");
		}
	}

	public void delete(long id) {
		boolean deleted = repository.delete(id);
		if (!deleted) {
			throw V1ApiException.notFound("소식을 찾을 수 없습니다.");
		}
	}

	private static void validate(NoticeUpsertRequest request) {
		if (request.title() == null || request.title().isBlank()) {
			throw V1ApiException.validation("제목을 입력해 주세요.");
		}
		if (request.items() == null || request.items().isEmpty()) {
			throw V1ApiException.validation("항목을 1개 이상 입력해 주세요.");
		}
		for (NoticeUpsertRequest.Item item : request.items()) {
			if (item.summary() == null || item.summary().isBlank()) {
				throw V1ApiException.validation("요약을 입력해 주세요.");
			}
			if (item.tag() == null || !TAGS.contains(item.tag())) {
				throw V1ApiException.validation("태그가 올바르지 않습니다.");
			}
			if (item.body() == null) {
				throw V1ApiException.validation("본문을 입력해 주세요.");
			}
			if (item.link() != null) {
				NoticeUpsertRequest.LinkInput link = item.link();
				if (link.href() == null || link.href().isBlank() || link.label() == null || link.label().isBlank()) {
					throw V1ApiException.validation("링크는 주소와 라벨을 모두 입력해 주세요.");
				}
			}
		}
		// publishedAt 자체의 형식 오류는 파싱 단계(parsePublishedAt)에서 400을 던진다.
		if (request.publishedAt() == null) {
			throw V1ApiException.validation("발행 시각을 입력해 주세요.");
		}
	}

	/**
	 * OffsetDateTime.parse 우선 시도 → 실패하면 오프셋 없는 "datetime-local" 값(예:
	 * "2026-08-03T09:00")으로 보고 KST(+09:00)를 부여한다. 둘 다 실패하면 400.
	 */
	private static OffsetDateTime parsePublishedAt(String raw) {
		try {
			return OffsetDateTime.parse(raw);
		} catch (DateTimeParseException e) {
			try {
				return LocalDateTime.parse(raw).atOffset(KST);
			} catch (DateTimeParseException e2) {
				throw V1ApiException.validation("발행 시각 형식이 올바르지 않습니다.");
			}
		}
	}

	private static List<ItemInput> toItemInputs(List<NoticeUpsertRequest.Item> items) {
		return items.stream()
				.map(item -> {
					String href = item.link() == null ? null : item.link().href();
					String label = item.link() == null ? null : item.link().label();
					return new ItemInput(item.tag(), item.summary(), item.body(), href, label);
				})
				.toList();
	}
}
