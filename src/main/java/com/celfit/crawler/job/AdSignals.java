package com.celfit.crawler.job;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 광고·협찬 표기 판별. 액터가 파트너십 필드를 주면 그것을 우선하고 (현 공식 액터들은 미제공),
 * 없으면 캡션의 표기 패턴으로 추정한다. 표기 없는 뒷광고는 잡지 못하는 보수적 판별.
 */
public final class AdSignals {

    private static final Pattern MARKERS = Pattern.compile(
            "#광고|#협찬|#유료광고|#기자단|#ad\\b|#sponsored\\b"
                    + "|유료\\s*광고|광고\\s*포함|제작비\\s*지원|제공\\s*받|협찬\\s*받|협찬받",
            Pattern.CASE_INSENSITIVE);

    public static boolean adMarked(Map<String, Object> detail) {
        if (Boolean.TRUE.equals(detail.get("paidPartnership"))
                || Boolean.TRUE.equals(detail.get("isPaidPartnership"))) {
            return true;
        }
        return detail.get("caption") instanceof String caption && MARKERS.matcher(caption).find();
    }

    private AdSignals() {}
}
