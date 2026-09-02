package com.celfit.monitoring.image;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 서빙 이미지 720px·q70 축소 데코레이터 (스펙 2026-09-02).
 *
 * <p>최장변 720px 초과분만 축소한다 — IG CDN 원본은 이미 고압축이라 동일 해상도 재인코딩은
 * 실측 +30% 역효과(스펙 §2). 결과가 원본보다 크거나 디코드 불가(webp 등)·예외면 원본을
 * 그대로 반환한다 — 리사이즈는 최적화이지 아카이브 성패 조건이 아니다.
 *
 * <p>analytics 모듈에 동일 클래스가 복제돼 있다 — 모듈 간 Java 공유는 계약 모듈만
 * 허용이라 {@code ImageDownloader} 전례를 따른다. 수정 시 양쪽을 함께 고칠 것.
 */
public final class ImageResizer {

	private static final Logger log = LoggerFactory.getLogger(ImageResizer.class);

	static final int MAX_EDGE = 720;
	static final float QUALITY = 0.70f;

	private ImageResizer() {
	}

	/** 다운로더 데코레이터 — 축소가 일어난 경우에만 contentType을 image/jpeg로 바꾼다. */
	public static ImageDownloader wrap(ImageDownloader delegate) {
		return url -> {
			ImageDownloader.Downloaded original = delegate.fetch(url);
			byte[] shrunk = shrink(original.bytes());
			return shrunk == original.bytes() ? original
					: new ImageDownloader.Downloaded(shrunk, "image/jpeg");
		};
	}

	/** 축소 시 새 JPEG 바이트, 아니면(이하·디코드 불가·역효과·예외) 원본 배열 그대로. */
	static byte[] shrink(byte[] original) {
		try {
			BufferedImage src = ImageIO.read(new ByteArrayInputStream(original));
			if (src == null) {
				return original; // webp 등 ImageIO 미지원 포맷 — 정상 폴백, 로그 없음
			}
			int edge = Math.max(src.getWidth(), src.getHeight());
			if (edge <= MAX_EDGE) {
				return original;
			}
			double scale = (double) MAX_EDGE / edge;
			int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
			int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
			BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = dst.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(src, 0, 0, w, h, null);
			g.dispose();
			byte[] jpeg = encodeJpeg(dst);
			return jpeg.length < original.length ? jpeg : original;
		} catch (Exception e) {
			log.warn("이미지 축소 실패 — 원본 유지: {}", e.toString());
			return original;
		}
	}

	private static byte[] encodeJpeg(BufferedImage img) throws Exception {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
		ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(QUALITY);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writer.setOutput(new MemoryCacheImageOutputStream(out));
		writer.write(null, new IIOImage(img, null, null), param);
		writer.dispose();
		return out.toByteArray();
	}
}
