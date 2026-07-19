package com.celfit.was.v1.inquiry;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 도입문의 저장(app.inquiries) — 운영자 확인은 DB 조회(pgweb·psql), 별도 조회 API 없음. */
@Repository
public class InquiryRepository {

	private final JdbcClient jdbcClient;

	public InquiryRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public UUID insert(InquiryRequest request) {
		return jdbcClient.sql("""
				INSERT INTO app.inquiries (user_type, name, email, organization, message)
				VALUES (:userType, :name, :email, :organization, :message)
				RETURNING id""")
				.param("userType", request.userType())
				.param("name", request.name())
				.param("email", request.email())
				.param("organization", request.organization())
				.param("message", request.message())
				.query(UUID.class)
				.single();
	}
}
