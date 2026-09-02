package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.ProfileInfo;
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
	 * 전까지는 "수집 준비 중"(was 계약의 판별 기준)이어야 하기 때문이다. 백필 상태 두 컬럼
	 * (backfill_error·backfill_completed_at)도 같은 이유로 리셋한다 — 재등록은 백필을 처음부터
	 * 다시 도는 것이라, 지난 가입의 완주·실패 기록이 남으면 was 폴링이 곧장 ready를 본다.
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
				    backfill_completed_at = NULL
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

	public List<BrandRow> findActive() {
		return db.query("""
				SELECT id, username, ig_user_id, status, last_swept_on, collection_months, has_own_link
				FROM brand_account WHERE status = 'ACTIVE' ORDER BY id""",
				BrandRepository::toRow);
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
	 */
	public void touchSwept(long brandId, LocalDate on) {
		db.update("""
				UPDATE brand_account
				SET last_swept_on = ?, last_swept_at = now(), sweep_completed_at = now(),
				    backfill_completed_at = COALESCE(backfill_completed_at, now()),
				    backfill_error = NULL
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
