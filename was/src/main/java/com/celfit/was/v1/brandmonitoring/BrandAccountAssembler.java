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
 * <p>상태 유도 규칙(정본은 monitoring 컬럼 몇 개뿐 — 별도 상태 컬럼이 없다):
 * <ul>
 *   <li>{@code last_swept_on} 있음(이번 창 기준 완주) → {@code ready}</li>
 *   <li>{@code last_swept_at} 있음 → {@code ready}(첫 등록 배치 완결·재가입·기간 확장 중)</li>
 *   <li>전부 null + {@code backfill_error} 있음 → {@code error} + collectionError, 아니면 {@code collecting}</li>
 * </ul>
 * 둘째 분기의 ready 기준이 {@code last_swept_on}이 아니라 {@code last_swept_at}(완주 사실값)인
 * 이유(08-10): 정책 리셋·스윕 실패·재가입으로 last_swept_on이 비어도 기존 수집분은 게시물 API가
 * 그대로 서빙하므로, FE가 로딩 화면으로 데이터를 가리는 것보다 보여주는 게 맞다.
 * 남은 {@code collecting}은 "보여줄 게 없는 첫 수집" 하나뿐이다.
 *
 * <p>2026-08-13 개정: 08-12에 넣었던 "확장 중 → collecting" 분기를 제거했다. 확장
 * ({@code BrandRepository.expandWindow})이 backfill_completed_at을 리셋하게 되면서 그 분기의
 * 조건(완주 이력 있음 + last_swept_on 빔)이 도달 불가가 됐다. FE 계약상 collectionStatus는
 * collecting|ready|error 3값 고정이고, 수집 진행 여부는 collectionCompletedAt == null로 판정한다 —
 * 확장 중에도 데이터는 계속 서빙되므로 ready가 오히려 정확하다.
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

	public BrandAccountAssembler(@Value("${was.brand.sweep-hour-kst:2}") int sweepHourKst) {
		this.sweepHourKst = sweepHourKst;
	}

	public BrandAccountResponse toResponse(BrandAccountRow row, String accountType) {
		String status;
		if (row.lastSweptOn() != null) {
			status = STATUS_READY;
		} else if (row.lastSweptAt() != null) {
			// 첫 등록 배치 완결(fast-ready) / 재가입 직후 기존 데이터 보유(08-10 결정) / 기간 확장 중
			// (08-13 — 확장이 완주 시각을 리셋하므로 이 분기로 온다). backfill_error가 남아 있어도
			// 무시한다 — 데이터가 있는데 에러 화면을 띄우는 오보 방지.
			status = STATUS_READY;
		} else {
			status = row.backfillError() != null ? STATUS_ERROR : STATUS_COLLECTING;
		}
		BrandAccountResponse.CollectionError error = STATUS_ERROR.equals(status)
				? new BrandAccountResponse.CollectionError(BACKFILL_FAILED, row.backfillError())
				: null;

		String sweptAt = KstTimestamps.toKstIso(row.lastSweptAt());

		return new BrandAccountResponse(
				String.valueOf(row.id()),
				// 타입은 brand_account가 아니라 호출자가 쥔 연결 행에서 온다 — 조립기는 계속 순수 변환이다.
				accountType,
				row.collectionMonths(),
				profile(row),
				status,
				// 확장 시 monitoring이 collection_started_at을 갱신한다 — FE 폴링 30분 상한의 앵커(요청서 §4).
				KstTimestamps.toKstIso(row.collectionStartedAt()),
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
				// 아카이브 사본(/img/ Vercel rewrite) 우선, 미아카이브는 원본 CDN URL 폴백 —
				// 원본은 인스타 서명 URL이라 며칠~2주면 만료된다(BrandPostAssembler.resolveImageUrl 동형).
				BrandPostAssembler.resolveImageUrl(row.imageObjectPath(), row.profilePicUrl()),
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
	 *
	 * <p>08-12 정정: 운영 브랜드 스윕은 서버 크론 KST 02:00(캠페인 스윕과 동시 — 사용자 수용)이라
	 * 기본값을 2로 맞춘다.
	 */
	static String nextScheduledAt(ZonedDateTime at, int hourKst) {
		// 하루 경계는 반드시 KST에서 잘라야 한다 — 다른 존의 시각이 들어오면 날짜가 어긋난다.
		ZonedDateTime now = at.withZoneSameInstant(KstTimestamps.KST);
		ZonedDateTime today = now.truncatedTo(ChronoUnit.DAYS).plusHours(hourKst);
		ZonedDateTime next = today.isAfter(now) ? today : today.plusDays(1);
		return KstTimestamps.toKstIso(next.toOffsetDateTime());
	}
}
