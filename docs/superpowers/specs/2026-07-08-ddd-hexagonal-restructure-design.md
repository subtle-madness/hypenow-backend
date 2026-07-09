# crawler DDD + 헥사고날 아키텍처 재구성 설계

날짜: 2026-07-08
상태: 승인됨 (실용적 헥사고날 · 서브도메인 분할 · 도메인 우선 패키지)

## 목표

기술 역할별 패키지(admin/apify/config/domain/job/ui)를 바운디드 컨텍스트 기준의
DDD + 헥사고날 구조로 재배치한다. 로직 변경 없음, 동작 불변.

## 목표 구조

```
com.celfit.crawler
├── CrawlerApplication
├── common
│   ├── config      CrawlerConfig, ScheduleProperties, DiscoverProperties, AggregateProperties
│   └── log         LogBuffer
├── crawling                          # 수집 파이프라인 컨텍스트
│   ├── domain                        # Account, CrawlRun, RunStatus, TriggerType, JobName,
│   │                                 # Raw*(5종), ShortCodes
│   ├── application
│   │   ├── port/in                   # TriggerJobUseCase (TriggerResult 포함)
│   │   ├── port/out                  # ApifyRunnerPort(구 ApifyRunner), ApifyResult, ApifyException,
│   │   │                             # Actors, ActorInputs, 리포지토리 인터페이스(Account/CrawlRun/Raw* 7종)
│   │   └── service                   # JobService, JobLock, DiscoverJob, CrawlExecutor,
│   │                                 # QualifyJob, AggregateJob, DiscoveryItemParser, AdSignals
│   └── adapter
│       ├── in/web                    # JobController, UiJobController
│       ├── in/scheduler              # ScheduleRunner
│       └── out/apify                 # ApifyClient, ApifyHttp, JdkApifyHttp, Sleeper, ApifyProperties
├── content                           # 콘텐츠·카테고리 컨텍스트
│   ├── domain                        # Content, ContentStatus, ContentType, ContentTypeFilter,
│   │                                 # Category, CategoryKeyword, CollectionRule
│   ├── application
│   │   ├── port/out                  # Content/Category/CategoryKeyword/CollectionRule 리포지토리
│   │   └── service                   # CategoryService
│   └── adapter/in/web                # CategoryAdminController, UiCategoryController
├── settings                          # 앱 설정 컨텍스트
│   ├── domain                        # AppSetting
│   ├── application
│   │   ├── port/out                  # AppSettingRepository
│   │   └── service                   # SettingsService
│   └── adapter/in/web                # SettingsController, UiSettingsController
└── dashboard                         # 크로스 도메인 읽기 전용 (CQRS식 조회)
    ├── application                   # StatusService
    └── adapter/in/web                # UiController, AdminQueryController
```

테스트는 동일 패키지 구조로 미러링. FakeApifyRunner·IntegrationTest 등 루트 테스트는 루트 유지.

## 핵심 결정

1. **실용적 헥사고날**: JPA 엔티티 = 도메인 모델. 영속 엔티티/도메인 분리와 매퍼는 도입하지 않는다.
2. **Spring Data 리포지토리 = 아웃바운드 포트**: 인터페이스를 `application/port/out`에 두고
   구현(JPA 프록시)을 어댑터로 간주. Load/Save 포트 + PersistenceAdapter 수작업 계층은 보일러플레이트라 생략.
3. **포트 계약 타입은 포트 옆에**: 잡이 사용하는 ApifyResult, ApifyException, Actors, ActorInputs는
   `port/out` 소속(application → adapter 역참조 방지). ShortCodes는 인스타그램 URL 도메인 유틸 → `crawling.domain`.
4. **port/in은 실익 있는 곳만**: 호출자가 복수(웹 + 스케줄러)인 잡 트리거만 `TriggerJobUseCase`로 추출.
   단일 구현 CRUD 서비스(Category/Settings)는 구체 클래스 직접 사용 — 필요 시 후속 추출.
5. **dashboard = 읽기 전용 크로스 컨텍스트**: StatusService·UiController가 여러 컨텍스트의
   리포지토리를 직접 읽는 것을 허용(조회 전용 예외). 쓰기는 금지.
6. **컨텍스트 간 의존 규칙**: crawling → content 허용(discover가 카테고리·키워드 읽음), 역방향 금지.
   dashboard → 전체 읽기 허용. 계층 방향은 adapter → application → domain 단방향.
7. **ApifyRunner → ApifyRunnerPort 개명**: 레퍼런스의 `*Port` 컨벤션 채택. 다른 클래스명은 유지(변경 최소화).

## 레퍼런스(auth 구조) 대비 조정한 것

- 도메인 계층 부재 → 컨텍스트별 `domain` 추가 (헥사고날의 중심은 도메인)
- Load/Save 포트 세분화 + PersistenceAdapter → 생략 (위 2번)
- 모든 서비스의 UseCase 인터페이스화 → 실익 있는 곳만 (위 4번)
- dto 루트/external 루트에 파일 방치 같은 위치 불일치 → 없음. 컨트롤러 내부 record DTO는 유지(추출은 후속 선택지)

## 검증

- `gradlew test` 전체 통과 = 완료 기준. 리소스(application.yml, Flyway, 템플릿, 정적 파일)는 변경 없음.
