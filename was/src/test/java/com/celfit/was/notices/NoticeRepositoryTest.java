package com.celfit.was.notices;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.notices.NoticeRepository.ItemInput;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** app.notices·app.notice_items 삭제 아카이브(트랙 NN) — 어드민 개별 삭제 경로 전용. */
class NoticeRepositoryTest extends IntegrationTest {

	@Autowired
	NoticeRepository repository;

	@Autowired
	JdbcClient jdbcClient;

	@Test
	void 삭제하면_notice와_items가_모두_아카이브되고_원본은_사라진다() {
		long noticeId = repository.insert("삭제 대상", OffsetDateTime.now().minusHours(1), List.of(
				new ItemInput("new", "요약1", "본문1", null, null),
				new ItemInput("changed", "요약2", "본문2", "https://a", "라벨A")));

		boolean deleted = repository.delete(noticeId);

		assertThat(deleted).isTrue();
		assertThat(repository.findById(noticeId)).isEmpty();

		long noticeArchiveCount = jdbcClient.sql("""
						SELECT count(*) FROM archive.archived_rows
						 WHERE table_name = 'app.notices' AND archived_reason = 'NOTICE_DELETED'
						   AND row_pk ->> 'id' = :noticeId
						""")
				.param("noticeId", String.valueOf(noticeId))
				.query(Long.class)
				.single();

		// notice_items는 이미 CASCADE로 삭제됐다 — payload의 notice_id로 아카이브 여부를 확인한다.
		long itemArchiveCount = jdbcClient.sql("""
						SELECT count(*) FROM archive.archived_rows
						 WHERE table_name = 'app.notice_items' AND archived_reason = 'NOTICE_DELETED'
						   AND payload ->> 'notice_id' = :noticeId
						""")
				.param("noticeId", String.valueOf(noticeId))
				.query(Long.class)
				.single();

		assertThat(noticeArchiveCount).isEqualTo(1);
		assertThat(itemArchiveCount).isEqualTo(2);
		assertThat(jdbcClient.sql("SELECT count(*) FROM app.notice_items WHERE notice_id = :id")
				.param("id", noticeId)
				.query(Long.class)
				.single()).isZero();
	}

	@Test
	void 없는_id를_삭제하면_false다() {
		assertThat(repository.delete(999_999L)).isFalse();
	}
}
