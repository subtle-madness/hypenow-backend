package com.celfit.was;

import com.celfit.was.crypto.FieldCipher;
import com.celfit.was.crypto.PiiBackfillRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 원시 SQL로 시드한 PII 행(app.users 등)의 *_enc·*_bidx를 채우는 테스트 헬퍼.
 *
 * <p>읽기 전환(스펙 §전환 2, 09-04) 이후 조회는 암호문·블라인드 인덱스만 본다 — 평문 컬럼만
 * 넣은 픽스처는 로그인·프로필 조회에 아예 잡히지 않는다(운영에는 그런 행이 없다: 백필 완료가
 * 전환의 전제였다). 픽스처를 그 전제에 맞추는 한 줄이며, 규칙을 다시 적지 않으려고
 * <b>운영 백필과 같은 코드</b>({@link PiiBackfillRunner})를 그대로 돌린다(*_enc IS NULL만 채우는
 * 멱등 커맨드라 여러 번 불러도 안전하다).
 */
public final class PiiTestSeed {

	private PiiTestSeed() {
	}

	public static void backfill(JdbcClient jdbcClient, FieldCipher fieldCipher) {
		new PiiBackfillRunner(jdbcClient, fieldCipher).backfillAll();
	}
}
