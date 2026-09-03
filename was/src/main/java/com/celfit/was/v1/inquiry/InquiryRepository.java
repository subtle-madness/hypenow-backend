package com.celfit.was.v1.inquiry;

import com.celfit.was.crypto.FieldCipher;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 도입문의 저장(app.inquiries) — 운영자 확인은 DB 조회(pgweb·psql), 별도 조회 API 없음. */
@Repository
public class InquiryRepository {

	private final JdbcClient jdbcClient;
	private final FieldCipher fieldCipher;

	public InquiryRepository(JdbcClient jdbcClient, FieldCipher fieldCipher) {
		this.jdbcClient = jdbcClient;
		this.fieldCipher = fieldCipher;
	}

	/** name_enc/email_enc/organization_enc/message_enc도 함께 채운다(스펙 §전환 1 이중 쓰기). */
	public UUID insert(InquiryRequest request) {
		return jdbcClient.sql("""
				INSERT INTO app.inquiries (user_type, name, email, organization, message,
				                           name_enc, email_enc, organization_enc, message_enc)
				VALUES (:userType, :name, :email, :organization, :message,
				        :nameEnc, :emailEnc, :organizationEnc, :messageEnc)
				RETURNING id""")
				.param("userType", request.userType())
				.param("name", request.name())
				.param("email", request.email())
				.param("organization", request.organization())
				.param("message", request.message())
				.param("nameEnc", fieldCipher.encrypt(request.name()))
				.param("emailEnc", fieldCipher.encrypt(request.email()))
				.param("organizationEnc", fieldCipher.encrypt(request.organization()))
				.param("messageEnc", fieldCipher.encrypt(request.message()))
				.query(UUID.class)
				.single();
	}
}
