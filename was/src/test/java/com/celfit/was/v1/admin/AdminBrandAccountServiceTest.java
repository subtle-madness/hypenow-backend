package com.celfit.was.v1.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandCallSumRow;
import com.celfit.was.monitoring.BrandReadRepository.PostCountRow;
import com.celfit.was.v1.common.V1ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 어드민 브랜드 목록 계정 API 집계 순서 로직 검증(2026-09-03) — q 필터·정렬 6종·정렬 유효성·
 * offset/page/limit 정규화·월 경계·monitoring 비활성 경로·같은 계정 다중 유저 행을 고정 픽스처로
 * 고정한다. 실 DB 왕복·인가는 AdminBrandAccountsIntegrationTest가 커버(같은 관례는
 * AdminCrawlingUsageServiceTest 참조).
 */
class AdminBrandAccountServiceTest {

	private final BrandLinkRepository links = mock(BrandLinkRepository.class);
	private final BrandReadRepository reads = mock(BrandReadRepository.class);
	private final AdminUserRepository users = mock(AdminUserRepository.class);

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-20T01:00:00Z"), ZoneOffset.UTC);

	private AdminBrandAccountService service() {
		return new AdminBrandAccountService(links, users, Optional.of(reads), CLOCK);
	}

	private AdminBrandAccountService serviceWithoutMonitoring() {
		return new AdminBrandAccountService(links, users, Optional.empty(), CLOCK);
	}

	private static BrandLinkRow link(long id, long userId, long brandId, String username, String accountType,
			int months, String createdAt) {
		return new BrandLinkRow(id, userId, brandId, username, accountType, months,
				OffsetDateTime.parse(createdAt), null);
	}

	private static AdminUserRow user(long id, String email, String name, String companyName) {
		return new AdminUserRow(id, email, name, "brand", null, companyName, null,
				OffsetDateTime.parse("2026-01-01T00:00:00Z"), null, null);
	}

	private static BrandAccountRow account(long id, String username, OffsetDateTime lastSweptOn,
			OffsetDateTime backfillCompletedAt) {
		return new BrandAccountRow(id, username, lastSweptOn == null ? null : lastSweptOn.toLocalDate(),
				lastSweptOn, OffsetDateTime.parse("2026-01-01T00:00:00Z"), backfillCompletedAt, null,
				100L, 10L, 50L, null, null, null, null, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-01-01T00:00:00Z"), false, null);
	}

	private static AdminPageRequest allPage() {
		return AdminPageRequest.ofOffset(0, 100);
	}

	@Test
	void q는_유저명_또는_계정명_대소문자_무시_부분일치다() {
		given(links.findAllActive()).willReturn(List.of(
				link(1, 10, 100, "linkuser", "own", 12, "2026-08-01T00:00:00+09:00"),
				link(2, 11, 101, "otherbrand", "own", 12, "2026-08-02T00:00:00+09:00")));
		given(users.findByIds(anyCollection())).willReturn(List.of(
				user(10, "marketer@Brand.io", "홍길동", ""),
				user(11, "someone@else.io", "", "")));
		given(reads.findAccountsByIds(anyCollection())).willReturn(List.of(
				account(100, "beauty_lover", null, null),
				account(101, "otherbrand", null, null)));
		given(reads.countPostsByBrand(anyCollection())).willReturn(List.of());
		given(reads.sumCallCountsByBrand(anyCollection(), org.mockito.ArgumentMatchers.any())).willReturn(List.of());

		// username 매치(대소문자 무시)
		AdminBrandAccountService.Result byUsername = service().list(allPage(), null, "BEAUTY");
		assertThat(byUsername.rows()).extracting(AdminBrandAccountRow::accountId).containsExactly("100");

		// 이메일 매치(대소문자 무시)
		AdminBrandAccountService.Result byEmail = service().list(allPage(), null, "brand.io");
		assertThat(byEmail.rows()).extracting(AdminBrandAccountRow::accountId).containsExactly("100");

		// 공백만이면 필터 없음(전체)
		assertThat(service().list(allPage(), null, "   ").rows()).hasSize(2);
	}

	@Test
	void 정렬키_user_asc_desc() {
		given(links.findAllActive()).willReturn(List.of(
				link(1, 10, 100, "a", "own", 12, "2026-08-01T00:00:00+09:00"),
				link(2, 11, 101, "b", "own", 12, "2026-08-02T00:00:00+09:00")));
		given(users.findByIds(anyCollection())).willReturn(List.of(
				user(10, "zzz@test.io", "", ""),
				user(11, "aaa@test.io", "", "")));
		given(reads.findAccountsByIds(anyCollection())).willReturn(List.of());
		given(reads.countPostsByBrand(anyCollection())).willReturn(List.of());
		given(reads.sumCallCountsByBrand(anyCollection(), org.mockito.ArgumentMatchers.any())).willReturn(List.of());

		assertThat(service().list(allPage(), "user:asc", null).rows())
				.extracting(r -> r.user().email()).containsExactly("aaa@test.io", "zzz@test.io");
		assertThat(service().list(allPage(), "user:desc", null).rows())
				.extracting(r -> r.user().email()).containsExactly("zzz@test.io", "aaa@test.io");
	}

	@Test
	void 정렬키_username_asc_desc() {
		given(links.findAllActive()).willReturn(List.of(
				link(1, 10, 100, "zeta", "own", 12, "2026-08-01T00:00:00+09:00"),
				link(2, 11, 101, "alpha", "own", 12, "2026-08-02T00:00:00+09:00")));
		given(users.findByIds(anyCollection())).willReturn(List.of(user(10, "u1@test.io", "", ""),
				user(11, "u2@test.io", "", "")));
		given(reads.findAccountsByIds(anyCollection())).willReturn(List.of(
				account(100, "zeta", null, null), account(101, "alpha", null, null)));
		given(reads.countPostsByBrand(anyCollection())).willReturn(List.of());
		given(reads.sumCallCountsByBrand(anyCollection(), org.mockito.ArgumentMatchers.any())).willReturn(List.of());

		assertThat(service().list(allPage(), "username:asc", null).rows())
				.extracting(AdminBrandAccountRow::username).containsExactly("alpha", "zeta");
		assertThat(service().list(allPage(), "username:desc", null).rows())
				.extracting(AdminBrandAccountRow::username).containsExactly("zeta", "alpha");
	}

	@Test
	void 정렬키_postCount_asc_desc() {
		given(links.findAllActive()).willReturn(List.of(
				link(1, 10, 100, "a", "own", 12, "2026-08-01T00:00:00+09:00"),
				link(2, 11, 101, "b", "own", 12, "2026-08-02T00:00:00+09:00")));
		given(users.findByIds(anyCollection())).willReturn(List.of(user(10, "u1@test.io", "", ""),
				user(11, "u2@test.io", "", "")));
		given(reads.findAccountsByIds(anyCollection())).willReturn(List.of(
				account(100, "a", null, null), account(101, "b", null, null)));
		given(reads.countPostsByBrand(anyCollection())).willReturn(List.of(
				new PostCountRow(100, 3), new PostCountRow(101, 9)));
		given(reads.sumCallCountsByBrand(anyCollection(), org.mockito.ArgumentMatchers.any())).willReturn(List.of());

		assertThat(service().list(allPage(), "postCount:asc", null).rows())
				.extracting(AdminBrandAccountRow::postCount).containsExactly(3L, 9L);
		assertThat(service().list(allPage(), "postCount:desc", null).rows())
				.extracting(AdminBrandAccountRow::postCount).containsExactly(9L, 3L);
	}

	@Test
	void 정렬키_crawlingCalls는_total_기준_asc_desc() {
		given(links.findAllActive()).willReturn(List.of(
				link(1, 10, 100, "a", "own", 12, "2026-08-01T00:00:00+09:00"),
				link(2, 11, 101, "b", "own", 12, "2026-08-02T00:00:00+09:00")));
		given(users.findByIds(anyCollection())).willReturn(List.of(user(10, "u1@test.io", "", ""),
				user(11, "u2@test.io", "", "")));
		given(reads.findAccountsByIds(anyCollection())).willReturn(List.of(
				account(100, "a", null, null), account(101, "b", null, null)));
		given(reads.countPostsByBrand(anyCollection())).willReturn(List.of());
		given(reads.sumCallCountsByBrand(anyCollection(), org.mockito.ArgumentMatchers.any())).willReturn(List.of(
				new BrandCallSumRow(100, 50, 10), new BrandCallSumRow(101, 20, 20)));

		assertThat(service().list(allPage(), "crawlingCalls:asc", null).rows())
				.extracting(r -> r.crawlingCalls().total()).containsExactly(20L, 50L);
		assertThat(service().list(allPage(), "crawlingCalls:desc", null).rows())
				.extracting(r -> r.crawlingCalls().total()).containsExactly(50L, 20L);
	}

	@Test
	void 정렬키_collectionStatus_알파벳순_asc_desc_null은_최솟값() {
		given(links.findAllActive()).willReturn(List.of(
				link(1, 10, 100, "ready-acc", "own", 12, "2026-08-01T00:00:00+09:00"),
				link(2, 11, 101, "error-acc", "own", 12, "2026-08-02T00:00:00+09:00"),
				link(3, 12, 102, "collecting-acc", "own", 12, "2026-08-03T00:00:00+09:00"),
				link(4, 13, 103, "unknown-acc", "own", 12, "2026-08-04T00:00:00+09:00")));
		given(users.findByIds(anyCollection())).willReturn(List.of(
				user(10, "u10@test.io", "", ""), user(11, "u11@test.io", "", ""),
				user(12, "u12@test.io", "", ""), user(13, "u13@test.io", "", "")));
		given(reads.countPostsByBrand(anyCollection())).willReturn(List.of());
		given(reads.sumCallCountsByBrand(anyCollection(), org.mockito.ArgumentMatchers.any())).willReturn(List.of());

		// error 상태를 만들려면 backfillError가 필요 — account() 헬퍼를 확장하지 않고 직접 구성.
		BrandAccountRow errorAccount = new BrandAccountRow(101, "error-acc", null, null,
				OffsetDateTime.parse("2026-01-01T00:00:00Z"), null, "실패", 0L, 0L, 0L, null, null, null, null, null,
				"ACTIVE", null, 12, OffsetDateTime.parse("2026-01-01T00:00:00Z"), false, null);
		BrandAccountRow collectingAccount = new BrandAccountRow(102, "collecting-acc", null, null,
				OffsetDateTime.parse("2026-01-01T00:00:00Z"), null, null, 0L, 0L, 0L, null, null, null, null, null,
				"ACTIVE", null, 12, OffsetDateTime.parse("2026-01-01T00:00:00Z"), false, null);
		given(reads.findAccountsByIds(anyCollection())).willReturn(List.of(
				account(100, "ready-acc", OffsetDateTime.parse("2026-08-10T00:00:00Z"), null),
				errorAccount, collectingAccount
				// 103(unknown-acc)은 monitoring DB에 없는 상태를 재현 — collectionStatus null
		));

		assertThat(service().list(allPage(), "collectionStatus:asc", null).rows())
				.extracting(AdminBrandAccountRow::collectionStatus)
				.containsExactly(null, "collecting", "error", "ready");
		assertThat(service().list(allPage(), "collectionStatus:desc", null).rows())
				.extracting(AdminBrandAccountRow::collectionStatus)
				.containsExactly("ready", "error", "collecting", null);
	}

	@Test
	void 정렬키_registeredAt_asc_desc() {
		given(links.findAllActive()).willReturn(List.of(
				link(1, 10, 100, "a", "own", 12, "2026-08-01T00:00:00+09:00"),
				link(2, 11, 101, "b", "own", 12, "2026-08-10T00:00:00+09:00")));
		given(users.findByIds(anyCollection())).willReturn(List.of(user(10, "u1@test.io", "", ""),
				user(11, "u2@test.io", "", "")));
		given(reads.findAccountsByIds(anyCollection())).willReturn(List.of());
		given(reads.countPostsByBrand(anyCollection())).willReturn(List.of());
		given(reads.sumCallCountsByBrand(anyCollection(), org.mockito.ArgumentMatchers.any())).willReturn(List.of());

		assertThat(service().list(allPage(), "registeredAt:asc", null).rows())
				.extracting(AdminBrandAccountRow::accountId).containsExactly("100", "101");
		// 기본값(파라미터 미지정)도 registeredAt:desc와 동일해야 한다.
		assertThat(service().list(allPage(), null, null).rows())
				.extracting(AdminBrandAccountRow::accountId).containsExactly("101", "100");
	}

	@Test
	void 정렬_타이브레이크는_registeredAt_desc_accountId_asc_userId_asc() {
		// 셋 다 같은 postCount(0)라 postCount 정렬은 전부 동점 — 타이브레이크로 갈린다.
		given(links.findAllActive()).willReturn(List.of(
				link(1, 20, 300, "a", "own", 12, "2026-08-01T00:00:00+09:00"),   // registeredAt 가장 이름
				link(2, 10, 100, "b", "own", 12, "2026-08-05T00:00:00+09:00"),   // registeredAt 동일, accountId 작음
				link(3, 5, 200, "c", "own", 12, "2026-08-05T00:00:00+09:00")));  // registeredAt 동일, accountId 큼
		given(users.findByIds(anyCollection())).willReturn(List.of(
				user(20, "u20@test.io", "", ""), user(10, "u10@test.io", "", ""), user(5, "u5@test.io", "", "")));
		given(reads.findAccountsByIds(anyCollection())).willReturn(List.of());
		given(reads.countPostsByBrand(anyCollection())).willReturn(List.of());
		given(reads.sumCallCountsByBrand(anyCollection(), org.mockito.ArgumentMatchers.any())).willReturn(List.of());

		assertThat(service().list(allPage(), "postCount:asc", null).rows())
				.extracting(AdminBrandAccountRow::accountId)
				// registeredAt desc 먼저(08-05 둘 vs 08-01 하나) → 그 안에서 accountId asc(100 < 200)
				.containsExactly("100", "200", "300");
	}

	@Test
	void 알수없는_키나_방향은_400이다() {
		AdminBrandAccountService service = service();
		AdminPageRequest page = allPage();
		assertThatThrownBy(() -> service.list(page, "unknownKey:asc", null)).isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> service.list(page, "registeredAt:sideways", null)).isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> service.list(page, "malformed", null)).isInstanceOf(V1ApiException.class);
	}

	@Test
	void offset_page_limit_정규화() {
		AdminPageRequest byOffset = AdminPageRequest.ofOffset(40, 10);
		assertThat(byOffset.offset()).isEqualTo(40);
		assertThat(byOffset.limit()).isEqualTo(10);

		AdminPageRequest byPage = AdminPageRequest.of(3, 10);
		assertThat(byPage.offset()).isEqualTo(20);

		// limit 상한(100)·하한(1) 클램프
		assertThat(AdminPageRequest.ofOffset(0, 500).limit()).isEqualTo(100);
		assertThat(AdminPageRequest.ofOffset(0, 0).limit()).isEqualTo(1);
		// 음수 offset은 0으로 방어
		assertThat(AdminPageRequest.ofOffset(-5, 10).offset()).isEqualTo(0);
	}

	@Test
	void 월경계는_KST_이번달_1일이다() {
		// CLOCK = 2026-08-20T01:00:00Z = KST 08-20 10:00 → 이번 달 시작 = 08-01.
		given(links.findAllActive()).willReturn(List.of(
				link(1, 10, 100, "a", "own", 12, "2026-01-01T00:00:00+09:00")));
		given(users.findByIds(anyCollection())).willReturn(List.of(user(10, "u1@test.io", "", "")));
		given(reads.findAccountsByIds(anyCollection())).willReturn(List.of(account(100, "a", null, null)));
		given(reads.countPostsByBrand(anyCollection())).willReturn(List.of());

		service().list(allPage(), null, null);

		then_month_start_arg_is("2026-08-01");
	}

	private void then_month_start_arg_is(String expectedIso) {
		org.mockito.ArgumentCaptor<LocalDate> captor = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
		org.mockito.Mockito.verify(reads).sumCallCountsByBrand(anyCollection(), captor.capture());
		assertThat(captor.getValue()).isEqualTo(LocalDate.parse(expectedIso));
	}

	@Test
	void monitoring_비활성이면_링크와_유저만으로_행이_나가고_모니터링_필드는_비어있다() {
		given(links.findAllActive()).willReturn(List.of(
				link(1, 10, 100, "link-snapshot-name", "competitor", 6, "2026-08-01T00:00:00+09:00")));
		given(users.findByIds(anyCollection())).willReturn(List.of(user(10, "u1@test.io", "이름", "회사")));

		AdminBrandAccountService.Result result = serviceWithoutMonitoring().list(allPage(), null, null);

		assertThat(result.rows()).hasSize(1);
		AdminBrandAccountRow row = result.rows().get(0);
		assertThat(row.username()).isEqualTo("link-snapshot-name");   // monitoring 없으니 링크 스냅샷 사용
		assertThat(row.postCount()).isZero();
		assertThat(row.crawlingCalls().total()).isZero();
		assertThat(row.crawlingCalls().month()).isZero();
		assertThat(row.collectionStatus()).isNull();
		assertThat(row.backfillCompletedAt()).isNull();
		assertThat(row.lastCollectedAt()).isNull();
		assertThat(row.collectionMonths()).isEqualTo(6);
		assertThat(row.mode()).isEqualTo("competitor");
		assertThat(row.user().name()).isEqualTo("이름");
		assertThat(row.user().orgName()).isEqualTo("회사");
	}

	@Test
	void 같은_계정을_여러_유저가_등록하면_행마다_계정값이_중복된다() {
		given(links.findAllActive()).willReturn(List.of(
				link(1, 10, 100, "shared", "own", 12, "2026-08-01T00:00:00+09:00"),
				link(2, 11, 100, "shared", "competitor", 12, "2026-08-02T00:00:00+09:00")));
		given(users.findByIds(anyCollection())).willReturn(List.of(
				user(10, "first@test.io", "", ""), user(11, "second@test.io", "", "")));
		given(reads.findAccountsByIds(anyCollection())).willReturn(List.of(account(100, "shared", null, null)));
		given(reads.countPostsByBrand(anyCollection())).willReturn(List.of(new PostCountRow(100, 42)));
		given(reads.sumCallCountsByBrand(anyCollection(), org.mockito.ArgumentMatchers.any()))
				.willReturn(List.of(new BrandCallSumRow(100, 500, 30)));

		AdminBrandAccountService.Result result = service().list(allPage(), "user:asc", null);

		assertThat(result.rows()).hasSize(2);
		assertThat(result.rows()).allSatisfy(r -> {
			assertThat(r.accountId()).isEqualTo("100");
			assertThat(r.postCount()).isEqualTo(42);
			assertThat(r.crawlingCalls().total()).isEqualTo(500);
		});
		assertThat(result.rows()).extracting(r -> r.user().email())
				.containsExactly("first@test.io", "second@test.io");
		assertThat(result.rows()).extracting(AdminBrandAccountRow::mode).containsExactly("own", "competitor");
	}

	// --- 60초 캐시(2026-09-04, staging monitoring-ro 풀 경합 실측 대응) ---

	@Test
	void 조립_결과는_60초_안에_재조회하면_리포지토리를_다시_부르지_않는다() {
		given(links.findAllActive()).willReturn(List.of(
				link(1, 10, 100, "a", "own", 12, "2026-08-01T00:00:00+09:00")));
		given(users.findByIds(anyCollection())).willReturn(List.of(user(10, "u1@test.io", "", "")));
		given(reads.findAccountsByIds(anyCollection())).willReturn(List.of());
		given(reads.countPostsByBrand(anyCollection())).willReturn(List.of());
		given(reads.sumCallCountsByBrand(anyCollection(), org.mockito.ArgumentMatchers.any())).willReturn(List.of());

		AdminBrandAccountService service = service();   // 같은 인스턴스를 재사용해야 캐시가 걸린다
		service.list(allPage(), null, null);
		// 정렬·검색이 바뀌어도 조립(4쿼리)은 캐시된 목록에서 재사용돼야 한다.
		service.list(allPage(), "username:asc", "a");

		org.mockito.Mockito.verify(links, org.mockito.Mockito.times(1)).findAllActive();
		org.mockito.Mockito.verify(reads, org.mockito.Mockito.times(1)).findAccountsByIds(anyCollection());
	}

	@Test
	void TTL_60초가_지나면_다시_조회한다() {
		given(links.findAllActive()).willReturn(List.of(
				link(1, 10, 100, "a", "own", 12, "2026-08-01T00:00:00+09:00")));
		given(users.findByIds(anyCollection())).willReturn(List.of(user(10, "u1@test.io", "", "")));
		given(reads.findAccountsByIds(anyCollection())).willReturn(List.of());
		given(reads.countPostsByBrand(anyCollection())).willReturn(List.of());
		given(reads.sumCallCountsByBrand(anyCollection(), org.mockito.ArgumentMatchers.any())).willReturn(List.of());

		MutableClock clock = new MutableClock(Instant.parse("2026-08-20T01:00:00Z"));
		AdminBrandAccountService service = new AdminBrandAccountService(links, users, Optional.of(reads), clock);

		service.list(allPage(), null, null);
		clock.advance(Duration.ofSeconds(61));   // CACHE_TTL(60초) 경과
		service.list(allPage(), null, null);

		org.mockito.Mockito.verify(links, org.mockito.Mockito.times(2)).findAllActive();
	}

	/** 테스트 전용 가변 Clock — {@link AdminBrandAccountService#CACHE_TTL} 경과를 재현한다. */
	private static final class MutableClock extends Clock {
		private final AtomicReference<Instant> instant;
		private final ZoneId zone;

		MutableClock(Instant start) {
			this(new AtomicReference<>(start), ZoneOffset.UTC);
		}

		private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
			this.instant = instant;
			this.zone = zone;
		}

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return new MutableClock(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant.get();
		}

		void advance(Duration duration) {
			instant.updateAndGet(i -> i.plus(duration));
		}
	}

	@Test
	void 브랜드_계정이_monitoring_DB에_없어도_던지지_않고_행은_나간다() {
		given(links.findAllActive()).willReturn(List.of(
				link(1, 10, 999, "orphan", "own", 12, "2026-08-01T00:00:00+09:00")));
		given(users.findByIds(anyCollection())).willReturn(List.of(user(10, "u1@test.io", "", "")));
		given(reads.findAccountsByIds(anyCollection())).willReturn(List.of());   // 계정 없음
		given(reads.countPostsByBrand(anyCollection())).willReturn(List.of());
		given(reads.sumCallCountsByBrand(anyCollection(), org.mockito.ArgumentMatchers.any())).willReturn(List.of());

		AdminBrandAccountService.Result result = service().list(allPage(), null, null);

		assertThat(result.rows()).hasSize(1);
		AdminBrandAccountRow row = result.rows().get(0);
		assertThat(row.username()).isEqualTo("orphan");   // 계정 없으니 링크 스냅샷 폴백
		assertThat(row.postCount()).isZero();
		assertThat(row.collectionStatus()).isNull();
	}
}
