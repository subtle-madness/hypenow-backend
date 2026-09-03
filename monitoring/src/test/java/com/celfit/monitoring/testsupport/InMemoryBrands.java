package com.celfit.monitoring.testsupport;

import com.celfit.instagram.source.ProfileInfo;
import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * {@link BrandRepository} 인메모리 스텁 — 원래 {@code BrandRegistrationServiceTest} 안의 private
 * static 클래스였으나, {@code BrandBackfillRetryJobTest}(2026-09 열거 실패 재시도 스케줄러)도 같은
 * 관용구가 필요해 공유 위치로 추출했다(설계 §4-C). 실 SQL 의미와 등가로 각 메서드를 재현한다 —
 * 실제 UPDATE의 조건절(WHERE)이 하는 일을 그대로 흉내낸다.
 *
 * <p>{@code enrichedCodes} 서플라이어는 등록 백필 시나리오(markServing·touchSwept 시점의 "지금까지
 * 보강 완료된 게시물" 스냅샷)에만 쓰인다 — 그 관측이 필요 없는 테스트는 {@link #InMemoryBrands()}
 * (빈 리스트 고정)를 쓴다.
 */
public class InMemoryBrands extends BrandRepository {

	public final Map<String, BrandRow> rows = new HashMap<>();
	public final List<Long> touched = new CopyOnWriteArrayList<>();
	public final List<Long> served = new CopyOnWriteArrayList<>();
	/** markServing 호출마다 그 시점의 보강 완료 코드 스냅샷 — "첫 배치 보강 뒤 ready"의 관측 지점. */
	public final List<List<String>> enrichedAtServingMark = new CopyOnWriteArrayList<>();
	/** touchSwept 시점의 보강 완료 코드 스냅샷 — FE 폴링 종료 조건이 미완성 목록에서 걸리는지 본다. */
	public final List<List<String>> enrichedAtTouchSwept = new CopyOnWriteArrayList<>();
	public final Map<Long, String> backfillErrors = new HashMap<>();
	public final List<Long> expanded = new ArrayList<>();
	/** raiseWindowCapped 호출 기록(스펙 §7-2) — 창·폴백 인자까지 본다. */
	public record CappedRaise(long brandId, int months, Instant coveredUntilFallback) {}

	public final List<CappedRaise> cappedRaises = new ArrayList<>();
	/** 동시 확장 경합 주입 — 더 큰 창이 이미 반영돼 조건부 UPDATE가 0행을 맞는 상황(rowcount false). */
	public boolean loseExpandRace = false;
	public long nextId = 1;

	/** 재시도 예산(2026-09) — backfill_attempts와 등가. touchSwept 성공이 0으로 되돌린다. */
	public final Map<Long, Integer> backfillAttempts = new HashMap<>();
	public final List<Long> markedAttempts = new CopyOnWriteArrayList<>();

	private final Supplier<List<String>> enrichedCodes;

	public InMemoryBrands(Supplier<List<String>> enrichedCodes) {
		super(null);
		this.enrichedCodes = enrichedCodes;
	}

	/** enrichedCodes 스냅샷이 필요 없는 시나리오(재시도 잡 테스트 등)용 — 항상 빈 리스트. */
	public InMemoryBrands() {
		this(List::of);
	}

	@Override
	public long insertOrReactivate(String username, ProfileInfo profile, int collectionMonths,
			boolean ownRequest) {
		BrandRow existing = rows.get(username);
		long id = existing != null ? existing.id() : nextId++;
		int months = existing != null ? Math.max(existing.collectionMonths(), collectionMonths) : collectionMonths;
		rows.put(username, new BrandRow(id, username, profile.userId(), BrandStatus.ACTIVE, null, months,
				ownRequest));
		return id;
	}

	@Override
	public void setHasOwnLink(String username, boolean hasOwnLink) {
		rows.computeIfPresent(username, (u, r) -> new BrandRow(r.id(), r.username(), r.igUserId(), r.status(),
				r.lastSweptOn(), r.collectionMonths(), hasOwnLink));
	}

	/** 실 SQL 의미와 등가 — GREATEST + "collection_months < months일 때만" 갱신하고 그 여부를 돌려준다. */
	@Override
	public boolean expandWindow(long brandId, int months) {
		expanded.add(brandId);
		BrandRow row = rows.values().stream().filter(r -> r.id() == brandId).findFirst().orElseThrow();
		if (loseExpandRace || months <= row.collectionMonths()) {
			return false;
		}
		rows.replaceAll((u, r) -> r.id() == brandId
				? new BrandRow(r.id(), r.username(), r.igUserId(), r.status(), null, months, r.hasOwnLink())
				: r);
		return true;
	}

	/** 실 SQL 의미와 등가 — 창만 GREATEST로 올리고 수집 상태(lastSweptOn)는 건드리지 않는다. */
	@Override
	public boolean raiseWindowCapped(long brandId, int months, Instant coveredUntilFallback) {
		cappedRaises.add(new CappedRaise(brandId, months, coveredUntilFallback));
		BrandRow row = rows.values().stream().filter(r -> r.id() == brandId).findFirst().orElseThrow();
		if (months <= row.collectionMonths()) {
			return false;
		}
		rows.replaceAll((u, r) -> r.id() == brandId
				? new BrandRow(r.id(), r.username(), r.igUserId(), r.status(), r.lastSweptOn(), months,
						r.hasOwnLink())
				: r);
		return true;
	}

	@Override
	public void markBackfillError(long brandId, String message) {
		backfillErrors.put(brandId, message);
	}

	@Override
	public Optional<BrandRow> findByUsername(String username) {
		return Optional.ofNullable(rows.get(username));
	}

	@Override
	public boolean close(String username) {
		BrandRow row = rows.get(username);
		if (row == null || row.status() != BrandStatus.ACTIVE) {
			return false;
		}
		rows.put(username, new BrandRow(row.id(), row.username(), row.igUserId(),
				BrandStatus.CLOSED, row.lastSweptOn(), row.collectionMonths(), row.hasOwnLink()));
		return true;
	}

	@Override
	public void touchSwept(long brandId, java.time.LocalDate on) {
		touched.add(brandId);
		enrichedAtTouchSwept.add(enrichedCodes.get());
		backfillErrors.remove(brandId);           // 실 SQL: backfill_error = NULL
		backfillAttempts.put(brandId, 0);         // 실 SQL: backfill_attempts = 0
		// 실 UPDATE와 동일하게 행에도 반영한다 — 확장 백필이 "재조회한 행"(lastSweptOn 비워짐)으로
		// 도는지를 스텁 행이 stale인 채로는 구분할 수 없다.
		rows.replaceAll((u, r) -> r.id() == brandId
				? new BrandRow(r.id(), r.username(), r.igUserId(), r.status(), on, r.collectionMonths(),
						r.hasOwnLink())
				: r);
	}

	@Override
	public void markServing(long brandId) {
		served.add(brandId);
		enrichedAtServingMark.add(enrichedCodes.get());
	}

	/**
	 * 후보 판정(2026-09) — 실 SQL의 세 조건(ACTIVE·lastSweptOn null·backfillError 有·attempts <
	 * maxAttempts)만 재현한다. 나이 창·백오프는 시각 계산이라 여기서 흉내내지 않는다 — 그건
	 * {@code BrandStoreTest}(Testcontainers, 실 SQL)가 검증한다. 정렬은 이 스텁에서 무의미(테스트가
	 * 소수 브랜드만 다룬다).
	 */
	@Override
	public List<BrandRow> findBackfillRetryCandidates(int maxAttempts, int maxAgeMinutes,
			int backoffMinutes, int limit) {
		return rows.values().stream()
				.filter(r -> r.status() == BrandStatus.ACTIVE)
				.filter(r -> r.lastSweptOn() == null)
				.filter(r -> backfillErrors.get(r.id()) != null)
				.filter(r -> backfillAttempts.getOrDefault(r.id(), 0) < maxAttempts)
				.limit(limit)
				.toList();
	}

	@Override
	public void markBackfillAttempt(long brandId) {
		markedAttempts.add(brandId);
		backfillAttempts.merge(brandId, 1, Integer::sum);
	}

	/** 실 SQL 의미와 등가 — attempts >= maxAttempts인 ACTIVE·미완주 브랜드의 문구를 교체하고,
	 * 이미 같은 문구인 행은 다시 세지 않는다(멱등 — 재틱 재교체 없음). */
	@Override
	public int markBackfillRetryExhausted(String message, int maxAttempts) {
		int count = 0;
		for (BrandRow r : rows.values()) {
			if (r.status() == BrandStatus.ACTIVE && r.lastSweptOn() == null
					&& backfillErrors.get(r.id()) != null
					&& backfillAttempts.getOrDefault(r.id(), 0) >= maxAttempts
					&& !message.equals(backfillErrors.get(r.id()))) {
				backfillErrors.put(r.id(), message);
				count++;
			}
		}
		return count;
	}
}
