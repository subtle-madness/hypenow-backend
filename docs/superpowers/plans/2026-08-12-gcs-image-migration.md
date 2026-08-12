# GCS 이미지 스토리지 이전 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: 🟢 활성 · 스펙: [2026-08-12-gcs-image-migration-design.md](../specs/2026-08-12-gcs-image-migration-design.md)
> Task 4는 구현 중 ADC 충돌 발견으로 IMAGE_GCS_KEY 방식으로 변경(사용자 승인) — ADC는 JVM당 1개뿐이라 analytics의 Vertex LLM과 공유 불가.

**Goal:** 서빙 이미지 아카이브를 OCI Object Storage(`hypenow-images`, 16.5GB)에서 GCS
`asia-northeast3`로 이전한다 — 코드(어댑터 스위치), 배포 배선, 감시 이식, 무손실 컷오버 런북 포함.

**Architecture:** `ImageStore` 포트는 불변. 모듈별(analytics·monitoring) `GcsImageStore` 구현을
추가하고 환경변수 `IMAGE_STORE=par|gcs`(기본 par)로 구현을 선택한다. 데이터 이관은 rclone,
서빙 전환은 celfit-front rewrite(사용자 직접), 감시는 기존 OCI 커스텀 메트릭에 GCS 크기를 게시.

**Tech Stack:** Java 21 / Spring Boot 4.1, `com.google.cloud:google-cloud-storage`(SDK, A안),
rclone(이관), python(서버 메트릭 스크립트, google-auth 추가).

## Global Constraints

- 주석·로그·커밋 메시지는 한국어, 커밋 prefix `feat(모듈):`/`chore(deploy):`/`docs:`.
- 테스트는 모듈 단위로만 실행: `./gradlew :analytics:test`, `./gradlew :monitoring:test`.
  이 계획의 테스트는 전부 순수 단위 테스트라 Testcontainers/DOCKER_HOST 불필요.
- 스키마 마이그레이션 없음 — DB·오브젝트 키 구조·`ImageStore` 인터페이스 무변경.
- analytics·monitoring의 어댑터는 모듈별 복제가 관용구(기존 ParImageStore 2벌) — 공유 모듈로
  합치지 않는다. 두 모듈의 기존 의미 차이를 보존한다: **analytics ParImageStore는 빈 URL이면
  ctor에서 throw, monitoring 쪽은 빈 URL 허용(잡이 no-op 판단)**.
- 작업 브랜치는 현 `feature/oracle-storage-growth-rate-8a8dee` 계속 사용, 완료 시 develop 대상 PR.

## 파일 구조

| 파일 | 책임 |
|---|---|
| `analytics/.../archive/GcsImageStore.java` (신규) | GCS 업로드 어댑터 (빈 버킷명 ctor throw) |
| `analytics/.../archive/GcsImageStoreTest.java` (신규) | 계약 단위 테스트 (Storage 목) |
| `analytics/.../config/JobConfig.java` (수정) | IMAGE_STORE 스위치 배선 |
| `analytics/src/main/resources/application.yml` (수정) | `analytics.image-store`·`image-gcs-bucket` 키 |
| `analytics/build.gradle` (수정) | google-cloud-storage 의존성 |
| `monitoring/.../image/GcsImageStore.java` (신규) | 미러 (빈 버킷명 허용 — 모듈 의미 보존) |
| `monitoring/.../image/GcsImageStoreTest.java` (신규) | 미러 테스트 |
| `monitoring/.../config/ImageArchiveConfig.java` (수정) | 스위치 배선 (3개 빈) |
| `monitoring/src/main/resources/application.yml` (수정) | 설정 키 |
| `monitoring/build.gradle` (수정) | 의존성 |
| `deploy/compose.yaml`·`.env.example` (수정) | env 배선 + SA 키 마운트 |
| `deploy/scripts/post-container-metrics.py` (수정) | GCS 버킷 크기 게시 |
| `deploy/README.md` (수정) | 컷오버 런북 §5-2 |

---

### Task 1: analytics `GcsImageStore` + 의존성

**Files:**
- Modify: `analytics/build.gradle` (dependencies 블록, 13행 근처)
- Create: `analytics/src/main/java/com/celfit/analytics/archive/GcsImageStore.java`
- Test: `analytics/src/test/java/com/celfit/analytics/archive/GcsImageStoreTest.java`

**Interfaces:**
- Consumes: 기존 `com.celfit.analytics.archive.ImageStore` — `void put(String objectPath, byte[] bytes, String contentType, String cacheControl)`
- Produces: `new GcsImageStore(String bucket)` 및 테스트용 `GcsImageStore(String bucket, Storage storage)` — Task 2가 사용

- [ ] **Step 1: 의존성 추가** — `analytics/build.gradle`의 `dependencies` 블록,
  `com.google.auth:google-auth-library-oauth2-http` 행 아래에:

```groovy
	implementation 'com.google.cloud:google-cloud-storage:2.48.0'
```

(빌드 시 해당 버전이 없으면 `./gradlew :analytics:dependencies --configuration compileClasspath`
로 확인 후 최신 안정 2.x로 조정. 버전 외 다른 변경 금지.)

- [ ] **Step 2: 실패하는 테스트 작성** — `GcsImageStoreTest.java`:

```java
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
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.archive.GcsImageStoreTest"`
Expected: 컴파일 실패 — `GcsImageStore` 미존재

- [ ] **Step 4: 구현** — `GcsImageStore.java`:

```java
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
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.archive.GcsImageStoreTest"`
Expected: 3 tests PASS

- [ ] **Step 6: 커밋**

```bash
git add analytics/build.gradle analytics/src/main/java/com/celfit/analytics/archive/GcsImageStore.java analytics/src/test/java/com/celfit/analytics/archive/GcsImageStoreTest.java
git commit -m "feat(analytics): GCS 이미지 업로드 어댑터 추가 — OCI→GCS 이전 A안"
```

---

### Task 2: analytics `IMAGE_STORE` 스위치 배선

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/config/JobConfig.java` (imageArchiveJob 빈, 120~130행)
- Modify: `analytics/src/main/resources/application.yml`

**Interfaces:**
- Consumes: Task 1의 `GcsImageStore(String bucket)`, 기존 `ParImageStore(String parUrl)`
- Produces: 설정 키 `analytics.image-store`(env `IMAGE_STORE`, 기본 `par`),
  `analytics.image-gcs-bucket`(env `IMAGE_GCS_BUCKET`) — Task 4의 compose 배선이 사용

- [ ] **Step 1: JobConfig 수정** — `imageArchiveJob` 빈 메서드 파라미터에 추가:

```java
			@Value("${analytics.image-store:par}") String imageStoreMode,
			@Value("${analytics.image-gcs-bucket:}") String imageGcsBucket,
```

그리고 `new ParImageStore(imageParUrl)` 호출을 다음으로 교체:

```java
		// IMAGE_STORE=gcs|par — OCI 복귀 보험(스펙 §실패 대응): par로 되돌리면 즉시 OCI 재개
		ImageStore store = "gcs".equalsIgnoreCase(imageStoreMode)
				? new GcsImageStore(imageGcsBucket)
				: new ParImageStore(imageParUrl);
		return new ImageArchiveJob(rawJdbcTemplate, analysisDataSource,
				store, ImageDownloader.http(), settings, reporter);
```

import 추가: `com.celfit.analytics.archive.GcsImageStore`, `com.celfit.analytics.archive.ImageStore`.
(빈은 기존대로 @Lazy — gcs 모드에서 버킷 미설정이면 첫 트리거 때 이 잡만 실패, 기동 무영향.)

- [ ] **Step 2: application.yml에 키 추가** — 기존 `analytics.image-par-url` 인접 위치에:

```yaml
  # 이미지 스토어 선택(2026-08-12 GCS 이전): par=OCI PAR(기본), gcs=GCS. 복귀는 par로 원복.
  image-store: ${IMAGE_STORE:par}
  image-gcs-bucket: ${IMAGE_GCS_BUCKET:}
```

- [ ] **Step 3: 모듈 테스트로 회귀 확인**

Run: `./gradlew :analytics:compileJava :analytics:test --tests "com.celfit.analytics.archive.*"`
Expected: BUILD SUCCESSFUL (기존 ImageArchiveJobTest·ParImageStoreTest 포함 전부 PASS)

- [ ] **Step 4: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/config/JobConfig.java analytics/src/main/resources/application.yml
git commit -m "feat(analytics): IMAGE_STORE 환경변수로 PAR/GCS 어댑터 선택"
```

---

### Task 3: monitoring 미러 — `GcsImageStore` + 스위치

**Files:**
- Modify: `monitoring/build.gradle` (dependencies 블록)
- Create: `monitoring/src/main/java/com/celfit/monitoring/image/GcsImageStore.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/image/GcsImageStoreTest.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/config/ImageArchiveConfig.java`
- Modify: `monitoring/src/main/resources/application.yml` (72~73행 인접)

**Interfaces:**
- Consumes: 기존 `com.celfit.monitoring.image.ImageStore`(analytics와 동일 시그니처)
- Produces: monitoring 컨테이너용 동일 env 계약 — `IMAGE_STORE`, `IMAGE_GCS_BUCKET`

**모듈 의미 차이(중요):** monitoring의 ParImageStore는 빈 URL을 ctor에서 허용하고 각 잡의
`run()`이 설정값 blank 여부로 no-op을 판단한다(ImageArchiveConfig 주석 참조). 미러도 같은
의미를 보존한다 — **monitoring GcsImageStore는 빈 버킷명을 ctor에서 허용**하고, 잡의 no-op
판단에 쓰이는 문자열 인자에는 모드에 맞는 값(par→parUrl, gcs→gcsBucket)을 넘긴다.

- [ ] **Step 1: 의존성 추가** — `monitoring/build.gradle` dependencies 블록에:

```groovy
	implementation 'com.google.cloud:google-cloud-storage:2.48.0'
```

(Task 1에서 버전을 조정했다면 같은 버전으로 맞춘다.)

- [ ] **Step 2: 실패하는 테스트 작성** — `GcsImageStoreTest.java`
  (Task 1의 테스트에서 패키지만 `com.celfit.monitoring.image`로 바꾸고, 세 번째 테스트를
  모듈 의미에 맞게 교체):

```java
	@Test
	void 빈_버킷명은_ctor에서_허용하고_put에서_실패한다() {
		Storage storage = mock(Storage.class);
		GcsImageStore store = new GcsImageStore(" ", storage); // 기동 실패 방지 — no-op 판단은 잡이
		assertThatThrownBy(() -> store.put("thumb/x.jpg", new byte[] {1}, "image/jpeg", "public"))
				.isInstanceOf(IllegalStateException.class);
	}
```

나머지 두 테스트(`put은_버킷과_경로와_메타데이터를_그대로_전달한다`,
`업로드_실패는_IllegalStateException으로_경로를_담아_던진다`)는 Task 1 Step 2와 동일 본문.

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.image.GcsImageStoreTest"`
Expected: 컴파일 실패

- [ ] **Step 4: 구현** — `monitoring/.../image/GcsImageStore.java`
  (Task 1 구현에서 패키지·ctor만 다름):

```java
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
```

주의: `GcsImageStore(String bucket)` 단독 ctor은 ADC를 즉시 로드하므로 par 모드 경로에서
호출되지 않도록 Step 5의 배선에서 gcs 분기 안에서만 생성한다.

- [ ] **Step 5: ImageArchiveConfig 배선** — 3개 빈 공통 패턴. 각 빈 메서드 파라미터에 추가:

```java
			@Value("${monitoring.image.store:par}") String storeMode,
			@Value("${monitoring.image.gcs-bucket:}") String gcsBucket,
```

각 빈 본문을 다음 패턴으로 교체 (`profileImageArchiveJob` 예 — 나머지 2개 빈도 잡 클래스명만
다르고 동일):

```java
		// gcs 모드에선 잡의 no-op 판단 인자(기존 parUrl 슬롯)에 버킷명을 넘긴다 — blank면 no-op
		String storeTarget = "gcs".equalsIgnoreCase(storeMode) ? gcsBucket : parUrl;
		ImageStore store = "gcs".equalsIgnoreCase(storeMode)
				? new GcsImageStore(gcsBucket) : new ParImageStore(parUrl);
		return new ProfileImageArchiveJob(db, store, ImageDownloader.http(), storeTarget, batchLimit);
```

import에 `com.celfit.monitoring.image.GcsImageStore`·`com.celfit.monitoring.image.ImageStore` 추가.
클래스 javadoc의 no-op 설명에 "gcs 모드에선 gcs-bucket blank가 같은 역할" 한 줄 보강.

- [ ] **Step 6: application.yml** — `monitoring.image.par-url` 아래에:

```yaml
    # 이미지 스토어 선택(2026-08-12 GCS 이전): par=OCI PAR(기본), gcs=GCS
    store: ${IMAGE_STORE:par}
    gcs-bucket: ${IMAGE_GCS_BUCKET:}
```

- [ ] **Step 7: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.image.*"`
Expected: 신규 3개 포함 전부 PASS (기존 3개 잡 테스트 회귀 없음)

- [ ] **Step 8: 커밋**

```bash
git add monitoring/build.gradle monitoring/src/main/java/com/celfit/monitoring/image/GcsImageStore.java monitoring/src/test/java/com/celfit/monitoring/image/GcsImageStoreTest.java monitoring/src/main/java/com/celfit/monitoring/config/ImageArchiveConfig.java monitoring/src/main/resources/application.yml
git commit -m "feat(monitoring): GCS 어댑터 미러 + IMAGE_STORE 스위치"
```

---

### Task 4: deploy compose 배선

**Files:**
- Modify: `deploy/compose.yaml` (analytics 116행 인접, monitoring 260행 인접)
- Modify: `deploy/.env.example` (21행 인접)

**Interfaces:**
- Consumes: Task 2·3의 env 계약 (`IMAGE_STORE`, `IMAGE_GCS_BUCKET`, ADC 표준
  `GOOGLE_APPLICATION_CREDENTIALS`)
- Produces: 서버 `.env`의 `IMAGE_STORE=gcs` 한 줄이 컷오버 스위치가 되는 배선

- [ ] **Step 1: compose.yaml — analytics 서비스** `ANALYTICS_IMAGE_PAR_URL` 행 아래에:

```yaml
      IMAGE_STORE: ${IMAGE_STORE:-par}
      IMAGE_GCS_BUCKET: ${IMAGE_GCS_BUCKET:-}
      GOOGLE_APPLICATION_CREDENTIALS: /run/secrets/gcs-image-archiver.json
```

같은 서비스의 `volumes:` 목록에 추가(없으면 신설):

```yaml
      - ./secrets/gcs-image-archiver.json:/run/secrets/gcs-image-archiver.json:ro
```

- [ ] **Step 2: compose.yaml — monitoring 서비스** `MONITORING_IMAGE_PAR_URL` 행 아래에 동일 3행
  + 동일 volume 추가. (참고: monitoring은 analytics의 PAR을 재사용해왔듯 같은 버킷·같은 SA 키를
  재사용한다.)

- [ ] **Step 3: .env.example** — `ANALYTICS_IMAGE_PAR_URL=` 아래에:

```
# 이미지 스토어(2026-08-12 GCS 이전): par|gcs. gcs면 IMAGE_GCS_BUCKET 필수 +
# deploy/secrets/gcs-image-archiver.json(SA 키) 필요. 복귀는 par로 원복.
IMAGE_STORE=par
IMAGE_GCS_BUCKET=
```

- [ ] **Step 4: compose 문법 검증**

Run: `docker compose -f deploy/compose.yaml config -q`
Expected: 출력 없이 종료 코드 0 (환경변수 미설정 경고는 무시 가능)
(참고: 키 파일이 없는 par 모드 배포에서도 마운트 소스 부재로 실패하지 않도록, 서버에는
컷오버 전 단계에서 빈 파일이라도 `deploy/secrets/gcs-image-archiver.json`을 만들어 둔다 —
런북 1단계에 포함.)

- [ ] **Step 5: 커밋**

```bash
git add deploy/compose.yaml deploy/.env.example
git commit -m "chore(deploy): GCS 이미지 스토어 env·SA 키 배선"
```

---

### Task 5: 메트릭 스크립트 — GCS 버킷 크기 게시

**Files:**
- Modify: `deploy/scripts/post-container-metrics.py` (87~92행의 버킷 블록)

**Interfaces:**
- Consumes: GCS JSON API 목록 조회(SA 키 `/home/ubuntu/deploy/secrets/gcs-image-archiver.json`,
  scope `devstorage.read_only`), 기존 `metric()` 헬퍼·게시 파이프라인
- Produces: 기존 OCI 커스텀 메트릭 `bucket_used_gb`에 dimension `bucketName=<GCS 버킷명>`으로
  게시 — 기존 `hypenow-bucket-high` 알람이 그대로 산다

- [ ] **Step 1: 버킷 측정 블록 교체** — 기존 87~92행(OCI `get_bucket` 블록)을 다음으로 교체:

```python
# 버킷 용량(GCS, 2026-08-12 이전) — 크기 합산은 전체 목록 페이징이라 정시(hour)에만.
# OCI 버킷은 이전 후 동결 스냅샷이라 더 게시하지 않는다(알람은 GCS 값으로 계속 동작).
GCS_BUCKETS = ["hypenow-images"]
GCS_KEY = "/home/ubuntu/deploy/secrets/gcs-image-archiver.json"
if now.minute == 0:
	from google.oauth2 import service_account
	from google.auth.transport.requests import AuthorizedSession
	creds = service_account.Credentials.from_service_account_file(
		GCS_KEY, scopes=["https://www.googleapis.com/auth/devstorage.read_only"])
	sess = AuthorizedSession(creds)
	for bucket in GCS_BUCKETS:
		total, page = 0, None
		while True:
			params = {"fields": "items(size),nextPageToken", "maxResults": 1000}
			if page:
				params["pageToken"] = page
			body = sess.get(f"https://storage.googleapis.com/storage/v1/b/{bucket}/o",
				params=params, timeout=30).json()
			total += sum(int(o["size"]) for o in body.get("items", []))
			page = body.get("nextPageToken")
			if not page:
				break
		data.append(metric("bucket_used_gb", {"bucketName": bucket}, round(total / 2**30, 3)))
```

(GCS 버킷명을 `hypenow-images-prod`로 만들었으면 `GCS_BUCKETS`를 그 이름으로. dimension이
바뀌므로 런북의 알람 갱신 단계에서 알람 쿼리도 같이 확인.)

- [ ] **Step 2: 문법 검증**

Run: `python3 -m py_compile deploy/scripts/post-container-metrics.py`
Expected: 출력 없음(성공)

- [ ] **Step 3: 커밋**

```bash
git add deploy/scripts/post-container-metrics.py
git commit -m "chore(deploy): 버킷 크기 메트릭을 GCS 측정으로 교체 — 기존 알람 유지"
```

(서버 반영 시 `.venv-oci-metrics`에 `pip install google-auth requests` 필요 — 런북 6단계.)

---

### Task 6: 컷오버 런북 문서화

**Files:**
- Modify: `deploy/README.md` (§5-1 뒤에 §5-2로 추가)

**Interfaces:**
- Consumes: Task 1~5의 결과물 전부
- Produces: 운영자가 그대로 따라 하는 컷오버 절차 — 실행은 이 계획의 코드 태스크 머지·배포 후

- [ ] **Step 1: deploy/README.md에 §5-2 추가** — 아래 내용 그대로:

````markdown
## 5-2. 이미지 스토리지 OCI→GCS 컷오버 (2026-08-12 스펙)

순서 불변식: **서빙(rewrite)이 보는 버킷 ⊇ 쓰기 대상 버킷**. front 전환은 반드시
"잡 정지 + 델타 복사 후, 백엔드 IMAGE_STORE=gcs 배포 전".

1. GCP 준비(로컬, 1회):
   ```bash
   gcloud config set project <PROJECT_ID>
   gcloud storage buckets create gs://hypenow-images --location=asia-northeast3 \
     --uniform-bucket-level-access   # 전역 이름 충돌 시 hypenow-images-prod
   gcloud storage buckets add-iam-policy-binding gs://hypenow-images \
     --member=allUsers --role=roles/storage.objectViewer
   gcloud iam service-accounts create image-archiver
   gcloud storage buckets add-iam-policy-binding gs://hypenow-images \
     --member=serviceAccount:image-archiver@<PROJECT_ID>.iam.gserviceaccount.com \
     --role=roles/storage.objectAdmin
   gcloud iam service-accounts keys create gcs-image-archiver.json \
     --iam-account=image-archiver@<PROJECT_ID>.iam.gserviceaccount.com
   ```
   **콘솔에서 유료 계정 업그레이드 + 예산 알람(월 $5)** — 90일 삭제 절벽 제거.
   키를 서버로: `scp gcs-image-archiver.json ubuntu@155.248.187.106:/home/ubuntu/deploy/secrets/`
2. 벌크 복사(서버에서, 서비스 무영향 — rclone remote는 oci=oracleobjectstorage(user
   principal), gcs=google cloud storage(SA 키) 타입으로 `rclone config`에서 1회 생성):
   ```bash
   rclone copy oci:hypenow-images gcs:hypenow-images --transfers 16 -P
   ```
3. 잡 정지: 진행 중 브랜드 스윕이 없는지 monitoring UI(8083)에서 확인 후
   `docker compose stop analytics monitoring`. (CDN 만료 여유 3~4일 — 수 시간 정지 무손실.)
4. 델타 복사 + 검증:
   ```bash
   rclone copy oci:hypenow-images gcs:hypenow-images -P
   rclone check oci:hypenow-images gcs:hypenow-images --size-only
   ```
   샘플 1건 Cache-Control 확인: `gcloud storage objects describe gs://hypenow-images/thumb/<아무거나>.jpg`
   — cacheControl 누락이면 일괄 보정:
   `gcloud storage objects update "gs://hypenow-images/thumb/**" --cache-control="public, max-age=31536000, immutable"`
5. **celfit-front rewrite 전환**(사용자): `/img/:path*`의 대상 OCI PAR URL →
   `https://storage.googleapis.com/hypenow-images/:path*`. 배포 후 기존 이미지 로드 확인.
6. 백엔드 전환: 서버 `.env`에 `IMAGE_STORE=gcs`·`IMAGE_GCS_BUCKET=hypenow-images` 설정,
   `.venv-oci-metrics`에 `pip install google-auth requests`, 정규 CD 배포(또는 compose up -d)
   로 재기동 — 잡 재개 겸용.
7. 확인: 다음 아카이브 잡 후 GCS에 신규 오브젝트 적재 + 프론트에서 신규 썸네일 로드 +
   정시 메트릭에 bucket_used_gb 게시. 알람 임계 상향:
   ```bash
   oci --profile HYPENOW monitoring alarm update \
     --alarm-id ocid1.alarm.oc1.ap-tokyo-1.amaaaaaa2qpilmqaat7adk6wfdeuxvzcqm7n65dnzqvybkybls36retft36q \
     --query-text 'bucket_used_gb[5m].max() > 50'
   ```
8. 롤백(5~6 사이 문제 시): front rewrite를 OCI로 원복 + `IMAGE_STORE=par`로 재배포 —
   해당 시점 두 버킷이 동일하므로 무손실.

OCI 버킷은 삭제하지 않는다(동결 스냅샷 안전망, 월 수백 원).
````

- [ ] **Step 2: 커밋**

```bash
git add deploy/README.md
git commit -m "docs: OCI→GCS 이미지 스토리지 컷오버 런북(§5-2)"
```

---

## Self-Review 결과

- 스펙 커버리지: GCP 구성=런북 1, 코드 A안=Task 1~3, 컷오버=런북 2~8(front 선행 불변식 포함),
  감시 이식=Task 5+런북 7, 실패 대응=런북 8, OCI 유지=런북 말미. 누락 없음.
- 타입 일관성: `IMAGE_STORE`/`IMAGE_GCS_BUCKET` env 이름이 Task 2·3·4·런북 6에서 동일,
  `GcsImageStore` ctor 시그니처가 두 모듈에서 의미 차이(throw vs 허용)를 갖는 것은 의도
  (Global Constraints 명기).
- 플레이스홀더: `<PROJECT_ID>`·`<아무거나>`는 운영자 치환값으로 의도된 것 외에 없음.
