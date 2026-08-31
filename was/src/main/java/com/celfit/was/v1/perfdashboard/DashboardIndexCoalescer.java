package com.celfit.was.v1.perfdashboard;

import com.celfit.was.v1.perfdashboard.PerformanceContentAssembler.DashboardIndex;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 대시보드 인덱스 <b>동시 조립 합류</b>(2026-08-31 — growth 26.5초 응급 처치).
 *
 * <p><b>무엇이 느렸나</b>: 대시보드 4표면은 요청마다 {@link PerformanceContentAssembler#index}로 유저의
 * 브랜드 풀 전량을 다시 만든다(운영 실측 2026-08-31, 브랜드 6개·창 안 15,177행: <b>8.4초</b>). 그런데
 * FE는 개요 화면에서 계정마다 growth를 1건씩 <b>병렬로</b> 쏘고, {@code accountIds}는 인덱스를 다 만든
 * 뒤 메모리에서 거는 필터라 N개 요청이 각자 N브랜드 전량을 만든다. 2코어 호스트에서 7건이 겹치자
 * 8.4초가 <b>26.5초</b>가 됐다(request_id mbx0prz2).
 *
 * <p><b>그래서 중복만 없앤다</b>: 같은 (유저, 버전키)의 조립이 이미 진행 중이면 그 결과에 합류한다.
 * 리더 1명만 조립하고 나머지는 같은 인스턴스를 받는다 — 인덱스는 순수 파생값이라 어느 쪽이 만들든
 * 결과가 같다.
 *
 * <p><b>보관하지 않는다 — 캐시가 아니다.</b> 맵에 올리는 것은 값이 아니라 "진행 중"이라는 표식이고
 * {@code finally}가 완료 즉시 제거한다. 보관은 웜 요청을 수십 ms로 만들지만 콜드 8.4초를 못 고치고
 * 유저당 ~13MB를 문다. 무엇보다 후속 구조 개편(화면 범위 스코핑 + DB 집계 — 화면은 724행이면 되는데
 * 15,177행을 만든다)이 오면 조립 자체가 100ms대가 되어 보관할 이유가 사라진다(설계 §2-2·§7-1).
 *
 * <p><b>키에 버전이 들어가는 이유</b>: 자정 경계·스윕 순간에는 요청마다 {@link DashboardVersion} 값이
 * 갈릴 수 있다. 버전이 다른 요청끼리 합류하면 ETag(신)와 바디(구)가 어긋난다 — 키를 유저로만 잡으면
 * 생기는 사고라 버전을 같이 넣는다.
 *
 * <p><b>리더 실패는 합류자에게도 전파한다</b>({@link #join}). 합류자가 각자 재시도하면 DB가 힘든 바로
 * 그 순간에 N중 재조립이 터진다 — 같이 실패하고 클라이언트가 재시도하는 편이 낫다. 실패한 키는
 * 맵에 남지 않으므로 다음 요청은 새로 조립한다.
 */
@Component
public class DashboardIndexCoalescer {

	private final PerformanceContentAssembler assembler;

	/** 진행 중인 조립만 담는다 — 완료 즉시 제거하므로 상주량은 "지금 조립 중인 키" 수다. */
	private final Map<Key, CompletableFuture<DashboardIndex>> inFlight = new ConcurrentHashMap<>();

	public DashboardIndexCoalescer(PerformanceContentAssembler assembler) {
		this.assembler = assembler;
	}

	/**
	 * 인덱스 — 같은 키의 조립이 진행 중이면 그 결과에 합류하고, 아니면 내가 리더로 조립한다.
	 *
	 * @param version 이 요청의 버전키({@link DashboardVersion#compute}) — 요청당 1회 계산한 값을 넘긴다
	 */
	public DashboardIndex index(String version, long userId) {
		Key key = new Key(userId, version);
		CompletableFuture<DashboardIndex> mine = new CompletableFuture<>();
		CompletableFuture<DashboardIndex> leader = inFlight.putIfAbsent(key, mine);
		if (leader != null) {
			return join(leader);
		}
		try {
			// 조립은 맵 밖에서 한다 — 8초짜리를 락 안에서 돌리면 다른 유저의 진입까지 줄을 선다.
			DashboardIndex value = assembler.index(userId);
			mine.complete(value);
			return value;
		} catch (Throwable t) {
			// 합류자가 영원히 매달리지 않게 반드시 완료시킨다(정상·실패 어느 쪽이든).
			mine.completeExceptionally(t);
			throw t;
		} finally {
			inFlight.remove(key, mine);
		}
	}

	/** 합류자 대기 — {@link CompletionException} 포장을 벗겨 리더가 던진 예외를 그대로 던진다. */
	private static DashboardIndex join(CompletableFuture<DashboardIndex> leader) {
		try {
			return leader.join();
		} catch (CompletionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw e;
		}
	}

	/** 합류 단위 — 유저 + 버전키. 버전이 다르면 다른 조립이다(위 javadoc). */
	private record Key(long userId, String version) {
	}
}
