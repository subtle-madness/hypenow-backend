# 인스타 DM POC 1단계 하네스

> ⚠️ **안전선(협상 불가)**: 100% 버릴 테스트 계정 전용. 수신자는 우리 통제 더미 화이트리스트만.
> 목록에 없는 수신자는 코드가 원천 차단한다(`guard.assert_recipient_allowed`). 실제 사람 발송 금지.

신규 인스타 계정이 워밍업 없이 콜드 DM을 자동 발송하면 며칠/몇 건까지 버티는지를 실측하는 독립 하네스.
설계 정본: [1단계 하네스 설계](../../docs/superpowers/specs/2026-09-02-instagram-dm-poc-phase1-harness-design.md),
[갈림길 핸드오프](../../docs/superpowers/specs/2026-09-01-instagram-dm-poc-handoff.md).

## 안전 기능
- **수신자 화이트리스트 코드 차단**: 발송 전 매번 더미 목록 대조, 미포함이면 예외로 차단.
- **밴 신호 3단계 분류**: 일시(429/PleaseWait=백오프)·액션차단(FeedbackRequired)·종료(ChallengeRequired 등). 하드 신호는 즉시 계정 死·정지, 재시도·복구 없음.
- **함대 서킷브레이커**: 짧은 창(기본 15분)에 2개 이상 계정이 하드 신호면 전체 정지.
- **사람 킬 스위치**: `KILL` 파일을 만들면 함대 즉시 정지.
- **드라이런 기본**: 실발송 없이 스케줄·기록만. 실계정 투입 전 필수 검증.

## 설치
```bash
cd poc/instagram-dm-harness
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
```
instagrapi는 `pyproject.toml`에 버전 핀 고정(밴 측정 재현성). 실제 설치 버전은 실행 시 원장에 기록된다.

## 테스트
```bash
python -m pytest -v
```
순수 로직 모듈(detector·pacer·ledger·config·guard·device·proxy·runner)은 instagrapi 없이 돈다.

## 드라이런(실발송 0)
```bash
cp config.example.yaml config.yaml   # 크리덴셜 채우기(.gitignore로 무시됨)
python -m igdm_harness --config config.yaml --max-actions 20
```
DryRunClient가 배선돼 네트워크에 접촉하지 않고 스케줄·원장 기록만 검증한다.

## 실발송(버릴 계정만)
`config.yaml`에서 `dry_run: false`로 두고:
```bash
python -m igdm_harness --config config.yaml --live --confirm-live I-UNDERSTAND-BURNER-ONLY
```
확인 문자열 없이는 실발송이 시작되지 않는다.

## 계정 조달(스코프 밖)
발송 계정·수신 더미 20개는 **운영자가 직접 준비**한다. 하네스는 config의 로그인 목록만 받는다(출처 무관).
계정 생성·구매 로직은 이 하네스에 없다.

## 원장 읽기
SQLite `ledger_path`에 계정 메타·발송 이벤트·死 이벤트 3표. 파생: 생존곡선(계정별 死 시점·누적발송), 死 사유 분포, 도착률.
```bash
sqlite3 ledger.db "SELECT account_alias, action, result, signal, delivered FROM send_event ORDER BY id;"
sqlite3 ledger.db "SELECT * FROM death_event;"
```
