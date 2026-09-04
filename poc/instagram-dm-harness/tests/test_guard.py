import pytest

from igdm_harness.guard import assert_recipient_allowed, RecipientNotAllowedError


def test_allows_recipient_in_allowlist():
    assert_recipient_allowed("dummy_01", {"dummy_01", "dummy_02"})  # 예외 없이 통과


def test_blocks_recipient_not_in_allowlist():
    with pytest.raises(RecipientNotAllowedError):
        assert_recipient_allowed("real_person", {"dummy_01", "dummy_02"})


def test_blocks_on_empty_allowlist():
    with pytest.raises(RecipientNotAllowedError):
        assert_recipient_allowed("dummy_01", set())


def test_case_and_at_sign_normalized():
    # @ 접두·대소문자 차이로 우회되지 않게 정규화
    assert_recipient_allowed("@Dummy_01", {"dummy_01"})


def test_blank_recipient_blocked():
    with pytest.raises(RecipientNotAllowedError):
        assert_recipient_allowed("  ", {"dummy_01"})
