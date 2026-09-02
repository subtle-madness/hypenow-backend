package com.celfit.analytics.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.analytics.analyze.JobResult;
import com.celfit.analytics.analyze.ProgressReporter;
import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.testsupport.TestDb;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 아카이브 잡 계약:
 * ① 신규 썸네일·프로필 업로드+기록 ② 기록된 썸네일은 다운로드 자체 생략(12개 윈도우 중복 무해)
 * ③ 프로필은 파일명 동일하면 생략·바뀌면 같은 키 덮어쓰기 ④ 배치 상한 초과분 이월(carriedOver)
 * ⑤ 한 건 실패 격리(계속 진행) ⑥ Cache-Control 종류별 차등
 * ⑦ oe(CDN 서명 만료) 지난 URL은 시도 없이 제외 ⑧ 만료 임박 순 처리
 * ⑨ 영구 무효 URL(비http 스킴·IG 플레이스홀더)은 시도 없이 제외하고 실패로 세지 않는다.
 */
class ImageArchiveJobTest {

	static final PostgreSQLContainer pg = TestDb.shared();

	JdbcTemplate db;
	DataSource ds;
	List<String> downloads = new ArrayList<>();       // fetch된 URL 기록
	List<Map<String, String>> puts = new ArrayList<>(); // put(objectPath, cacheControl) 기록
	List<String> failUrls = new ArrayList<>();          // 다운로드를 실패시킬 URL

	ImageDownloader fakeDownloader() {
		return url -> {
			downloads.add(url);
			if (failUrls.contains(url)) throw new IllegalStateException("다운로드 실패 HTTP 403: " + url);
			return new ImageDownloader.Downloaded("bytes".getBytes(), "image/jpeg");
		};
	}

	ImageStore fakeStore() {
		return (objectPath, bytes, contentType, cacheControl) ->
				puts.add(Map.of("path", objectPath, "cacheControl", cacheControl));
	}

	ImageArchiveJob job(int batchLimit) {
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.archive-batch-limit', ?) "
				+ "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", String.valueOf(batchLimit));
		return new ImageArchiveJob(db, ds, fakeStore(), fakeDownloader(),
				new AnalyticsSettings(db), ProgressReporter.NOOP);
	}

	@BeforeEach
	void setUp() {
		ds = TestDb.rawDataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		// raw 쪽 대체물 — 잡이 읽는 뷰와 같은 이름의 테이블 + app_setting
		db.execute("CREATE SCHEMA analytics");
		db.execute("CREATE TABLE analytics.v_contents (short_code text, thumbnail_url text)");
		db.execute("CREATE TABLE analytics.v_accounts (handle text, profile_image_url text)");
		db.execute("CREATE TABLE IF NOT EXISTS app_setting (key text PRIMARY KEY, value text)");
		downloads.clear();
		puts.clear();
		failUrls.clear();
	}

	void seedContent(String shortCode, String url) {
		db.update("INSERT INTO analytics.v_contents VALUES (?, ?)", shortCode, url);
	}

	void seedAccount(String handle, String url) {
		db.update("INSERT INTO analytics.v_accounts VALUES (?, ?)", handle, url);
	}

	@Test
	void 신규_썸네일과_프로필을_업로드하고_기록한다() {
		seedContent("abc123", "https://cdn.example/v/t51/463_111_n.jpg?sig=1");
		seedAccount("celfit", "https://cdn.example/v/t51/999_222_n.jpg?sig=2");

		JobResult result = job(1000).run();

		assertThat(result.processed()).isEqualTo(2);
		assertThat(result.failed()).isZero();
		assertThat(puts).extracting(m -> m.get("path"))
				.containsExactlyInAnyOrder("thumb/abc123.jpg", "profile/celfit.jpg");
		Integer rows = db.queryForObject("SELECT count(*) FROM image_assets", Integer.class);
		assertThat(rows).isEqualTo(2);
		String sourceName = db.queryForObject(
				"SELECT source_name FROM image_assets WHERE kind='profile' AND key='celfit'", String.class);
		assertThat(sourceName).isEqualTo("999_222_n.jpg");
	}

	@Test
	void 기록된_썸네일은_다운로드_자체를_생략한다() {
		seedContent("abc123", "https://cdn.example/v/463_111_n.jpg?sig=1");
		job(1000).run();
		downloads.clear();
		puts.clear();

		JobResult second = job(1000).run();

		assertThat(second.processed()).isZero();
		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	@Test
	void 프로필_파일명_같으면_생략_바뀌면_같은_키_덮어쓰기() {
		seedAccount("celfit", "https://cdn-a.example/v/999_222_n.jpg?sig=1");
		job(1000).run();
		downloads.clear();
		puts.clear();

		// 호스트·서명만 바뀐 같은 파일명 → 생략
		db.update("UPDATE analytics.v_accounts SET profile_image_url = ?",
				"https://cdn-b.example/v/999_222_n.jpg?sig=99");
		assertThat(job(1000).run().processed()).isZero();
		assertThat(downloads).isEmpty();

		// 파일명 변경(실제 교체) → 같은 키 재업로드 + source_name 갱신
		db.update("UPDATE analytics.v_accounts SET profile_image_url = ?",
				"https://cdn-b.example/v/1000_333_n.jpg?sig=5");
		JobResult changed = job(1000).run();
		assertThat(changed.processed()).isEqualTo(1);
		assertThat(puts).extracting(m -> m.get("path")).containsExactly("profile/celfit.jpg");
		Integer rows = db.queryForObject(
				"SELECT count(*) FROM image_assets WHERE kind='profile'", Integer.class);
		assertThat(rows).isEqualTo(1);
		String sourceName = db.queryForObject(
				"SELECT source_name FROM image_assets WHERE kind='profile' AND key='celfit'", String.class);
		assertThat(sourceName).isEqualTo("1000_333_n.jpg");
	}

	@Test
	void 배치_상한_초과분은_이월된다() {
		seedContent("c1", "https://cdn.example/1_n.jpg");
		seedContent("c2", "https://cdn.example/2_n.jpg");
		seedContent("c3", "https://cdn.example/3_n.jpg");

		JobResult result = job(2).run();

		assertThat(result.processed()).isEqualTo(2);
		assertThat(result.carriedOver()).isTrue();
	}

	@Test
	void 한_건_실패해도_나머지는_계속() {
		seedContent("bad", "https://cdn.example/expired_n.jpg");
		seedContent("good", "https://cdn.example/ok_n.jpg");
		failUrls.add("https://cdn.example/expired_n.jpg");

		JobResult result = job(1000).run();

		assertThat(result.processed()).isEqualTo(1);
		assertThat(result.failed()).isEqualTo(1);
		// 실패분은 기록되지 않아 다음 실행에서 재대상
		Integer rows = db.queryForObject(
				"SELECT count(*) FROM image_assets WHERE key='bad'", Integer.class);
		assertThat(rows).isZero();
	}

	@Test
	void CacheControl은_종류별_차등() {
		seedContent("abc123", "https://cdn.example/1_n.jpg");
		seedAccount("celfit", "https://cdn.example/2_n.jpg");

		job(1000).run();

		assertThat(puts).anySatisfy(m -> {
			assertThat(m.get("path")).startsWith("thumb/");
			assertThat(m.get("cacheControl")).isEqualTo("public, max-age=31536000, immutable");
		});
		assertThat(puts).anySatisfy(m -> {
			assertThat(m.get("path")).startsWith("profile/");
			assertThat(m.get("cacheControl")).isEqualTo("public, max-age=86400");
		});
	}

	/** 인스타 CDN URL의 oe 파라미터(hex unix 초) 생성 — 만료 판정 테스트용. */
	static String oeUrl(String name, long epochSecond) {
		return "https://cdn.example/v/" + name + "?stp=dst-jpg&oe="
				+ Long.toHexString(epochSecond).toUpperCase() + "&_nc_sid=8b3546";
	}

	@Test
	void 만료된_URL은_다운로드_시도_없이_제외한다() {
		long now = java.time.Instant.now().getEpochSecond();
		seedContent("dead", oeUrl("dead_n.jpg", now - 3600));      // 이미 만료
		seedContent("live", oeUrl("live_n.jpg", now + 86400));     // 유효
		seedContent("noOe", "https://cdn.example/v/no_oe_n.jpg");  // oe 없음 → 시도 유지

		JobResult result = job(1000).run();

		assertThat(result.processed()).isEqualTo(2);
		assertThat(result.failed()).isZero();
		assertThat(result.carriedOver()).isFalse(); // 만료 제외분은 이월이 아니다
		assertThat(downloads).noneMatch(u -> u.contains("dead_n.jpg"));
		Integer rows = db.queryForObject(
				"SELECT count(*) FROM image_assets WHERE key='dead'", Integer.class);
		assertThat(rows).isZero();
	}

	@Test
	void 만료_임박한_URL부터_처리한다() {
		long now = java.time.Instant.now().getEpochSecond();
		seedContent("far", oeUrl("far_n.jpg", now + 86400 * 3));
		seedContent("soon", oeUrl("soon_n.jpg", now + 3600));
		seedContent("noOe", "https://cdn.example/v/no_oe_n.jpg"); // 만료 미상 → 뒤로

		JobResult result = job(1).run();

		assertThat(result.processed()).isEqualTo(1);
		assertThat(result.carriedOver()).isTrue();
		assertThat(downloads).containsExactly(oeUrl("soon_n.jpg", now + 3600));
	}

	/**
	 * 08-16 운영 실측 — 영구 불능 URL 15건이 매일 재시도되며 매 실행을 FAILED로 만들었다(어드민
	 * 카드 "실패" 뱃지): 리터럴 {@code exception://}(Hiker 업스트림 센티널 — 트랙 KK 결함②와 같은
	 * 부류) 5건 + {@code rsrc.php/null.jpg}(삭제·비공개 미디어의 IG 플레이스홀더, 영구 HTTP 400)
	 * 10건. 재시도해도 영원히 실패라 시도 전에 제외한다 — 만료 제외(⑦)와 같은 "제외"지 실패가
	 * 아니다. 재크롤이 정상 URL을 주면 자연 복귀한다(후보 선정이 매 실행 원본 뷰 기준이라).
	 */
	@Test
	void 영구_무효_URL은_시도_없이_제외되고_실패로_세지_않는다() {
		seedAccount("sentinel", "exception://");                                    // 무효 스킴
		seedContent("gone1", "https://static.cdninstagram.com/rsrc.php/null.jpg");  // 플레이스홀더
		seedContent("gone2", "http://static.cdninstagram.com/rsrc.php/null.jpg");   // http 변형(실측 혼재)
		seedContent("good", "https://cdn.example/ok_n.jpg");

		JobResult result = job(1000).run();

		assertThat(result.processed()).isEqualTo(1);
		assertThat(result.failed()).isZero();       // 무효 제외는 실패가 아니다 — 뱃지 정상화의 핵심
		assertThat(result.carriedOver()).isFalse(); // 이월도 아니다
		assertThat(downloads).containsExactly("https://cdn.example/ok_n.jpg");
		Integer rows = db.queryForObject(
				"SELECT count(*) FROM image_assets WHERE key IN ('sentinel','gone1','gone2')", Integer.class);
		assertThat(rows).isZero();
	}

	/**
	 * 메모리 계약(2026-09-01): 대상 선정은 v_contents 전량을 자바 리스트로 적재하지 않는다 —
	 * 커서 스트리밍(읽기전용 트랜잭션 + fetchSize) + 상한 크기 후보만 유지한다. 전량 적재 구현은
	 * 드라이버 버퍼와 Target 리스트가 행 수에 비례해 이중으로 쌓인다(미러 08-31 OOM과 같은 패턴).
	 * 시드는 3000행 × 100KB URL ≈ 300MB — Gradle 테스트 JVM 기본 힙(512m) 전제에서 전량
	 * 적재(버퍼+UTF-16 문자열 ≈ 2배)면 OOM, 스트리밍이면 fetch 창(500행)+상한(5건)만 남는다.
	 */
	@Test
	void 대상이_힙보다_커도_상한만큼만_유지한다() {
		db.execute("""
				INSERT INTO analytics.v_contents
				SELECT 'c' || i, 'https://cdn.example/v/' || repeat('x', 100000) || i || '_n.jpg'
				FROM generate_series(1, 3000) i
				""");

		JobResult result = job(5).run();

		assertThat(result.processed()).isEqualTo(5);
		assertThat(result.failed()).isZero();
		assertThat(result.carriedOver()).isTrue();
	}

	@Test
	void 파싱_불가_URL은_그_건만_실패하고_나머지는_계속() {
		seedAccount("broken", "https://cdn.example/v/463 111_n.jpg?sig=1"); // 공백 — URI 파싱 불가
		seedContent("good", "https://cdn.example/ok_n.jpg");

		JobResult result = job(1000).run();

		assertThat(result.processed()).isEqualTo(1);
		assertThat(result.failed()).isEqualTo(1);
		Integer rows = db.queryForObject(
				"SELECT count(*) FROM image_assets WHERE key='broken'", Integer.class);
		assertThat(rows).isZero();
	}
}
