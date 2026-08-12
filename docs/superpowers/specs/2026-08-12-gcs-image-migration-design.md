# 이미지 스토리지 OCI → GCS 이전 설계

> 상태: 🟢 활성

## 배경과 결정

서빙 이미지 아카이브(`hypenow-images`, 2026-08-12 실측 16.5GB / 148,445개)는 OCI Object
Storage(도쿄)에 있다. OCI 잔류가 비용상 최적임을 확인했으나(월 300원 미만 — 분석은 이 설계의
선행 대화 기록 참조), 사용자가 GCP $300 크레딧 계정을 개설하고 GCS 이전을 결정했다.

핵심 전제 확인 사항:
- GCP 무료 체험은 90일 시한이며 만료 후 30일 내 미업그레이드 시 데이터가 삭제된다.
  **이관 직후 유료 업그레이드로 이 절벽을 제거한다** (크레딧은 업그레이드 후에도 90일까지
  우선 차감되므로 조기 업그레이드에 손해가 없다).
- 버킷 구성비(2026-08-12): `thumb/` 99.3%(피드 7.59GB·릴스 6.92GB·미분류 1.92GB),
  `profile/` 0.07GB, `monitor-*` 0.04GB.

## 요구사항 (사용자 확정)

1. **기존 데이터 전량 이관** — OCI 16.5GB 전부 GCS로 복사, 이후 GCS가 원본.
2. **OCI 버킷은 유지, 동기화 없음** — 이관 시점 스냅샷을 안전망으로 방치(월 수백 원).
   신규 이미지는 GCS에만 적재된다.
3. **90일 이후 유료 전환 예정** — 안전장치는 조기 업그레이드 + 예산 알람으로 충분.
4. **celfit-front rewrite는 사용자가 직접 반영** — 백엔드가 정확한 변경 diff를 전달한다.

## 불변 조건

- DB `image_assets.object_path`(상대 경로), was의 `COALESCE('/img/' || object_path, cdnUrl)`
  생성 로직, `ImageStore` 인터페이스, 오브젝트 키 구조(`thumb/<shortCode>.jpg` 등)는 무변경.
- 시스템 경계(crawler/analytics/was) 규칙 무변경 — 이 작업은 analytics·monitoring의
  어댑터 계층과 배포 설정만 건드린다.

## GCP 구성

- 버킷: `hypenow-images`(전역 이름 충돌 시 `hypenow-images-prod`), 리전 **`asia-northeast3`
  (서울)**, Standard 클래스, uniform bucket-level access, `allUsers: objectViewer` 공개 읽기
  (현행 PAR 읽기 URL과 동일한 공개 수준).
- 서비스 계정 `image-archiver@<project>` — 해당 버킷 한정 `roles/storage.objectAdmin`만.
  키 JSON은 서버 docker compose env로 주입.
- 안전장치: 이관 직후 유료 업그레이드 + 월 $5 예산 알람.

## 코드 변경 (A안 — GCS Java SDK)

선택지 비교: (A) `google-cloud-storage` SDK — 표준 경로, 구현 ~30줄, 의존성 큼.
(B) S3 interop + SigV4 수제 서명 — 의존성 0이나 서명 코드 ~100줄 직접 유지.
**A 채택** — 수제 서명의 유지비가 의존성 추가보다 비싸다. (GCS signed URL은 최대 7일이라
현행 "장기 PAR URL + HTTP PUT" 패턴은 이식 불가능하다.)

- analytics·monitoring 각각 `google-cloud-storage` 의존성 추가.
- `GcsImageStore implements ImageStore` — put(objectPath, bytes, contentType, cacheControl)
  하나, 현 `ParImageStore`와 동일 계약(cache-control 메타 포함).
- `ParImageStore`는 삭제하지 않는다. **환경변수 `IMAGE_STORE=gcs|par`로 구현을 선택**
  (기본값 par — 배포 설정이 없는 환경은 현행 유지). OCI 복귀 보험의 코드 쪽 짝.
- 테스트: 계약 테스트 수준(경로 조립·헤더·구현 선택 스위치). 실 PUT은 스테이징 검증으로.

## 컷오버 순서 (무손실 설계)

1. GCS 버킷·SA 생성 → 유료 업그레이드 → 예산 알람.
2. rclone 벌크 복사 OCI→GCS(16.5GB, 1시간 내외) — 서비스 무영향.
3. **아카이브 잡 일시 정지** — CDN 만료 여유가 3~4일이므로 몇 시간 정지는 무손실.
4. rclone 델타 복사(2 이후 적재분).
5. 백엔드 배포(`IMAGE_STORE=gcs`) + celfit-front rewrite를 GCS 공개 URL
   (`https://storage.googleapis.com/<bucket>/:path*`)로 교체 — 같은 창에서.
6. 잡 재개 → 신규 이미지가 GCS에 적재되는 것 확인.
7. OCI 버킷은 손대지 않는다.

## 감시 이식

- 서버 `deploy/scripts/post-container-metrics.py`: 버킷 크기 측정을 OCI `get_bucket` →
  GCS API로 교체하되, **결과는 기존 OCI 커스텀 메트릭 `bucket_used_gb`에 계속 게시** —
  기존 알람·대시보드 연속성 유지.
- `hypenow-bucket-high` 알람 임계값 15GB → 50GB 상향(절벽 전제 소멸).

## 실패 대응

- GCS 장애·사고 시: celfit-front rewrite를 OCI로 되돌리고 `IMAGE_STORE=par` 재배포 —
  복귀 ~10분. 이전 이후 적재분만 GCS에 고립(필요 시 역복사).
- 컷오버 검증: `rclone check`로 오브젝트 수·바이트 대조 + 프론트에서 구(이관분)·신(이관 후
  적재분) 이미지 각 1건 실제 로드 확인.

## 비용 전망 (참고)

크레딧 기간 $0 → 크레딧 소진/만료 후 저장 ~$0.4/월 + 이그레스 $0.12/GB(추정 월 $1~2.5).
OCI 대비 월 수천 원 비싸지는 구조임을 인지한 채로 결정된 이전이다.
