package com.celfit.analytics.archive;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

/**
 * GCS 업로드 어댑터(2026-08-12 스펙 — OCI→GCS 이전 A안). 인증은 ADC —
 * 운영은 GOOGLE_APPLICATION_CREDENTIALS로 SA 키를 주입한다(compose.yaml 참고).
 * ParImageStore와 동일 계약: Cache-Control을 객체 메타데이터로 저장해 공개 읽기가 따른다.
 */
public class GcsImageStore implements ImageStore {

	private final String bucket;
	private final Storage storage;

	public GcsImageStore(String bucket) {
		this(bucket, StorageOptions.getDefaultInstance().getService());
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
