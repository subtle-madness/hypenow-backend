package com.celfit.was.v1.account;

import com.celfit.was.v1.common.V1ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 프로필 이미지 파일 저장소(스펙 6.13) — `was.profile-image.dir` 아래 `user-{id}.{png|jpg}` 1인 1파일.
 * 형식 판별은 content-type이 아니라 **매직 바이트**(위조 헤더 차단), 1MB 초과는 앱 검증이 정본
 * (컨테이너 multipart 한도 2MB는 뒷선 방어). 정적 서빙은 ProfileImageWebConfig가 /profile-images/** 매핑.
 */
@Component
public class ProfileImageStore {

	private static final long MAX_BYTES = 1024 * 1024; // 1MB (스펙 6.13)
	private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47};
	private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

	private final Path dir;
	private final Clock clock;

	public ProfileImageStore(@Value("${was.profile-image.dir:./data/profile-images}") String dir, Clock clock) {
		this.dir = Path.of(dir);
		this.clock = clock;
	}

	/**
	 * 검증(413/415) 후 저장하고 서빙 URL을 돌려준다 — `?v={epochSecond}`는 같은 경로 재업로드 시
	 * 브라우저 캐시 무효화용. 확장자가 바뀌면(png↔jpg) 반대 확장자의 기존 파일을 지운다.
	 */
	public String store(long userId, byte[] bytes) {
		if (bytes.length > MAX_BYTES) {
			// 상태 상수는 RFC 9110 개명(CONTENT_TOO_LARGE=413)을 따르고, 에러 code는 스펙 3.2 표기를 유지한다
			throw new V1ApiException(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
					"이미지는 1MB 이하만 업로드할 수 있어요.");
		}
		String ext = detectExtension(bytes);
		String other = ext.equals("png") ? "jpg" : "png";
		try {
			Files.createDirectories(dir);
			Files.write(dir.resolve(fileName(userId, ext)), bytes);
			Files.deleteIfExists(dir.resolve(fileName(userId, other)));
		} catch (IOException e) {
			throw new UncheckedIOException("프로필 이미지 저장 실패: user-" + userId, e);
		}
		return "/profile-images/" + fileName(userId, ext) + "?v=" + clock.instant().getEpochSecond();
	}

	/** 멱등 삭제 — 파일이 없어도 조용히 끝난다(스펙 6.13 DELETE 204 멱등). */
	public void delete(long userId) {
		try {
			Files.deleteIfExists(dir.resolve(fileName(userId, "png")));
			Files.deleteIfExists(dir.resolve(fileName(userId, "jpg")));
		} catch (IOException e) {
			throw new UncheckedIOException("프로필 이미지 삭제 실패: user-" + userId, e);
		}
	}

	/** 매직 바이트 판별 — PNG(89 50 4E 47)·JPEG(FF D8 FF) 외에는 415. */
	private static String detectExtension(byte[] bytes) {
		if (startsWith(bytes, PNG_MAGIC)) {
			return "png";
		}
		if (startsWith(bytes, JPEG_MAGIC)) {
			return "jpg";
		}
		throw new V1ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
				"PNG, JPEG 형식만 업로드할 수 있어요.");
	}

	private static boolean startsWith(byte[] bytes, byte[] magic) {
		if (bytes.length < magic.length) {
			return false;
		}
		for (int i = 0; i < magic.length; i++) {
			if (bytes[i] != magic[i]) {
				return false;
			}
		}
		return true;
	}

	private static String fileName(long userId, String ext) {
		return "user-" + userId + "." + ext;
	}
}
