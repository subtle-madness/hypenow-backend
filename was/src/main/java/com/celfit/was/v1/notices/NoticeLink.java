package com.celfit.was.v1.notices;

/** 업데이트 소식 항목의 상세 링크 — href·label 둘 다 값이거나(DB CHECK 제약) 둘 다 없다. */
public record NoticeLink(String href, String label) {
}
