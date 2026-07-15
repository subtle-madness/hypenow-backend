package com.celfit.was.postdetail;

import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentMetricSnapshot;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 게시물 상세 모달 API — 계약 record 3종을 조회해 블록 응답으로 조립한다. */
@RestController
public class PostDetailController {

	/** 집계 기간 끝 날짜의 KST 하루가 끝나는 시각(다음날 0시) — as-of 선택 규칙(§7 2026-07-14). */
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final PostDetailRepository repository;
	private final PostDetailAssembler assembler;

	public PostDetailController(PostDetailRepository repository, PostDetailAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@GetMapping("/api/posts/{shortCode}")
	public PostDetailResponse postDetail(@PathVariable String shortCode,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
		Content content = repository.findContent(shortCode)
				.orElseThrow(() -> notFound(shortCode));
		Optional<AsOfMetrics> asOf = Optional.ofNullable(endDate).map(d -> {
			// UTC 정규화는 같은 순간의 표기 통일 — DB(timestamptz) 비교는 순간 기준이라 무관하고,
			// OffsetDateTime.equals()가 오프셋까지 비교하는 탓에 테스트 스텁 매칭·로그 표기가 어긋나는 것을 막는다.
			OffsetDateTime cutoff = d.plusDays(1).atStartOfDay(KST)
					.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
			ContentMetricSnapshot snapshot = repository.findSnapshotAsOf(shortCode, cutoff)
					// 그 시점 스냅샷이 없으면 그 기간 화면에 존재하지 않는 게시물 (목록 필터링과 동일 규칙)
					.orElseThrow(() -> notFound(shortCode));
			return new AsOfMetrics(snapshot, cutoff);
		});
		return assembler.toResponse(
				content,
				repository.findAccount(content.accountHandle()).orElse(null),
				repository.findComments(shortCode),
				repository.findAnalysis(shortCode),
				asOf);
	}

	private ResponseStatusException notFound(String shortCode) {
		// reason은 서버 로그용 — 프론트 계약은 상태코드 404
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "게시물을 찾을 수 없습니다: " + shortCode);
	}
}
