package com.celfit.monitoring.alarm;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 수신자 해석 — analysis DB의 app 스키마를 읽기 전용 롤(alarm_reader)로 조회한다(계약 v2 §6 역방향).
 * 읽는 객체는 {@code app.users(email)}와 {@code app.monitoring_email_opt_outs} 둘뿐이고,
 * 롤에도 그 둘만 GRANT한다 — 그 밖을 건드리면 권한 오류로 fail-closed다.
 *
 * <p>DataSource는 **지연 생성**한다: 알람이 꺼진 환경(로컬·테스트·개통 전 운영)에서 DSN이 없다는
 * 이유로 monitoring 부팅이 깨지면 안 된다. 스프링 자동구성 DataSource와 별개인 수동 조립이라
 * 빈으로 노출하지 않는다 — 두 번째 DataSource 빈이 뜨면 기존 JdbcTemplate 주입이 모호해진다.
 */
@Component
public class AlarmRecipientReader {

	private final String url;
	private final String username;
	private final String password;

	private volatile JdbcTemplate db;
	private HikariDataSource dataSource;

	/**
	 * Spring이 쓰는 생성자 — 아래 test-only 생성자와 함께 두 개라 명시가 필요하다(단일 생성자면
	 * Spring이 애노테이션 없이도 골라 쓰지만, 둘 이상이면 어느 쪽인지 스스로 정하지 못하고
	 * 기본 생성자를 찾다가 부팅이 깨진다 — RegistrationApiTest 등 전체 컨텍스트 테스트에서 실측).
	 */
	@Autowired
	public AlarmRecipientReader(@Value("${monitoring.alarm.reader.url:}") String url,
			@Value("${monitoring.alarm.reader.username:}") String username,
			@Value("${monitoring.alarm.reader.password:}") String password) {
		this.url = url;
		this.username = username;
		this.password = password;
	}

	/** 테스트 전용 — 이미 만들어진 DataSource로 조립한다(컨테이너 공유). */
	AlarmRecipientReader(DataSource ds) {
		this(null, null, null);
		this.db = new JdbcTemplate(ds);
	}

	/** DSN이 없으면 발송 잡 자체를 돌리지 않는다 — 유저마다 예외를 던져 로그를 채우는 것보다 낫다. */
	public boolean configured() {
		return db != null || (url != null && !url.isBlank());
	}

	public Optional<String> findEmail(long userId) {
		return db().queryForList("SELECT email FROM app.users WHERE id = ?", String.class, userId)
				.stream().filter(e -> e != null && !e.isBlank()).findFirst();
	}

	/** 행이 없으면 켜짐(기본 on) — 설정 화면과 1:1이라 빈 테이블이 곧 "전원 수신"이다. */
	public Set<AlarmEventType> findOptOuts(long userId) {
		Set<AlarmEventType> out = EnumSet.noneOf(AlarmEventType.class);
		for (String value : db().queryForList("""
				SELECT event_type FROM app.monitoring_email_opt_outs WHERE user_id = ?""",
				String.class, userId)) {
			// 모르는 어휘는 무시한다 — was가 새 이벤트 종류를 먼저 배포할 수 있다.
			AlarmEventType.parse(value).ifPresent(out::add);
		}
		return out;
	}

	private JdbcTemplate db() {
		JdbcTemplate local = db;
		if (local == null) {
			synchronized (this) {
				local = db;
				if (local == null) {
					HikariConfig hikari = new HikariConfig();
					hikari.setJdbcUrl(url);
					hikari.setUsername(username);
					hikari.setPassword(password);
					hikari.setMaximumPoolSize(2);   // 5분 틱 조회 전용
					hikari.setPoolName("alarm-reader");
					hikari.setReadOnly(true);
					dataSource = new HikariDataSource(hikari);
					local = new JdbcTemplate(dataSource);
					db = local;
				}
			}
		}
		return local;
	}

	@PreDestroy
	void close() {
		if (dataSource != null) {
			dataSource.close();
		}
	}
}
