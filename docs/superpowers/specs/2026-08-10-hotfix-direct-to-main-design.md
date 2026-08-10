# 핫픽스 직행 경로(hotfix/* → main) + 자동 역머지 설계

> 상태: 🟢 활성 · 2026-08-10 설계

## 1. 배경 — 승격 흐름의 사각지대

승격 흐름은 develop→staging→main이다(07-29~). 운영 장애를 고치려면 develop에 넣고 → staging 승격
→ test 배포 확인 → main 승격, 세 번의 PR과 두 번의 배포를 거쳐야 한다. 운영이 죽은 상황에서
이 경로는 너무 길다. **staging을 건너뛰고 main으로 직행하는 핫픽스 경로**를 규약으로 세운다.

## 2. 선행 조사 — 막혀 있는 것은 없다

레포 설정을 확인한 결과 **`hotfix/* → main` PR은 추가 설정 없이 지금도 동작한다.**

- ci.yml 트리거가 `pull_request: branches: [develop, staging, main]` — main 대상 PR에서 필수 체크
  3종(`Gradle 전체 테스트` / `마이그레이션 롤링 호환 가드` / `분석 뷰 SQL 하니스`)이 그대로 돈다.
- 룰셋 `protect-release-branches`(main)는 규칙이 `deletion` / `non_fast_forward` / `pull_request` /
  `required_status_checks` 넷이다. **소스 브랜치를 제한하는 규칙이 없다** — staging에서 와야 한다는
  제약은 어디에도 없고 규약으로만 존재했다.
- main 머지 = cd.yml(`push: branches: [main]`) 발화 = 운영 배포. 이미 걸려 있다.
- **main에는 머지 큐가 없다.** 큐는 `protect-develop`(`~DEFAULT_BRANCH`, `MERGE`/`ALLGREEN`)에만 있다.
  ci.yml의 `merge_group: branches: [develop, staging, main]`은 나중에 큐를 넓힐 때를 위한 선반영일 뿐
  main에 큐를 켜지 않는다. 즉 **핫픽스는 큐 대기 없이 체크 통과 즉시 머지된다.**

따라서 이 설계에서 바꿀 git 설정은 없다. 룰셋도 그대로 둔다(bypass actor 추가 없음).
진짜 공백은 두 가지다 — **규약 문서**와 **역머지**.

## 3. 핵심 위험 — 역머지 누락

핫픽스가 main에만 들어가면 main이 develop·staging보다 앞선다. 브랜치가 갈라진 채로 방치되면:

- 다음 승격(staging→main)이 충돌하거나, 핫픽스가 들어간 파일을 구버전으로 되돌린다.
- test 스택(staging)에는 핫픽스가 없어 운영과 다른 코드를 검증하게 된다.

역머지는 "잊지 않으면 되는 일"이 아니다 — 급한 불을 끈 직후가 가장 잊기 쉬운 시점이므로 자동화한다.

## 4. 결정

### 4-1. 핫픽스 경로 (설정 변경 없음, 규약만)

`hotfix/<슬러그>`를 **main에서 분기** → **base=main으로 PR** → CI 3종 통과 → 머지 → 운영 배포.

main 룰셋의 `strict_required_status_checks_policy: true`는 PR이 main 최신이어야 함을 뜻하는데,
main에서 분기하므로 자연히 충족된다.

제약 두 가지를 규약에 못 박는다:

- **핫픽스는 staging을 건너뛰므로 운영에서 처음 돈다.** 범위를 최소로 유지한다.
- **스키마 마이그레이션 동반 핫픽스 금지.** expand-contract는 "참조 코드가 끊긴 다음 릴리스에서
  contract"를 전제하는데 핫픽스는 릴리스 간격 자체를 없앤다. migration-guard는 여전히 돌지만
  가드가 잡는 것은 파일 내용이지 릴리스 간격이 아니다. 스키마 변경이 필요하면 정규 경로로 간다.

### 4-2. 자동 역머지 워크플로 `.github/workflows/backmerge.yml`

트리거: `push: branches: [main]` + `workflow_dispatch`(수동 dry-run).
대상: **develop·staging 둘 다** — staging까지 열어야 test 스택이 즉시 핫픽스를 반영한다.

**언제 열지 판정.** 평범한 staging→main 승격마다 PR이 열리면 노이즈가 되어 규약이 죽는다. 판정식:

```
git log --no-merges origin/<target>..origin/main
```

- 정상 승격: main에 새로 들어온 비머지 커밋은 전부 staging·develop 유래라 **같은 SHA로 이미 존재**
  → 빈 목록 → skip.
- 핫픽스: hotfix 브랜치 커밋이 target에 없음 → 목록 있음 → PR 생성.

이 판정은 **승격 PR이 merge commit일 때만 성립한다**(squash·rebase는 SHA를 새로 만들어 판정이 깨진다).
develop의 머지 큐는 `MERGE` 방식이라 원 커밋 SHA를 보존하므로 문제없고, 승격 PR은 규약으로 고정한다.

**PR 형태.** 별도 역머지 브랜치를 만들지 않고 `--head main --base develop` / `--base staging`으로
main을 직접 head로 삼는다. 브랜치 생성·정리가 사라지고, 충돌이 나도 GitHub UI에서 그대로 해결할 수
있으며, PR이 열려 있는 동안 main에 커밋이 더 오면 자동 반영된다. 이미 열린 PR이 있으면 재생성하지
않는다.

**자동 머지.** 생성 직후 `gh pr merge --auto --merge`. develop은 머지 큐를 타고, staging은 체크
통과 즉시 머지된다. `--merge` 고정 — squash로 머지되면 4-2의 판정식이 이후로 깨진다.

**토큰.** `secrets.BACKMERGE_TOKEN`(PAT). `GITHUB_TOKEN`으로 만든 PR에는 GitHub이 워크플로를
트리거하지 않아 필수 체크가 영영 붙지 않고 PR이 머지 불가 상태로 멈춘다. fine-grained면 이 레포에
Contents: RW + Pull requests: RW, classic이면 `repo`.

### 4-3. 채택하지 않은 것

- **main 직접 push(bypass actor)**: CI 검증 없이 운영 배포가 나간다. 핫픽스야말로 급하게 짠 코드라
  검증이 더 필요하다.
- **역머지 PR의 develop 머지 큐 우회**: 룰셋 bypass는 **PR 단위가 아니라 행위자 단위**라
  "핫픽스 역머지일 때만"을 표현할 수 없다. 그 계정의 모든 develop 머지가 큐·필수 체크를 건너뛸 수
  있게 되어 큐 도입 이유(base 변경 후 Flyway 번호 재검증 — 07-30 #181 3차 재발)가 통째로 뚫린다.
  역머지는 이미 배포가 나간 뒤라 급하지 않으므로 큐 대기를 감수한다.
- **역머지 누락 감지만 하고 실행은 수동**: 급한 불을 끈 직후가 가장 잊기 쉬운 시점이다.

## 5. 검증

- 워크플로 문법: `workflow_dispatch`로 수동 실행 — main·develop·staging이 정렬된 평시 상태에서는
  세 대상 모두 skip으로 끝나야 한다(판정식이 정상 승격을 걸러내는지 확인).
- 판정식 자체는 로컬에서 확인 가능:
  `git log --no-merges origin/develop..origin/main` → 평시 빈 목록.
- 실제 핫픽스 발생 시 첫 실행에서 PR 2건 생성·auto-merge 동작 확인.

## 6. 판정식 실측 — 그리고 드러난 승격 경로 드리프트

작성 시점(2026-08-10) 실제 브랜치에 판정식을 돌린 결과:

```
git log --no-merges origin/develop..origin/main   → 0건 (skip)
git log --no-merges origin/staging..origin/main   → 1건 (aa43a040)
```

develop은 의도대로 skip이다. **staging이 1건 뒤처진 이유는 핫픽스가 아니라 승격 경로 드리프트다** —
main의 최근 승격 머지가 `Merge pull request #377 from subtle-madness/develop`,
`#376 from .../develop`으로 **staging이 아니라 develop에서 곧장 왔다**(그 이전 #372·#368·#365는
staging 유래). 규약은 develop→staging→main인데 실제 운영은 develop→main으로 새고 있다.

이 설계는 그 드리프트를 고치지 않는다(별건). 다만 **부작용은 명시해 둔다**: develop→main 직행
승격이 계속되는 한 main→staging 역머지 PR이 핫픽스가 아닐 때도 열리고 auto-merge된다. 이는
오작동이 아니라 실제 뒤처짐을 메우는 동작이며, 결과적으로 staging이 main을 자동 추종하게 된다
(staging push → cd-test.yml → test 스테이징 배포가 그만큼 늘어난다). develop→main 직행을
멈추고 규약대로 staging을 경유하면 이 PR도 자연히 사라진다.

## 7. 사용자 액션

- PAT 생성 후 `BACKMERGE_TOKEN` 리포지토리 시크릿 등록. **등록 전까지 역머지 워크플로는 실패한다**
  (핫픽스 경로 자체는 시크릿과 무관하게 동작).
