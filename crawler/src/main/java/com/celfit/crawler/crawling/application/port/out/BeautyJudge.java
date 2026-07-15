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

    record Verdict(String username, boolean beauty, String reason) {}

    /** 실패(CLI 오류·타임아웃·파싱 불가)는 ApifyException — 호출자가 배치 단위로 격리한다. */
    List<Verdict> judge(List<ProfileCard> cards);
}
