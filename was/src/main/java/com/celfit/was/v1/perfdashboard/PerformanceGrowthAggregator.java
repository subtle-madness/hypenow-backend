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
 *
 * <p><b>부분 버킷 라벨은 요청 구간으로 클램프한다</b>(PR ③ Task 1 리뷰) — WEEK·MONTH에서 from/to가
 * 버킷 중간이면 그 버킷에 실리는 건 <b>구간과의 교집합</b>뿐인데, 라벨이 온전한 버킷 경계(월요일~
 * 일요일·1일~월말)면 FE 축에서 양끝 버킷이 이유 없이 과소로 보인다. 그래서 Point의 start·end를
 * {@code max(버킷시작, from)}·{@code min(버킷끝, to)}로 내려 <b>라벨이 실제 집계 범위를 정직하게
 * 반영</b>하게 한다 — 신규 필드 없이도 FE가 "라벨 길이가 온전한 버킷보다 짧다"로 부분 버킷을
 * 식별한다. from/to가 null인 쪽은 클램프하지 않는다(그 끝은 요청이 자른 게 아니라 데이터 범위다).
 */
public final class PerformanceGrowthAggregator {

	private PerformanceGrowthAggregator() {
	}

	/** 버킷 단위 — 응답의 {@code granularity}는 이 이름의 소문자다. */
	public enum Granularity { DAY, WEEK, MONTH }

	/**
	 * 집계에 실제로 쓰이는 유효 범위(양끝 포함) — {@link #effectiveRange}의 결과다.
	 *
	 * @param from 시작(null이면 범위 없음 — 유효 ref 0건)
	 * @param to 끝(null이면 범위 없음)
	 */
	public record Range(LocalDate from, LocalDate to) {

		/** 접을 버킷이 없는 범위 — 한쪽이 미확정이거나 뒤집힌 구간이다. */
		public boolean empty() {
			return from == null || to == null || from.isAfter(to);
		}
	}

	/**
	 * 유효 범위 산출 — 요청 from/to가 있으면 그 값, 없는 쪽은 <b>데이터 범위</b>(업로드일 미상을 뺀
	 * refs의 최소·최대 업로드일)다. 유효 ref가 0건이고 그쪽 끝도 지정되지 않으면 null이 남는다.
	 *
	 * <p>{@link #aggregate}와 컨트롤러의 <b>버킷 예산 재판정</b>이 같은 메서드를 부른다 — 규칙이 갈리면
	 * 재판정한 버킷 수와 실제 생성 수가 어긋나 상한이 헐거워지거나 정상 조회를 막는다.
	 */
	public static Range effectiveRange(List<DashboardRef> refs, LocalDate from, LocalDate to) {
		LocalDate minUploadedOn = null;
		LocalDate maxUploadedOn = null;
		// 양쪽이 다 지정됐으면 데이터 범위는 결과에 영향이 없지만, 순회 1회라 분기를 두지 않는다.
		for (DashboardRef ref : refs) {
			LocalDate uploadedOn = ref.uploadedOn();
			if (uploadedOn == null) {
				continue;
			}
			if (minUploadedOn == null || uploadedOn.isBefore(minUploadedOn)) {
				minUploadedOn = uploadedOn;
			}
			if (maxUploadedOn == null || uploadedOn.isAfter(maxUploadedOn)) {
				maxUploadedOn = uploadedOn;
			}
		}
		return new Range(from != null ? from : minUploadedOn, to != null ? to : maxUploadedOn);
	}

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

		Range range = effectiveRange(refs, from, to);
		if (range.empty()) {
			// 유효 ref도 없고 범위도 주어지지 않았다(또는 빈 구간) — 접을 버킷 자체가 없다.
			List<AccountSeries> empty = new ArrayList<>(ids.size());
			ids.forEach(id -> empty.add(new AccountSeries(id, List.of())));
			return new PerformanceGrowthResponse(label, List.copyOf(empty), List.of());
		}
		LocalDate rangeFrom = range.from();
		LocalDate rangeTo = range.to();

		Map<LocalDate, List<DashboardRef>> total = new HashMap<>();
		Map<String, Map<LocalDate, List<DashboardRef>>> byAccount = new LinkedHashMap<>();
		ids.forEach(id -> byAccount.computeIfAbsent(id, key -> new HashMap<>()));
		for (DashboardRef ref : refs) {
			LocalDate uploadedOn = ref.uploadedOn();
			// 업로드일 미상은 어느 버킷에도 실리지 않는다(범위 산출에서도 같은 규칙 — effectiveRange).
			if (uploadedOn == null || uploadedOn.isBefore(rangeFrom) || uploadedOn.isAfter(rangeTo)) {
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
			accounts.add(new AccountSeries(id, fold(starts, byAccount.get(id), granularity, from, to)));
		}
		return new PerformanceGrowthResponse(label, List.copyOf(accounts),
				fold(starts, total, granularity, from, to));
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
			Granularity granularity, LocalDate from, LocalDate to) {
		List<Point> points = new ArrayList<>(starts.size());
		for (LocalDate start : starts) {
			points.add(foldOne(start, byBucket.getOrDefault(start, List.of()), granularity, from, to));
		}
		return List.copyOf(points);
	}

	/**
	 * 버킷 1개 접기 — 합계는 아는 값만, 하나도 모르면 null이고 못 더한 사유는 카운트로 남는다.
	 * 라벨은 요청 구간과의 교집합으로 클램프한다(클래스 javadoc) — 빈 버킷·계정 시리즈도 같은 경로라
	 * 총계와 계정 축의 라벨이 언제나 같다.
	 *
	 * <p><b>followersSum은 참여율 분모다</b>(FE 요청 2026-08-27 ①) — 좋아요를 아는 게시물
	 * (스냅샷 있음 + 숨김 아님 + likes 값 있음)의 팔로워만 담는다. 팔로워를 알면 무조건 더하던 구
	 * 규칙은 분자(likes 합)와 모수가 어긋나 참여율을 과소 표시했다(5월 실측 약 19% 과소).
	 * {@code followersMissingCount}는 그 게이트와 무관하게 "팔로워 미상 건수" 의미를 유지한다.
	 *
	 * <p><b>likes 게이트가 {@code /comparison}과 갈리는 유일한 케이스</b>(PR ③ 리뷰): 여기서는
	 * {@code latestLikesHidden}이면 값이 있어도 합산에서 빼고 {@code likesHiddenCount}로만 남기는데,
	 * {@code /comparison}은 숨김 여부와 무관하게 값을 더한다(카운트는 따로 센다). 두 표면이 실제로
	 * 갈리는 입력은 {@code likes != null && likesHidden == true} 조합뿐이고, 그건 산지가 둘인 콘텐츠의
	 * 스냅샷 병합({@code PerformanceContentAssembler.mergeOne})에서만 나온다 — 한 산지는 값을 주고
	 * 다른 산지는 숨김을 관측했을 때 값은 채택되고 hidden은 OR로 남는다. 방향은 <b>growth가 보수적</b>
	 * (숨김 관측이 하나라도 있으면 값을 안 쓴다). mergeOne 정합은 이 PR 범위 밖이다.
	 */
	private static Point foldOne(LocalDate start, List<DashboardRef> bucket, Granularity granularity,
			LocalDate from, LocalDate to) {
		Long views = null;
		Long likes = null;
		Long comments = null;
		Long followersSum = null;
		int viewsMissingCount = 0;
		int likesHiddenCount = 0;
		int followersMissingCount = 0;

		for (DashboardRef ref : bucket) {
			// 팔로워 미상 카운트는 작성자 속성이라 스냅샷 유무와 무관하다(분모 합산 게이트와 다르다).
			if (ref.followers() == null) {
				followersMissingCount++;
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
				// 참여율 분모(FE 요청 2026-08-27 ①) — 분자(likes)에 실리는 게시물의 팔로워만 담는다.
				// 숨김·미상으로 분자에서 빠진 게시물이 분모에 남으면 참여율이 과소 표시된다.
				// 인플루언서 집계 ratedFollowers(likesKnown && followers != null)와 같은 게이트다.
				if (ref.latestLikes() != null && ref.followers() != null) {
					followersSum = accumulate(followersSum, ref.followers());
				}
			}
			comments = accumulate(comments, ref.latestComments());
		}

		return new Point(clampStart(start, from).toString(),
				clampEnd(bucketEnd(start, granularity), to).toString(), bucket.size(),
				views, likes, comments, followersSum,
				viewsMissingCount, likesHiddenCount, followersMissingCount);
	}

	/** 라벨 시작 = max(버킷 시작, from) — from이 없으면 클램프 없음(클래스 javadoc). */
	private static LocalDate clampStart(LocalDate start, LocalDate from) {
		return from != null && start.isBefore(from) ? from : start;
	}

	/** 라벨 끝 = min(버킷 끝, to) — to가 없으면 클램프 없음. */
	private static LocalDate clampEnd(LocalDate end, LocalDate to) {
		return to != null && end.isAfter(to) ? to : end;
	}

	/** null 유지 합산 — 첫 non-null에서 합이 시작되고, value가 null이면 sum을 건드리지 않는다. */
	private static Long accumulate(Long sum, Long value) {
		if (value == null) {
			return sum;
		}
		return sum == null ? value : sum + value;
	}
}
