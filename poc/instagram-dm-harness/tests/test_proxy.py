import pytest

from igdm_harness.proxy import assign_proxies, ProxyAssignmentError
from igdm_harness.config import SenderAccount


def _sender(alias, exit_):
    return SenderAccount(alias=alias, username=alias, password="p",
                         verification="phone", arm="send", proxy_exit=exit_)


def test_assigns_one_to_one():
    senders = [_sender("s1", "exit_1"), _sender("s2", "exit_2")]
    proxies = {"exit_1": "http://a", "exit_2": "http://b"}
    m = assign_proxies(senders, proxies)
    assert m == {"s1": "http://a", "s2": "http://b"}


def test_raises_on_missing_exit():
    senders = [_sender("s1", "exit_9")]
    with pytest.raises(ProxyAssignmentError):
        assign_proxies(senders, {"exit_1": "http://a"})


def test_raises_on_overlap():
    senders = [_sender("s1", "exit_1"), _sender("s2", "exit_1")]
    with pytest.raises(ProxyAssignmentError):
        assign_proxies(senders, {"exit_1": "http://a"})
