package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.ProfileInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
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
	 */
	public long insertOrReactivate(String username, ProfileInfo profile, int collectionMonths) {
		return db.queryForObject("""
				INSERT INTO brand_account (username, ig_user_id, followers, biography, full_name,
				                           profile_pic_url, is_verified, external_url, following, media_count,
				                           collection_months, collection_started_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
				ON CONFLICT (username) DO UPDATE SET
				  ig_user_id = EXCLUDED.ig_user_id, followers = EXCLUDED.followers,
				  biography = EXCLUDED.biography, full_name = EXCLUDED.full_name,
				  profile_pic_url = EXCLUDED.profile_pic_url, is_verified = EXCLUDED.is_verified,
				  external_url = EXCLUDED.external_url, following = EXCLUDED.following,
				  media_count = EXCLUDED.media_count, status = 'ACTIVE', closed_at = NULL,
				  last_swept_on = NULL, backfill_error = NULL, backfill_completed_at = NULL,
				  registered_at = now(),
				  collection_months = GREATEST(brand_account.collection_months, EXCLUDED.collection_months),
				  collection_started_at = now()
				RETURNING id""",
				Long.class, username, profile.userId(), profile.followers(), profile.biography(),
				profile.fullName(), profile.profilePicUrl(), profile.isVerified(), profile.externalUrl(),
				profile.following(), profile.mediaCount(), collectionMonths);
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
	 * 브랜드 소개 — 해시태그 판정기(BrandMentionJudge)의 이름 충돌 방어 컨텍스트(스펙 §4-6).
	 * BrandRow는 조회 단면(콜 파라미터)이라 biography까지 넣으면 스윕 경로 전역에 파급되므로,
	 * 스윕 진입 시 1회(콜 0, DB만)만 별도로 뽑는다. 행이 없으면 null(브랜드 조회 자체가 이미
	 * 앞단에서 검증됐다는 전제라 이 경로에서 예외로 승격하지 않는다).
	 */
	public String findBiography(long brandId) {
		return db.query("SELECT biography FROM brand_account WHERE id = ?",
				(rs, i) -> rs.getString("biography"), brandId)
				.stream().findFirst().orElse(null);
	}

	public Optional<BrandRow> findByUsername(String username) {
		return db.query("""
				SELECT id, username, ig_user_id, status, last_swept_on, collection_months
				FROM brand_account WHERE username = ?""",
				BrandRepository::toRow, username).stream().findFirst();
	}

	public List<BrandRow> findActive() {
		return db.query("""
				SELECT id, username, ig_user_id, status, last_swept_on, collection_months
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
	 * 성공했으니 직전 백필 오류도 여기서 클리어한다 — 다음 스윕 성공이 오류 기록의 유일한 해제 지점.
	 */
	public void touchSwept(long brandId, LocalDate on) {
		db.update("""
				UPDATE brand_account
				SET last_swept_on = ?, last_swept_at = now(),
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
				swept == null ? null : swept.toLocalDate(), rs.getInt("collection_months"));
	}
}
