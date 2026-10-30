package com.kitex.mom.mdm.sop;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.controller.MdmFormalSopController;
import com.kitex.mom.mdm.entity.MdmSopOriginalEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class MdmSopMediaStorageTest {

    private static final int TWENTY_MIB = 20 * 1024 * 1024;

    // ISO BMFF: ftyp(isom/mp42), moov/trak/mdia/hdlr(vide), nonempty mdat.
    // This fixture proves container acceptance; it does not claim browser codec compatibility.
    private static final byte[] VIDEO_CONTAINER = HexFormat.of().parseHex(
            "000000146674797069736f6d000000006d703432"
                    + "000000386d6f6f76000000307472616b000000286d6469610000002068646c72"
                    + "000000000000000076696465000000000000000000000000"
                    + "000000096d64617465");

    @TempDir
    Path directory;

    @BeforeEach
    void resolvePrivateTempDirectory() throws Exception {
        // macOS may expose its temporary directory through a symbolic-link ancestor.
        directory = directory.toRealPath();
    }

    @Test
    void pngUploadKeepsCanonicalTypeOriginalBytesAndPrivateRoundTrip() throws Exception {
        byte[] bytes = image("png");
        var upload = storage().inspect("  装配步骤.PNG  ", "image/png", bytes);

        assertEquals("装配步骤.PNG", upload.name());
        assertEquals("image/png", upload.mediaType());
        assertSame(bytes, upload.bytes());
        assertRoundTrip(upload);
    }

    @Test
    void bothJpegExtensionsUseCanonicalTypeAndRoundTrip() throws Exception {
        byte[] bytes = image("jpeg");

        for (String name : new String[]{"步骤.JPG", "步骤.jpeg"}) {
            var upload = storage().inspect(name, "application/octet-stream", bytes);

            assertEquals("image/jpeg", upload.mediaType());
            assertRoundTrip(upload);
        }
    }

    @Test
    void mp4ContainerKeepsVideoTypeAndVerifiedBytes() throws Exception {
        var upload = storage().inspect("演示.mp4", "video/mp4", VIDEO_CONTAINER);

        assertEquals("video/mp4", upload.mediaType());
        assertRoundTrip(upload);
    }

    @Test
    void legacyPdfUtf8TextAndMarkdownStillRoundTrip() throws Exception {
        var pdf = storage().inspect("作业.pdf", "application/pdf",
                "%PDF-1.7\n1 0 obj\n%%EOF".getBytes(StandardCharsets.US_ASCII));
        var text = storage().inspect("作业.txt", "text/plain; charset=UTF-8", "工序安全说明".getBytes(StandardCharsets.UTF_8));
        var markdown = storage().inspect("作业.md", "text/plain; charset=UTF-8",
                "# 操作\n按图装配".getBytes(StandardCharsets.UTF_8));

        assertEquals("application/pdf", pdf.mediaType());
        assertEquals("text/plain", text.mediaType());
        assertEquals("text/markdown", markdown.mediaType());
        assertRoundTrip(pdf);
        assertRoundTrip(text);
        assertRoundTrip(markdown);
    }

    @Test
    void multipartUploadUsesTheSameContentChecks() throws Exception {
        byte[] bytes = image("png");
        var file = new MockMultipartFile("file", "步骤.png", "image/png", bytes);

        var upload = storage().inspect(file);

        assertEquals("image/png", upload.mediaType());
        assertRoundTrip(upload);
        assertThrows(CheckedException.class, () -> storage().inspect(
                new MockMultipartFile("file", "步骤.png", "image/png", "not an image".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void wrongExtensionOrSuppliedMimeCannotDisguiseMedia() throws Exception {
        byte[] png = image("png");

        assertThrows(CheckedException.class, () -> storage().inspect("步骤.png", "image/jpeg", png));
        assertThrows(CheckedException.class, () -> storage().inspect("步骤.jpeg", "image/jpeg", png));
        assertThrows(CheckedException.class, () -> storage().inspect("步骤.mp4", "video/mp4", png));
        assertThrows(CheckedException.class, () -> storage().inspect("步骤.png", "text/html", png));
        assertThrows(CheckedException.class, () -> storage().inspect("步骤.svg", "image/svg+xml", png));
    }

    @Test
    void damagedMediaRaisesBusinessExceptionInsteadOfLeakingFormatException() throws Exception {
        byte[] png = image("png");
        byte[] jpeg = image("jpeg");

        var error = assertThrows(CheckedException.class,
                () -> storage().inspect("步骤.png", "image/png", Arrays.copyOf(png, png.length - 4)));
        assertTrue(error.getMessage().contains("SOP"));
        assertThrows(CheckedException.class,
                () -> storage().inspect("步骤.jpeg", "image/jpeg", Arrays.copyOf(jpeg, jpeg.length - 2)));
        assertThrows(CheckedException.class, () -> storage().inspect("步骤.mp4", "video/mp4",
                Arrays.copyOf(VIDEO_CONTAINER, VIDEO_CONTAINER.length - 1)));
    }

    @Test
    void legacyDocumentValidationStillRejectsInvalidContent() {
        assertThrows(CheckedException.class, () -> storage().inspect("步骤.pdf", "application/pdf",
                "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(CheckedException.class, () -> storage().inspect("步骤.pdf", "application/pdf",
                "not-pdf\n%%EOF".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(CheckedException.class, () -> storage().inspect("步骤.txt", "text/plain",
                new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(CheckedException.class, () -> storage().inspect("步骤.md", null, new byte[]{'a', 0}));
        assertThrows(CheckedException.class, () -> storage().inspect("步骤.txt", null,
                " \n\t".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void emptyAndOverLimitBytesAreRejectedBeforeStorage() {
        assertThrows(CheckedException.class, () -> storage().inspect("步骤.txt", null, null));
        assertThrows(CheckedException.class, () -> storage().inspect("步骤.txt", null, new byte[0]));
        assertThrows(CheckedException.class, () -> storage().inspect("步骤.txt", null, new byte[TWENTY_MIB + 1]));
        assertThrows(CheckedException.class, () -> storage().inspect(
                new MockMultipartFile("file", "步骤.txt", "text/plain", new byte[TWENTY_MIB + 1])));
    }

    @Test
    void exactTwentyMibOriginalStillRoundTrips() throws Exception {
        byte[] bytes = new byte[TWENTY_MIB];
        Arrays.fill(bytes, (byte) 'a');

        var upload = storage().inspect("步骤.txt", "text/plain", bytes);

        assertRoundTrip(upload);
    }

    @Test
    void unsafeClientNamesCannotBecomeStoragePaths() throws Exception {
        byte[] bytes = image("png");

        for (String name : new String[]{"../步骤.png", "dir/步骤.png", "dir\\步骤.png", "步\n骤.png"}) {
            assertThrows(CheckedException.class, () -> storage().inspect(name, "image/png", bytes));
        }
    }

    @Test
    void repeatedStoresCreateIndependentPrivateObjects() throws Exception {
        var upload = storage().inspect("步骤.png", "image/png", image("png"));

        String first = storage().store(upload);
        String second = storage().store(upload);

        assertNotEquals(first, second);
        assertArrayEquals(upload.bytes(), Files.readAllBytes(directory.resolve(first)));
        assertArrayEquals(upload.bytes(), Files.readAllBytes(directory.resolve(second)));
    }

    @Test
    void sameSizeTamperingInvalidatesOriginalSha() throws Exception {
        var upload = storage().inspect("步骤.png", "image/png", image("png"));
        var original = original(storage().store(upload), upload);
        byte[] changed = upload.bytes().clone();
        changed[changed.length / 2] ^= 1;
        Files.write(directory.resolve(original.getStorageKey()), changed);

        assertThrows(CheckedException.class, () -> storage().readVerified(original));
    }

    @Test
    void changedSizeInvalidatesOriginalBeforeDownload() throws Exception {
        var upload = storage().inspect("步骤.mp4", "video/mp4", VIDEO_CONTAINER);
        var original = original(storage().store(upload), upload);
        Files.write(directory.resolve(original.getStorageKey()),
                Arrays.copyOf(upload.bytes(), upload.bytes().length - 1));

        assertThrows(CheckedException.class, () -> storage().readVerified(original));
    }

    @Test
    void unconfiguredStorageAndForeignKeysRemainUnavailable() throws Exception {
        var upload = storage().inspect("步骤.txt", "text/plain", "操作说明".getBytes(StandardCharsets.UTF_8));

        assertThrows(CheckedException.class, () -> new MdmSopOriginalStorage("").store(upload));
        assertThrows(CheckedException.class, () -> storage().readVerified(original("../步骤.txt", upload)));
    }

    @Test
    void mediaDownloadKeepsAttachmentNoStoreAndNosniffHeaders() throws Exception {
        var upload = storage().inspect("装配步骤.png", "image/png", image("png"));
        byte[] verified = storage().readVerified(original(storage().store(upload), upload));

        var response = MdmFormalSopController.download(new MdmFormalSopSourceService.OriginalContent(
                upload.name(), upload.mediaType(), verified));

        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getContentDisposition().isAttachment());
        assertEquals("装配步骤.png", response.getHeaders().getContentDisposition().getFilename());
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertArrayEquals(upload.bytes(), response.getBody());
    }

    private MdmSopOriginalStorage storage() {
        return new MdmSopOriginalStorage(directory.toString());
    }

    private void assertRoundTrip(MdmSopOriginalStorage.Upload upload) throws Exception {
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(upload.bytes()));

        String key = storage().store(upload);

        assertEquals(expectedHash, upload.hash());
        assertTrue(key.matches("[0-9a-f-]{36}\\.bin"));
        Path file = directory.resolve(key);
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.isSymbolicLink(file));
        assertArrayEquals(upload.bytes(), storage().readVerified(original(key, upload)));
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
    }

    private MdmSopOriginalEntity original(String key, MdmSopOriginalStorage.Upload upload) {
        var original = new MdmSopOriginalEntity();
        original.setFileName(upload.name());
        original.setMediaType(upload.mediaType());
        original.setSha256(upload.hash());
        original.setByteSize((long) upload.bytes().length);
        original.setStorageKey(key);
        return original;
    }

    private byte[] image(String format) throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x112233);
        image.setRGB(1, 1, 0x445566);
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output));
        return output.toByteArray();
    }
}
