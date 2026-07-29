package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * user_id 기준 모니터링 오케스트레이션(스펙 §6).
 * 등록은 멱등키 선저장 2단계 — was가 호출 직후 죽어도 키가 남아 같은 키 replay가 가능하다.
 * 명령은 (user, target) 매핑 소유 검증 후 위임. 삭제는 해지 성공 후에만 매핑을 지운다.
 *
 * 주의: registrationKey 멱등성은 "같은 논리적 요청의 재시도"에만 유효하다 — 같은 유저가 같은
 * 대상을 새 요청으로 두 번 등록하면 별개 캠페인 2개가 정상 생성된다(계약 §2-1). 동일 대상
 * 중복 등록을 막을지는 이 서비스 책임 밖(프론트 API 계층에서 판단할 것).
 *
 * 이 서비스(특히 등록)를 @Transactional로 감싸지 말 것 — insertPending이 HTTP 호출 전에
 * 커밋되는 것이 멱등키 replay·크래시 복구의 전제다. 트랜잭션으로 묶으면 전송 실패 시 pending
 * 행까지 롤백되어 설계가 무력화되고, 최대 ~20초(10s×2회) 커넥션을 점유한다.
 */
public class MonitoringCampaignService {

	private final MonitoringCommandClient client;
	private final MonitoringCampaignMappingRepository mappings;

	public MonitoringCampaignService(MonitoringCommandClient client,
			MonitoringCampaignMappingRepository mappings) {
		this.client = client;
		this.mappings = mappings;
	}

	public RegisterResult registerAccount(long userId, String username, KeywordRule keywordRule,
			OffsetDateTime expiresAt) {
		UUID key = UUID.randomUUID();
		mappings.insertPending(userId, key);
		return completeRegistration(key, RegisterRequest.account(key, username, keywordRule, expiresAt));
	}

	public RegisterResult registerPost(long userId, String shortCode, OffsetDateTime expiresAt) {
		UUID key = UUID.randomUUID();
		mappings.insertPending(userId, key);
		return completeRegistration(key, RegisterRequest.post(key, shortCode, expiresAt));
	}

	private RegisterResult completeRegistration(UUID key, RegisterRequest request) {
		RegisterResult result;
		try {
			result = registerWithOneRetry(request);
		} catch (MonitoringApiException e) {
			// 확정 실패(계정 없음·비공개 등) — monitoring에 target 미생성이므로 pending 정리
			mappings.deleteByKey(key);
			throw e;
		}
		// confirmTarget의 IllegalStateException(갱신 0행)은 현재 도달 불가 — pending 행을 지우는
		// 경로가 위 ApiException catch뿐이라서다. 후속으로 pending 청소 잡이 생기면 느린 재시도와의
		// 레이스로 이 경로가 열리므로 그때 처리 방식을 재검토할 것(스펙 §6 알려진 한계).
		// 전송 계열(Unavailable)로 여기 못 오면 pending 행이 남는다 — 의도된 보류(스펙 §6 알려진 한계)
		mappings.confirmTarget(key, result.targetId());
		return result;
	}

	private RegisterResult registerWithOneRetry(RegisterRequest request) {
		try {
			return client.register(request);
		} catch (MonitoringUnavailableException first) {
			// 같은 registrationKey 멱등 replay — 첫 호출로 target이 만들어졌어도 같은 행이 200으로 온다
			return client.register(request);
		}
	}

	public ApproveResult approve(long userId, long targetId, long candidateId) {
		requireOwned(userId, targetId);
		return client.approve(targetId, candidateId);
	}

	public RejectResult reject(long userId, long targetId, long candidateId) {
		requireOwned(userId, targetId);
		return client.reject(targetId, candidateId);
	}

	public ExtendResult extend(long userId, long targetId, OffsetDateTime expiresAt) {
		requireOwned(userId, targetId);
		return client.extend(targetId, expiresAt);
	}

	/**
	 * 유저의 "캠페인 삭제"(계약 §5) — monitoring엔 해지(상태 전이)만 보내고, 성공했을 때만
	 * was 매핑을 지운다. 순서 고정으로 "monitoring엔 살아있는데 매핑만 없는" 상태를 막는다.
	 */
	public CancelResult cancelAndDelete(long userId, long targetId) {
		requireOwned(userId, targetId);
		CancelResult result = client.cancel(targetId);
		mappings.deleteByUserAndTarget(userId, targetId);
		return result;
	}

	private void requireOwned(long userId, long targetId) {
		var unused = mappings.findByUserAndTarget(userId, targetId)
				.orElseThrow(() -> new MonitoringCampaignNotFoundException(targetId));
	}
}
