package com.celfit.analytics.llm;

/** Gemini Batch API 표면 — 백필 러너가 보는 최소 계약 (테스트 fake 용이). 구현은 {@link GeminiHttpApi}. */
public interface GeminiBatchApi {

	/** File API 업로드 — JSONL 바이트를 올리고 files/NNN 이름을 돌려준다. */
	String uploadFile(byte[] jsonl, String displayName);

	/** 배치 잡 생성 — batches/NNN 이름을 돌려준다. */
	String createBatch(String model, String inputFileName, String displayName);

	/** 배치 잡 조회 — 응답 JSON 전체(state·결과 파일 탐색은 호출자). */
	String getBatch(String batchName);

	/** 결과 파일 다운로드(JSONL). */
	String downloadFile(String fileName);
}
