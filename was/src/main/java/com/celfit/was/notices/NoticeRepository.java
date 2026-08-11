package com.celfit.was.notices;

import com.celfit.was.archive.ArchiveReason;
import com.celfit.was.archive.ArchiveTables;
import com.celfit.was.archive.ArchiveWriter;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * app.notices·app.notice_items CRUD — 업데이트 소식(제품 공지). 부모-자식은 쿼리 2회 후 자바에서
 * 조립한다(RegistrationRepository 관례). insert·update는 SQL 문 여러 개를 한 트랜잭션으로 묶어야
 * 해서 메서드에 직접 {@code @Transactional}을 붙인다(UserRepository.delete 관례 — Task 1 시점엔
 * 서비스 계층이 아직 없어 호출측 위임이 불가능하기도 하다).
 */
@Repository
public class NoticeRepository {

	private final JdbcClient jdbcClient;
	private final ArchiveWriter archiveWriter;

	public NoticeRepository(JdbcClient jdbcClient, ArchiveWriter archiveWriter) {
		this.jdbcClient = jdbcClient;
		this.archiveWriter = archiveWriter;
	}

	/** 항목 입력 — 리스트 순서가 그대로 position이 된다(어드민 작성 순서 보존). */
	public record ItemInput(String tag, String summary, String body, String linkHref, String linkLabel) {
	}

	public record ItemRow(long id, String tag, String summary, String body, String linkHref, String linkLabel) {
	}

	public record NoticeRow(long id, String title, OffsetDateTime publishedAt, List<ItemRow> items) {
	}

	/** 유저 노출용 — 예약 발행(미래 published_at)은 제외한다. */
	public List<NoticeRow> findPublished() {
		List<NoticeHeader> headers = jdbcClient.sql("""
				SELECT id, title, published_at
				FROM app.notices
				WHERE published_at <= now()
				ORDER BY published_at DESC, id DESC
				""")
				.query(NoticeHeader.class)
				.list();
		return assemble(headers);
	}

	/** 어드민 목록용 — 예약분 포함, 정렬은 findPublished와 동일. */
	public List<NoticeRow> findAll() {
		List<NoticeHeader> headers = jdbcClient.sql("""
				SELECT id, title, published_at
				FROM app.notices
				ORDER BY published_at DESC, id DESC
				""")
				.query(NoticeHeader.class)
				.list();
		return assemble(headers);
	}

	public Optional<NoticeRow> findById(long id) {
		Optional<NoticeHeader> header = jdbcClient.sql("""
				SELECT id, title, published_at
				FROM app.notices
				WHERE id = :id
				""")
				.param("id", id)
				.query(NoticeHeader.class)
				.optional();
		if (header.isEmpty()) {
			return Optional.empty();
		}
		NoticeHeader h = header.get();
		List<ItemRow> items = itemsByNotice(List.of(id)).getOrDefault(id, List.of());
		return Optional.of(new NoticeRow(h.id(), h.title(), h.publishedAt(), items));
	}

	/** 소식 1건 생성 — notices RETURNING id 후 items를 순회 INSERT(position = 리스트 인덱스). */
	@Transactional
	public long insert(String title, OffsetDateTime publishedAt, List<ItemInput> items) {
		long id = jdbcClient.sql("""
				INSERT INTO app.notices (title, published_at)
				VALUES (:title, :publishedAt)
				RETURNING id
				""")
				.param("title", title)
				.param("publishedAt", publishedAt)
				.query(Long.class)
				.single();
		insertItems(id, items);
		return id;
	}

	/**
	 * 전체 교체(PATCH 계약) — title·published_at 갱신 후 items를 전량 삭제·재삽입한다(항목 id
	 * 재발급 허용, 요청서 명시). UPDATE 결과 0행이면(대상 부재) items는 건드리지 않고 false 반환.
	 */
	@Transactional
	public boolean update(long id, String title, OffsetDateTime publishedAt, List<ItemInput> items) {
		int updated = jdbcClient.sql("""
				UPDATE app.notices
				SET title = :title, published_at = :publishedAt, updated_at = now()
				WHERE id = :id
				""")
				.param("title", title)
				.param("publishedAt", publishedAt)
				.param("id", id)
				.update();
		if (updated == 0) {
			return false;
		}
		jdbcClient.sql("DELETE FROM app.notice_items WHERE notice_id = :id")
				.param("id", id)
				.update();
		insertItems(id, items);
		return true;
	}

	/**
	 * app.notices만 지우면 notice_items는 ON DELETE CASCADE로 함께 삭제된다. 삭제 전 notice·items를
	 * 전부 아카이브한다(트랙 NN). items는 PK(id)를 모르므로 notice_id로 먼저 조회해 건별로
	 * archiveByPk한다 — CASCADE로 사라지는 자식이라 삭제 건수는 대조하지 않는다
	 * (UserRepository.deleteAndVerify와 같은 이유 — MonitoringItemRepository.delete 관례).
	 */
	@Transactional
	public boolean delete(long id) {
		List<Long> itemIds = jdbcClient.sql("SELECT id FROM app.notice_items WHERE notice_id = :id")
				.param("id", id)
				.query(Long.class)
				.list();
		for (Long itemId : itemIds) {
			archiveWriter.archiveByPk(ArchiveTables.NOTICE_ITEMS, ArchiveReason.NOTICE_DELETED,
					Map.of("id", itemId));
		}

		int archived = archiveWriter.archiveByPk(ArchiveTables.NOTICES, ArchiveReason.NOTICE_DELETED,
				Map.of("id", id));
		int deleted = jdbcClient.sql("DELETE FROM app.notices WHERE id = :id")
				.param("id", id)
				.update();
		archiveWriter.verifyMatched(ArchiveTables.NOTICES, archived, deleted);
		return deleted > 0;
	}

	private void insertItems(long noticeId, List<ItemInput> items) {
		for (int position = 0; position < items.size(); position++) {
			ItemInput item = items.get(position);
			jdbcClient.sql("""
					INSERT INTO app.notice_items (notice_id, position, tag, summary, body, link_href, link_label)
					VALUES (:noticeId, :position, :tag, :summary, :body, :linkHref, :linkLabel)
					""")
					.param("noticeId", noticeId)
					.param("position", position)
					.param("tag", item.tag())
					.param("summary", item.summary())
					.param("body", item.body())
					.param("linkHref", item.linkHref())
					.param("linkLabel", item.linkLabel())
					.update();
		}
	}

	private List<NoticeRow> assemble(List<NoticeHeader> headers) {
		if (headers.isEmpty()) {
			return List.of();
		}
		List<Long> ids = headers.stream().map(NoticeHeader::id).toList();
		Map<Long, List<ItemRow>> byNotice = itemsByNotice(ids);
		return headers.stream()
				.map(h -> new NoticeRow(h.id(), h.title(), h.publishedAt(), byNotice.getOrDefault(h.id(), List.of())))
				.toList();
	}

	/** notice_id별 items 일괄 조회 — ids가 비면 IN () SQL 오류를 피해 쿼리 자체를 생략한다. */
	private Map<Long, List<ItemRow>> itemsByNotice(List<Long> noticeIds) {
		if (noticeIds.isEmpty()) {
			return Map.of();
		}
		List<ItemRowWithNoticeId> rows = jdbcClient.sql("""
				SELECT notice_id, id, tag, summary, body, link_href, link_label
				FROM app.notice_items
				WHERE notice_id IN (:ids)
				ORDER BY notice_id ASC, position ASC
				""")
				.param("ids", noticeIds)
				.query(ItemRowWithNoticeId.class)
				.list();
		return rows.stream().collect(Collectors.groupingBy(ItemRowWithNoticeId::noticeId,
				Collectors.mapping(
						r -> new ItemRow(r.id(), r.tag(), r.summary(), r.body(), r.linkHref(), r.linkLabel()),
						Collectors.toList())));
	}

	/** items 없이 헤더만 매핑하기 위한 내부 전용 행 — RecordRowMapper 컬럼 매칭용. */
	private record NoticeHeader(long id, String title, OffsetDateTime publishedAt) {
	}

	/** notice_id를 포함한 아이템 행 — 일괄 조회 후 그룹핑하기 위한 내부 전용 매핑용. */
	private record ItemRowWithNoticeId(long noticeId, long id, String tag, String summary, String body,
			String linkHref, String linkLabel) {
	}
}
