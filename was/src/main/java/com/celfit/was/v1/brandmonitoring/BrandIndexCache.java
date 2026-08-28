package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.v1.perfdashboard.DashboardVersion;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 브랜드 표면 인덱스 캐시(FE 요청 2026-08-27 ② — 요청당 고정비 2~2.5초 제거).
 *
 * <p><b>무엇이 느렸나</b>(스테이징 실측 2026-08-28, 계정 119·창 안 5,111행): 브랜드 게시물 목록
 * 1.72초 중 {@link BrandReadRepository#findBrandPostIndex} 단독이 1.59초다. 그 안을 더 쪼개면
 * DB 실행 430ms(협찬 마커 정규식 350ms 포함) + psql 전송까지 505ms이고, 나머지 ~1.1초는 JDBC
 * 행 매핑이다(5,111행 × 16컬럼). <b>캡션 전송은 이미 제거돼 있다</b>(2026-08-27 P0 —
 * {@code caption_marker} boolean 1컬럼) — 남은 비용은 캡션이 아니라 모수 그 자체라, 같은 쿼리를
 * 쓰는 성과 대시보드도 200 응답은 동일하게 3초대다(그쪽이 빨라 보이는 것은 ETag 304 경로다).
 *
 * <p><b>그래서 요청마다 다시 만들지 않는다</b>: 인덱스는 "이 유저가 이 브랜드에서 보는 게시물
 * 모수"라는 파생값이고, 그 입력이 안 바뀌면 결과도 같다. 입력이 바뀌었는지는 성과 대시보드가 이미
 * 쓰는 버전키({@link DashboardVersion#compute})가 판정한다 — 스윕 워터마크·유저 쓰기 지문 5종·
 * KST 날짜·배포 세대를 묶은 값이라, 무효화 규칙을 새로 만들지 않고 <b>검증된 계약을 그대로 상속</b>한다
 * (수용된 지연 3건도 그대로 상속 — 상한은 하루이고 자가 치유된다, {@link DashboardVersion} javadoc).
 * FE가 게시물 2,000건을 100건씩 20회로 받아가는 실제 사용 패턴에서 첫 요청만 모수를 만들고 나머지
 * 19회는 캐시를 탄다.
 *
 * <p><b>왜 Redis가 아니라 인프로세스인가</b>: 값이 5천 행 객체 그래프라 JSON 직렬화 왕복
 * ({@code CacheConfig}의 관용구)이 아끼려는 비용보다 크다. 수명도 "한 유저의 페이징 버스트" 단위라
 * 프로세스 밖으로 나갈 이유가 없다 — 인스턴스마다 자기 것을 만들면 그만이다(롤링 배포 창에서
 * 신구 인스턴스가 각자 채운다. 배포 세대가 버전키에 들어 있어 섞이지도 않는다).
 *
 * <p>상한은 {@link #MAX_ENTRIES}개의 LRU다 — 버전키가 바뀌면 옛 엔트리는 <b>다시 조회되지 않고</b>
 * LRU 꼬리로 밀려 자연 축출된다(별도 만료 스캔 없음). 엔트리 하나가 수 MB일 수 있어 상한을 작게
 * 잡았고, 클로즈 베타 규모(유저 × 브랜드 × withViews 2종)에서는 이걸로 충분하다.
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class BrandIndexCache {

	/**
	 * LRU 상한 — 엔트리 하나가 브랜드 모수 전체다. <b>힙 실측(2026-08-28)</b>: ref당 약 975바이트
	 * (로컬 실데이터 11,438 ref에 풀GC 전후 +11.1MB)라, 운영 최대 브랜드(계정 120·창 안 10,489행)
	 * 기준 엔트리 하나가 <b>약 10MB</b>다. 운영 was는 힙 2GB에 Old Gen p99가 798MB·최대 887MB이고
	 * 힙 합계가 최대 2,038MB까지 올라간 적이 있어(Prometheus 7일) 한가한 힙이 아니다 — 그래서 최악
	 * 상주를 <b>약 80MB</b>로 묶는다. 클로즈 베타 규모에서 동시 사용 유저가 이보다 많지 않아 적중률
	 * 손해는 사실상 없다(엔트리는 유저 × 브랜드 × 종류로 갈린다).
	 */
	static final int MAX_ENTRIES = 8;

	private final DashboardVersion dashboardVersion;
	private final BrandPostAssembler assembler;
	private final BrandReadRepository brandReadRepository;

	/** 접근 순서 LRU(동기화 래핑) — 값 계산은 락 밖에서 한다({@link #cached} 참조). */
	private final Map<Key, Object> entries = Collections.synchronizedMap(
			new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Key, Object> eldest) {
					return size() > MAX_ENTRIES;
				}
			});

	public BrandIndexCache(DashboardVersion dashboardVersion, BrandPostAssembler assembler,
			BrandReadRepository brandReadRepository) {
		this.dashboardVersion = dashboardVersion;
		this.assembler = assembler;
		this.brandReadRepository = brandReadRepository;
	}

	/**
	 * 이 요청의 버전키 — 한 요청 안에서 <b>한 번만</b> 부르고 그 값을 아래 조회들에 넘긴다. 표면마다
	 * 다시 계산하면 같은 요청 안에서 키가 갈릴 수 있고(자정 경계·동시 스윕), 그러면 게시물 목록과
	 * 패싯이 서로 다른 모수를 보게 된다.
	 */
	public String version(long userId) {
		return dashboardVersion.compute(userId);
	}

	/** 캐시된 인덱스 — 없으면 {@link BrandPostAssembler#indexForBrand}로 만들어 담는다. */
	public BrandPostAssembler.BrandPostIndex index(String version, long userId, BrandAccountRow account,
			boolean withViews) {
		return cached(new Key(version, userId, account.id(), withViews ? Kind.INDEX_WITH_VIEWS : Kind.INDEX),
				() -> assembler.indexForBrand(userId, account, withViews));
	}

	/**
	 * 캐시된 최신 스냅샷 프로젝션 — 인플루언서 집계가 인덱스와 <b>짝으로</b> 읽는 두 번째 조회다
	 * (실측 550ms/4계정). 유저 관점 파생이 없어 userId는 키에 넣지 않는다.
	 */
	public List<BrandReadRepository.LatestSnapshotRow> latestSnapshots(String version, long brandId,
			boolean enrichedOnly) {
		return cached(new Key(version, 0L, brandId,
						enrichedOnly ? Kind.SNAPSHOTS_ENRICHED : Kind.SNAPSHOTS_ALL),
				() -> brandReadRepository.findLatestSnapshotsForBrand(brandId,
						BrandPostAssembler.windowCutoff(), enrichedOnly));
	}

	/**
	 * 조회 → (없으면) 계산 → 적재. <b>계산은 맵 락 밖에서</b> 한다 — 1.6초짜리 조립을 락 안에서 돌리면
	 * 다른 브랜드·다른 유저의 캐시 조회까지 그 시간만큼 줄을 선다. 같은 키에 동시 진입하면 계산이
	 * 두 번 될 수 있지만(경합 창에서만), 값이 순수 파생이라 어느 쪽이 이겨도 결과가 같다 — 락을
	 * 좁게 유지하는 편이 낫다.
	 */
	private <T> T cached(Key key, java.util.function.Supplier<T> loader) {
		Object hit = entries.get(key);
		if (hit != null) {
			@SuppressWarnings("unchecked")
			T typed = (T) hit;
			return typed;
		}
		T value = loader.get();
		entries.put(key, value);
		return value;
	}

	/** 캐시 값의 종류 — 같은 (버전·유저·브랜드)라도 셰이프가 다르면 섞이면 안 된다. */
	private enum Kind { INDEX, INDEX_WITH_VIEWS, SNAPSHOTS_ENRICHED, SNAPSHOTS_ALL }

	/**
	 * 캐시 키 — 버전키가 무효화를 전담하므로 나머지는 "어느 모수인가"만 식별한다. userId가 키에 있는
	 * 이유: 인덱스는 조회자 관점 파생값(등록자 전용 노출 필터·{@code source} 판정)을 담아 유저 간
	 * 공유가 불가능하다.
	 */
	private record Key(String version, long userId, long brandId, Kind kind) {
		Key {
			Objects.requireNonNull(version, "version");
		}
	}
}
