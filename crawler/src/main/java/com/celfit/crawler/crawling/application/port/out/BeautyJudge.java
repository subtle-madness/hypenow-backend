package com.celfit.crawler.crawling.application.port.out;

import java.util.List;

/**
 * 프로필 텍스트 재료로 뷰티 계정 여부를 판정. 현 구현은 로컬 Claude CLI —
 * 서버 배포 시 이 포트 뒤에서 Anthropic API 구현으로 교체한다.
 */
public interface BeautyJudge {

    /** captions — 최근 게시물 캡션 일부(개수·길이 절단은 호출자 몫). 없으면 빈 리스트. */
    record ProfileCard(String username, String fullName, String category, String biography,
                       List<String> captions) {}

    /**
     * 2축 판정 결과 — beauty(뷰티 제품)·fnb(식품/음료 제품) 축을 독립 판정한다(스펙 2026-08-23 §2).
     * 축별 class는 모델 응답이 무효·누락이면 null — 호출자는 null 아닌 축만 적용한다(해당 축은
     * 미판정으로 남아 다음 실행 재시도). 파생 boolean은 각 enum 규칙에 위임.
     */
    record Verdict(String username, com.celfit.crawler.crawling.domain.BeautyClass beautyClass,
                   String reason, String basis,
                   com.celfit.crawler.crawling.domain.CategoryClass fnbClass,
                   String fnbReason, String fnbBasis) {
        public boolean beauty() {
            return beautyClass != null && beautyClass.beauty();
        }

        public boolean company() {
            return beautyClass != null && beautyClass.company();
        }
    }

    /** 실패(CLI 오류·타임아웃·파싱 불가)는 ApifyException — 호출자가 배치 단위로 격리한다. */
    List<Verdict> judge(List<ProfileCard> cards);
}
