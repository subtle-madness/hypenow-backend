package com.celfit.instagram.source;

import java.util.List;

public record HashtagPage(List<HashtagPost> posts, String nextPageId) {}
