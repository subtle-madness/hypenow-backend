package com.celfit.monitoring.service;

import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.SubjectNotFoundException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 스윕 런 1회 스코프의 <b>원시 게시물 fetch 캐시</b>(2026-09-03 야간 스윕 단축) — 여러 브랜드가
 * 같은 게시물을 감시하면 2단계 단건 재수집이 브랜드 수만큼 같은 콜을 반복한다(야간 실측: 2단계
 * 콜 6,724건 중 1,030건, 15%가 브랜드 간 중복).
 *
 * <p><b>캐시하는 것은 원시 fetch 결과뿐이다</b> — 브랜드별 후처리(adjustLotteryMetrics·savePost·
 * touchCrawled·markUnavailable)는 캐시 적중이든 아니든 각 브랜드가 그대로 수행한다. 브랜드별
 * 상태(크롤 워터마크·부재 마킹·스냅샷)를 공유하면 안 되기 때문이다.
 *
 * <p><b>부재(SubjectNotFound)도 캐시한다</b> — 삭제·비공개 게시물에 브랜드마다 404를 재과금하지
 * 않기 위함이다. 반대로 <b>일시 실패(타임아웃·5xx 등)는 캐시하지 않는다</b> — 다음 브랜드가 다시
 * 시도할 수 있어야 한다(캐시하면 한 번의 순간 장애가 그 스윕 내내 그 게시물을 죽인다).
 *
 * <p>수명은 <b>스윕 런 1회</b>다({@link BrandSweepJob}이 런 시작 시 만들고 끝나면 버린다) — 스윕
 * 사이에 지표가 갱신되므로 런을 넘겨 캐시하면 낡은 스냅샷을 저장하게 된다. 크기 상한을 두지
 * 않는 근거도 여기 있다: 상한은 스윕 1회의 고유 게시물 수(수천 단위)로 자연 유계다.
 *
 * <p>엔트리를 값이 아니라 {@link CompletableFuture}로 들고 있는 이유는 <b>동시 진입 단일화</b>다 —
 * 브랜드 병렬 스윕에서 두 브랜드가 같은 코드에 동시에 닿아도 콜은 1회이고 늦게 온 쪽은 결과를
 * 기다린다({@code computeIfAbsent}로는 안 된다: 1.9초짜리 로더가 맵 빈(bin)을 잠근다). 로더는
 * 항상 <i>먼저 진입한 호출 스레드</i>에서 돌므로 대기자가 있어도 진행이 보장된다.
 */
public class SweepPostCache {

	private final ConcurrentHashMap<String, CompletableFuture<Outcome>> entries = new ConcurrentHashMap<>();
	private final AtomicLong loads = new AtomicLong();
	private final AtomicLong hits = new AtomicLong();

	/**
	 * 캐시된 결과를 주거나, 없으면 {@code loader}로 받아 캐시한다.
	 *
	 * @throws SubjectNotFoundException 부재 확정(캐시 적중이면 같은 메시지의 새 예외)
	 */
	public PostInfo fetch(String shortCode, Supplier<PostInfo> loader) {
		CompletableFuture<Outcome> mine = new CompletableFuture<>();
		CompletableFuture<Outcome> existing = entries.putIfAbsent(shortCode, mine);
		if (existing != null) {
			hits.incrementAndGet();
			return await(existing);
		}
		loads.incrementAndGet();
		Outcome outcome;
		try {
			outcome = new Outcome(loader.get(), null);
		} catch (SubjectNotFoundException e) {
			outcome = new Outcome(null, e.getMessage());
		} catch (RuntimeException e) {
			// 일시 실패는 캐시하지 않는다 — 엔트리를 걷어내 다음 브랜드가 다시 시도하게 하고,
			// 이미 기다리고 있는 대기자에게는 같은 실패를 전달한다(무한 대기 방지).
			entries.remove(shortCode, mine);
			mine.completeExceptionally(e);
			throw e;
		}
		mine.complete(outcome);
		return outcome.postOrThrow();
	}

	/** 실제로 나간 콜 수(캐시 미적중). */
	public long loads() {
		return loads.get();
	}

	/** 캐시로 막은 중복 콜 수. */
	public long hits() {
		return hits.get();
	}

	private static PostInfo await(CompletableFuture<Outcome> entry) {
		try {
			return entry.join().postOrThrow();
		} catch (CompletionException e) {
			// 먼저 진입한 쪽의 일시 실패 — 원 예외를 그대로 올려 호출부의 건별 격리에 맡긴다.
			if (e.getCause() instanceof RuntimeException cause) {
				throw cause;
			}
			throw e;
		}
	}

	/** 성공(post) 또는 부재 확정(absentMessage) — 둘 중 하나만 채워진다. */
	private record Outcome(PostInfo post, String absentMessage) {

		PostInfo postOrThrow() {
			if (post == null) {
				// 스택 트레이스가 남의 스레드 것이 되지 않도록 인스턴스를 재사용하지 않는다.
				throw new SubjectNotFoundException(absentMessage);
			}
			return post;
		}
	}
}
