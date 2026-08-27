package com.celfit.was.v1.perfdashboard;

import com.celfit.was.v1.perfdashboard.PerformanceContentAssembler.DashboardRef;
import com.celfit.was.v1.perfdashboard.PerformanceGrowthResponse.AccountSeries;
import com.celfit.was.v1.perfdashboard.PerformanceGrowthResponse.Point;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 성장 시계열 집계(스펙 2026-08-28 §5) — 목록·인플루언서 탭과 같은 인덱스 ref를 업로드일 버킷으로
 * 접는 순수 함수다(DB 신규 쿼리 없음). 지표는 ref가 이미 들고 있는 <b>최신 스냅샷</b> 유래 값이라
 * 여기서 스냅샷을 다시 고르지 않는다 — 즉 "그 날 관측된 값"이 아니라 "그 날 올라온 게시물의 현재
 * 값"의 합이다(FE 계약: 게시일 기준 성과 추이).
 *
 * <p>결과는 입력 순서와 무관하게 결정적이다 — 버킷은 범위에서 생성돼 항상 오름차순이고, 빈 버킷도
 * Point로 나온다(차트 축이 끊기지 않게).
 */
public final class PerformanceGrowthAggregator {

	private PerformanceGrowthAggregator() {
	}

	/** 버킷 단위 — 응답의 {@code granularity}는 이 이름의 소문자다. */
	public enum Granularity { DAY, WEEK, MONTH }

	/**
	 * 필터 적용 후 ref → 시계열 집계. 업로드일 미상 ref는 제외한다. from/to가 null이면 그쪽 끝을
	 * 데이터 범위(최소·최대 업로드일)로 잡고, 지정되면 그 구간의 버킷을 빈 버킷 포함 연속 생성한다.
	 * 구간을 지정하면 구간 밖 업로드일은 (버킷이 구간을 덮더라도) 어느 버킷에도 실리지 않는다.
	 *
	 * @param accountIds 계정 축 — 시리즈를 만들 brandAccountId 목록(0건 계정도 시리즈 유지).
	 *     총계 points는 accountIds와 무관하게 refs 전량(미귀속 individual 포함)이다.
	 */
	public static PerformanceGrowthResponse aggregate(List<DashboardRef> refs, Granularity granularity,
			LocalDate from, LocalDate to, List<String> accountIds) {
		List<String> ids = accountIds == null ? List.of() : accountIds;
		String label = granularity.name().toLowerCase(Locale.ROOT);

		List<DashboardRef> dated = new ArrayList<>(refs.size());
		LocalDate minUploadedOn = null;
		LocalDate maxUploadedOn = null;
		for (DashboardRef ref : refs) {
			LocalDate uploadedOn = ref.uploadedOn();
			if (uploadedOn == null) {
				continue;
			}
			dated.add(ref);
			if (minUploadedOn == null || uploadedOn.isBefore(minUploadedOn)) {
				minUploadedOn = uploadedOn;
			}
			if (maxUploadedOn == null || uploadedOn.isAfter(maxUploadedOn)) {
				maxUploadedOn = uploadedOn;
			}
		}

		LocalDate rangeFrom = from != null ? from : minUploadedOn;
		LocalDate rangeTo = to != null ? to : maxUploadedOn;
		if (rangeFrom == null || rangeTo == null || rangeFrom.isAfter(rangeTo)) {
			// 유효 ref도 없고 범위도 주어지지 않았다(또는 빈 구간) — 접을 버킷 자체가 없다.
			List<AccountSeries> empty = new ArrayList<>(ids.size());
			ids.forEach(id -> empty.add(new AccountSeries(id, List.of())));
			return new PerformanceGrowthResponse(label, List.copyOf(empty), List.of());
		}

		Map<LocalDate, List<DashboardRef>> total = new HashMap<>();
		Map<String, Map<LocalDate, List<DashboardRef>>> byAccount = new LinkedHashMap<>();
		ids.forEach(id -> byAccount.computeIfAbsent(id, key -> new HashMap<>()));
		for (DashboardRef ref : dated) {
			LocalDate uploadedOn = ref.uploadedOn();
			if (uploadedOn.isBefore(rangeFrom) || uploadedOn.isAfter(rangeTo)) {
				continue;
			}
			LocalDate key = bucketStart(uploadedOn, granularity);
			total.computeIfAbsent(key, bucket -> new ArrayList<>()).add(ref);
			// 미귀속(individual)·축에 없는 계정은 총계에만 실린다.
			Map<LocalDate, List<DashboardRef>> account = ref.brandAccountId() == null
					? null : byAccount.get(ref.brandAccountId());
			if (account != null) {
				account.computeIfAbsent(key, bucket -> new ArrayList<>()).add(ref);
			}
		}

		List<LocalDate> starts = bucketStarts(rangeFrom, rangeTo, granularity);
		List<AccountSeries> accounts = new ArrayList<>(ids.size());
		for (String id : ids) {
			accounts.add(new AccountSeries(id, fold(starts, byAccount.get(id), granularity)));
		}
		return new PerformanceGrowthResponse(label, List.copyOf(accounts), fold(starts, total, granularity));
	}

	/** 버킷 시작일 — DAY: 그대로, WEEK: ISO 월요일, MONTH: 1일. (패키지 공개 — 경계 테스트 대상) */
	static LocalDate bucketStart(LocalDate date, Granularity granularity) {
		return switch (granularity) {
			case DAY -> date;
			// with(DayOfWeek)는 ISO 주(월~일) 기준이라 같은 주의 월요일로 간다 — 월요일이면 자기 자신.
			case WEEK -> date.with(DayOfWeek.MONDAY);
			case MONTH -> date.withDayOfMonth(1);
		};
	}

	/** 버킷 종료일(양끝 포함) — DAY: 시작일, WEEK: +6일, MONTH: 월말(달마다 다름). */
	static LocalDate bucketEnd(LocalDate start, Granularity granularity) {
		return switch (granularity) {
			case DAY -> start;
			case WEEK -> start.plusDays(6);
			case MONTH -> start.plusMonths(1).minusDays(1);
		};
	}

	/** 범위를 덮는 버킷 시작일들 — 오름차순 연속(빈 버킷 포함)이라 차트 축이 끊기지 않는다. */
	private static List<LocalDate> bucketStarts(LocalDate rangeFrom, LocalDate rangeTo, Granularity granularity) {
		LocalDate last = bucketStart(rangeTo, granularity);
		List<LocalDate> starts = new ArrayList<>();
		for (LocalDate cursor = bucketStart(rangeFrom, granularity); !cursor.isAfter(last);
				cursor = next(cursor, granularity)) {
			starts.add(cursor);
		}
		return starts;
	}

	/** 다음 버킷 시작일 — 시작일에서 걸음을 더하므로 달 길이·주 경계가 자동으로 맞는다. */
	private static LocalDate next(LocalDate start, Granularity granularity) {
		return switch (granularity) {
			case DAY -> start.plusDays(1);
			case WEEK -> start.plusWeeks(1);
			case MONTH -> start.plusMonths(1);
		};
	}

	private static List<Point> fold(List<LocalDate> starts, Map<LocalDate, List<DashboardRef>> byBucket,
			Granularity granularity) {
		List<Point> points = new ArrayList<>(starts.size());
		for (LocalDate start : starts) {
			points.add(foldOne(start, byBucket.getOrDefault(start, List.of()), granularity));
		}
		return List.copyOf(points);
	}

	/** 버킷 1개 접기 — 합계는 아는 값만, 하나도 모르면 null이고 못 더한 사유는 카운트로 남는다. */
	private static Point foldOne(LocalDate start, List<DashboardRef> bucket, Granularity granularity) {
		Long views = null;
		Long likes = null;
		Long comments = null;
		Long followersSum = null;
		int viewsMissingCount = 0;
		int likesHiddenCount = 0;
		int followersMissingCount = 0;

		for (DashboardRef ref : bucket) {
			// 팔로워는 작성자 속성이라 스냅샷 유무와 무관하다(지표 3종과 게이트가 다르다).
			if (ref.followers() == null) {
				followersMissingCount++;
			}
			else {
				followersSum = accumulate(followersSum, ref.followers());
			}
			// 지표 합산은 스냅샷 있는 게시물만 — 관측 전무는 "0"이 아니라 모수 밖이다.
			boolean observed = ref.hasSnapshots();
			if (!observed || ref.latestViews() == null) {
				viewsMissingCount++;   // 피드 게시물은 조회수가 항상 null이다
			}
			else {
				views = accumulate(views, ref.latestViews());
			}
			if (!observed) {
				continue;
			}
			if (ref.latestLikesHidden()) {
				likesHiddenCount++;
			}
			else {
				likes = accumulate(likes, ref.latestLikes());
			}
			comments = accumulate(comments, ref.latestComments());
		}

		return new Point(start.toString(), bucketEnd(start, granularity).toString(), bucket.size(),
				views, likes, comments, followersSum,
				viewsMissingCount, likesHiddenCount, followersMissingCount);
	}

	/** null 유지 합산 — 첫 non-null에서 합이 시작되고, value가 null이면 sum을 건드리지 않는다. */
	private static Long accumulate(Long sum, Long value) {
		if (value == null) {
			return sum;
		}
		return sum == null ? value : sum + value;
	}
}
