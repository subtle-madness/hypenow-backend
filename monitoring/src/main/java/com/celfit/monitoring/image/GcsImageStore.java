package com.celfit.monitoring.image;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * GCS 업로드 어댑터 미러(analytics GcsImageStore 참조, 2026-08-12 스펙). 모듈 의미 차이:
 * 빈 버킷명을 ctor에서 허용한다(기동 실패 방지) — no-op 판단은 각 잡의 run()이 내린다.
 * 인증은 IMAGE_GCS_KEY로 지정한 전용 SA 키 파일(analytics와 같은 키·같은 버킷을 재사용).
 */
public class GcsImageStore implements ImageStore {

	private final String bucket;
	private final Storage storage;

	public GcsImageStore(String bucket, String keyFile) {
		this.bucket = bucket;
		// no-op 케이스(빈 버킷)에선 키 로드·클라이언트 생성 자체를 건너뛴다 — 기동 실패 방지
		this.storage = (bucket == null || bucket.isBlank()) ? null : buildStorage(keyFile);
	}

	/** keyFile 미설정이면 ADC 폴백 — 로컬·테스트 편의. 운영은 IMAGE_GCS_KEY로 전용 자격증명 파일을 명시한다(Vertex ADC와 분리).
	 * 파일은 SA 키·gcloud ADC(authorized_user) 둘 다 허용 — 무조직 개인 프로젝트는 org policy가 SA 키 생성을 봉쇄해 ADC 파일을 쓴다(2026-08-12 실측). */
	private static Storage buildStorage(String keyFile) {
		if (keyFile == null || keyFile.isBlank()) {
			return StorageOptions.getDefaultInstance().getService();
		}
		try (FileInputStream in = new FileInputStream(keyFile)) {
			return StorageOptions.newBuilder()
					.setCredentials(GoogleCredentials.fromStream(in))
					.build().getService();
		} catch (IOException e) {
			throw new IllegalStateException("GCS 키 파일 로드 실패: " + keyFile, e);
		}
	}

	GcsImageStore(String bucket, Storage storage) {
		this.bucket = bucket;
		this.storage = storage;
	}

	@Override
	public void put(String objectPath, byte[] bytes, String contentType, String cacheControl) {
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalStateException("monitoring.image.gcs-bucket 미설정: " + objectPath);
		}
		BlobInfo info = BlobInfo.newBuilder(bucket, objectPath)
				.setContentType(contentType)
				.setCacheControl(cacheControl)
				.build();
		try {
			storage.create(info, bytes);
		} catch (RuntimeException e) {
			throw new IllegalStateException("업로드 실패: " + objectPath, e);
		}
	}
}
