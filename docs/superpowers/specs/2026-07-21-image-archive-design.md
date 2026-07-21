> 상태: 🟢 활성 · ✅ 구현됨 (PR #98, 운영 개통은 Task 6 대기)

# 서빙용 이미지 아카이브 설계 — CDN 만료 전 오라클 오브젝트 스토리지 적재

## 배경

인스타 CDN 서명 URL은 수집 후 ~4일이면 만료(403)된다. 현재 프로필 이미지·릴스 썸네일·게시글
썸네일은 raw payload → 분석 뷰(00_base) → 미러 → was 경로로 **CDN URL 문자열이 그대로**
프론트까지 내려가므로, 수집 4일 뒤 화면에서 이미지가 깨진다. 목적은 **서빙 이미지 3종의 영속화**
— VLM 분석용 하베스트(릴스 영상)와는 별개 트랙이다.

인프라 선행분(이미 완료):

- OCI 버킷 `hypenow-images` (네임스페이스 `nr4nxrxoojw8`, 도쿄 리전).
- 프론트 Vercel rewrite 배포됨: `/img/:path*` → `https://objectstorage.ap-tokyo-1.oraclecloud.com/n/nr4nxrxoojw8/b/hypenow-images/o/:path*`.

## 확정 결정 4가지

### 1. 소유 모듈 = analytics 별도 잡

raw에서 URL 읽기 → 다운로드 → OCI 업로드 → analysis DB `image_assets` 기록.
경계 규칙(raw 읽기·분석 결과 쓰기)에 부합, 크롤러 무변경, 어드민 UI에서 트리거·관측.
크롤 직후 데일리 실행이면 URL은 몇 시간짜리라 만료(4일)까지 여유 충분.

### 2. URL 치환 지점 = was 조회 COALESCE

was 조회 SQL이 같은 analysis DB 안의 `image_assets`를 LEFT JOIN 해
`COALESCE('/img/' || ia.object_path, 원본 CDN URL)`로 서빙. 아카이브 전(오늘 수집분)은
신선한 CDN URL 폴백으로 자연 동작. 뷰·미러·record 계약 무변경, was는 읽기만(서빙 전용 원칙 유지).
분석 결과 테이블끼리의 조인이라 경계 위반 아님. **프론트에는 절대 URL이 아닌 `/img/<객체경로>`
상대경로**를 내려 스토리지 교체 시 rewrite만 바꾸면 되게 한다.

### 3. 갱신 정책 = 썸네일 1회 불변 · 프로필은 파일명 변경 시만

- **썸네일(릴스·게시글)**: shortCode당 1회. `image_assets`에 이미 기록된 shortCode는
  **다운로드 자체를 안 함**(대상 선정 SQL에서 제외) — 최근 12개 윈도우가 매일 겹쳐도 재저장 없음.
- **프로필**: URL의 **파일명 세그먼트**(인스타 미디어 ID — 호스트·서명 쿼리는 크롤마다 바뀌므로
  비교 제외)를 `image_assets`에 기록해두고, 바뀌었을 때만 재다운로드 → **같은 키 덮어쓰기**.
  이 비교는 트래픽 절약용 휴리스틱 — 오판해도 손해는 재다운로드 1회뿐(같은 키라 축적 없음).
- 객체 키는 결정적: `thumb/{shortCode}.jpg`, `profile/{handle}.jpg` — 계정·콘텐츠당 1객체 고정,
  버저닝 off라 이전 바이트도 안 남음. 2만 계정 규모에서도 총량 = 계정·콘텐츠 수 × 1장.
- `image_assets`는 **미러 목록(MirrorConfig) 제외** — content_analyses처럼 잡 소유 누적 테이블
  (analysis DB Flyway DDL). 새벽 미러 TRUNCATE+INSERT의 영향 없음.

### 4. 버킷 접근 = 퍼블릭 읽기 + Vercel rewrite 엣지 캐시

- 버킷은 읽기 전용 공개(목록 조회 차단), 쓰기는 analytics의 OCI 인증만.
  rewrite 목적지에 인증 토큰이 없으므로 퍼블릭 읽기가 전제 — 대상이 인스타 공개 이미지라 노출 리스크 없음.
- 프론트는 같은 오리진 `/img/<경로>` 로드 → Vercel 엣지 캐시가 흡수, OCI에는 캐시 미스만 도달.
- 캐시 헤더는 **업로드 시 객체에 부착**: 썸네일 `max-age=31536000, immutable`(불변),
  프로필 `max-age=86400`(같은 키 덮어쓰기라 교체 후 최대 하루 내 반영).
- 비용: OCI 아웃바운드 월 10TB 무료, API 요청 월 5만 건 무료(초과 1만 건당 $0.0034) — 여유 큼.

## 데일리 사이클 (새벽)

```
크롤(새 payload) → 미러(accounts·contents TRUNCATE+INSERT — 신선한 CDN URL로 갱신)
               → 아카이브 잡(image_assets에 없는 것만 다운로드→업로드→기록)
               → was 조회: COALESCE('/img/'||object_path, CDN URL)
```

## 스코프 제외

- **이미 만료된 과거분**: 아카이브 불가(다운로드 원본이 없음). 크롤이 살아있는 대상은 매일 새 서명
  URL이 들어오므로 다음 잡 실행 때 회복, 크롤이 끊긴 옛 대상만 영구 결번(현상 유지 — 회귀 아님).
- **릴스 영상 VLM 하베스트**: 별도 트랙.
- **was 이미지 프록시 / 미러 후처리 UPDATE**: 검토 후 기각 — 전자는 트래픽·구현 부담 과잉,
  후자는 "미러 = 뷰 사본" 불변식 파괴 + 미러↔UPDATE 틈의 만료 URL 노출.

## 남은 것 (구현 계획에서)

`image_assets` DDL 상세(키·인덱스), OCI 업로드 방식(SDK vs S3 호환 API), 실패 재시도·부분 실패
처리, 어드민 잡 카드 배선, was 조회 지점 목록(콘텐츠 카드·상세·인플루언서 프로필·리더보드 등).
