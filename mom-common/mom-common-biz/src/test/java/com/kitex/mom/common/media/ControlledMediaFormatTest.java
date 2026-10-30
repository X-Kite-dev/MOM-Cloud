package com.kitex.mom.common.media;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 格式基础验证原文档与实际编码图片；MP4只证明有界容器，不证明终端支持编码。 */
class ControlledMediaFormatTest {

	@Test
	void legacyDocumentsKeepTheirCanonicalMediaTypes() {
		assertEquals("application/pdf", inspect("a.pdf", "%PDF-1.7\n%%EOF").mediaType());
		assertEquals("text/plain", inspect("a.TXT", "工序要求").mediaType());
		assertEquals("text/markdown", ControlledMediaFormat.inspect("a.md", "text/plain; charset=utf-8",
				"# 工序".getBytes(StandardCharsets.UTF_8)).mediaType());
	}

	@Test
	void encodedPngAndJpegImagesAreRecognized() throws Exception {
		assertEquals("IMAGE", ControlledMediaFormat.inspect("a.png", "image/png", image("png")).kind());
		assertEquals("jpeg", ControlledMediaFormat.inspect("a.jpg", "image/jpeg", image("jpeg")).extension());
		assertEquals("image/jpeg", ControlledMediaFormat.inspect("a.jpeg", null, image("jpeg")).mediaType());
	}

	@Test
	void boundedVideoContainerHasVideoTrackAndMediaData() {
		var result = ControlledMediaFormat.inspect("a.mp4", "video/mp4", movie("vide", false));

		assertEquals("video/mp4", result.mediaType());
		assertEquals("VIDEO", result.kind());
		assertEquals("mp4", result.extension());
	}

	@Test
	void extendedAndZeroSizedFinalDataBoxesAreBounded() {
		assertEquals("VIDEO", ControlledMediaFormat.inspect("a.mp4", null, movie("vide", true)).kind());
		byte[] movie = movie("vide", false);
		ByteBuffer.wrap(movie, movie.length - 10, 4).putInt(0);
		assertEquals("VIDEO", ControlledMediaFormat.inspect("a.mp4", null, movie).kind());
	}

	@Test
	void audioOnlyContainerIsNotAcceptedAsSopVideo() {
		assertThrows(IllegalArgumentException.class, () -> ControlledMediaFormat.inspect("a.mp4", null, movie("soun", false)));
	}

	@Test
	void extensionMimeAndContentMustAgree() throws Exception {
		byte[] png = image("png");

		assertThrows(IllegalArgumentException.class, () -> ControlledMediaFormat.inspect("a.png", "video/mp4", png));
		assertThrows(IllegalArgumentException.class, () -> ControlledMediaFormat.inspect("a.jpg", "image/jpeg", png));
		assertThrows(IllegalArgumentException.class, () -> ControlledMediaFormat.inspect("a.mp4", null, png));
	}

	@Test
	void activeMarkupAndUnknownTypesAreNotMediaFormats() {
		assertThrows(IllegalArgumentException.class, () -> inspect("a.svg", "<svg/>"));
		assertThrows(IllegalArgumentException.class, () -> inspect("a.html", "<script>alert(1)</script>"));
		assertThrows(IllegalArgumentException.class, () -> inspect("a.exe", "MZ"));
	}

	@Test
	void emptyOversizedAndUnsafeNamesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> ControlledMediaFormat.inspect("a.txt", null, new byte[0]));
		assertThrows(IllegalArgumentException.class, () -> ControlledMediaFormat.inspect("a.txt", null,
				new byte[ControlledMediaFormat.MAX_BYTES + 1]));
		assertThrows(IllegalArgumentException.class, () -> inspect("../a.txt", "hello"));
		assertThrows(IllegalArgumentException.class, () -> inspect("a\n.txt", "hello"));
	}

	@Test
	void malformedUtf8BinaryAndTruncatedPdfRemainRejected() {
		assertThrows(IllegalArgumentException.class, () -> ControlledMediaFormat.inspect("a.txt", null, new byte[] { (byte) 0xff }));
		assertThrows(IllegalArgumentException.class, () -> inspect("a.txt", "\0binary"));
		assertThrows(IllegalArgumentException.class, () -> inspect("a.md", "   "));
		assertThrows(IllegalArgumentException.class, () -> inspect("a.pdf", "%PDF-1.7"));
	}

	@Test
	void truncatedImagesAndMovieBoxesCannotPassByHeaderAlone() throws Exception {
		byte[] png = image("png");
		byte[] brokenMovie = movie("vide", false);
		ByteBuffer.wrap(brokenMovie, 0, 4).putInt(Integer.MAX_VALUE);

		assertThrows(IllegalArgumentException.class, () -> ControlledMediaFormat.inspect("a.png", null,
				java.util.Arrays.copyOf(png, 16)));
		assertThrows(IllegalArgumentException.class, () -> ControlledMediaFormat.inspect("a.mp4", null, brokenMovie));
		assertThrows(IllegalArgumentException.class, () -> ControlledMediaFormat.inspect("a.mp4", null,
				box("ftyp", "isom0000mp42".getBytes(StandardCharsets.US_ASCII))));
	}

	@Test
	void emptyDataAndDuplicateMovieHeadersAreRejected() {
		byte[] movie = movie("vide", false);

		assertThrows(IllegalArgumentException.class, () -> ControlledMediaFormat.inspect("a.mp4", null,
				concat(movie, box("ftyp", "isom0000mp42".getBytes(StandardCharsets.US_ASCII)))));
		assertThrows(IllegalArgumentException.class, () -> ControlledMediaFormat.inspect("a.mp4", null,
				java.util.Arrays.copyOf(movie, movie.length - 2)));
	}

	@Test
	void excessivelyManyBoxesAreRejectedWithoutUnboundedTraversal() {
		var out = new ByteArrayOutputStream();
		for (int i = 0; i < 10_001; i++) {
			out.writeBytes(box("free", new byte[0]));
		}

		assertThrows(IllegalArgumentException.class, () -> ControlledMediaFormat.inspect("a.mp4", null, out.toByteArray()));
	}

	@Test
	void imageDimensionsAreCheckedBeforeAllocatingDecodedPixels() throws Exception {
		byte[] png = image("png");
		ByteBuffer.wrap(png, 16, 8).putInt(8192).putInt(8192);
		var crc = new java.util.zip.CRC32();
		crc.update(png, 12, 17);
		ByteBuffer.wrap(png, 29, 4).putInt((int) crc.getValue());

		var error = assertThrows(IllegalArgumentException.class,
				() -> ControlledMediaFormat.inspect("a.png", null, png));
		org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("尺寸"));
	}

	@Test
	void truncatedJpegEntropyCannotPassWithAnAppendedEndMarker() throws Exception {
		var pixels = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
		var random = new java.util.Random(42);
		for (int y = 0; y < pixels.getHeight(); y++) {
			for (int x = 0; x < pixels.getWidth(); x++) {
				pixels.setRGB(x, y, random.nextInt());
			}
		}
		var out = new ByteArrayOutputStream();
		ImageIO.write(pixels, "jpeg", out);
		byte[] original = out.toByteArray();
		byte[] truncated = java.util.Arrays.copyOf(original, original.length / 2 + 2);
		truncated[truncated.length - 2] = (byte) 255;
		truncated[truncated.length - 1] = (byte) 217;

		assertEquals("IMAGE", ControlledMediaFormat.inspect("original.jpg", "image/jpeg", original).kind());
		assertThrows(IllegalArgumentException.class,
				() -> ControlledMediaFormat.inspect("truncated.jpg", "image/jpeg", truncated));
	}

	private ControlledMediaFormat.Format inspect(String name, String body) {
		return ControlledMediaFormat.inspect(name, null, body.getBytes(StandardCharsets.UTF_8));
	}

	private byte[] image(String format) throws Exception {
		var out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, out);
		return out.toByteArray();
	}

	private byte[] movie(String handler, boolean extended) {
		byte[] type = concat("isom".getBytes(StandardCharsets.US_ASCII), new byte[4], "mp42".getBytes(StandardCharsets.US_ASCII));
		byte[] metadata = new byte[25];
		System.arraycopy(handler.getBytes(StandardCharsets.US_ASCII), 0, metadata, 8, 4);
		byte[] data = extended ? ByteBuffer.allocate(18).putInt(1).put("mdat".getBytes(StandardCharsets.US_ASCII))
				.putLong(18).put(new byte[] { 1, 2 }).array() : box("mdat", new byte[] { 1, 2 });
		return concat(box("ftyp", type), box("moov", box("trak", box("mdia", box("hdlr", metadata)))), data);
	}

	private byte[] box(String type, byte[] body) {
		return ByteBuffer.allocate(8 + body.length).putInt(8 + body.length)
				.put(type.getBytes(StandardCharsets.US_ASCII)).put(body).array();
	}

	private byte[] concat(byte[]... parts) {
		var out = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			out.writeBytes(part);
		}
		return out.toByteArray();
	}
}
