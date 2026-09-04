# 인스타 DM 자동 발송 POC — 조사 근거 정리

> 상태: 🟢 활성 · 조사 기록 · 2026-09-01
> 범위: "인스타 DM 자동 발송(콜드 아웃리치)"을 검토하며 이 세션에서 수집한 근거 전량.
> 원칙: 자동화 툴 벤더 블로그(instantdm, creatorflow, spurnow 등)는 상업적 편향으로 **근거에서 제외**. 1차(메타 공식·소스코드·연구논문·유지보수자 발언) 우선, 실무 증언은 등급 표기.
> 관련: 결정·갈림길은 [핸드오프 노트](2026-09-01-instagram-dm-poc-handoff.md).

## 근거력 등급 범례

- **A**: 메타 공식 문서 / 메타 연구팀 논문 (1차)
- **B**: 소스코드·최고 성숙 도구 유지보수자 발언·반복된 실무 증언
- **C**: 기술적으로 타당하나 1차 증거는 벤더뿐
- **D**: 근거 없음 / 통속 표현 / 벤더 관행치

---

## 1. 공식 API로 콜드 아웃리치가 가능한가 → 불가 (A)

- **Instagram Messaging API는 "유저가 먼저 접촉한 24시간 창" 안 응답만 허용.** 24시간 밖은 Human Agent 태그로 7일간 "사람이 수동 응답"만. 브랜드가 인플루언서에게 **먼저** 보내는 콜드 첫 DM은 API 경로 자체가 없음.
  - 출처: [Messenger/IG 메시지 API 정책](https://developers.facebook.com/documentation/business-messaging/messenger-platform/policy) (Updated 2026.4.6) — 원문: "표준 메시지 — 비즈니스는 24시간 이내에 사용자에게 답해야 합니다. 24시간 이내에 전송된 메시지에는 홍보성 콘텐츠가 포함될 수 있습니다."
  - Human Agent: 같은 문서 — "7일 이내에 사용자 메시지에 직접 답할 수 있는 인간 상담원 태그". 콜드 발송 정당화 조항 아님.
- **결론**: 콜드 아웃리치는 공식 API 탈락 → 자체 세션(비공식)만 기술적으로 가능. (국내 업체 행태가 이를 증명 — §6)

## 2. Rate limit — 계정 단위인가 IP 단위인가 → 축이 다름 (A + C)

- **발송량 캡은 "계정 단위"가 공식.** 메타 공식 rate limit은 전부 "Instagram 프로페셔널 계정당(per account)"으로 명시. **IP 단위 제한은 공식 문서에 언급 없음.**
  - 출처: [Messenger 플랫폼 사용 제한](https://developers.facebook.com/documentation/business-messaging/messenger-platform/overview/rate-limiting) (Updated 2026.3.23) — Send API 텍스트/링크 초당 300콜, 오디오/동영상 초당 10콜, Conversation API 초당 2콜, 고용량 임계치 72,000건(적용 시간창은 문서에 없음). 전부 계정당.
  - 단, 이 공식 수치는 **공식 Graph/Messaging API 경로** 기준. 비공식 private API/브라우저 자동화엔 이 문서가 적용 대상이 아님(공식 문서가 그 경우를 다루지 않음 — 갭).
- **IP는 "상한"이 아니라 "계정 연결(account linking)" 축.** 같은 IP + 유사 이메일 패턴 + 동일 디바이스 지문 + 겹치는 로그인 시간이 모이면 인스타가 계정들을 한 몸으로 묶어 **연결된 계정 전부를 동시 조치**. (등급 C — 널리 관찰되나 알고리즘은 비공개. §4 참조)
- 참고(D): "시간당 200 DM" 등 구체 상한은 메타 공식이 아니라 벤더 관행 페이싱치.

## 3. 밴은 "많이 보내면"인가 → 아님, 여러 신호 종합 (A)

- **메타 공식 스팸 정책 원문**: "스팸임을 드러내는 다른 지표(예: 반복적인 콘텐츠 게시)나 허위 신호가 있을 경우, **활동 빈도가 더 낮은 경우에도 계정에 제한을 가할 수 있습니다.**"
  - 출처: [Meta 커뮤니티 규정 — 스팸](https://transparency.meta.com/policies/community-standards/spam/)
  - → "빈도 하나가 아니라 빈도 + 콘텐츠 반복성 + 비진정성 신호를 종합 판단"은 **공식 근거 있음**.
- **단, "점수제(scoring)"라는 용어·가중치·합산 방식은 메타 어디에도 없음 (D).** 'score' 표현 자체가 공식 문서에 부재. 업계 통속 표현이지 확정 메커니즘 아님.
- **행위 타이밍이 실제 프로덕션 탐지 피처 (A-)**: 메타 Core Data Science 팀 논문이 계정 간 상호작용을 `(source, target, action, timestamp)` 시퀀스로 모델링, **연속 행동 간 시간 간격 Δt를 명시적 피처로 사용**. 원문: "Rate of interaction is an important signal for detecting abusiveness."
  - 출처: [TIES: Temporal Interaction Embeddings ... at Facebook, arXiv 2002.07917](https://arxiv.org/abs/2002.07917) (2020). 단 Facebook 대상 서술 — IG DM 직접 적용은 추정.
- **ToS 근거**: [Instagram 이용약관 4.2절](https://help.instagram.com/581066165581870/) — "허가받지 않은 방법으로 ... 자동화된 방식으로 정보에 액세스하고 정보를 수집하는 행위" 금지. 다만 문언은 스크래핑 초점, DM 자동 발송을 콕 집진 않음(스팸 정책으로 포괄).

## 4. 탐지 기술 — 위장으로 회피 가능한가 → 부분만 (B+)

- **클라이언트 생성 UUID(X-IG-Device-ID, X-IG-Android-ID, phone_id)는 위장·재사용 쉬움.** instagrapi가 `set_uuids()`/`dump_settings()`로 "안정된 가짜 디바이스 프로필 1개 유지"를 표준 워크플로로 지원.
  - 출처: [instagrapi private.py](https://github.com/subzeroid/instagrapi/blob/master/instagrapi/mixins/private.py)
- **그러나 서버 발급 식별자 X-MID가 위장의 천장.** X-MID는 클라이언트가 정하는 값이 아니라 **서버가 발급하고 응답 헤더 `ig-set-x-mid`로 갱신 지시**. 즉 클라가 뭐라 주장하든 메타 서버가 세션/계정/디바이스 이력을 독립 추적. (소스코드 확인 — B+)
- **IP 인프라 계층은 지문과 독립 신호.** 데이터센터/클라우드 IP는 ASN 조회로 즉시 "데이터센터"로 판별돼 1차 필터. 레지던셜(가정용) IP와 구분됨. (기술적으로 타당하나 1차 증거는 프록시 벤더뿐 — C)
- **계정 연결 탐지의 정확한 알고리즘은 비공개.** 메타 [Fake Accounts 리포트](https://transparency.meta.com/reports/community-standards-enforcement/fake-accounts)는 "IP·디바이스 지문 등 수백 개 차원 사용"이라는 정성 서술만. (조사 중 "메타 특허"로 오인할 뻔한 US12255911·US12160433은 각각 Raritex·Amazon 소유로 확인·정정 — 메타 것 아님.)
- **종합**: 디바이스 지문 위장은 "완전 회피"가 아니라 **독립된 여러 탐지 층 중 하나만 통과**하는 것. ① 서버발 X-MID로 서버 독립 추적, ② IP ASN, ③ 행위 타이밍(TIES) — 한 층 뚫어도 나머지에 걸림. instagrapi 유지보수자 권고(안정된 디바이스 1개 + 안정된 IP 1개 + 세션 유지)가 실무 합의선이며, 셋 중 하나만 깨져도 나머지 위장이 무의미.

## 5. 실무자 실제 밴 사례 (B — instagrapi GitHub, 실제 열람)

- **유지보수자 본인 결론**: DM 자동 발송은 "라이브러리 차원에서 안전하게 만들 신뢰할 방법이 없다(no reliable library-side switch)... 여전히 공식 API가 더 안전한 방향". DM(`direct_send`)은 팔로우·좋아요보다 **더 위험한 액션 카테고리**.
  - 출처: [instagrapi #2136](https://github.com/subzeroid/instagrapi/issues/2136)
- **실제 수치 사례**:
  - **40건 → 경고**: 매시간 5건씩(40초~2분 무작위 간격), 하루 8회 = 총 40건 후 경고. "고품질" 프록시 + 세션 재사용에도. ([#2136](https://github.com/subzeroid/instagrapi/issues/2136))
  - **스크래핑 10건 → 즉시 밴** 사례 ([#1806](https://github.com/subzeroid/instagrapi/issues/1806))
  - **1년 무사고 계정도 어느 날 갑자기 정지 + 셀피 인증 요구** — 정석(세션 재사용·IP 고정) 다 지켜도. 여러 계정·시점에 반복 보고.
- **유지보수자의 밴 판정 설명**: "account age and trust, device consistency, proxy reputation, request volume, action mix, repeated fresh logins, prior challenges..." 종합 판정 → §3 메타 공식과 일치.
- **에러 대응 정석**: `429`→백오프, `PleaseWaitFewMinutes`→쓰기 중단, `FeedbackRequired`→해당 액션 즉시 중단, `ChallengeRequired`→자동 우회 말고 수동 인증. ([best-practices](https://subzeroid.github.io/instagrapi/usage-guide/best-practices.html))
- **상충 증언(정직)**: "정석 지키니 몇 달 무사고"와 "똑같이 했는데 밴"이 같은 스레드에서 직접 충돌 → 재현성 낮고 시점 의존적. (POC로 우리 조건 직접 실측해야 하는 이유)
- **한계**: Reddit은 이 세션 도구에서 정책상 차단으로 미조사. Stack Overflow direct_v2 구체치 못 찾음.

## 6. 국내 경쟁사 업체 (A- — 공식 문서·헬프센터 직접 인용)

- **피처링(Featuring)** — 우리가 검토하는 모델과 동일:
  - 콜드 아웃리치 지원(발굴 단계 인플루언서에게 최대 200명 일괄 DM).
  - **공식 API 아님**: openads 기사 "피처링에서도 ... **자체적인 DM 발송 기능을 구현**". 발신 계정 다중화(스탠다드 3 / 프리미엄 10 / 엔터프라이즈 무제한)로 공식 API의 계정 단일 인증과 다른 구조.
  - **책임 전가 조항(헬프센터 원문)**: "DM 발송으로 인한 발신 계정의 차단은 피처링이 책임지지 않습니다", "인스타그램의 DM 발송 제재로 인한 계정 차단 및 DM 접근 불가 현상은 피처링이 책임지지 않습니다".
  - **발송 속도 사용자 선택(밴 리스크 앵커)**: Safe 시간당 3건 / Plus 8건 / Max 21건. 경고: "시간당 발송 수가 많아질수록 ... 차단하거나 제재할 확률이 올라".
  - 가격: 월 42만원(1회 50명, 발신계정 3) ~ 83.7만원(1회 200명, 발신계정 10) ~ 엔터프라이즈 협의.
  - 출처: [docs.channel.io/featuring_guide](https://docs.channel.io/featuring_guide/ko/articles/DM%EC%9D%B4%EB%A9%94%EC%9D%BC-%EB%B0%9C%EC%86%A1-562957f2), featuring.co/pricing, [openads.co.kr/14816](https://www.openads.co.kr/content/contentDetail?contsId=14816)
- **제리와콩나무 / 스프레이** — 콜드 지원. "부계정 여러 개 만들어 번갈아 발송" + 계정 생성 후 즉시 대량 발송 말고 "자연스러운 메시지 몇 차례(워밍업)" 권장. 공식 API·책임 문구 미확인. ([openads.co.kr/13822](https://openads.co.kr/content/contentDetail?contsId=13822), [jerrybeanstalk.com/blog](https://jerrybeanstalk.com/blog/how-to-prevent-instagram-block))
- **소셜비즈(NHN데이터)** — 콜드 아님. "댓글 남기면 DM"류 **선행 신호 기반**만(24h 응답 규정 안). "Meta 공식 API 기반" 명시. 약관은 표준 SaaS 문법으로 책임을 회원에게(정보통신망법 준수 의무 포함). ([socialbiz.nhndata.com](https://socialbiz.nhndata.com/post/blog_260703))
- **크몽 개인 셀러** — 콜드 대량 발송 대행, 책임 전가 더 노골적("계정 문제 시 환불·AS 책임 없음", "수신 동의·분쟁 책임은 의뢰인"). 가격 예: 100건 29,000원 ~ 1만건 29만원. ([kmong.com/gig/500809](https://kmong.com/gig/500809), [gig/448095](https://kmong.com/gig/448095))
- **패턴 종합**: 콜드 아웃리치 광고 업체는 **전부 공식 API 미사용 + 자체세션 + 계정 다중화 + 워밍업 + 발송속도 조절 + 책임 전가.** 공식 API 쓰는 곳은 콜드가 아닌 선행신호 기반만. → 우리 결론(§1) 그대로 증명.

## 7. 국내 개발자 한글 콘텐츠 (B~D — 얕음)

- **"만드는 법"은 많고 "쓰다 어떻게 됐다"는 거의 없음.** 셀레니움·instagrapi 봇 튜토리얼은 널렸는데 운영 중 정지 후속 기록이 국내엔 얇음.
- 가장 근접한 1차 경험담: [GPTers 커뮤니티](https://www.gpters.org/dev/post/creating-instagram-communication-bot-i7MtxjHczolNCRr) — instagrapi 봇 개발 중 "비정상적인 로그인 감지 → 본인 인증" 챌린지 트리거(완전 밴 아님).
- 돌아다니는 국내 수치(DM 하루 70건, 좋아요 150~200/일 등)는 죄다 대행사·SEO 업체 재탕(D). A/B 실측 근거 아님.
- 국내 특유 조건(국내 IP·통신사·KISA·방통위) 결부 콘텐츠는 0건.
- **혼동 주의**: 2025년 5월 "인스타 무고 계정 연쇄 정지 사태"는 메타의 **아동 성착취 탐지 AI 오탐**이 원인 — **DM 자동화와 무관한 별개 사건**. 검색에 섞여 나옴.

## 8. 우리 코드베이스 재사용 가능 요소

- **재사용 높음**: `crawler/.../adapter/out/instagram/JdkInstagramWebClient.java` — 인스타 GraphQL POST, 프록시 로테이션, 쿠키 관리, HTTP/2 고정, User-Agent·Sec-Fetch 헤더 위장. 프록시 관리(`ProxyProperties`), 외부 API 통합 패턴(Hiker/Apify/DataLikers).
- **없음(신규 필요)**: 인스타 **로그인/세션** 로직(현재 전부 익명 크롤 전용), DM 엔드포인트 스키마.
- Hiker/Apify/DataLikers 전부 **조회 전용** — DM 발송 기능 없음.

## 종합 결론

외부에 "확정 수치"는 존재하지 않는다. 있는 것은 (A) 메타가 공식 인정한 정성 원칙 몇 개 + (B) 실무자들의 "정석 지켜도 태운다, 40건에 경고" 반복 증언 + (A-) 국내 경쟁사 피처링의 실측 벤치(계정당 시간당 3~21건, 계정 다중화, 책임 전가). 세 갈래 모두 **"콜드 DM 자동화는 계정을 태운다"**는 한 방향을 가리킨다. 그래서 남의 추측 대신 **우리 조건에서 직접 실측하는 POC**의 가치가 분명하며, 안전선 앵커는 피처링 Max(21건/시간/계정)이다.

## 조사의 정직한 한계

- Reddit·국내 롱테일 커뮤니티(DCinside·티스토리 롱테일)는 세션 도구 정책상 미조사.
- 밴 판정의 정량 알고리즘·가중치는 원천적으로 비공개 — 어떤 조사로도 안 나옴.
- 경쟁사 실제 발송 인프라(자체세션 여부·프록시 종류)는 공개문서 정황 추정이지 코드/트래픽 확인 아님.
- TIES 논문은 Facebook 대상 — IG DM 직접 적용은 추정.
