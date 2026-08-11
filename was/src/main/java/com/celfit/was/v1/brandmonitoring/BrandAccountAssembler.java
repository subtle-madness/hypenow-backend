package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.v1.common.KstTimestamps;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * brand_account 1행 → BrandAccountResponse 조립 + 상태 유도(스펙 §5-2). 순수 변환 — DB·외부 호출 없음.
 *
 * <p>상태 유도 규칙(정본은 monitoring 컬럼 2개뿐 — 별도 상태 컬럼이 없다):
 * <ul>
 *   <li>{@code last_swept_at} 있음(한 번이라도 스윕 완주 = 서빙할 데이터 있음) → {@code ready}</li>
 *   <li>{@code last_swept_at} null + {@code backfill_error} 있음 → {@code error} + collectionError</li>
 *   <li>둘 다 null → {@code collecting}</li>
 * </ul>
 * ready 기준이 {@code last_swept_on}(이번 정책·가입 기준 완주)이 아니라 {@code last_swept_at}
 * (완주 사실값)인 이유(08-10): 정책 리셋·스윕 실패·재가입으로 last_swept_on이 비어도 기존 수집분은
 * 게시물 API가 그대로 서빙하므로, FE가 로딩 화면으로 데이터를 가리는 것보다 보여주는 게 맞다.
 * collecting은 "정말 보여줄 게 없는 첫 수집"에만 해당한다.
 * {@code brand_account.status}(ACTIVE/CLOSED)는 유도에 쓰지 않는다 — 값 공간이 가입/탈퇴라 "수집
 * 준비 중"을 표현하지 못한다. 등록 응답의 status("ACTIVE" 하드코딩)도 마찬가지로 신뢰하지 않는다.
 */
@Component
public class BrandAccountAssembler {

	private static final String PROFILE_URL_PREFIX = "https://www.instagram.com/";
	private static final String BACKFILL_FAILED = "BACKFILL_FAILED";
	private static final String STATUS_COLLECTING = "collecting";
	private static final String STATUS_READY = "ready";
	private static final String STATUS_ERROR = "error";

	private final int sweepHourKst;

	public BrandAccountAssembler(@Value("${was.brand.sweep-hour-kst:3}") int sweepHourKst) {
		this.sweepHourKst = sweepHourKst;
	}

	public BrandAccountResponse toResponse(BrandAccountRow row) {
		boolean ready = row.lastSweptAt() != null;
		// ready면 backfill_error가 남아 있어도 무시한다(재가입 백필 실패 등) — 데이터가 있는데
		// 에러 화면을 띄우는 오보를 막고, 미수집분은 다음 스윕이 백스톱한다.
		String status = ready ? STATUS_READY : (row.backfillError() != null ? STATUS_ERROR : STATUS_COLLECTING);
		BrandAccountResponse.CollectionError error = STATUS_ERROR.equals(status)
				? new BrandAccountResponse.CollectionError(BACKFILL_FAILED, row.backfillError())
				: null;

		String sweptAt = KstTimestamps.toKstIso(row.lastSweptAt());

		return new BrandAccountResponse(
				String.valueOf(row.id()),
				profile(row),
				status,
				KstTimestamps.toKstIso(row.registeredAt()),
				KstTimestamps.toKstIso(row.backfillCompletedAt()),
				sweptAt,
				sweptAt,   // 감지/추적 구분은 08-06 개정으로 폐지 — 매일 전량 스윕 하나가 둘 다 채운다
				nextScheduledAt(ZonedDateTime.now(KstTimestamps.KST), sweepHourKst),
				error,
				// createdAt = 등록 시각. was 링크 생성 시각과 초 단위로 같고(등록 트랜잭션 직전 monitoring
				// 호출), 재가입 때 registered_at이 갱신돼 "지금 보고 있는 가입"을 가리킨다.
				KstTimestamps.toKstIso(row.registeredAt()));
	}

	private static BrandAccountResponse.Profile profile(BrandAccountRow row) {
		return new BrandAccountResponse.Profile(
				PROFILE_URL_PREFIX + row.username() + "/",
				row.username(),
				row.fullName() == null ? "" : row.fullName(),
				row.profilePicUrl(),
				Boolean.TRUE.equals(row.isVerified()),
				row.mediaCount(),
				row.followers(),
				row.following(),
				row.biography() == null ? "" : row.biography(),
				row.externalUrl());
	}

	/**
	 * 다음 스윕 예정 시각 — KST 기준 매일 {@code hourKst}시 정각(monitoring 브랜드 스윕 크론과 동일 시각).
	 * 오늘 그 시각이 이미 지났으면 내일. 크론은 monitoring env로 주입되므로 값이 어긋나면
	 * {@code was.brand.sweep-hour-kst}로 맞춘다(표시 전용 — 실제 스케줄에 영향 없음).
	 */
	static String nextScheduledAt(ZonedDateTime at, int hourKst) {
		// 하루 경계는 반드시 KST에서 잘라야 한다 — 다른 존의 시각이 들어오면 날짜가 어긋난다.
		ZonedDateTime now = at.withZoneSameInstant(KstTimestamps.KST);
		ZonedDateTime today = now.truncatedTo(ChronoUnit.DAYS).plusHours(hourKst);
		ZonedDateTime next = today.isAfter(now) ? today : today.plusDays(1);
		return KstTimestamps.toKstIso(next.toOffsetDateTime());
	}
}
