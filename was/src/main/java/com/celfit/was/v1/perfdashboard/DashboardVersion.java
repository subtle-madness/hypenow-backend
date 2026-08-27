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
 * <p><b>입력 다섯 종</b>(설계 §2-1) — 하나라도 놓치면 실패가 아니라 <b>낡은 데이터의 조용한 서빙</b>이다:
 * <ol>
 *   <li>레거시 스윕 워터마크 — {@link MonitoringReadRepository#lastSuccessfulSweepAt()}</li>
 *   <li>브랜드 스윕 워터마크 — 내 연결 브랜드의 {@code (id, last_swept_at, covered_until)}.
 *       {@code covered_until}까지 넣는 이유는 그것이 커버리지 클램프 술어의 입력이라 스윕 시각이
 *       그대로여도 응답 모수를 바꾸기 때문이다.</li>
 *   <li>유저 자신의 쓰기 — {@link DashboardVersionRepository}의 행 지문 5종</li>
 *   <li>KST 날짜 — 데이터가 하나도 안 바뀌어도 자정을 넘기면 상태 유도와 365일 창이 달라진다</li>
 *   <li>배포 세대({@code cacheEpoch}) — 응답 스키마가 바뀐 배포에서 옛 ETag가 맞으면 새 필드가 영영
 *       안 나간다(설계 §2-6)</li>
 * </ol>
 * 이미지 아카이브 워터마크(§2-1 ③)는 <b>의도적으로 뺐다</b> — {@code brand_post_meta}가 22,003행에
 * 해당 인덱스가 없어 순차 스캔이고, 미반영 상한은 스윕 워터마크가 매일 바뀌므로 하루다(설계 §2-5 ③).
 * 그동안 서빙되는 값은 지금도 쓰는 원본 CDN URL이라 회귀가 아니다.
 *
 * <p>런타임 토글({@code monitoring.brand.ad-disclosure.expose} 등)은 {@code @Value} 시동 속성이라
 * 변경에 재배포가 따르고, 그 재배포가 {@code cacheEpoch}를 바꿔 전 ETag를 무효화한다 — 별도 입력이 아니다.
 */
@Component
public class DashboardVersion {

	/**
	 * 지문 <b>구성</b>의 세대 — 필드를 더하거나 빼거나 순서를 바꾸면 올린다. 배포 세대
	 * ({@code cacheEpoch})가 이미 모든 배포에서 키를 무효화하므로 안전망이지만, 빌드 시각을 못 읽는
	 * 환경({@code "dev"} 폴백)에서 구성만 바뀐 경우의 유일한 방어선이다.
	 */
	private static final String LAYOUT = "pdv1";

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
				repository.postCampaignLinksFingerprint(userId));
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
	 * ② 브랜드 스윕 워터마크 — 활성 연결의 monitoring 계정 행에서 {@code (id, last_swept_at,
	 * covered_until)}을 뽑아 <b>brand_id 오름차순</b>으로 join한다. 정렬을 고정하는 이유는 연결 순서
	 * ({@code created_at})가 바뀌어도 워터마크 자체는 같은 값이어야 하고, 무엇보다 순서가 흔들리면
	 * 데이터가 그대로여도 키가 달라져 304가 영영 안 나기 때문이다.
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
					.append(instantText(account.coveredUntil()));
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
