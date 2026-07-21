package com.celfit.analytics.llm;

import java.util.function.Consumer;

/**
 * Gemini Batch API 표면 — 백필 러너가 보는 최소 계약 (테스트 fake 용이).
 * 구현은 {@link GeminiHttpApi}(AI Studio)·{@link VertexHttpApi}(Vertex GCS).
 */
public interface GeminiBatchApi {

	/** File API 업로드 — JSONL 바이트를 올리고 files/NNN 이름을 돌려준다. */
	String uploadFile(byte[] jsonl, String displayName);

	/** 배치 잡 생성 — batches/NNN 이름을 돌려준다. */
	String createBatch(String model, String inputFileName, String displayName);

	/** 배치 잡 조회 — 응답 JSON 전체(state·결과 파일 탐색은 호출자). */
	String getBatch(String batchName);

	/**
	 * 결과 파일(JSONL) 스트리밍 다운로드 — 전체를 메모리에 올리지 않고 비어있지 않은 줄을
	 * 순서대로 소비자에 전달한다(줄 끝 개행 제외). 운영 실측 119MB+ 결과의 OOM 재발 방지 계약 —
	 * 구현은 본문 전체 String 적재 금지.
	 */
	void downloadResults(String fileName, Consumer<String> onLine);
}
