package com.celfit.analytics.archive;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * GCS 업로드 어댑터(2026-08-12 스펙 — OCI→GCS 이전 A안). 인증은 IMAGE_GCS_KEY로 지정한
 * 전용 SA 키 파일 — analytics는 같은 JVM에서 Vertex LLM이 ADC
 * (GOOGLE_APPLICATION_CREDENTIALS=vertex-sa.json)를 이미 점유하므로, ADC를 덮지 않고 분리한다.
 * ParImageStore와 동일 계약: Cache-Control을 객체 메타데이터로 저장해 공개 읽기가 따른다.
 */
public class GcsImageStore implements ImageStore {

	private final String bucket;
	private final Storage storage;

	public GcsImageStore(String bucket, String keyFile) {
		this(bucket, buildStorage(keyFile));
	}

	/** keyFile 미설정이면 ADC 폴백 — 로컬·테스트 편의. 운영은 IMAGE_GCS_KEY로 전용 SA를 명시한다(Vertex ADC와 분리). */
	private static Storage buildStorage(String keyFile) {
		if (keyFile == null || keyFile.isBlank()) {
			return StorageOptions.getDefaultInstance().getService();
		}
		try (FileInputStream in = new FileInputStream(keyFile)) {
			return StorageOptions.newBuilder()
					.setCredentials(ServiceAccountCredentials.fromStream(in))
					.build().getService();
		} catch (IOException e) {
			throw new IllegalStateException("GCS 키 파일 로드 실패: " + keyFile, e);
		}
	}

	GcsImageStore(String bucket, Storage storage) {
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalStateException("analytics.image-gcs-bucket 미설정 — GCS 버킷명이 필요하다");
		}
		this.bucket = bucket;
		this.storage = storage;
	}

	@Override
	public void put(String objectPath, byte[] bytes, String contentType, String cacheControl) {
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
