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
     * 3분류 판정 결과 — 비뷰티(beauty=false) / 뷰티 인플루언서(beauty=true, company=false) /
     * 뷰티 회사(beauty=true, company=true). 회사 계정은 명단 리스트업만 하고 수집·유사발굴에서 제외.
     */
    record Verdict(String username, boolean beauty, boolean company, String reason) {}

    /** 실패(CLI 오류·타임아웃·파싱 불가)는 ApifyException — 호출자가 배치 단위로 격리한다. */
    List<Verdict> judge(List<ProfileCard> cards);
}
