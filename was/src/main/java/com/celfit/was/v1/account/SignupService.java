package com.celfit.was.v1.account;

import com.celfit.was.auth.UserProfile;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.v1.common.V1ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입 트랜잭션 — users INSERT와 가입 코드 선점(claim)을 한 트랜잭션으로 묶는다(설계 2026-07-19).
 * 동일 코드 동시 가입 레이스에서 claim의 조건부 UPDATE가 한 명만 통과시키고,
 * 낙오자는 403과 함께 INSERT가 롤백되므로 코드 없는 계정이 남지 않는다.
 */
@Service
public class SignupService {

	private final UserRepository userRepository;
	private final SignupCodeRepository signupCodeRepository;
	private final PasswordEncoder passwordEncoder;

	public SignupService(UserRepository userRepository, SignupCodeRepository signupCodeRepository,
			PasswordEncoder passwordEncoder) {
		this.userRepository = userRepository;
		this.signupCodeRepository = signupCodeRepository;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	public UserProfile register(SignupRequest request) {
		UserProfile profile;
		try {
			profile = userRepository.insertProfile(request.toNewUser(), passwordEncoder.encode(request.password()));
		} catch (DuplicateKeyException e) {
			throw V1ApiException.conflict("EMAIL_ALREADY_EXISTS", "이미 가입된 이메일이에요. 로그인해 주세요.");
		}
		if (!signupCodeRepository.claim(request.signupCode(), profile.id())) {
			throw V1ApiException.forbidden("INVALID_SIGNUP_CODE", "존재하지 않거나 이미 사용된 코드입니다.");
		}
		return profile;
	}
}
