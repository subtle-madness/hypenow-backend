-- 업데이트 소식(app.notices/app.notice_items) 어드민 개별 삭제 아카이브 사유 추가(트랙 NN).
-- NoticeRepository.delete가 이 값으로 이관한다. CHECK 값 추가는 허용 범위를 넓히기만 하므로
-- 롤링 중 구버전 코드가 이 값을 아직 안 써도 위반이 나지 않는다(순수 additive).
ALTER TABLE archive.archived_rows DROP CONSTRAINT archived_rows_archived_reason_check;
ALTER TABLE archive.archived_rows ADD CONSTRAINT archived_rows_archived_reason_check
    CHECK (archived_reason IN
        ('ACCOUNT_DELETION', 'SAVED_REMOVED', 'CAMPAIGN_DELETED', 'REGISTRATION_ROLLBACK', 'NOTICE_DELETED'));
