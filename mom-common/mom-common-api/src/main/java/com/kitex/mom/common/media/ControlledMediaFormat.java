package com.kitex.mom.common.media;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 有界原件格式检查，不持有存储、业务版本、身份或终端解码能力。 */
public final class ControlledMediaFormat {

	public static final int MAX_BYTES = 20 * 1024 * 1024;
	private static final long MAX_IMAGE_PIXELS = 16_777_216L;

	private ControlledMediaFormat() {
	}

	public record Format(String mediaType, String extension, String kind) {
	}

	/** 保留原字节；检查格式不产生发布、批准或阅读证明。 */
	public static Format inspect(String fileName, String suppliedType, byte[] bytes) {
		String name = fileName == null ? "" : fileName.trim();
		require(!name.isEmpty() && name.length() <= 200 && !name.contains("/") && !name.contains("\\")
				&& !name.contains("..") && name.codePoints().noneMatch(Character::isISOControl),
				"文件名不能含路径或控制字符且不能超过200字符");
		require(bytes != null && bytes.length > 0 && bytes.length <= MAX_BYTES, "原件不能为空且不能超过20MiB");
		String lower = name.toLowerCase(Locale.ROOT);
		Format format;
		if (lower.endsWith(".pdf")) {
			format = new Format("application/pdf", "pdf", "DOCUMENT");
		} else if (lower.endsWith(".txt")) {
			format = new Format("text/plain", "txt", "DOCUMENT");
		} else if (lower.endsWith(".md")) {
			format = new Format("text/markdown", "md", "DOCUMENT");
		} else if (lower.endsWith(".png")) {
			format = new Format("image/png", "png", "IMAGE");
		} else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
			format = new Format("image/jpeg", "jpeg", "IMAGE");
		} else if (lower.endsWith(".mp4")) {
			format = new Format("video/mp4", "mp4", "VIDEO");
		} else {
			throw new IllegalArgumentException("仅支持PDF、UTF-8文本、Markdown、PNG、JPEG和MP4原件");
		}
		require(suppliedType == null || suppliedType.equals("application/octet-stream")
				|| suppliedType.split(";", 2)[0].equals(format.mediaType())
				|| format.mediaType().equals("text/markdown") && suppliedType.startsWith("text/plain"),
				"文件扩展名与媒体类型不一致");
		try {
			if ("IMAGE".equals(format.kind())) {
				image(bytes, format.mediaType());
			} else if ("VIDEO".equals(format.kind())) {
				new Movie(bytes).inspect();
			} else if ("application/pdf".equals(format.mediaType())) {
				String head = new String(bytes, 0, Math.min(8, bytes.length), StandardCharsets.US_ASCII);
				String tail = new String(bytes, Math.max(0, bytes.length - 1024),
						Math.min(1024, bytes.length), StandardCharsets.US_ASCII);
				require(head.startsWith("%PDF-") && tail.contains("%%EOF"), "PDF原件头或结束标记不完整");
			} else {
				String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
						.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
				require(!body.isBlank() && body.indexOf('\0') < 0, "文本原件为空或含二进制内容");
			}
		} catch (IOException ex) {
			throw new IllegalArgumentException("原件无法按声明格式完整读取", ex);
		}
		return format;
	}

	private static void image(byte[] bytes, String media) throws IOException {
		boolean png = "image/png".equals(media);
		if (png) {
			byte[] signature = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };
			require(bytes.length >= 20 && java.util.Arrays.equals(signature, java.util.Arrays.copyOf(bytes, 8))
					&& ascii(bytes, bytes.length - 8, 4).equals("IEND"), "PNG原件签名或结束块不完整");
		} else {
			require(bytes.length >= 4 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216
					&& (bytes[bytes.length - 2] & 255) == 255 && (bytes[bytes.length - 1] & 255) == 217,
					"JPEG原件签名或结束标记不完整");
		}
		try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
			var readers = ImageIO.getImageReaders(input);
			require(readers.hasNext(), "图片原件无法解码");
			var reader = readers.next();
			try {
				var warned = new java.util.concurrent.atomic.AtomicBoolean();
				// ImageIO可能容错补齐截断JPEG，解码器报告损坏时不能归档恢复后的部分画面。
				reader.addIIOReadWarningListener((source, warning) -> warned.set(true));
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				require(width > 0 && height > 0 && width <= 16384 && height <= 16384
						&& (long) width * height <= MAX_IMAGE_PIXELS, "图片尺寸超过允许范围");
				require(reader.read(0) != null, "图片原件无法完整解码");
				require(!warned.get(), "图片原件存在损坏或不完整的解码内容");
			} finally {
				reader.dispose();
			}
		}
	}

	/** 只核MP4容器边界及视频轨道声明，终端仍须实际加载后才能确认已阅。 */
	private static final class Movie {
		private final byte[] bytes;
		private int boxes;
		private int fileTypes;
		private int movies;
		private boolean video;
		private boolean data;

		private Movie(byte[] bytes) {
			this.bytes = bytes;
		}

		private void inspect() {
			walk(0, bytes.length, 0, "");
			require(fileTypes == 1 && movies == 1 && video && data, "MP4缺少唯一文件类型、电影元数据、视频轨道或媒体数据");
		}

		private void walk(int begin, int end, int depth, String path) {
			require(depth <= 8, "MP4容器层次过深");
			int offset = begin;
			while (offset < end) {
				require(++boxes <= 10000 && end - offset >= 8, "MP4容器盒数量超限或头部截断");
				long size = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4).getInt());
				String type = ascii(bytes, offset + 4, 4);
				int header = 8;
				if (size == 1) {
					require(end - offset >= 16, "MP4扩展盒头部截断");
					size = ByteBuffer.wrap(bytes, offset + 8, 8).getLong();
					header = 16;
				} else if (size == 0) {
					size = end - offset;
				}
				require(size >= header && size <= end - offset, "MP4容器盒长度越界");
				int payload = offset + header;
				int next = offset + (int) size;
				if (path.isEmpty() && type.equals("ftyp")) {
					fileTypes++;
					require(next - payload >= 8 && (next - payload) % 4 == 0, "MP4文件类型盒不完整");
					boolean supported = brand(payload);
					for (int position = payload + 8; position < next; position += 4) {
						supported |= brand(position);
					}
					require(supported, "MP4文件类型未受支持");
				} else if (path.isEmpty() && type.equals("moov")) {
					movies++;
					walk(payload, next, depth + 1, "moov");
				} else if (path.equals("moov") && type.equals("trak")
						|| path.equals("moov/trak") && type.equals("mdia")) {
					walk(payload, next, depth + 1, path + "/" + type);
				} else if (path.equals("moov/trak/mdia") && type.equals("hdlr")) {
					require(next - payload >= 24, "MP4轨道处理器盒不完整");
					video |= ascii(bytes, payload + 8, 4).equals("vide");
				} else if (path.isEmpty() && type.equals("mdat")) {
					data |= next > payload;
				}
				offset = next;
			}
		}

		private boolean brand(int offset) {
			return java.util.Set.of("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ")
					.contains(ascii(bytes, offset, 4));
		}
	}

	private static String ascii(byte[] bytes, int offset, int length) {
		return new String(bytes, offset, length, StandardCharsets.US_ASCII);
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalArgumentException(message);
		}
	}
}
