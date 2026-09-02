package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 협찬 마커 판정의 SQL(Postgres ARE)↔Java 동치성 봉인(2026-08-27 P0) — 슬림 인덱스가 캡션 대신
 * SQL 매치 boolean을 받으므로, 두 구현이 갈라지면 counts·필터가 화면과 어긋난다. 마커 상수를
 * 고칠 때는 이 코퍼스에 사례를 추가하면 두 구현이 함께 검증된다.
 */
class BrandSponsorshipSqlEquivalenceTest extends IntegrationTest {

	@Autowired
	DataSource dataSource;

	/** 기존 classifier 주석의 함정 사례 전부 + 경계 사례. 기대값은 명시하지 않는다 — 두 구현의 일치만 단언. */
	private static final List<String> CORPUS = List.of(
			"#광고 협찬 후기", "오늘의 데일리룩 #adventure", "광고 아님 진짜 후기", "adorable puppy",
			"Bu bir reklamdır", "가장 reklam 같은", "웃reklam", "reklam", "_reklam var",
			"광고 ㅣ 신상 리뷰", "광고 l 신상", "광고 l", "AD | brand", "ad- daily", "협찬:후기",
			"#AD!", "#ad", "x#ad y", "##ad", "#prsample 후기", "#pr!", "#pring", "#Werbung.",
			"業配 내돈내산 아님", "広告です", "广告", "유료 광고 포함", "유료광고", "광고입니다",
			"이건광고입니다", "협찬받아 작성", "제품 제공 받았어요", "제공받아 솔직 후기",
			"#sponsored", "#sponsor", "#gifted post", "#paidpartnership", "#publicidad", "#anzeige",
			"\u00A0광고 ㅣ 선두가 NBSP", "  광고 — 대시 구분자", "광고~물결", "협찬 · 가운뎃점",
			"그냥 일상 글", "", " ", "커피 #맛집 #서울카페", "sponsored by nobody",
			"광고비 없이 씀", "This is an ad for fun",   // "an ad for" — 태그 아님·선두 아님 → 비매치 기대
			// 수직탭(U+000B) 경계 — Java \s에는 들어가지만 \t\n\f\r에는 안 잡혀 ARE_SPACE에서 빠뜨리기
			// 쉬운 문자. 구분자 앞자리·선두 공백 두 자리 모두 확인한다(빠지면 SQL만 미탐).
			"광고\013ㅣ 수직탭 구분자", "\013광고 ㅣ 수직탭 선두",
			// 비ASCII 경계 — Postgres [:alnum:]이 유니코드 인식이어야 Java \p{L}\p{N}과 같은 답을 낸다.
			// 앞 둘은 양쪽 다 비매치가 기대값(태그 토큰 불일치 / reklam 단어 중간),
			// "#광고태그중간ad"는 "#광고" 부분 문자열에 걸려 양쪽 다 매치가 기대값이다.
			"#ad한글", "한글reklam", "#광고태그중간ad");

	@Test
	void SQL_마커_매치는_Java_판정과_전_코퍼스에서_일치한다() {
		JdbcClient jdbc = JdbcClient.create(dataSource);
		String regex = BrandSponsorshipClassifier.postgresMarkerRegex();
		// 첫 불일치에서 끊지 않고 전부 모아 보고한다 — 정규식을 고칠 때 갈라진 지점을 한 번에 본다.
		List<String> mismatches = new ArrayList<>();
		for (String caption : CORPUS) {
			boolean sql = jdbc.sql("SELECT lower(:caption) ~ :regex")
					.param("caption", caption).param("regex", regex)
					.query(Boolean.class).single();
			boolean java = BrandSponsorshipClassifier.containsSponsorshipMarker(caption);
			if (sql != java) {
				mismatches.add("캡션 %s → SQL=%s Java=%s".formatted(quote(caption), sql, java));
			}
		}
		assertThat(mismatches).as("SQL↔Java 마커 판정 불일치 (정규식 %s)", regex).isEmpty();
	}

	/** 공백·제어문자가 눈에 보이게 — 실패 메시지에서 수직탭·NBSP를 구분하기 위해. */
	private static String quote(String caption) {
		StringBuilder sb = new StringBuilder("\"");
		caption.codePoints().forEach(cp -> {
			if (cp < 0x20 || cp == 0xA0) {
				sb.append("\\u%04X".formatted(cp));
			} else {
				sb.appendCodePoint(cp);
			}
		});
		return sb.append('"').toString();
	}
}
