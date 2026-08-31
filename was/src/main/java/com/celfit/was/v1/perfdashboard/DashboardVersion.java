package com.celfit.was.v1.perfdashboard;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.v1.common.KstTimestamps;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * 성과 대시보드 버전키(2026-08-13 ETag 설계) — 조립 <b>전에</b> 계산해 {@code If-None-Match} 일치 시
 * 304 조기 반환의 근거가 된다. 조기 반환이 조립·직렬화·전송을 통째로 건너뛰는 것이 이 설계의 이득이고,
 * 그래서 응답 본문을 만든 뒤 해싱하는 방식({@code ShallowEtagHeaderFilter})은 쓰지 않는다(설계 §3).
 *
 * <p><b>입력 여섯 종</b>(설계 §2-1, 2026-08-28 해시태그 장부 지문 추가로 5→6) — 하나라도 놓치면 실패가
 * 아니라 <b>낡은 데이터의 조용한 서빙</b>이다:
 * <ol>
 *   <li>레거시 스윕 워터마크 — {@link MonitoringReadRepository#lastSuccessfulSweepAt()}</li>
 *   <li>브랜드 스윕 워터마크 — 내 연결 브랜드의 {@code brand_account} 행에서 응답에 영향을 주는
 *       6필드({@link #brandWatermarks} 참조). 스윕 시각만으로는 부족하다: 창 확장·재활성화처럼
 *       <b>스윕 밖</b>에서 그 행을 바꾸는 경로가 있고, 브랜드는 유저 간 공유 자산이라 <b>남의</b>
 *       등록·확장이 내 응답을 바꾼다. 다만 스윕 워터마크가 <b>스윕이 쓰는 모든 데이터를</b> 덮는
 *       것은 아니다 — 아래 "수용된 지연" 참조.</li>
 *   <li>유저 자신의 쓰기 — {@link DashboardVersionRepository}의 행 지문 6종(해시태그 장부 포함,
 *       {@link DashboardVersionRepository#hashtagTagsFingerprint} 참조 — {@code
 *       app.brand_hashtag_tags} 추가·삭제가 {@code BrandIndexCache}가 캐시하는 인덱스의 해시태그
 *       격리 판정을 바꾸는데, 이 지문이 없으면 태그를 고쳐도 캐시가 최대 하루 옛 판정을 서빙한다 —
 *       이 표면의 계약은 "장부 변경이 다음 GET에 즉시 반영"이라 그 지연이 <b>수용 가능한 지연이
 *       아니다</b>, 아래 "수용된 지연" 3건과는 다른 급이라 별도 지문으로 막는다)</li>
 *   <li>KST 날짜 — 데이터가 하나도 안 바뀌어도 자정을 넘기면 상태 유도와 365일 창이 달라진다</li>
 *   <li>배포 세대({@code cacheEpoch}) — 응답 스키마가 바뀐 배포에서 옛 ETag가 맞으면 새 필드가 영영
 *       안 나간다(설계 §2-6)</li>
 * </ol>
 * <p><b>수용된 지연 3건</b> — 어느 것도 "영구 미반영"이 아니다. 상한이 <b>하루</b>이고
 * (다음 스윕이 워터마크를 반드시 움직인다), 유저 자신의 쓰기나 KST 자정이 먼저 오면 그보다 빨리
 * 풀린다. 과소 무효화지만 <b>자가 치유</b>라 수용한다:
 * <ol>
 *   <li><b>이미지 아카이브 워터마크</b>(§2-1 ③)는 <b>의도적으로 뺐다</b> — {@code brand_post_meta}가
 *       22,003행에 해당 인덱스가 없어 순차 스캔이고, 미반영 상한은 스윕 워터마크가 매일 바뀌므로
 *       하루다(설계 §2-5 ③). 그동안 서빙되는 값은 지금도 쓰는 원본 CDN URL이라 회귀가 아니다.</li>
 *   <li><b>브랜드 direct 2단계 스윕</b> — {@code BrandSweepJob.runSweep}은 브랜드마다
 *       {@code collect.sweep} → {@code touchSwept} → {@code directCollect.sweepDirect} 순으로 돈다.
 *       워터마크({@code last_swept_at})가 direct 단계 <b>앞에서</b> 찍히므로, 그 런의 direct 갱신분
 *       (직접 등록 게시물의 새 스냅샷)은 이번 워터마크 밖이고 다음 스윕에서야 키를 움직인다.</li>
 *   <li><b>등록 직후 비동기 지표 백필</b>({@code RegistrationService.scheduleMetricsBackfill}, ~1분)
 *       — 등록 자체는 app 쓰기라 유저 쓰기 지문이 키를 <b>1회</b> 움직이지만, 그 뒤 백그라운드가
 *       채우는 저장·리포스트 값은 app을 다시 쓰지 않는다. 그래서 백필된 스냅샷은 다음 스윕·유저의
 *       다음 쓰기·KST 자정 중 <b>먼저 오는 것</b>까지 키를 안 움직인다.</li>
 * </ol>
 *
 * <p>런타임 토글({@code monitoring.brand.ad-disclosure.expose} 등)은 {@code @Value} 시동 속성이라
 * 변경에 재배포가 따르고, 그 재배포가 {@code cacheEpoch}를 바꿔 전 ETag를 무효화한다 — 별도 입력이 아니다.
 * 런타임 DB 설정({@code app.app_setting})은 <b>현재 대시보드 4표면의 입력이 아님을 확인했다</b>
 * (2026-08-28) — 이후 app_setting 기반 토글이 이 표면에 들어오면 지문에 추가할 것.
 */
@Component
public class DashboardVersion {

	/**
	 * 지문 <b>구성</b>의 세대 — 필드를 더하거나 빼거나 순서를 바꾸면 올린다. 배포 세대
	 * ({@code cacheEpoch})가 이미 모든 배포에서 키를 무효화하므로 안전망이지만, 빌드 시각을 못 읽는
	 * 환경({@code "dev"} 폴백)에서 구성만 바뀐 경우의 유일한 방어선이다.
	 */
	private static final String LAYOUT = "pdv3";

	/** 값이 없는 자리의 표식 — 빈 문자열을 쓰면 인접 필드와 구분이 흐려진다. */
	private static final String ABSENT = "-";

	/** 필드 구분자 — 어느 입력 값에도 나타나지 않는다(md5 hex·epoch 숫자·ISO instant·숫자 id). */
	private static final String FIELD = "|";

	private final DashboardVersionRepository repository;
	private final Optional<MonitoringReadRepository> monitoringReadRepository;
	private final BrandLinkRepository linkRepository;
	private final Optional<BrandReadRepository> brandReadRepository;
	private final Clock clock;
	private final String cacheEpoch;

	public DashboardVersion(DashboardVersionRepository repository,
			Optional<MonitoringReadRepository> monitoringReadRepository,
			BrandLinkRepository linkRepository, Optional<BrandReadRepository> brandReadRepository,
			ObjectProvider<BuildProperties> buildProperties, Clock clock) {
		this.repository = repository;
		this.monitoringReadRepository = monitoringReadRepository;
		this.linkRepository = linkRepository;
		this.brandReadRepository = brandReadRepository;
		this.clock = clock;
		// CacheConfig의 cacheEpoch 관용구와 같다(그쪽은 Redis 키 prefix 세대, 여기는 ETag 세대) —
		// 한 줄짜리라 공용화하지 않고 복제한다. 한쪽을 고치면 다른 쪽도 같이 볼 것.
		BuildProperties bp = buildProperties.getIfAvailable();
		this.cacheEpoch = (bp == null || bp.getTime() == null) ? "dev"
				: String.valueOf(bp.getTime().getEpochSecond());
	}

	/**
	 * 버전키 — md5 hex 32자. 같은 입력이면 항상 같다(순서 고정 join 후 md5).
	 *
	 * <p>필드는 전부 고정 길이이거나 구분자를 품지 않는 값이라, 서로 다른 입력 조합이 같은 문자열로
	 * 접히지 않는다 — 이 성질이 "입력 하나만 바뀌어도 키가 바뀐다"의 근거다.
	 */
	public String compute(long userId) {
		LocalDate kstToday = LocalDate.ofInstant(clock.instant(), KstTimestamps.KST);
		String raw = String.join(FIELD,
				LAYOUT,
				cacheEpoch,
				Long.toString(userId),
				kstToday.toString(),
				legacySweepAt(),
				brandWatermarks(userId),
				repository.monitoringItemsFingerprint(userId),
				repository.brandLinksFingerprint(userId),
				repository.directPostsFingerprint(userId),
				repository.campaignsFingerprint(userId),
				repository.postCampaignLinksFingerprint(userId),
				repository.hashtagTagsFingerprint(userId));
		return md5Hex(raw);
	}

	/** ETag 헤더 값 — {@code W/"<version 앞 16자>"}. */
	public static String etagOf(String version) {
		return "W/\"" + version.substring(0, Math.min(16, version.length())) + "\"";
	}

	/**
	 * {@code If-None-Match} 매칭 — {@code W/} 접두·따옴표 무시, 쉼표 복수 값, {@code *}는 항상 일치
	 * (RFC 9110 §13.1.2). 약한 검증자만 쓰므로 약/강 구분은 하지 않는다: 우리 ETag는 의미적 동등성만
	 * 주장하고 Range 요청 표면이 아니라 강한 비교가 필요한 지점이 없다(설계 §2-7).
	 */
	public static boolean matches(String ifNoneMatchHeader, String etag) {
		if (ifNoneMatchHeader == null || ifNoneMatchHeader.isBlank()) {
			return false;
		}
		String target = normalizeTag(etag);
		for (String candidate : ifNoneMatchHeader.split(",")) {
			String value = candidate.trim();
			if (value.isEmpty()) {
				continue;
			}
			if ("*".equals(value) || normalizeTag(value).equals(target)) {
				return true;
			}
		}
		return false;
	}

	/** {@code W/} 접두와 감싼 따옴표를 벗겨 낸 알맹이 — 비교는 항상 이 형태끼리 한다. */
	private static String normalizeTag(String raw) {
		String value = raw.trim();
		if (value.startsWith("W/")) {
			value = value.substring(2).trim();
		}
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			value = value.substring(1, value.length() - 1);
		}
		return value;
	}

	/**
	 * ① 레거시 스윕 워터마크. monitoring 비활성이면 빈 Optional이라 상수({@code "-"})로 접힌다 —
	 * 그 환경에서는 이 입력이 응답에 영향을 줄 수 없다(레거시 스냅샷 산지가 통째로 없다).
	 */
	private String legacySweepAt() {
		return monitoringReadRepository
				.map(MonitoringReadRepository::lastSuccessfulSweepAt)
				.map(DashboardVersion::instantText)
				.orElse(ABSENT);
	}

	/**
	 * ② 브랜드 워터마크 — 활성 연결의 monitoring 계정 행({@code brand_account})에서 응답에 영향을
	 * 주는 6필드를 뽑아 <b>brand_id 오름차순</b>으로 join한다.
	 *
	 * <table>
	 *   <caption>필드 ↔ 응답 영향 지점</caption>
	 *   <tr><th>필드</th><th>응답 영향</th></tr>
	 *   <tr><td>{@code id}</td><td>행 식별(정렬 키)</td></tr>
	 *   <tr><td>{@code last_swept_at}</td><td>스윕 세대 — 게시물·스냅샷이 갱신되는 <b>주된</b> 지점
	 *       (전부는 아니다: 같은 런의 direct 2단계는 이 값이 찍힌 <b>뒤</b>에 돈다 — 클래스 javadoc
	 *       "수용된 지연" ②·③). 08-31 개정: 스윕·백필 <b>도중에도</b> 페이지 정산마다 전진한다
	 *       ({@code BrandRepository.touchProgress}) — 안 그러면 등록 백필 수 분간 폴링이 전부 이
	 *       키의 캐시에 붙어 게시물이 완주 시점에 한꺼번에 나타난다(08-31 skinfood 실측)</td></tr>
	 *   <tr><td>{@code covered_until}</td><td>목록의 커버리지 클램프 술어 · {@code /comparison}의 covered 판정</td></tr>
	 *   <tr><td>{@code backfill_completed_at}</td><td>{@code /comparison}의 {@code accountCovered} 판정</td></tr>
	 *   <tr><td>{@code last_swept_on}</td><td>〃 (같은 술어의 다른 절)</td></tr>
	 *   <tr><td>{@code collection_months}</td><td>{@code /comparison}의 창 시작일({@code today.minusMonths})</td></tr>
	 *   <tr><td>{@code collection_started_at}</td><td>{@code /comparison} 응답 필드(수집 시작 시각)</td></tr>
	 * </table>
	 *
	 * <p>{@code username}은 응답에 실리지만 <b>일부러 뺐다</b> — 계정명 변경은 스윕 <b>안에서</b>
	 * 갱신되므로 같은 런의 {@code last_swept_at}이 전이적으로 덮는다(스윕 밖 경로가 없다). 뒤 4필드와
	 * 다른 점이 이것이라, 여기 넣으면 지문만 길어지고 무효화 시점은 그대로다.
	 *
	 * <p><b>스윕 시각 2개만으로는 부족하다.</b> 뒤 4필드는 창 확장·재활성화·상한 조정
	 * ({@code BrandRepository.expandWindow}·{@code insertOrReactivate}·{@code raiseWindowCapped}) 같은
	 * <b>스윕 밖</b> 경로에서 바뀌고, 그때 {@code last_swept_at}·{@code covered_until}은 미동이다.
	 * 게다가 브랜드는 유저 간 공유 자산이라 <b>다른 유저의</b> 등록·확장이 내 응답을 바꾸는데,
	 * 그건 내 app 링크 지문({@link DashboardVersionRepository#brandLinksFingerprint})이 잡을 수 없다.
	 *
	 * <p>정렬을 고정하는 이유는 연결 순서({@code created_at})가 바뀌어도 워터마크 자체는 같은 값이어야
	 * 하고, 무엇보다 순서가 흔들리면 데이터가 그대로여도 키가 달라져 304가 영영 안 나기 때문이다.
	 *
	 * <p>계정 행이 없는 연결(경합·삭제)은 건너뛴다 — 조립도 그 브랜드를 통째로 빼므로 응답과 같은
	 * 판정이다. 나중에 행이 생기면 이 문자열이 달라져 자연히 무효화된다.
	 *
	 * <p>monitoring 비활성이거나 활성 연결이 없으면 {@code "-"}로 접힌다.
	 */
	private String brandWatermarks(long userId) {
		List<BrandLinkRow> links = linkRepository.findAllActiveByUser(userId);
		if (brandReadRepository.isEmpty() || links.isEmpty()) {
			return ABSENT;
		}
		List<BrandAccountRow> accounts = new ArrayList<>(links.size());
		for (BrandLinkRow link : links) {
			brandReadRepository.get().findAccount(link.brandId()).ifPresent(accounts::add);
		}
		if (accounts.isEmpty()) {
			return ABSENT;
		}
		accounts.sort(Comparator.comparingLong(BrandAccountRow::id));
		StringBuilder sb = new StringBuilder();
		for (BrandAccountRow account : accounts) {
			if (sb.length() > 0) {
				sb.append(',');
			}
			sb.append(account.id()).append(':')
					.append(instantText(account.lastSweptAt())).append(':')
					.append(instantText(account.coveredUntil())).append(':')
					.append(instantText(account.backfillCompletedAt())).append(':')
					.append(account.lastSweptOn() == null ? ABSENT : account.lastSweptOn().toString()).append(':')
					.append(account.collectionMonths()).append(':')
					.append(instantText(account.collectionStartedAt()));
		}
		return sb.toString();
	}

	/**
	 * 타임스탬프의 정본 표기 — {@code OffsetDateTime.toString()}은 오프셋 표기가 산지에 따라 갈리므로
	 * instant로 정규화한다(같은 시각이 두 표기를 갖지 않게).
	 */
	private static String instantText(OffsetDateTime at) {
		return at == null ? ABSENT : at.toInstant().toString();
	}

	private static String md5Hex(String raw) {
		try {
			// md5는 여기서 암호학적 용도가 아니라 캐시 검증자다(약한 ETag) — 충돌 저항이 아니라
			// "같은 입력이면 같은 값"만 쓰고, 값 자체는 유저에게 노출돼도 무해한 파생값이다.
			MessageDigest digest = MessageDigest.getInstance("MD5");
			return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("MD5 다이제스트를 쓸 수 없다 — JRE 표준 알고리즘이라 도달 불가", e);
		}
	}
}
