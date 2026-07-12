package com.celfit.analytics.mirror;

import java.util.List;

/** 미러 대상 등록부. B1부터 서빙 형태 뷰 3종(accounts·contents·content_comments)이 추가된다. */
public record MirrorRegistry(List<MirrorSpec<?>> specs) {
}
