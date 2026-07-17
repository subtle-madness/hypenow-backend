package com.celfit.was.v1.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.v1.common.V1ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

/** ProfileImageStore 단위 검증 — @TempDir 실제 파일 IO, Clock은 고정(캐시버스터 v 검증). */
class ProfileImageStoreTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-07-15T12:00:00Z"); // epochSecond=1784116800

	@TempDir
	Path tempDir;

	private ProfileImageStore store;

	@BeforeEach
	void setUp() {
		store = new ProfileImageStore(tempDir.toString(), Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
	}

	private static byte[] png(int size) {
		byte[] bytes = new byte[size];
		bytes[0] = (byte) 0x89;
		bytes[1] = 0x50;
		bytes[2] = 0x4E;
		bytes[3] = 0x47;
		return bytes;
	}

	private static byte[] jpeg(int size) {
		byte[] bytes = new byte[size];
		bytes[0] = (byte) 0xFF;
		bytes[1] = (byte) 0xD8;
		bytes[2] = (byte) 0xFF;
		return bytes;
	}

	@Test
	void PNG_매직_바이트면_png로_저장하고_캐시버스터_URL을_돌려준다() throws Exception {
		String url = store.store(7L, png(100));

		assertThat(url).isEqualTo("/profile-images/user-7.png?v=" + FIXED_NOW.getEpochSecond());
		Path saved = tempDir.resolve("user-7.png");
		assertThat(saved).exists();
		assertThat(Files.readAllBytes(saved)).isEqualTo(png(100));
	}

	@Test
	void JPEG_매직_바이트면_jpg로_저장한다() {
		String url = store.store(7L, jpeg(100));

		assertThat(url).isEqualTo("/profile-images/user-7.jpg?v=" + FIXED_NOW.getEpochSecond());
		assertThat(tempDir.resolve("user-7.jpg")).exists();
	}

	// content-type이 아니라 내용으로 판별 — 확장자·헤더 위조를 걸러낸다
	@Test
	void 매직_바이트가_PNG_JPEG_둘_다_아니면_415다() {
		byte[] gif = new byte[100];
		gif[0] = 'G';
		gif[1] = 'I';
		gif[2] = 'F';

		assertThatThrownBy(() -> store.store(7L, gif))
				.isInstanceOfSatisfying(V1ApiException.class, e -> {
					assertThat(e.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
					assertThat(e.code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
					assertThat(e.getMessage()).isEqualTo("PNG, JPEG 형식만 업로드할 수 있어요.");
				});
		assertThat(tempDir.resolve("user-7.png")).doesNotExist();
	}

	@Test
	void 매직_바이트를_읽을_수_없는_짧은_파일도_415다() {
		assertThatThrownBy(() -> store.store(7L, new byte[] {(byte) 0xFF}))
				.isInstanceOfSatisfying(V1ApiException.class,
						e -> assertThat(e.code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	void 일_MB_초과는_413이다() {
		assertThatThrownBy(() -> store.store(7L, png(1024 * 1024 + 1)))
				.isInstanceOfSatisfying(V1ApiException.class, e -> {
					assertThat(e.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
					assertThat(e.code()).isEqualTo("PAYLOAD_TOO_LARGE");
					assertThat(e.getMessage()).isEqualTo("이미지는 1MB 이하만 업로드할 수 있어요.");
				});
	}

	@Test
	void 일_MB_정확히는_허용이다() {
		assertThatCode(() -> store.store(7L, png(1024 * 1024))).doesNotThrowAnyException();
	}

	// png→jpg 재업로드 시 옛 파일이 남으면 dir가 무한히 자란다 — 1인 1파일 불변식
	@Test
	void 반대_확장자로_재업로드하면_기존_파일을_정리한다() {
		store.store(7L, png(50));
		store.store(7L, jpeg(60));

		assertThat(tempDir.resolve("user-7.png")).doesNotExist();
		assertThat(tempDir.resolve("user-7.jpg")).exists();
	}

	@Test
	void 같은_확장자_재업로드는_덮어쓴다() throws Exception {
		store.store(7L, png(50));
		byte[] second = png(80);
		Arrays.fill(second, 10, 80, (byte) 1);
		store.store(7L, second);

		assertThat(Files.readAllBytes(tempDir.resolve("user-7.png"))).isEqualTo(second);
	}

	@Test
	void delete는_두_확장자_모두_지우고_없어도_멱등이다() {
		store.store(7L, png(50));

		store.delete(7L);
		assertThat(tempDir.resolve("user-7.png")).doesNotExist();

		assertThatCode(() -> store.delete(7L)).doesNotThrowAnyException(); // 두 번째 호출도 조용히 204감
	}
}
