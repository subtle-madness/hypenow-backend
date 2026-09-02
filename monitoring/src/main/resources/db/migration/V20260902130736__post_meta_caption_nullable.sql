-- 캡션 3-상태 계약(트랙 HH) — null=미수집(파싱 실패) / ''=확인된 무캡션 / 값=원문.
-- caption을 NOT NULL로 두면 저장 계층이 미수집을 표현할 수 없어, SnapshotWriter가 null을
-- ""로 강제 변환해 온 것이 곧 기존 캡션을 지우는 결손 경로였다(데이터 보호 결함 수정).
-- DROP NOT NULL은 제약을 느슨히 하는 expand 단계 — 참조 코드 유무와 무관하게 항상 안전하다.
ALTER TABLE post_meta ALTER COLUMN caption DROP NOT NULL;
