package com.celfit.analytics.mirror;

import java.util.List;

/** 미러 대상 등록부 — 서빙 뷰 3종(B1) + 인플루언서 상세 3종(C1). 등록은 MirrorConfig.mirrorRegistry(). */
public record MirrorRegistry(List<MirrorSpec<?>> specs) {
}
