package com.celfit.instagram.source;

import java.util.List;

/** 해시태그 recent 스트림 게시물 + 사진 태그된 계정 목록(소문자 정규화). */
public record HashtagPost(PostInfo post, List<String> taggedUsernames) {}
