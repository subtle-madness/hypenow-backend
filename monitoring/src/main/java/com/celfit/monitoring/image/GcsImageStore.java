package com.celfit.monitoring.image;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

/**
 * GCS 업로드 어댑터 미러(analytics GcsImageStore 참조, 2026-08-12 스펙). 모듈 의미 차이:
 * 빈 버킷명을 ctor에서 허용한다(기동 실패 방지) — no-op 판단은 각 잡의 run()이 내린다.
 */
public class GcsImageStore implements ImageStore {

	private final String bucket;
	private final Storage storage;

	public GcsImageStore(String bucket) {
		this(bucket, StorageOptions.getDefaultInstance().getService());
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
