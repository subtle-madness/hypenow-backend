# Vertex 전환·백필 실행 런북 (2026-07-20)

> 상태: 🟢 활성 · 스펙: [../superpowers/specs/2026-07-20-vertex-migration-recent12-backfill-design.md](../superpowers/specs/2026-07-20-vertex-migration-recent12-backfill-design.md)

## 1. GCP 준비 (사용자 직접 — 계정·결제는 에이전트 불가)

1. https://console.cloud.google.com — 기존 구글 계정으로 $300 무료 체험 활성화
   (⚠️ 활성화 순간부터 90일 카운트다운 — 아래 2~4와 코드 배포가 준비된 뒤에 할 것)
2. 프로젝트 생성(예: `hypenow-llm`) → API 활성화: Vertex AI API, Cloud Storage API
3. GCS 버킷 생성: 이름 예 `hypenow-llm-batch`, 리전 `us`(멀티리전 — gemini-3.1-flash-lite 가용 리전 global/us/eu와 정합)
4. 서비스 계정 생성(예: `analytics-llm`) → 역할: `Vertex AI User`, `Storage Object Admin`
   → JSON 키 발급·다운로드
5. 키를 오라클 서버로 업로드: `scp key.json hypenow:/opt/hypenow/secrets/vertex-sa.json`

## 2. 서버 설정

- analytics 서비스 환경에 `GOOGLE_APPLICATION_CREDENTIALS=/opt/hypenow/secrets/vertex-sa.json`
  (compose env — `.env`는 JVM 자동 로드 안 됨)
- app_setting (raw DB — 분석 뷰·`AnalyticsSettings`가 읽는 단일 원천):
  ```sql
  INSERT INTO app_setting(key,value) VALUES
    ('analytics.vertex-project','<프로젝트ID>'),
    ('analytics.vertex-gcs-bucket','hypenow-llm-batch')
  ON CONFLICT (key) DO UPDATE SET value=excluded.value;
  -- analytics.vertex-location은 기본값 'global'이 gemini-3.1-flash-lite와 정합 — 생략 가능.
  -- 전환 스위치는 스모크 후:
  -- UPDATE app_setting SET value='vertex' WHERE key='analytics.llm-provider';
  ```

## 3. 뷰 적용 + 배포

기존 운영 뷰 적용·미러·배포 런북 그대로 (⚠️ origin/develop 워크트리 기준 —
세션 간 뷰 되덮기 함정): 뷰 04 수동 적용 → analytics·was 배포.

## 4. 스모크 (순서 고정)

1. **동기 1콜**: `analytics.llm-provider=vertex` 설정 + **analytics 재기동** 후 admin UI에서
   분석 잡 1건 트리거 — 로그 `gemini usage:` 라인·content_analyses 적재 확인.
   실패 시 provider를 `gemini`로 롤백(§5 — 프로바이더는 빈 생성 시 고정이라 **재기동 필수**).
2. **배치 제출 직후 확인**: 백필 submit 후 GCP 콘솔에서 잡 상태·건수를 확인하고 이상하면
   즉시 취소(과금은 완료분만). 작업 디렉토리의 `backfill-input.jsonl`은 submit 실행 중
   생성돼 배치 생성과 거의 동시라 사전 확인용이 아니다 — 물량 예측은 submit 전에
   `SELECT count(*)`로 뷰∩기준선∩미분석을 직접 세어볼 것.
   ⚠️ 배치 `model` 필드가 거부되면(400) `VertexHttpApi.createBatch`의 짧은 경로
   (`publishers/google/models/{m}`)를 풀 경로(`projects/{p}/locations/{loc}/publishers/google/models/{m}`
   — `modelPath(model)` 재사용)로 교체 재시도 — 코드 주석(`VertexHttpApi.java:141-142`)에 명시돼 있음.
3. **본 백필**: `--analytics.backfill-submit=true --spring.main.web-application-type=none`
   → 로그의 잡 이름(`projects/{p}/locations/{loc}/batchPredictionJobs/{id}` 전체 리소스명)으로
   (완료 후, ≤24h) `--analytics.backfill-collect=<잡 이름>`
4. **수거 후 스팟체크**: `SELECT metric_timeliness, count(*) FROM content_analyses GROUP BY 1;`
   — timely/late_backfill 증가분이 제출 물량과 정합하는지 확인.

## 5. 롤백

`UPDATE app_setting SET value='gemini' WHERE key='analytics.llm-provider';` + analytics 재기동.
백필 수거는 멱등(`ON CONFLICT DO NOTHING`) — 재실행 안전.
