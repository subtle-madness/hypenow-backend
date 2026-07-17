package com.celfit.was.v1.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.v1.common.V1ApiException;
import org.junit.jupiter.api.Test;

class SignupValidatorTest {

	private final SignupValidator validator = new SignupValidator();

	/** 유효한 기본값에서 필드 하나씩 바꿔 위반을 만드는 뮤터블 픽스처 — record 16필드 재나열 방지. */
	private static final class Req {
		String email = "user@example.com";
		String password = "Passw0rd!";
		String name = "김우민";
		String nickname = null;
		String userType = "brand";
		String signupRoute = "portal_search";
		String phoneCountryCode = "+82";
		String phoneNumber = "010-1234-5678";
		String companyName = "하이프나우";
		String companySize = "2-10";
		String industry = "beauty";
		String jobTitle = "staff";
		Boolean agreedTerms = true;
		Boolean agreedPrivacy = true;
		Boolean agreedAge14 = true;
		Boolean agreedMarketing = false;

		SignupRequest build() {
			return new SignupRequest(email, password, name, nickname, userType, signupRoute,
					phoneCountryCode, phoneNumber, companyName, companySize, industry, jobTitle,
					agreedTerms, agreedPrivacy, agreedAge14, agreedMarketing);
		}
	}

	private void assertValidationFails(Req req) {
		assertThatThrownBy(() -> validator.validate(req.build()))
				.isInstanceOfSatisfying(V1ApiException.class,
						e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
	}

	@Test
	void 정상_요청은_통과한다() {
		assertThatCode(() -> validator.validate(new Req().build())).doesNotThrowAnyException();
	}

	@Test
	void 이메일_형식_위반은_VALIDATION_FAILED() {
		Req req = new Req();
		req.email = "bad-email";
		assertValidationFails(req);

		req.email = null;
		assertValidationFails(req);
	}

	@Test
	void 비밀번호_8자_미만은_거부된다() {
		Req req = new Req();
		req.password = "Pa0!x";
		assertValidationFails(req);
	}

	@Test
	void 비밀번호_대문자_누락은_거부된다() {
		Req req = new Req();
		req.password = "passw0rd!";
		assertValidationFails(req);
	}

	@Test
	void 비밀번호_소문자_누락은_거부된다() {
		Req req = new Req();
		req.password = "PASSW0RD!";
		assertValidationFails(req);
	}

	@Test
	void 비밀번호_숫자_누락은_거부된다() {
		Req req = new Req();
		req.password = "Password!";
		assertValidationFails(req);
	}

	@Test
	void 비밀번호_특수문자_누락은_거부된다() {
		Req req = new Req();
		req.password = "Passw0rd1";
		assertValidationFails(req);
	}

	@Test
	void userType_허용값_밖은_거부된다() {
		Req req = new Req();
		req.userType = "hacker";
		assertValidationFails(req);
	}

	@Test
	void signupRoute_허용값_밖은_거부된다() {
		Req req = new Req();
		req.signupRoute = "tv_ad";
		assertValidationFails(req);
	}

	@Test
	void phoneCountryCode_허용값_밖은_거부된다() {
		Req req = new Req();
		req.phoneCountryCode = "+49";
		assertValidationFails(req);
	}

	@Test
	void companySize_허용값_밖은_거부된다() {
		Req req = new Req();
		req.companySize = "1";
		assertValidationFails(req);
	}

	@Test
	void industry_허용값_밖은_거부된다() {
		Req req = new Req();
		req.industry = "gaming";
		assertValidationFails(req);
	}

	@Test
	void jobTitle_허용값_밖은_거부된다() {
		Req req = new Req();
		req.jobTitle = "intern";
		assertValidationFails(req);
	}

	@Test
	void 이름이_비어있으면_거부된다() {
		Req req = new Req();
		req.name = "  ";
		assertValidationFails(req);
	}

	@Test
	void 전화번호가_비어있으면_거부된다() {
		Req req = new Req();
		req.phoneNumber = "";
		assertValidationFails(req);
	}

	@Test
	void 회사명이_비어있으면_거부된다() {
		Req req = new Req();
		req.companyName = null;
		assertValidationFails(req);
	}

	@Test
	void 필수_약관_하나라도_동의하지_않으면_거부된다() {
		Req req = new Req();
		req.agreedTerms = false;
		assertValidationFails(req);

		Req req2 = new Req();
		req2.agreedPrivacy = null;
		assertValidationFails(req2);

		Req req3 = new Req();
		req3.agreedAge14 = false;
		assertValidationFails(req3);
	}

	@Test
	void 마케팅_동의는_자유다() {
		Req req = new Req();
		req.agreedMarketing = null;
		assertThatCode(() -> validator.validate(req.build())).doesNotThrowAnyException();
	}
}
