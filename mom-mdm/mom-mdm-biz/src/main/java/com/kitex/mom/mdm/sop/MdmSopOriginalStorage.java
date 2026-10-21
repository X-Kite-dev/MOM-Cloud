package com.kitex.mom.mdm.sop;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.entity.MdmSopOriginalEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import static com.kitex.mom.mdm.sop.MdmSopSupport.*;

/**
 * 私有持久原件存储。未显式配置时拒绝原件操作，不默认落临时目录。
 * 文件名只用于显示，存储键由服务端随机生成；下载重新核真实字节而非相信元数据。
 */
@Component
public class MdmSopOriginalStorage {
    private static final Logger LOG = LoggerFactory.getLogger(MdmSopOriginalStorage.class);
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    private final String configuredRoot;

    public MdmSopOriginalStorage(@Value("${mom.sop.storage-root:}") String configuredRoot) {
        this.configuredRoot = configuredRoot;
    }

    /** 已校验上传内容，尚不产生业务批准证明。 */
    public record Upload(String name, String mediaType, byte[] bytes, String hash) { }

    public Upload inspect(MultipartFile file) {
        require(file != null && !file.isEmpty() && file.getSize() <= MAX_BYTES,
                "SOP原件不能为空且不能超过20MiB");
        try (var input = file.getInputStream()) {
            return inspect(file.getOriginalFilename(), file.getContentType(), input.readNBytes(MAX_BYTES + 1));
        } catch (IOException ex) { throw new CheckedException("SOP原件读取失败，请检查文件格式"); }
    }

    /** 上传与历史受控对象转入使用相同字节校验，不能因内部读取而绕过格式限制。 */
    public Upload inspect(String fileName, String suppliedType, byte[] bytes) {
        String name = text(fileName, 200, "文件名", true);
        require(!name.contains("/") && !name.contains("\\") && !name.contains("..")
                && name.codePoints().noneMatch(Character::isISOControl), "文件名不能含路径或控制字符");
        String lower = name.toLowerCase(Locale.ROOT);
        String media = lower.endsWith(".pdf") ? "application/pdf"
                : lower.endsWith(".md") ? "text/markdown" : lower.endsWith(".txt") ? "text/plain" : "";
        require(!media.isEmpty(), "仅支持PDF、UTF-8纯文本或Markdown原件");
        require(suppliedType == null || suppliedType.equals("application/octet-stream")
                || suppliedType.split(";", 2)[0].equals(media)
                || media.equals("text/markdown") && suppliedType.startsWith("text/plain"), "文件扩展名与媒体类型不一致");
        try {
            require(bytes != null && bytes.length > 0 && bytes.length <= MAX_BYTES, "原件字节数不符合限制");
            if (media.equals("application/pdf")) {
                String head = new String(bytes, 0, Math.min(8, bytes.length), StandardCharsets.US_ASCII);
                String tail = new String(bytes, Math.max(0, bytes.length - 1024), Math.min(1024, bytes.length), StandardCharsets.US_ASCII);
                require(head.startsWith("%PDF-") && tail.contains("%%EOF"), "PDF原件头或结束标记不完整");
            } else {
                String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                require(!body.isBlank() && body.indexOf('\0') < 0, "文本原件为空或含二进制内容");
            }
            return new Upload(name, media, bytes, digest(bytes));
        } catch (IOException ex) { throw new CheckedException("SOP原件读取失败，请检查文件格式"); }
    }

    public String store(Upload upload) {
        Path root = root();
        String key = UUID.randomUUID() + ".bin";
        boolean created = false;
        try {
            var attributes = Files.getFileStore(root).supportsFileAttributeView("posix")
                    ? new java.nio.file.attribute.FileAttribute<?>[] {
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")) }
                    : new java.nio.file.attribute.FileAttribute<?>[0];
            // 权限与CREATE_NEW原子创建，避免先写默认可读文件再chmod的暴露窗口。
            try (var channel = Files.newByteChannel(root.resolve(key),
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes)) {
                created = true;
                ByteBuffer bytes = ByteBuffer.wrap(upload.bytes());
                while (bytes.hasRemaining()) channel.write(bytes);
            }
            return key;
        } catch (IOException ex) {
            if (created) discardUncommitted(key);
            throw new CheckedException("SOP私有原件存储不可写，未产生正式来源");
        }
    }

    /** 仅由本次失败写入/明确回滚回调调用，不提供删除已提交原件的业务入口。 */
    public void discardUncommitted(String key) {
        if (key == null || !key.matches("[0-9a-f-]{36}\\.bin")) return;
        try {
            Path file = root().resolve(key);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(file);
        } catch (Exception ex) {
            LOG.warn("未提交SOP临时对象清理失败，请由存储管理员核对孤儿对象标识 {}", key);
        }
    }

    public byte[] readVerified(MdmSopOriginalEntity original) {
        require(original.getStorageKey() != null && original.getStorageKey().matches("[0-9a-f-]{36}\\.bin"),
                "SOP原件存储标识异常");
        Path file = root().resolve(original.getStorageKey());
        try {
            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file), "SOP原件不存在或不是私有普通文件");
            long size = Files.size(file);
            require(size > 0 && size <= MAX_BYTES && original.getByteSize() != null
                    && size == original.getByteSize(), "SOP原件字节数已变化，禁止引用");
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            require(bytes.length == size && digest(bytes).equals(original.getSha256()), "SOP原件校验失败，禁止引用或下载");
            return bytes;
        } catch (IOException ex) { throw new CheckedException("SOP原件不可读取，不能产生有效来源证明"); }
    }

    private Path root() {
        require(configuredRoot != null && !configuredRoot.isBlank(),
                "尚未配置MDM私有持久原件目录mom.sop.storage-root，请在Nacos配置后使用正式SOP");
        Path root = Path.of(configuredRoot).normalize();
        require(root.isAbsolute() && root.getParent() != null, "SOP原件目录必须是明确的绝对业务目录");
        for (Path part = root; part != null; part = part.getParent()) {
            require(!Files.isSymbolicLink(part), "SOP原件目录不能经过符号链接");
        }
        try {
            Files.createDirectories(root);
            require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "SOP原件目录不可用");
            return root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) { throw new CheckedException("SOP原件目录不可用，请检查持久卷权限"); }
    }
}
