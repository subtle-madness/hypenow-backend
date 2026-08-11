package com.celfit.analytics.analyze;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * 배치 잡별 사이드카(JSONL) 파일 경로 관리 — 제출 시 기록한 기준선 스냅샷을 수거 시점에
 * 복원하기 위한 저장소. 파싱 자체는 {@link GeminiBatchLines#readSidecar}를 그대로 쓰고,
 * 이 클래스는 "배치 이름 → 파일 경로" 매핑만 담당한다.
 *
 * <p>배치 이름을 파일명으로 안전하게 쓰기 위해 영숫자 외 문자는 '_'로 치환한다.
 * GeminiBackfillRunner의 고정 파일명(backfill-sidecar.jsonl)과 달리, 상시 배치는 하루에도
 * timely·late_backfill 두 배치가 동시에 pending일 수 있어 배치 이름별로 파일을 분리한다
 * (2026-08-11, Vertex 배치 전송 전환).
 */
final class BatchSidecarStore {

	private static final ObjectMapper OM = new ObjectMapper();

	private BatchSidecarStore() {
	}

	static void write(Path workDir, String batchName, String jsonl) {
		try {
			Files.createDirectories(workDir);
			Files.writeString(fileFor(workDir, batchName), jsonl);
		} catch (IOException e) {
			throw new IllegalStateException("배치 사이드카 저장 실패: " + batchName, e);
		}
	}

	static Map<String, Map<String, String>> read(Path workDir, String batchName) {
		return GeminiBatchLines.readSidecar(OM, fileFor(workDir, batchName));
	}

	/** 수거 완료 후 정리 — 실패해도 치명적이지 않다(잔존 파일은 무해, 다음 재기동 때도 그대로). */
	static void delete(Path workDir, String batchName) {
		try {
			Files.deleteIfExists(fileFor(workDir, batchName));
		} catch (IOException e) {
			// 정리 실패는 무시 — 사이드카 잔존은 디스크만 소모할 뿐 동작에 영향 없음
		}
	}

	private static Path fileFor(Path workDir, String batchName) {
		return workDir.resolve(sanitize(batchName) + "-sidecar.jsonl");
	}

	private static String sanitize(String batchName) {
		return batchName.replaceAll("[^a-zA-Z0-9]", "_");
	}
}
