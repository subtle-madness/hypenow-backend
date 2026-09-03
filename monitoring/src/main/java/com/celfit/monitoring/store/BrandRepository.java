package com.celfit.monitoring.store;

import com.celfit.instagram.source.ProfileInfo;
import com.celfit.monitoring.domain.BrandStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** brand_account 접점 — username UNIQUE가 멱등 키다(같은 계정 재가입은 같은 행 재활성). */
@Repository
public class BrandRepository {

	private final JdbcTemplate db;

	public BrandRepository(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * 등록 또는 재가입 — CLOSED 행이 있으면 ACTIVE로 재활성하고 프로필 관측값을 갱신한다.
	 * last_swept_on을 null로 되돌리는 이유: 재가입 시점의 윈도우(90일)를 백필이 다시 채우기
	 * 전까지는 "수집 준비 중"(was 계약의 판별 기준)이어야 하기 때문이다. 백필 상태 컬럼
	 * (backfill_error·backfill_completed_at)도 같은 이유로 리셋한다 — 재등록은 백필을 처음부터
	 * 다시 도는 것이라, 지난 가입의 완주·실패 기록이 남으면 was 폴링이 곧장 ready를 본다.
	 * <b>재시도 예산(backfill_attempts·backfill_attempted_at, 2026-09 결함 수정)도 0·NULL로
	 * 리셋</b> — 재등록 전 열거가 상한(3회)까지 소진돼 있으면, 리셋 없이는 재등록 직후 첫 열거
	 * 실패가 곧장 exhausted 문구로 떨어져 재시도 스케줄러가 구제할 기회 자체가 없다.
	 * 수집 창은 GREATEST로 합친다 — 재가입 축소 요청은 무시한다: 기존 수집분이 이미 있어 창을
	 * 줄이면 응답 창과 보유 데이터가 어긋난다.
	 *
	 * <p>{@code ownRequest}(2026-08-19 경쟁사 판정 제거 설계, was accountType != 'competitor')는
	 * has_own_link 초기화·승격에 쓰인다 — 신규 삽입이면 그 값 그대로 심고, 기존 행 재가입이면 true일
	 * 때만 승격한다(경쟁사 재등록은 다른 유저의 own 연결을 모를 수 있어 절대 false로 내리지 않는다).
	 */
	public long insertOrReactivate(String username, ProfileInfo profile, int collectionMonths,
			boolean ownRequest) {
		return db.queryForObject("""
				INSERT INTO brand_account (username, ig_user_id, followers, biography, full_name,
				                           profile_pic_url, is_verified, external_url, following, media_count,
				                           collection_months, collection_started_at, has_own_link)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?)
				ON CONFLICT (username) DO UPDATE SET
				  ig_user_id = EXCLUDED.ig_user_id, followers = EXCLUDED.followers,
				  biography = EXCLUDED.biography, full_name = EXCLUDED.full_name,
				  profile_pic_url = EXCLUDED.profile_pic_url, is_verified = EXCLUDED.is_verified,
				  external_url = EXCLUDED.external_url, following = EXCLUDED.following,
				  media_count = EXCLUDED.media_count, status = 'ACTIVE', closed_at = NULL,
				  last_swept_on = NULL, backfill_error = NULL, backfill_completed_at = NULL,
				  backfill_attempts = 0, backfill_attempted_at = NULL,
				  registered_at = now(),
				  collection_months = GREATEST(brand_account.collection_months, EXCLUDED.collection_months),
				  collection_started_at = now(),
				  has_own_link = CASE WHEN EXCLUDED.has_own_link THEN true ELSE brand_account.has_own_link END
				RETURNING id""",
				Long.class, username, profile.userId(), profile.followers(), profile.biography(),
				profile.fullName(), profile.profilePicUrl(), profile.isVerified(), profile.externalUrl(),
				profile.following(), profile.mediaCount(), collectionMonths, ownRequest);
	}

	/**
	 * own-link 플래그 절대값 설정(멱등, PUT /api/brands/{username}/own-link — 2026-08-19 경쟁사 판정
	 * 제거 설계 §2) — was가 연결 변이(changeType 양방향·부분 해지) 커밋 후 원장에서 재계산한 값을
	 * 그대로 민다. insertOrReactivate의 "승격만" 규칙과 달리 여기는 false로도 내릴 수 있다 — was가
	 * 이미 전체 원장을 재계산한 정본값이라서다.
	 */
	public void setHasOwnLink(String username, boolean hasOwnLink) {
		db.update("UPDATE brand_account SET has_own_link = ? WHERE username = ?", hasOwnLink, username);
	}

	/**
	 * 기간 확장(collectionMonths 스펙 §3) — 창 상향 + 백필 재개 신호를 한 UPDATE로.
	 * last_swept_on NULL이 핵심이다: 확장 백필이 죽어도 다음 새벽 스윕이 백필 분기(전체 창 열거)로
	 * 자동 복구한다(기존 백스톱 상속).
	 *
	 * <p>2026-09 결함 수정: backfill_attempts·backfill_attempted_at도 0·NULL로 리셋한다 — 확장
	 * 전 열거가 재시도 상한을 소진해 있었다면, 리셋 없이는 확장 백필의 첫 실패가 재시도 없이 곧장
	 * exhausted로 떨어진다(위 insertOrReactivate와 같은 결함, 같은 이유).
	 *
	 * <p>2026-08-13 개정: backfill_completed_at도 리셋한다(08-12의 "보존한다" 결정을 뒤집는다).
	 * FE 폴링 종료 조건이 이 값(응답 collectionCompletedAt)이 되면서, 보존하면 확장 시작 즉시
	 * 폴링이 멎어 확장분이 화면에 반영되지 않는다. 그 대가로 was의 "확장 중 → collecting" 유도
	 * 분기가 도달 불가가 되어 함께 제거했다 — 확장 중 상태는 ready이고, 진행 여부는 FE가
	 * collectionCompletedAt == null로 판정한다(collectionStatus 값 공간을 collecting|ready|error
	 * 3값으로 고정해달라는 FE 요청 계약). 확장 완주 시 touchSwept의 COALESCE가 다시 채운다.
	 *
	 * <p>축소 금지("collection_months는 절대 줄지 않는다")의 판정은 호출자 게이트가 아니라 이
	 * UPDATE 자체에 있다. 호출자의 check-then-act(읽은 창보다 크면 확장)는 동시 요청 둘이 같은 옛
	 * 값을 읽으면 둘 다 통과하고, 그러면 마지막 쓰기가 이겨 12→6 축소가 난다. GREATEST가 그 인터리빙
	 * 에서도 단조 증가를 보장하고, WHERE collection_months &lt; ?가 "이미 더 큰(같은) 창"이면 행을
	 * 아예 건드리지 않아 부수효과(백필 재개 신호·폴링 앵커 리셋)도 남기지 않는다.
	 *
	 * @return 실제로 창이 커졌으면 true — rowcount가 곧 판정 결과다(false = 경합에서 더 큰 창이 이김).
	 */
	public boolean expandWindow(long brandId, int months) {
		return db.update("""
				UPDATE brand_account
				SET collection_months = GREATEST(collection_months, ?), last_swept_on = NULL,
				    collection_started_at = now(), backfill_error = NULL,
				    backfill_completed_at = NULL, backfill_attempts = 0, backfill_attempted_at = NULL
				WHERE id = ? AND collection_months < ?""", months, brandId, months) > 0;
	}

	/**
	 * 확장 스킵 경로(스펙 §7-2) — 이미 상한 도달인 브랜드의 창 상향: 수집 상태(last_swept_on·
	 * backfill_completed_at)는 건드리지 않고 창·커버리지 마킹만 한다. 재백필이 기지 게시물만 세다
	 * 컷될 것이 확정이라 백필을 제출하지 않으므로, {@link #expandWindow}의 재개 신호를 타면 안 된다.
	 * covered_until은 기존값 우선(COALESCE) — 기존 백필이 컷 없이 완주했던(=NULL) 브랜드는
	 * 폴백(기존 창 컷)이 실수집 깊이의 근사다.
	 *
	 * @param coveredUntilFallback covered_until 폴백(기존 창 컷) — <b>NULL 금지</b>: 바인딩이
	 *     {@code Timestamp.from}이라 null이면 NPE다. 호출자가 "now − 기존 창"을 항상 계산해 넘긴다.
	 * @return 실제로 창이 커졌으면 true — {@link #expandWindow}와 같은 rowcount 판정
	 *     (false = 이미 같거나 더 큰 창이라 아무 흔적도 남기지 않음).
	 */
	public boolean raiseWindowCapped(long brandId, int months, Instant coveredUntilFallback) {
		return db.update("""
				UPDATE brand_account
				SET collection_months = GREATEST(collection_months, ?), collection_capped = true,
				    covered_until = COALESCE(covered_until, ?)
				WHERE id = ? AND collection_months < ?""",
				months, Timestamp.from(coveredUntilFallback), brandId, months) > 0;
	}

	/** 창 커버리지 단면(스펙 §7-1) — 일일 스윕의 컷 클램프 입력 + was 노출 원천. */
	public record Coverage(boolean capped, Instant coveredUntil) {}

	/**
	 * 커버리지 조회(스펙 §7-1) — 행이 없으면 미컷 단면을 돌려준다(컬럼 DEFAULT와 같은 값):
	 * 이 값의 소비처는 "컷이면 열거 깊이를 클램프"라 미지 브랜드는 클램프하지 않는 쪽이 안전하다.
	 */
	public Coverage coverage(long brandId) {
		return db.query("SELECT collection_capped, covered_until FROM brand_account WHERE id = ?",
				(rs, i) -> {
					Timestamp until = rs.getTimestamp("covered_until");
					return new Coverage(rs.getBoolean("collection_capped"),
							until == null ? null : until.toInstant());
				}, brandId)
				.stream().findFirst().orElse(new Coverage(false, null));
	}

	/**
	 * 백필 종료 시 커버리지 기록(스펙 §7-1) — 컷 도달이면 true + 실수집 깊이(열거가 실제 도달한
	 * 최고령 편입 taken_at), 완주면 false + NULL(= 요청 창 전체 커버). 백필 실행에서만 부른다 —
	 * 일일 스윕은 창 커버리지를 건드리지 않는다(범위의 이야기지 신선도의 이야기가 아니다).
	 */
	public void updateCoverage(long brandId, boolean capped, Instant coveredUntil) {
		db.update("UPDATE brand_account SET collection_capped = ?, covered_until = ? WHERE id = ?",
				capped, coveredUntil == null ? null : Timestamp.from(coveredUntil), brandId);
	}

	public Optional<BrandRow> findByUsername(String username) {
		return db.query("""
				SELECT id, username, ig_user_id, status, last_swept_on, collection_months, has_own_link
				FROM brand_account WHERE username = ?""",
				BrandRepository::toRow, username).stream().findFirst();
	}

	/**
	 * id 조회(2026-08-18 direct 통합 §T4) — direct 게시물 명령 API(POST/DELETE
	 * {@code /api/brands/{brandId}/direct-posts})가 쓴다. was가 들고 있는 건 username이 아니라
	 * {@code app.brand_monitorings.brand_id}라서(username은 계정명 변경으로 흔들린다) 기존 등록·
	 * 태그 관리 API의 {@code {username}} 경로 변수와 의도적으로 다르다.
	 */
	public Optional<BrandRow> findById(long brandId) {
		return db.query("""
				SELECT id, username, ig_user_id, status, last_swept_on, collection_months, has_own_link
				FROM brand_account WHERE id = ?""",
				BrandRepository::toRow, brandId).stream().findFirst();
	}

	/**
	 * IG 표시명(full_name) — 해시태그 제안 AI 입력(2026-09-03 자동 시드 재설계 §3-3). 등록 시
	 * 프로필 1콜로 저장되고 매일 스윕이 {@link #refreshProfile}로 갱신한다.
	 *
	 * <p>{@link BrandRow}에 싣지 않고 전용 조회로 두는 이유: BrandRow는 스윕·등록의 뜨거운 경로가
	 * 전부 물고 다니는 단면이라 이 한 필드를 위해 넓히면 비용이 크고, 표시명은 제안 계산에서만
	 * 쓰인다(브랜드당 생애 1회).
	 *
	 * <p>미수집(null)·공백은 empty — 호출측이 "표시명 없음"으로 다루고 계정명만으로 진행한다.
	 */
	public Optional<String> findFullName(long brandId) {
		// Stream.findFirst()는 원소가 null이면 Optional.of(null)에서 NPE다(full_name 미수집 행이
		// 정확히 이 경우) — 행 매퍼가 Optional을 담아 null을 안전하게 통과시키고 flatMap으로 편다.
		return db.query("SELECT full_name FROM brand_account WHERE id = ?",
				(rs, i) -> Optional.ofNullable(rs.getString("full_name")), brandId)
				.stream().findFirst().flatMap(value -> value).filter(value -> !value.isBlank());
	}

	public List<BrandRow> findActive() {
		return db.query("""
				SELECT id, username, ig_user_id, status, last_swept_on, collection_months, has_own_link
				FROM brand_account WHERE status = 'ACTIVE' ORDER BY id""",
				BrandRepository::toRow);
	}

	/**
	 * 활성 브랜드를 <b>무거운 순</b>으로 — 브랜드 스윕 병렬화(2026-09-03 설계 §3-1)의 LPT 배정 입력.
	 * 무거움의 정본은 {@code calledOn}(KST 달력일)의 {@code brand_call_count.calls}다: 직전 스윕(전날
	 * KST 02:00~)의 콜이 그 날짜에 계상되므로 호출부는 "KST 오늘 − 1일"을 넘긴다. 이력 없는 브랜드는
	 * 0으로 맨 뒤, 동률은 id 순(결정적). 전날 등록된 브랜드는 백필 콜로 앞에 서는데 무해하다(먼저 돌
	 * 뿐). 다른 호출처(기동 러너들)는 순서가 무의미해 {@link #findActive()}를 그대로 쓴다.
	 */
	public List<BrandRow> findActiveHeaviestFirst(LocalDate calledOn) {
		return db.query("""
				SELECT b.id, b.username, b.ig_user_id, b.status, b.last_swept_on, b.collection_months, b.has_own_link
				FROM brand_account b
				LEFT JOIN brand_call_count c ON c.brand_id = b.id AND c.called_on = ?
				WHERE b.status = 'ACTIVE'
				ORDER BY COALESCE(c.calls, 0) DESC, b.id""",
				BrandRepository::toRow, calledOn);
	}

	/** 탈퇴 — ACTIVE였던 행만 닫는다. @return 실제로 전이됐으면 true(이미 닫힘·미존재는 false). */
	public boolean close(String username) {
		return db.update("""
				UPDATE brand_account SET status = 'CLOSED', closed_at = now()
				WHERE username = ? AND status = 'ACTIVE'""", username) > 0;
	}

	/**
	 * 전량 수집(스윕·백필) 완주 기록 — last_swept_on이 null이면 "수집 준비 중"(백필 상태 판별,
	 * 08-06 결정). last_swept_at은 시각 공급(was의 lastDetectedAt·lastTrackedAt),
	 * backfill_completed_at은 <b>최초</b> 완주 시각이라 COALESCE로 첫 값을 보존한다.
	 * sweep_completed_at은 <b>마지막</b> 완주 시각 — touchProgress가 last_swept_at을 진행
	 * 워터마크로 넓힌 뒤(08-31) 완주 시각을 재는 소비자(Grafana 수집 소요·신선도 패널)용
	 * 전용 컬럼으로, 여기서만 찍는다(09-02).
	 * 성공했으니 직전 백필 오류도 여기서 클리어한다 — 다음 스윕 성공이 오류 기록의 유일한 해제 지점.
	 *
	 * <p><b>backfill_completed_at(= 응답 collectionCompletedAt, FE 폴링 종료 조건)의 확정 의미
	 * (2026-09 완주 스탬프 축소 개정)</b> — "열거된 모든 페이지의 <b>게시물·게시자 보강이 정산
	 * (markEnriched)됨</b> = 목록·지표·게시자가 서빙 완비"다. 종전엔 "모든 페이지 보강(댓글·판정
	 * 포함) 뒤"였으나, was가 이 값을 해석 없이 통과시킬 뿐이라 "판정 포함 완주"에 의존하는 소비자가
	 * 없음을 확인하고 좁혔다. <b>댓글 수집·광고 표기 판정은 이 표식 밖의 후행 단계</b>다 — 등록
	 * 백필({@link com.celfit.monitoring.service.BrandRegistrationService#runBackfillSafely})의
	 * 페이지 join이 후행을 기다리지 않고 여기가 찍힌 뒤 후행 전용 executor에서 조용히 채워질 수
	 * 있다(계약 {@code docs/contracts/monitoring-was-contract.md} collectionCompletedAt 절 참조).
	 *
	 * <p><b>backfill_attempts도 여기서 0으로 되돌린다(2026-09 열거 실패 재시도 스케줄러 신설)</b> —
	 * 성공이 재시도 예산의 유일한 해제 지점이라는 backfill_error와 같은 규율의 연장이다. 이걸 안 하면
	 * 이후 기간 확장 재백필({@link #expandWindow})이 지난 등록의 소진된 예산을 물려받아 재시도가
	 * 열리지 않는다({@link com.celfit.monitoring.service.BrandBackfillRetryJob} 참조).
	 */
	public void touchSwept(long brandId, LocalDate on) {
		db.update("""
				UPDATE brand_account
				SET last_swept_on = ?, last_swept_at = now(), sweep_completed_at = now(),
				    backfill_completed_at = COALESCE(backfill_completed_at, now()),
				    backfill_error = NULL, backfill_attempts = 0
				WHERE id = ?""", on, brandId);
	}

	/**
	 * 조기 서빙 마크(2026-08-13 완결 배치 서빙 스펙 §1 — 구 "서빙 창(최근 30일) 커버" 기준 폐기) —
	 * 등록 백필의 <b>첫 페이지 배치 보강이 끝난</b> 시점에 was ready 판정 컬럼(last_swept_at)만
	 * 당긴다. last_swept_on(다음 스윕 열거 깊이 판정)과 backfill_completed_at(FE 폴링 종료 조건인
	 * 응답 collectionCompletedAt)은 전 페이지 보강 완료 시점의 touchSwept가 찍는다 — 여기서
	 * last_swept_on까지 찍으면 이후 열거 실패 시 다음 스윕이 14일 컷만 돌아 30~365일 구간이 영구
	 * 공백이 된다. IS NULL 가드: 첫 백필에서만 유효(재가입·이미 서빙 중이면 no-op).
	 */
	public void markServing(long brandId) {
		db.update("UPDATE brand_account SET last_swept_at = now() WHERE id = ? AND last_swept_at IS NULL",
				brandId);
	}

	/**
	 * 진행 워터마크(2026-08-31 등록 백필 캐시 고착 수리) — 페이지 정산(markEnriched)마다
	 * last_swept_at을 전진시킨다. was 버전키(DashboardVersion.brandWatermarks)의 브랜드 입력이
	 * 이 컬럼이라, 안 움직이면 백필 도중(첫 페이지 markServing ~ 완주 touchSwept 사이 수 분)
	 * 폴링이 전부 캐시에 붙어 게시물이 완주 시점에 한꺼번에 나타난다(08-31 skinfood 실측).
	 * markServing과 달리 가드 없이 무조건 전진한다 — 재가입·기간 확장 재백필(last_swept_at이
	 * 이미 찬 상태)도 같은 고착에 걸리기 때문. last_swept_at의 의미는 이 개정으로 "완주 시각"에서
	 * "마지막 수집 활동 시각"으로 넓어졌다(완주 판정은 원래부터 last_swept_on·backfill_completed_at
	 * 몫이라 판정 로직 영향 없음, was lastCollectedAt 표기는 오히려 정확해진다).
	 * 완주 <b>시각</b>이 필요한 소비자(Grafana 수집 소요·신선도 패널)는 touchSwept 전용
	 * sweep_completed_at을 본다(09-02 분리) — 이 메서드는 그 컬럼을 건드리지 않는다.
	 */
	public void touchProgress(long brandId) {
		db.update("UPDATE brand_account SET last_swept_at = now() WHERE id = ?", brandId);
	}

	/**
	 * 초기 백필 실패 기록 — was 폴링이 "수집 중"에서 빠져나올 신호(계약 §5-2).
	 * last_swept_on이 이미 찬 브랜드(= 한 번이라도 완주한 ready 상태)는 덮지 않는다:
	 * 그쪽은 이미 보여줄 데이터가 있어서 실패를 사용자 화면에 띄울 이유가 없다.
	 */
	public void markBackfillError(long brandId, String message) {
		db.update("UPDATE brand_account SET backfill_error = ? WHERE id = ? AND last_swept_on IS NULL",
				message, brandId);
	}

	/**
	 * 백필 재시도 후보 조회(2026-09 열거 실패 재시도 스케줄러, {@link
	 * com.celfit.monitoring.service.BrandBackfillRetryJob} 전용) — 세 조건이 함께 "정상 진행 중"과
	 * "상한 소진"을 자연 배제한다:
	 * <ul>
	 *   <li>{@code last_swept_on IS NULL} — 한 번이라도 완주한 브랜드는 대상 아님</li>
	 *   <li>{@code backfill_error IS NOT NULL} — 정상 진행 중인 브랜드는 이 값이 null이라(신규 삽입·
	 *       기간 확장 둘 다 NULL로 시작) 자동 제외된다. {@link #markBackfillError}가 {@code sweepCore}
	 *       예외 시에만 찍는 구조(페이지별 댓글·판정 실패는 내부에서 삼켜짐) 덕에 성립한다</li>
	 *   <li>{@code backfill_attempts < maxAttempts} — 상한 소진 브랜드는 자동 제외</li>
	 * </ul>
	 * 나이 앵커는 {@code registered_at}이 아니라 {@code collection_started_at}이다 — 등록·재등록·
	 * 기간 확장 모두가 {@code now()}로 갱신하고(was의 FE 폴링 30분 상한과 같은 앵커), 이 재시도의
	 * 목적이 "폴링 중인 사용자 화면 구제"이지 범용 수리 도구가 아니라는 것과, 최초 배포 시 기존
	 * 실패 잔량이 한꺼번에 예산을 받는 herd를 자르는 것 둘 다의 근거다. 백오프는 시도 횟수에
	 * 비례해 선형으로 늘어난다({@code backoffMinutes * (attempts + 1)}) — 상한 3 기준 대략
	 * t+5, t+15, t+30분에 재시도가 들어간다. 정렬은 최신 등록 우선(화면을 보고 있을 확률이 높은 순).
	 */
	public List<BrandRow> findBackfillRetryCandidates(int maxAttempts, int maxAgeMinutes,
			int backoffMinutes, int limit) {
		return db.query("""
				SELECT id, username, ig_user_id, status, last_swept_on, collection_months, has_own_link
				FROM brand_account
				WHERE status = 'ACTIVE'
				  AND last_swept_on IS NULL
				  AND backfill_error IS NOT NULL
				  AND backfill_attempts < ?
				  AND collection_started_at > now() - make_interval(mins => ?)
				  AND (backfill_attempted_at IS NULL
				       OR backfill_attempted_at < now() - make_interval(mins => ? * (backfill_attempts + 1)))
				ORDER BY collection_started_at DESC
				LIMIT ?""",
				BrandRepository::toRow, maxAttempts, maxAgeMinutes, backoffMinutes, limit);
	}

	/**
	 * 재시도 제출 직전 호출 — 완료 시점이 아니라 <b>제출 직전</b>에 증가시킨다: 완료 시 증가로 하면
	 * 재시도 도중 프로세스가 죽었을 때 예산이 환불돼 무한 재시도가 된다. {@code last_swept_on IS
	 * NULL} 가드는 {@link #markBackfillError}와 같은 이유 — 그사이 다른 경로(동시 재가입 등)가
	 * 이미 완주시켰으면 더 이상 재시도 대상이 아니다.
	 */
	public void markBackfillAttempt(long brandId) {
		db.update("""
				UPDATE brand_account SET backfill_attempts = backfill_attempts + 1, backfill_attempted_at = now()
				WHERE id = ? AND last_swept_on IS NULL""", brandId);
	}

	/**
	 * 재시도 예산 소진 브랜드의 {@code backfill_error} 문구를 "다음 새벽 정기 수집에서 다시
	 * 시도해요."로 교체한다 — 상한 소진 후보는 {@link #findBackfillRetryCandidates}가 애초에 뽑지
	 * 않으므로(attempts &lt; maxAttempts 조건), 문구 교체는 매 틱 이 별도 UPDATE로 한다.
	 * {@code backfill_error <> ?} 조건이 멱등성을 준다 — 이미 교체된 행은 재틱에서 다시 세지 않는다
	 * (반환값은 <b>이번 틱에 새로</b> 교체된 행 수).
	 */
	public int markBackfillRetryExhausted(String message, int maxAttempts) {
		return db.update("""
				UPDATE brand_account
				SET backfill_error = ?
				WHERE status = 'ACTIVE' AND last_swept_on IS NULL AND backfill_error IS NOT NULL
				  AND backfill_attempts >= ? AND backfill_error <> ?""",
				message, maxAttempts, message);
	}

	/**
	 * 매일 스윕의 프로필 관측 반영(08-06 개정 — 등록 1회 아님). 추이는 brand_profile_snapshot에.
	 * 관측값을 그대로 덮는다(부분 null 보존 없음) — 프로필 콜은 성공하면 한 응답이 통째로 오고
	 * 실패하면 여기까지 오지도 않는다(호출부가 best-effort로 격리). 그래서 null은 그 응답의 판정
	 * 그대로다: 외부링크 없음이거나 인증뱃지 키 부재(=unknown, ProfileInfo 주석). 옛 값을 남기면
	 * 링크 삭제·뱃지 해제를 영영 못 따라간다.
	 */
	public void refreshProfile(long brandId, ProfileInfo profile) {
		db.update("""
				UPDATE brand_account
				SET followers = ?, biography = ?, full_name = ?, profile_pic_url = ?,
				    is_verified = ?, external_url = ?, following = ?, media_count = ?
				WHERE id = ?""",
				profile.followers(), profile.biography(), profile.fullName(), profile.profilePicUrl(),
				profile.isVerified(), profile.externalUrl(), profile.following(), profile.mediaCount(),
				brandId);
	}

	private static BrandRow toRow(ResultSet rs, int i) throws SQLException {
		java.sql.Date swept = rs.getDate("last_swept_on");
		return new BrandRow(rs.getLong("id"), rs.getString("username"), rs.getString("ig_user_id"),
				BrandStatus.valueOf(rs.getString("status")),
				swept == null ? null : swept.toLocalDate(), rs.getInt("collection_months"),
				rs.getBoolean("has_own_link"));
	}
}
