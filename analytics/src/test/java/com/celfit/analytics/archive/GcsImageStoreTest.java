package com.celfit.analytics.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GcsImageStoreTest {

	@Test
	void put은_버킷과_경로와_메타데이터를_그대로_전달한다() {
		Storage storage = mock(Storage.class);
		new GcsImageStore("test-bucket", storage)
				.put("thumb/abc.jpg", new byte[] {1, 2}, "image/jpeg", "public, max-age=1");

		ArgumentCaptor<BlobInfo> captor = ArgumentCaptor.forClass(BlobInfo.class);
		verify(storage).create(captor.capture(), any(byte[].class));
		BlobInfo info = captor.getValue();
		assertThat(info.getBucket()).isEqualTo("test-bucket");
		assertThat(info.getName()).isEqualTo("thumb/abc.jpg");
		assertThat(info.getContentType()).isEqualTo("image/jpeg");
		assertThat(info.getCacheControl()).isEqualTo("public, max-age=1");
	}

	@Test
	void 업로드_실패는_IllegalStateException으로_경로를_담아_던진다() {
		Storage storage = mock(Storage.class);
		when(storage.create(any(BlobInfo.class), any(byte[].class)))
				.thenThrow(new StorageException(503, "backend error"));

		assertThatThrownBy(() -> new GcsImageStore("b", storage)
				.put("thumb/x.jpg", new byte[] {1}, "image/jpeg", "public"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("thumb/x.jpg");
	}

	@Test
	void 빈_버킷명은_ctor에서_거부한다() {
		assertThatThrownBy(() -> new GcsImageStore(" ", mock(Storage.class)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 없는_키_파일_경로는_IllegalStateException() {
		assertThatThrownBy(() -> new GcsImageStore("b", "/없는/경로/key.json"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("키 파일");
	}
}
