package com.celfit.monitoring.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;

/** 리사이즈 데코레이터 계약 — 스펙 2026-09-02 §2·§5. */
class ImageResizerTest {

	/** 그라데이션 JPEG 생성 — 압축 여지가 있는 자연스러운 콘텐츠 근사. */
	private static byte[] jpeg(int w, int h, float quality) throws Exception {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				img.setRGB(x, y, (x % 256) << 16 | (y % 256) << 8 | ((x + y) % 256));
			}
		}
		return encode(img, quality);
	}

	private static byte[] encode(BufferedImage img, float quality) throws Exception {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
		ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(quality);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writer.setOutput(new MemoryCacheImageOutputStream(out));
		writer.write(null, new IIOImage(img, null, null), param);
		writer.dispose();
		return out.toByteArray();
	}

	@Test
	void 초과분은_720으로_축소되고_바이트가_줄어든다() throws Exception {
		byte[] original = jpeg(1440, 1080, 0.9f);
		byte[] shrunk = ImageResizer.shrink(original);
		assertThat(shrunk.length).isLessThan(original.length);
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(shrunk));
		assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isEqualTo(720);
		assertThat(decoded.getWidth()).isEqualTo(720); // 1440x1080 → 720x540 비율 유지
		assertThat(decoded.getHeight()).isEqualTo(540);
	}

	@Test
	void 이하는_원본_바이트를_그대로_반환한다() throws Exception {
		byte[] original = jpeg(640, 640, 0.9f);
		assertThat(ImageResizer.shrink(original)).isSameAs(original);
	}

	@Test
	void 디코드_불가_바이트는_원본을_그대로_반환한다() {
		byte[] notAnImage = "webp라고 치자".getBytes();
		assertThat(ImageResizer.shrink(notAnImage)).isSameAs(notAnImage);
	}

	@Test
	void 재인코딩이_더_크면_원본을_유지한다() throws Exception {
		// 초고압축 노이즈 원본 — q70 재인코딩이 원본보다 커지는 케이스 재현
		BufferedImage noise = new BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB);
		Random rnd = new Random(42);
		for (int y = 0; y < 900; y++) {
			for (int x = 0; x < 900; x++) {
				noise.setRGB(x, y, rnd.nextInt(0xFFFFFF));
			}
		}
		byte[] original = encode(noise, 0.05f);
		byte[] result = ImageResizer.shrink(original);
		// 720 축소 후에도 q70 노이즈가 q5 원본보다 크면 원본 유지가 계약이다.
		// (혹시 더 작아지면 축소본 채택도 계약상 정상 — 어느 쪽이든 결과가 원본보다 크면 안 된다)
		assertThat(result.length).isLessThanOrEqualTo(original.length);
	}

	@Test
	void wrap은_축소_시_contentType을_jpeg로_바꾸고_아니면_원본_Downloaded를_보존한다() throws Exception {
		byte[] big = jpeg(1440, 1440, 0.9f);
		ImageDownloader.Downloaded bigDownloaded = new ImageDownloader.Downloaded(big, "image/webp");
		ImageDownloader wrapped = ImageResizer.wrap(url -> bigDownloaded);
		ImageDownloader.Downloaded result = wrapped.fetch("http://x");
		assertThat(result.contentType()).isEqualTo("image/jpeg");
		assertThat(result.bytes().length).isLessThan(big.length);

		byte[] small = jpeg(320, 320, 0.9f);
		ImageDownloader.Downloaded smallDownloaded = new ImageDownloader.Downloaded(small, "image/webp");
		ImageDownloader wrappedSmall = ImageResizer.wrap(url -> smallDownloaded);
		assertThat(wrappedSmall.fetch("http://x")).isSameAs(smallDownloaded);
	}
}
