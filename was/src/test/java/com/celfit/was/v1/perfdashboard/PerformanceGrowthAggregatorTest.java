package com.celfit.was.v1.perfdashboard;

import static com.celfit.was.v1.perfdashboard.PerformanceGrowthAggregator.Granularity.DAY;
import static com.celfit.was.v1.perfdashboard.PerformanceGrowthAggregator.Granularity.MONTH;
import static com.celfit.was.v1.perfdashboard.PerformanceGrowthAggregator.Granularity.WEEK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.celfit.was.v1.perfdashboard.PerformanceContentAssembler.DashboardRef;
import com.celfit.was.v1.perfdashboard.PerformanceGrowthResponse.Point;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 성장 시계열 집계 순수 함수 검증(스펙 2026-08-28 §5) — 버킷 경계·빈 버킷 연속성·결측 카운트를
 * DB 없이 고정한다. 필터링(기간·브랜드·스폰서십)은 호출부 몫이라 여기서 검증하지 않는다.
 */
class PerformanceGrowthAggregatorTest {

	@Test
	void month_버킷은_달력월이고_빈_달도_연속_생성된다() {
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-06-15"),
				ref("2026-08-03"),
				ref("2026-08-20")), MONTH, null, null, List.of());

		assertThat(res.granularity()).isEqualTo("month");
		assertThat(res.points()).extracting(Point::start)
				.containsExactly("2026-06-01", "2026-07-01", "2026-08-01");
		assertThat(res.points().get(0).end()).isEqualTo("2026-06-30");
		assertThat(res.points().get(0).contentCount()).isEqualTo(1);
		// 사이 빈 달도 Point가 있어야 차트 축이 끊기지 않는다.
		assertThat(res.points().get(1).contentCount()).isZero();
		assertThat(res.points().get(1).views()).isNull();
		assertThat(res.points().get(1).likes()).isNull();
		assertThat(res.points().get(1).comments()).isNull();
		assertThat(res.points().get(1).followersSum()).isNull();
		assertThat(res.points().get(1).viewsMissingCount()).isZero();
		assertThat(res.points().get(1).likesHiddenCount()).isZero();
		assertThat(res.points().get(1).followersMissingCount()).isZero();
		assertThat(res.points().get(2).contentCount()).isEqualTo(2);
	}

	@Test
	void month_버킷_끝은_달마다_다르다() {
		// 윤년 2월(29일)·31일 달 — 월말을 고정 상수로 계산하면 깨진다.
		var res = PerformanceGrowthAggregator.aggregate(List.of(ref("2028-02-10")),
				MONTH, LocalDate.parse("2028-01-31"), LocalDate.parse("2028-03-01"), List.of());

		// 양끝은 요청 구간으로 클램프된다 — 가운데 2월만 온전한 버킷이라 월말(윤년 29일)이 그대로다.
		assertThat(res.points()).extracting(Point::start)
				.containsExactly("2028-01-31", "2028-02-01", "2028-03-01");
		assertThat(res.points()).extracting(Point::end)
				.containsExactly("2028-01-31", "2028-02-29", "2028-03-01");
	}

	@Test
	void week_버킷은_ISO_월요일_시작이다() {
		// 2026-08-27(목)·2026-08-30(일)은 같은 주 — 둘 다 2026-08-24(월) 버킷이다.
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-27"),
				ref("2026-08-30")), WEEK, null, null, List.of());

		assertThat(res.granularity()).isEqualTo("week");
		assertThat(res.points()).hasSize(1);
		assertThat(res.points().get(0).start()).isEqualTo("2026-08-24");
		assertThat(res.points().get(0).end()).isEqualTo("2026-08-30");
		assertThat(res.points().get(0).contentCount()).isEqualTo(2);
	}

	@Test
	void week_버킷은_한_주씩_연속_전진한다() {
		// 2026-08-31(월)은 다음 주다 — 일요일 경계를 넘기면 버킷이 갈라진다.
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-30"),
				ref("2026-09-14")), WEEK, null, null, List.of());

		assertThat(res.points()).extracting(Point::start)
				.containsExactly("2026-08-24", "2026-08-31", "2026-09-07", "2026-09-14");
		assertThat(res.points()).extracting(Point::end)
				.containsExactly("2026-08-30", "2026-09-06", "2026-09-13", "2026-09-20");
		assertThat(res.points().get(1).contentCount()).isZero();
	}

	@Test
	void day_버킷은_하루_단위고_빈_날도_생성된다() {
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-26"),
				ref("2026-08-28")), DAY, null, null, List.of());

		assertThat(res.granularity()).isEqualTo("day");
		assertThat(res.points()).extracting(Point::start)
				.containsExactly("2026-08-26", "2026-08-27", "2026-08-28");
		assertThat(res.points()).allSatisfy(point -> assertThat(point.end()).isEqualTo(point.start()));
		assertThat(res.points().get(1).contentCount()).isZero();
	}

	@Test
	void from_to_지정_시_그_구간의_버킷을_만들고_구간_밖_ref는_없다() {
		// 호출부가 이미 기간 필터를 하지만 집계기 단독으로도 구간 밖 uploadedOn은 어디에도 안 실린다.
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-24"),   // from 이전
				ref("2026-08-26"),
				ref("2026-08-29")),  // to 이후
				DAY, LocalDate.parse("2026-08-25"), LocalDate.parse("2026-08-27"), List.of());

		assertThat(res.points()).extracting(Point::start)
				.containsExactly("2026-08-25", "2026-08-26", "2026-08-27");
		assertThat(res.points()).extracting(Point::contentCount).containsExactly(0, 1, 0);
	}

	@Test
	void 구간_밖_ref는_버킷이_구간을_덮어도_제외된다() {
		// 주 버킷은 2026-08-24(월)에서 시작하지만 from이 08-26이라 08-24 업로드는 실리지 않는다.
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-24"),
				ref("2026-08-27")),
				WEEK, LocalDate.parse("2026-08-26"), LocalDate.parse("2026-08-28"), List.of());

		assertThat(res.points()).hasSize(1);
		// 라벨은 버킷 시작(08-24)이 아니라 요청 구간과의 교집합이다(부분 버킷 클램프).
		assertThat(res.points().get(0).start()).isEqualTo("2026-08-26");
		assertThat(res.points().get(0).end()).isEqualTo("2026-08-28");
		assertThat(res.points().get(0).contentCount()).isEqualTo(1);
	}

	@Test
	void 부분_버킷의_라벨은_요청_구간으로_클램프된다() {
		// from=수요일·to=화요일 → 양끝 버킷이 잘린다. 라벨이 온전한 주(월~일)면 FE 축에서 양끝이
		// 이유 없이 과소로 보인다 — 라벨이 실제 집계 범위를 말해야 한다.
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-27", "12", true, 100L, null, false, null, null)),
				WEEK, LocalDate.parse("2026-08-26"), LocalDate.parse("2026-09-01"), List.of("12"));

		assertThat(res.points()).extracting(Point::start, Point::end).containsExactly(
				tuple("2026-08-26", "2026-08-30"),
				tuple("2026-08-31", "2026-09-01"));
		// 계정 시리즈·빈 버킷도 같은 라벨이다(차트 축 공유).
		assertThat(res.accounts().get(0).points()).extracting(Point::start, Point::end).containsExactly(
				tuple("2026-08-26", "2026-08-30"),
				tuple("2026-08-31", "2026-09-01"));
		assertThat(res.points().get(1).contentCount()).isZero();
	}

	@Test
	void 한쪽만_지정하면_그쪽만_클램프된다() {
		// to만 지정 — 시작 라벨은 데이터 범위(버킷 경계) 그대로다(요청이 자른 끝이 아니다).
		var res = PerformanceGrowthAggregator.aggregate(List.of(ref("2026-08-27")),
				WEEK, null, LocalDate.parse("2026-08-28"), List.of());

		assertThat(res.points()).hasSize(1);
		assertThat(res.points().get(0).start()).isEqualTo("2026-08-24");
		assertThat(res.points().get(0).end()).isEqualTo("2026-08-28");
	}

	@Test
	void 결측_규칙_조회수_좋아요숨김_팔로워를_각각_카운트한다() {
		// 같은 날 6건: 릴스·피드(views null)·스냅샷 없음·좋아요 숨김·전부 미상·스냅샷 없는 숨김.
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-26", null, true, 100L, 10L, false, 1L, 1000L),
				ref("2026-08-26", null, true, null, 20L, false, 2L, 2000L),
				ref("2026-08-26", null, false, 999L, 999L, false, 999L, 3000L),
				ref("2026-08-26", null, true, 300L, 40L, true, 4L, null),
				ref("2026-08-26", null, true, null, null, false, null, null),
				ref("2026-08-26", null, false, null, null, true, null, null)),
				DAY, null, null, List.of());

		var point = res.points().get(0);
		assertThat(point.contentCount()).isEqualTo(6);              // 스냅샷 유무 무관
		assertThat(point.views()).isEqualTo(400L);                  // 100+300 (피드·스냅샷 없음 제외)
		assertThat(point.viewsMissingCount()).isEqualTo(4);         // 피드·스냅샷 없음 2건·전부 미상
		assertThat(point.likes()).isEqualTo(30L);                   // 10+20 (숨김·스냅샷 없음 제외)
		assertThat(point.likesHiddenCount()).isEqualTo(1);          // 스냅샷 있는 숨김만
		assertThat(point.comments()).isEqualTo(7L);                 // 1+2+4
		assertThat(point.followersSum()).isEqualTo(3000L);          // likes 아는 1·2행만 — 1000+2000
		assertThat(point.followersMissingCount()).isEqualTo(3);
	}

	@Test
	void 관측이_하나도_없는_버킷은_합계가_null이고_게시물은_센다() {
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-26", null, false, null, null, false, null, null),
				ref("2026-08-26", null, true, null, null, false, null, null)),
				DAY, null, null, List.of());

		var point = res.points().get(0);
		assertThat(point.contentCount()).isEqualTo(2);
		assertThat(point.views()).isNull();
		assertThat(point.likes()).isNull();
		assertThat(point.comments()).isNull();
		assertThat(point.followersSum()).isNull();
		assertThat(point.viewsMissingCount()).isEqualTo(2);
		assertThat(point.followersMissingCount()).isEqualTo(2);
	}

	@Test
	void followersSum은_게시물별_작성자_팔로워_합이다() {
		// 같은 작성자 2건이면 2회 더한다(참여율 분모 정의 — 게시물당 1회).
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-26", null, true, null, 10L, false, null, 1000L),
				ref("2026-08-26", null, true, null, 20L, false, null, 1000L)),
				DAY, null, null, List.of());

		assertThat(res.points().get(0).followersSum()).isEqualTo(2000L);
		assertThat(res.points().get(0).followersMissingCount()).isZero();
	}

	@Test
	void followersSum은_좋아요를_아는_게시물만_담는다() {
		// 참여율 분모 규칙(FE 요청 2026-08-27 ①) — 분자(likes)가 숨김·미상으로 빠지는 게시물의
		// 팔로워가 분모에 남으면 분자·분모 모수가 어긋나 참여율이 과소 표시된다. 인플루언서 집계의
		// ratedFollowers(숨김 아님 + likes 있음 + 팔로워 있음)와 같은 게이트다.
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-26", null, true, null, 10L, false, 1L, 1000L),      // likes 앎 → 포함
				ref("2026-08-26", null, true, null, 40L, true, 1L, 2000L),       // 숨김 → 제외
				ref("2026-08-26", null, true, null, null, false, 1L, 4000L),     // likes 미상 → 제외
				ref("2026-08-26", null, false, null, null, false, null, 8000L)), // 스냅샷 없음 → 제외
				DAY, null, null, List.of());

		var point = res.points().get(0);
		assertThat(point.followersSum()).isEqualTo(1000L);
		// followersMissingCount의 의미(팔로워 미상 건수)는 불변이다 — 4건 모두 팔로워를 안다.
		assertThat(point.followersMissingCount()).isZero();
		assertThat(point.likesHiddenCount()).isEqualTo(1);
	}

	@Test
	void 계정_시리즈는_귀속_ref만_접고_0건_계정도_빈_시리즈를_유지한다() {
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-26", "12", true, 100L, null, false, null, null),
				ref("2026-08-28", null, true, 700L, null, false, null, null)),   // individual(미귀속)
				DAY, null, null, List.of("12", "15"));

		// 총계는 미귀속 포함.
		assertThat(res.points()).extracting(Point::contentCount).containsExactly(1, 0, 1);
		assertThat(res.points().get(2).views()).isEqualTo(700L);

		assertThat(res.accounts()).extracting(PerformanceGrowthResponse.AccountSeries::brandAccountId)
				.containsExactly("12", "15");                       // 인자 순서 그대로
		var twelve = res.accounts().get(0).points();
		assertThat(twelve).extracting(Point::contentCount).containsExactly(1, 0, 0);
		assertThat(twelve.get(0).views()).isEqualTo(100L);
		assertThat(twelve.get(2).views()).isNull();                 // 미귀속은 계정 시리즈에 없다
		// 0건 계정도 같은 축을 유지한다.
		assertThat(res.accounts().get(1).points()).extracting(Point::contentCount).containsExactly(0, 0, 0);

		// 계정 시리즈의 버킷 경계·개수는 총계와 동일하다(차트 축 공유).
		for (var series : res.accounts()) {
			assertThat(series.points()).extracting(Point::start, Point::end)
					.containsExactlyElementsOf(res.points().stream()
							.map(point -> tuple(point.start(), point.end()))
							.toList());
		}
	}

	@Test
	void 업로드일_미상_ref는_어디에도_안_실린다() {
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref(null, "12", true, 999L, 999L, false, 999L, 999L),
				ref("2026-08-26", "12", true, 100L, null, false, null, null)),
				DAY, null, null, List.of("12"));

		assertThat(res.points()).hasSize(1);
		assertThat(res.points().get(0).contentCount()).isEqualTo(1);
		assertThat(res.points().get(0).views()).isEqualTo(100L);
		assertThat(res.accounts().get(0).points().get(0).contentCount()).isEqualTo(1);
	}

	@Test
	void 계정_시리즈_순서는_인자_순서지_id_정렬이_아니다() {
		// 축 순서는 호출부(컨트롤러)가 정한 순서 그대로다 — 여기서 정렬하면 FE 범례 순서가 뒤집힌다.
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-26", "12", true, 100L, null, false, null, null),
				ref("2026-08-26", "15", true, 200L, null, false, null, null)),
				DAY, null, null, List.of("15", "12"));

		assertThat(res.accounts()).extracting(PerformanceGrowthResponse.AccountSeries::brandAccountId)
				.containsExactly("15", "12");
		assertThat(res.accounts().get(0).points().get(0).views()).isEqualTo(200L);
		assertThat(res.accounts().get(1).points().get(0).views()).isEqualTo(100L);
	}

	@Test
	void from이_to보다_뒤면_빈_시리즈다() {
		// 뒤집힌 구간은 버킷이 없다 — 무한 루프도, 역순 축도 만들지 않는다.
		var res = PerformanceGrowthAggregator.aggregate(List.of(ref("2026-08-26")),
				DAY, LocalDate.parse("2026-08-28"), LocalDate.parse("2026-08-26"), List.of("12"));

		assertThat(res.points()).isEmpty();
		assertThat(res.accounts()).hasSize(1);
		assertThat(res.accounts().get(0).points()).isEmpty();
	}

	@Test
	void from만_지정하면_to는_데이터_범위로_폴백한다() {
		// 한쪽만 지정 — 반대쪽 끝은 데이터의 최대 업로드일이다(구간 밖은 여전히 안 실린다).
		var res = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-24"),   // from 이전 — 제외
				ref("2026-08-26"),
				ref("2026-08-28")),
				DAY, LocalDate.parse("2026-08-26"), null, List.of());

		assertThat(res.points()).extracting(Point::start)
				.containsExactly("2026-08-26", "2026-08-27", "2026-08-28");
		assertThat(res.points()).extracting(Point::contentCount).containsExactly(1, 0, 1);
	}

	@Test
	void 유효_ref가_없고_범위도_없으면_빈_시리즈다() {
		var res = PerformanceGrowthAggregator.aggregate(List.of(ref(null)),
				DAY, null, null, List.of("12"));

		assertThat(res.points()).isEmpty();
		assertThat(res.accounts()).hasSize(1);
		assertThat(res.accounts().get(0).brandAccountId()).isEqualTo("12");
		assertThat(res.accounts().get(0).points()).isEmpty();
	}

	@Test
	void 결과는_입력_순서와_무관하게_버킷_오름차순이다() {
		var forward = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-26"), ref("2026-08-27"), ref("2026-08-28")), DAY, null, null, List.of());
		var shuffled = PerformanceGrowthAggregator.aggregate(List.of(
				ref("2026-08-28"), ref("2026-08-26"), ref("2026-08-27")), DAY, null, null, List.of());

		assertThat(shuffled.points()).extracting(Point::start)
				.containsExactly("2026-08-26", "2026-08-27", "2026-08-28");
		assertThat(shuffled).isEqualTo(forward);
	}

	@Test
	void bucketStart와_bucketEnd는_granularity별_경계를_준다() {
		var thursday = LocalDate.parse("2026-08-27");
		assertThat(PerformanceGrowthAggregator.bucketStart(thursday, DAY)).isEqualTo(thursday);
		assertThat(PerformanceGrowthAggregator.bucketStart(thursday, WEEK))
				.isEqualTo(LocalDate.parse("2026-08-24"));
		assertThat(PerformanceGrowthAggregator.bucketStart(LocalDate.parse("2026-08-24"), WEEK))
				.isEqualTo(LocalDate.parse("2026-08-24"));   // 월요일은 자기 자신
		assertThat(PerformanceGrowthAggregator.bucketStart(thursday, MONTH))
				.isEqualTo(LocalDate.parse("2026-08-01"));

		assertThat(PerformanceGrowthAggregator.bucketEnd(thursday, DAY)).isEqualTo(thursday);
		assertThat(PerformanceGrowthAggregator.bucketEnd(LocalDate.parse("2026-08-24"), WEEK))
				.isEqualTo(LocalDate.parse("2026-08-30"));
		assertThat(PerformanceGrowthAggregator.bucketEnd(LocalDate.parse("2026-02-01"), MONTH))
				.isEqualTo(LocalDate.parse("2026-02-28"));
	}

	/** 버킷 축만 varying — 귀속·지표는 기본값. */
	private static DashboardRef ref(String uploadedOn) {
		return ref(uploadedOn, null, true, null, null, false, null, null);
	}

	/** 전량 지정 — 집계와 무관한 나머지 필드(contentKey·source·status·handle 등)는 고정값. */
	private static DashboardRef ref(String uploadedOn, String brandAccountId, boolean hasSnapshots,
			Long views, Long likes, boolean likesHidden, Long comments, Long followers) {
		return new DashboardRef("ck", "sc", "post", null, "collected",
				uploadedOn == null ? null : LocalDate.parse(uploadedOn), brandAccountId, null,
				"a", null, null, followers,
				views, likes, likesHidden, comments, hasSnapshots);
	}
}
