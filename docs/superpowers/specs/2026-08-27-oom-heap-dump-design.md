# OOM 자동 힙 덤프 설계

> 상태: 🟢 활성 · ✅ 구현/실행/반영됨

## 목적

운영 JVM 서비스에서 `OutOfMemoryError` 발생 시 힙 덤프(`.hprof`)가 자동으로 남아,
사후 원인 분석(무엇이 힙을 채웠나)이 가능하게 한다. 08-12 monitoring 힙 OOM 때는
덤프가 없어 힙 예산 재계산(코드 추정)으로만 대응했다 — 그 공백을 메운다.

## 결정

- **compose `JAVA_OPTS`에 `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps/<서비스>.hprof` 추가**,
  덤프 디렉토리는 `./dumps`(deploy 디렉토리 하위) 호스트 바인드 마운트로 영속화.
  컨테이너 재기동 시 파일 유실을 막는다(배포 시 컨테이너 로그 유실과 같은 이유).
- 대상: 운영 4개(was·crawler·analytics·monitoring) + test 스택 3개(test-was·test-analytics·test-monitoring).
  test는 `test-<서비스>.hprof`로 파일명 구분(같은 `./dumps` 공유).
- Dockerfile에 굽지 않는다 — 로컬 실행에 강제되고 경로가 이미지에 박힌다. compose 주입이
  이 레포의 기존 관용구(`JAVA_OPTS`)와도 일치.

## 특성·트레이드오프 (수용함)

- **cgroup OOM kill(SIGKILL)은 덤프가 안 남는다** — 이 플래그가 잡는 건 Java 힙(`-Xmx`) 소진뿐.
  mem_limit는 힙보다 1.5~2배 여유라 실제 OOM은 대부분 힙 소진 유형(08-12 실사례 동일).
- **HotSpot은 기존 덤프 파일을 덮어쓰지 않는다** → 서비스당 최대 1개(첫 OOM분)만 보존.
  크래시루프여도 디스크가 계속 차지 않는다(최악 합계 ~4.3GB). 분석 후 수동 삭제가 운영 절차.
- `-XX:+ExitOnOutOfMemoryError`(monitoring)와 공존 — 덤프를 먼저 쓰고 종료한다.
- OOM 전에는 플래그 비용 zero.

## 권한 함정 (구현 중 발견)

JVM 컨테이너는 전부 non-root(`USER was` 등, 이미지별 상이한 시스템 uid)다. `./dumps`를
도커 자동 생성(root 755)에 맡기면 덤프 쓰기가 Permission denied로 **조용히 실패**한다 —
OOM 순간에만 드러나는 최악의 침묵 실패. 그래서 CD 워크플로(cd.yml·cd-test.yml)의 기존
사전 mkdir 단계에 `install -d -m 1777 ~/deploy/dumps`를 추가했다(1777 = /tmp 관용구,
이미지별 uid 차이를 흡수). staging이 main보다 먼저 도는 승격 순서상 cd-test.yml에도 필요하다.

## 적용

승격 배포(develop→staging→main)를 타면 compose 변경 + CD의 dumps 디렉토리 생성이
서버에 반영된다. 별도 수동 런북 불필요.
