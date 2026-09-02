# 인스타그램 공식 Messaging API — DM 파이프라인 "합법 구간" 조사 (POC)

> 상태: 🟢 활성 · 조사 기록 · 2026-09-02
> 범위: 공식 Instagram Messaging API(Meta Graph API)로 DM 파이프라인의 "API 합법 구간"이 실제로 어디까지 되는지 검증. 특히 **인플루언서가 답장한 이후의 대화 관리**를 공식(밴 안전) API로 할 수 있는가.
> 위치: 인플루언서 콜드 아웃리치 DM 자동 발송 프로젝트의 **병렬 트랙**. 비공식 자체세션으로 첫 콜드 DM을 보내는 메인 트랙([핸드오프](2026-09-01-instagram-dm-poc-handoff.md) / [조사 근거](2026-09-01-instagram-dm-poc-research.md))과 별개.
> 원칙: 자동화 벤더 블로그(instantdm·spurnow 등)는 상업 편향으로 근거에서 제외. Meta 공식 개발자 문서(A) 우선, 서드파티·개발자포럼·정황추론은 등급 표기.

## 이 문서가 답하려는 것 (그리고 답하지 않는 것)

- **답 안 함**: "공식 API로 콜드 첫 DM을 보낼 수 있는가" — 이미 불가로 확정([핸드오프 확정결정 #1](2026-09-01-instagram-dm-poc-handoff.md)). 재론 안 함.
- **답함**: 콜드 첫 DM **이후**, 인플루언서가 답장한 다음의 대화를 공식 API로 조회·응답·감지할 수 있는가. 성립하면 **"비공식 세션은 첫 터치에만, 이후 대화는 공식 API"라는 하이브리드**로 버너 계정 경로 노출을 줄일 수 있다.

## 근거력 등급 범례

- **A**: Meta 공식 개발자 문서(developers.facebook.com) / 메타 연구팀 논문 (1차)
- **A-**: Meta 공식이나 간접·요약 경유, 또는 공식 정책 + 다수 독립 출처 일치
- **B**: 소스코드·개발자 포럼(Stack Overflow, Meta Developer Community, n8n 등)의 재현 가능한 실증 증언
- **C**: 기술적으로 타당한 정황 추론 (공식 문서가 직접 답하지 않음, 조건절 부재로부터의 소거 추론 등)
- **D**: 근거 없음 / 벤더 관행치 / 통속 표현

---

## 요약 (TL;DR)

| 구간 | 공식 API 가능 여부 | 핵심 제약 | 등급 |
|---|---|---|---|
| ① 셋업 | 가능 | FB 페이지 불요(신 경로). 단 **App Review(Advanced Access) 필수** = 행정 장벽 | A |
| ② 대화·메시지 읽기 | 가능(제한적) | **대화당 최근 20개 메시지만** 상세 조회. 그 이전은 에러 | A |
| ③ 수신 답장 감지 | 가능 | `messages` 웹훅으로 실시간. 단 페이로드에 **IGSID만, username 없음** | A |
| ④ 답장 발송 | 가능(창 제약) | 24h 창 + HUMAN_AGENT 7일. IG는 그 외 태그 사실상 없음 | A |
| ⑤ ★브리지(비공식 발신→공식 이어받기) | **미확인 — 실계정 테스트 필요** | 문서 정황은 "된다" 쪽(C), 확정 근거(A/B) 없음 | C |

**하이브리드 성립의 두 관문**:
1. **브리지 질문(⑤)** — 비공식으로 시작한 대화를 공식 API가 실제로 보는가. 문서만으론 미확정, 정황은 긍정적.
2. **username↔IGSID 매핑(②③의 부수 발견)** — 공식 API는 대화 상대를 IGSID로만 주고 username을 직접 반환하지 않는 것으로 보인다(B급). 우리가 어떤 인플루언서에게 보냈는지 대화를 되짚어 연결하는 게 공식 API 단독으론 막힐 수 있다. **이게 브리지 질문만큼 중요한 실측 대상이다.**

---

## ① 셋업 요건·비용

### 계정·앱 요건 — 신 경로가 페이지 장벽을 제거함 (A)

- **신 경로 (Instagram API with Instagram Login, 2024~2025 도입)**: "This API setup **does not require a Facebook Page** to be linked to the Instagram professional account." 요구는 IG 프로페셔널(비즈니스/크리에이터) 계정 + Meta 개발자 앱뿐. → [Instagram API with Instagram Login](https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login/) (A)
- **구 경로 (Facebook Login for Business, 레거시)**: IG 프로 계정이 FB 페이지에 연결돼야 하고 메시징이 Messenger Platform을 경유. 신규 구축은 비권장. → [Overview](https://developers.facebook.com/docs/instagram-platform/overview/) (A)

### 권한 스코프 (A)

| 경로 | 메시징 필요 스코프 |
|---|---|
| 신(Instagram Login) | `instagram_business_basic` + `instagram_business_manage_messages` |
| 구(Facebook Login) | `instagram_basic` + `instagram_manage_messages` 등 — **구 스코프는 2025-01-27부 폐지 예고** |

출처: [Overview](https://developers.facebook.com/docs/instagram-platform/overview/), [Instagram API with Instagram Login](https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login/), [Send Messages](https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login/messaging-api/)

### App Review — 실질 장벽 (A)

- **개발/테스트 모드**: 앱 대시보드에 명시적으로 추가한 **테스터 계정만** 사용 가능. (자체 계정으로 즉시 실측 가능 = 이게 우리 POC의 진입점.)
- **실사용자(고객사 계정) 대상 프로덕션**: `instagram_business_manage_messages`를 실사용자에게 쓰려면 **Advanced Access → App Review 필수**. "Your app must complete Meta App Review to be granted Advanced Access" ([Overview](https://developers.facebook.com/docs/instagram-platform/overview/), A).
- 심사 요구물: 각 권한 사용처 스크린캐스트, 개인정보처리방침·이용약관 URL, **Business Verification**(무료, 서류), 리뷰어용 실 테스트 환경.
- 메시징 권한은 "프라이빗 앱(내부망·UI 없음)" 예외 승인 경로에 **포함되지 않음** — 즉 메시징엔 이 우회로가 없다 (A).
- **소요기간**: 공식은 "런칭 최소 2주 전 제출 권장"만 명시. 실제 2~7영업일~20일이라는 서드파티 보고는 **미확인(C)**.

### 토큰·비용 (A / 일부 B)

- 토큰: **IG User Access Token**. 단기(1시간) → 장기(60일) 교환, 만료 전 앱이 직접 갱신. 페이지 토큰이 아니라 IG 유저 토큰이 발송 주체 (A).
- 비용: **API 호출·24h 창 내 메시징 자체는 무료.** Business Verification도 무료. 유료 "Meta Verified" 배지는 API와 무관 — 혼동 주의. → 금전 비용은 낮고, **실질 비용은 App Review·심사 대기라는 행정 비용** (A-/B).

---

## ② 대화·메시지 내역 읽기

| 항목 | 내용 | 등급 |
|---|---|---|
| 대화 목록 | `GET /{page-id 또는 me}/conversations?platform=instagram` → 대화 ID + `updated_time`. `user_id`로 특정 상대 대화만 필터 | A |
| 메시지 목록 | `GET /{conversation-id}/messages` → 메시지 ID + `created_time` | A |
| 개별 메시지 | `GET /{message-id}?fields=...` → `id`, `created_time`, `from`, `to`, `message`(텍스트), `reply_to` | A |
| **과거 이력 한계** | **대화당 "가장 최근 20개 메시지"만 상세 조회 가능. 그보다 오래된 메시지 조회는 에러.** 보관기간 제한이 아니라 조회 창 제한 | A |
| 30일 비활성 | Requests 폴더의 미수락 대화는 30일 비활성 시 목록 미반환 | A |
| 신/구 토큰 어느 쪽으로 되는지 | `/me/conversations`가 신 경로(Instagram Login) 토큰으로 되는지 구 페이지 토큰이 필요한지 문서로 미확정 | **미확인** |

출처: [Conversations API](https://developers.facebook.com/docs/messenger-platform/conversations/), [Instagram Conversation feature](https://developers.facebook.com/docs/messenger-platform/instagram/features/conversation/)

> **함의**: "대화 전체 아카이빙"엔 최근 20개 창 제한이 걸린다. 우리가 능동 관리하는 라이브 스레드엔 문제 없지만, 과거 대량 이력 백필 용도로는 부적합.

---

## ③ 수신 답장 감지 (웹훅)

| 항목 | 내용 | 등급 |
|---|---|---|
| 구독 필드 | `messages`, `messaging_seen`, `messaging_postbacks`, `messaging_reactions`, `messaging_referrals`, `message_reactions`, `messaging_handover` 등 (Instagram 오브젝트) | A |
| `messages` 페이로드 | `sender.id`(IGSID), `recipient.id`, `timestamp`, `message{ text?, mid?, attachments, reply_to, story }`, `is_self`, `is_deleted`, `folder` | A(스키마) / A-(text·mid는 Messenger 동형 근거로 추론) |
| 읽음 감지 | `messaging_seen` 웹훅 존재 | A |
| 셋업 | 콜백 URL + Verify Token 등록·검증. 앱 대시보드 "Configure webhooks" | A |
| 개발모드 제약 | App Review 승인 전엔 **테스터 등록 계정 발신 메시지에만** 웹훅 발화(개발단계 한정) | B |
| 재시도·순서보장 | 공식 문구 미확보 | **미확인** |

출처: [Instagram Webhooks Reference](https://developers.facebook.com/docs/graph-api/webhooks/reference/instagram), [Messaging API](https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login/messaging-api/)

### ★부수 발견 — username이 안 온다 (하이브리드의 실질 리스크)

- 웹훅 `sender`/`recipient`는 **`id`(IGSID)만** 담고 username을 담지 않는다 (A, 위 두 출처 + Messenger 동형).
- 그리고 **IGSID→username 직접 조회가 막힌 것으로 보인다**: `GET /{igsid}?fields=username` 시도 시 `"(#100) Tried accessing nonexisting field (username) on node type (IGBusinessScopedID)"`. `name`(표시명)·프로필 사진은 값이 있으면 조회 가능하다는 언급. → [Meta Developer Community 스레드](https://developers.facebook.com/community/threads/812192713020345/) (B — 포럼이나 재현 가능한 에러메시지라 신뢰도 높음)
- **함의**: 하이브리드에서 우리는 "인플루언서 @xxx에게 콜드 DM을 보냈다"는 걸 username으로 안다. 그런데 공식 API가 답장을 IGSID로만 주고 username 역매핑이 막히면, **어느 대화가 어느 타겟의 답장인지 자동 연결**이 공식 API 단독으로는 어렵다. (완화책: `name` 표시명 매칭, 또는 첫 DM 본문에 상관용 토큰 삽입, 또는 비공식 세션이 이미 아는 스레드 식별자를 넘겨받기 — 전부 실측 필요.)
- **미확인**: 공식 "Get a Person's Profile" 유의 엔드포인트가 username을 반환하는지 최종 확정 못 함(검색이 서드파티 스크레이핑 API로 오염). 실계정 콜로 재검증 우선순위 높음.

---

## ④ 답장 발송 제약

| 항목 | 내용 | 등급 |
|---|---|---|
| 24h 표준 창 | "Your app has 24 hours to respond to any message sent from an Instagram user to your app user." 유저의 마지막 인바운드 시점부터 24h. 창 안엔 프로모션 포함 자유 응답 | A |
| 창 리셋 | 유저가 다시 보내면 새 24h 창. (공식 명시성 약함, 업계 표준 해석) | A-/B |
| HUMAN_AGENT 태그 | 사람이 응답 시 태그로 창을 **7일까지** 연장. IG Messaging 전용 문구 존재. 남용(자동발송에 부착 등) 시 발송 제한 | A / A- |
| 기타 메시지 태그 | Messenger 표준 태그(ACCOUNT_UPDATE 등)는 **2026-04-27부 폐기**(error code 100). IG에 적용된 적 자체가 불명확. → **IG에서 24h 밖을 여는 실질 수단은 HUMAN_AGENT(7일)가 사실상 유일** | A-/B |
| 창 만료 후 발송 | `403 IGApiException, code 10, subcode 2534022` — "This message is sent outside of allowed window." | B(개발자 커뮤니티 실증) |

출처: [Send Messages](https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login/messaging-api/), [Messenger/IG Messaging 정책](https://developers.facebook.com/documentation/business-messaging/messenger-platform/policy), [n8n Community — outside allowed window](https://community.n8n.io/t/instagram-messaging-api-this-message-is-sent-outside-of-allowed-window/262109)

> **함의**: 인플루언서가 답장한 뒤엔 24h 안 자유 응답이 정상 경로. 협의가 며칠 늘어지면 HUMAN_AGENT(7일)로 커버되나 "사람이 실제 응답" 요건이 붙는다 — 자동발송을 이 태그로 우회하면 정책 위반. 대화가 7일+ 끊기면 공식 API로는 재개 불가(유저가 다시 써야 창이 열림).

---

## ⑤ ★브리지 질문 (가장 중요) — 미확인, 정황은 긍정

**질문**: 첫 콜드 DM을 비공식 자체세션(instagrapi 등)으로 보낸 **우리 발신 계정**을 공식 Meta 앱에 연결하면, 공식 API가 그 (비공식으로 시작된) 대화 스레드를 조회·이어서 관리할 수 있는가?

### 결론: **미확인 — 실계정 테스트 필요.** 문서 정황은 "된다" 쪽에 무게(등급 C).

Meta 공식 문서는 이 혼합 채널 시나리오를 명시적으로 확인도 부정도 하지 않는다. 아래는 정황 근거.

**긍정 정황 1 — 공식 API는 별도 샌드박스가 아니라 네이티브 인박스를 들여다보는 창이다 (B급 근거의 C급 추론)**
- 문서가 대화 동작을 **Primary / Requests / General**이라는 순전한 네이티브 인스타그램 인박스 폴더 개념으로 설명한다. API 전용 대화만 다뤘다면 네이티브 폴더 개념이 등장할 이유가 없다. → API가 실제 네이티브 인박스를 반영한다는 강한 시사.
- 출처: [Send Messages — Instagram Inbox 섹션](https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login/messaging-api/)

**긍정 정황 2 — 24h 창·웹훅 트리거가 "발송 채널"이 아니라 "그 계정이 메시지를 받았는가"로 정의된다 (C)**
- "24 hours to respond to any message sent **from an Instagram user to your app user**", "When an Instagram user sends a message to your app user, an event is triggered", "Conversations only begin when an Instagram user sends a message ... through Feed, posts, story mentions, and **other channels**." — 세 문장 모두 인바운드가 **어느 채널로 시작됐는지 제약하지 않는다.**
- 시나리오 대입: 우리 계정이 비공식으로 먼저 보냄 → 상대가 네이티브 앱에서 답장 → 그 답장은 "인스타그램 유저가 우리 계정에 보낸 메시지"에 해당 → 웹훅 발화·24h 창 오픈 **가능성 높음.** 단 이는 조건절에 경로 제약이 "없다"는 데서 역추론한 것(등급 C).

**혼동 주의 — 반대처럼 보이나 아닌 근거**
- "Webhooks/API로 전달된 메시지는 인스타 앱 인박스에서 '읽음' 처리 안 됨. 답장을 보낸 후에만 읽음 처리." → 이건 **API→네이티브 방향**의 비대칭이지, **네이티브→API 방향**(네이티브로 오간 게 API에 안 보인다)이 아니다. 브리지 질문(네이티브→API 가시성)을 부정하지 않는다.

**공백**
- Meta 공식·Stack Overflow·Meta Developer Community 어디에도 "비공식/서드파티로 보낸 메시지에 웹훅이 발화하는가"를 직접 다룬 스레드가 검색에서 안 나왔다. 이 조합을 공개적으로 시도한 개발자가 드묾을 시사 — 즉 **공개 근거로는 확정 불가, 우리가 직접 실측할 수밖에 없다.**

| 하위 질문 | 문서 명시 | 정황 방향 | 등급 |
|---|---|---|---|
| conversations/messages가 계정 전체 인박스 반영? | 안 됨 | Yes(네이티브 폴더 개념) | C |
| 24h 창이 발송 경로 무관 실 수신 기준? | 안 됨 | Yes(조건절 경로 제약 없음) | C |
| 웹훅이 계정 전체 수신 DM에 발화? | 안 됨 | Yes(동일 근거) | C |

---

## 하이브리드 아키텍처 성립 판정

**"비공식 세션은 첫 콜드 터치에만, 이후 대화는 공식 API로"** 라는 하이브리드가 성립하려면 두 관문을 통과해야 한다. 둘 다 **문서만으론 확정 불가 → 실계정 실측 필요**.

1. **브리지 가시성(⑤)**: 비공식으로 시작한 대화가 공식 API `GET /me/conversations`·웹훅에 나타나는가. — 정황 긍정(C), 미확정.
2. **타겟 상관(②③ 부수발견)**: 공식 API가 IGSID만 주는데, 이 IGSID를 우리가 콜드 DM을 보낸 인플루언서 username과 어떻게 연결하는가. — 공식 API 단독으론 막힐 소지(B), 완화책 있으나 미검증.

**추가로 유의할 구조적 사실 (하이브리드를 택하든 안 하든)**:
- 공식 API를 실사용자(고객사) 계정에 쓰려면 **App Review + Business Verification**을 통과해야 한다. 이건 계정마다가 아니라 우리 앱 한 번이지만, 심사에서 "메시징 권한 사용처"를 정직하게 시연해야 한다 — 콜드 아웃리치 용도가 심사 통과에 유리하지 않을 수 있음(정책 정합성은 별도 검토 필요, 이 문서 범위 밖).
- 하이브리드의 이점은 **버너 계정을 첫 1콜(콜드 DM)에만 노출**시켜 밴 리스크 표면을 줄이는 것. 다만 첫 콜드 DM 자체가 가장 위험한 액션(메인 트랙 근거)이라, 하이브리드가 리스크를 없애지 못하고 **"이후 대화 왕복"만큼의 노출을 덜어줄** 뿐임을 분명히 할 것.

---

## 실계정 검증 플랜 (문서로 확정 못 한 것을 실측으로)

셋업 자체는 **개발/테스트 모드 + 자체 IG 프로 계정 2개**(발신용·수신용)면 App Review 없이 즉시 가능하다. 우선순위:

1. **[최우선] 브리지 + 타겟 상관 동시 검증**:
   a. 발신 계정(공식 앱에 연결·웹훅 구독)에서 **비공식 세션(instagrapi)으로** 수신 계정에 첫 DM 발송.
   b. 수신 계정이 네이티브 앱에서 답장.
   c. 발신 계정 쪽에서 (i) 웹훅이 발화하는가 (ii) `GET /me/conversations`에 이 스레드가 뜨는가 (iii) 페이로드/조회에 **어떤 식별자**(IGSID·name)가 오는가, username 역매핑이 되는가를 관찰.
   → 이 한 실험이 브리지(⑤)와 타겟상관(②③) 두 관문을 동시에 판정한다.
2. 24h 창 밖 발송이 실제로 `403 code 10 subcode 2534022`를 주는지, HUMAN_AGENT 태그로 7일까지 열리는지.
3. 대화당 최근 20개 초과 조회 시 에러 재현.
4. 웹훅 재시도·순서보장 실동작(문서 공백).

---

## 미확인 항목 총정리 (재조사·실측 우선순위순)

| # | 항목 | 현재 상태 | 확인 방법 |
|---|---|---|---|
| 1 | **브리지 — 비공식 시작 대화가 공식 API에 보이는가** | 미확인(정황 C, 긍정) | 실계정 실험 1 |
| 2 | **IGSID↔username 역매핑 가능 여부** | B급으로 "불가"에 무게 | 실계정 실험 1 |
| 3 | `/me/conversations`가 신 경로(Instagram Login) 토큰으로 되는지 | 미확인 | 실계정 콜 |
| 4 | 웹훅 재시도·순서보장·HTTPS 강제 문구 | 미확인 | 실측/추가 문서 |
| 5 | App Review 실제 소요일수·통과 난이도(콜드 용도 정합성) | 미확인(C) | 실제 제출/정책 검토 |
| 6 | 24h 밖 IG 유료 재개 채널 존재 여부(2026 최신) | 미확인 | Meta 공식 요금 페이지 |

---

## 안전선 (이 트랙 내내)

- 이 트랙은 **조사·문서 + 필요시 테스트용 Meta 앱 셋업**까지. 운영/서비스 코드 변경·PR·push는 사용자 명시 승인 전 금지.
- 실계정 실험은 **자체 소유 더미 계정으로만**(발신·수신 양쪽). 실제 인플루언서 대상 금지.
- 메인 핸드오프 파일([2026-09-01-instagram-dm-poc-handoff.md](2026-09-01-instagram-dm-poc-handoff.md))은 다른 세션이 병렬 편집 중 — 수정하지 않는다.

## 출처 총괄 (Meta 공식 = A)

- [Instagram Platform Overview](https://developers.facebook.com/docs/instagram-platform/overview/)
- [Instagram API with Instagram Login](https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login/)
- [Send Messages (Messaging API)](https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login/messaging-api/)
- [Conversations API](https://developers.facebook.com/docs/messenger-platform/conversations/) / [Instagram Conversation feature](https://developers.facebook.com/docs/messenger-platform/instagram/features/conversation/)
- [Instagram Webhooks Reference](https://developers.facebook.com/docs/graph-api/webhooks/reference/instagram)
- [Messenger/IG Messaging 정책](https://developers.facebook.com/documentation/business-messaging/messenger-platform/policy)
- [Permissions Reference](https://developers.facebook.com/docs/permissions/)
- 개발자 포럼(B): [IGSID→username 스레드](https://developers.facebook.com/community/threads/812192713020345/), [n8n — outside allowed window](https://community.n8n.io/t/instagram-messaging-api-this-message-is-sent-outside-of-allowed-window/262109), [n8n — 웹훅 테스트모드 제약](https://community.n8n.io/t/instagram-dms-webhooks-work-only-in-test-mode/176851)
