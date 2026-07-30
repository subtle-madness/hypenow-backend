# CB3 — 도입문의 API

- **소속 트랙군**: 클로즈베타 전환 트랙 — 2026-07-19 프론트 요청서(초대코드 가입 전용 + 도입문의 접수)
- **의존**: CB2(스택 — V 번호 순서)
- **상태**: ✅ ([PR #56](https://github.com/subtle-madness/hypenow-backend/pull/56) 머지 완료 07-19)

## 내용

V10 `inquiries`(uuid PK) + `POST /v1/inquiries`(Public, IP 분당 2회) — 운영자 확인은 DB 조회, Resend 알림은 후속 옵션
