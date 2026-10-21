package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 总耗料与恢复原证据的局部、有界读取器，不修改公共Jackson或原版JSON写法/SHA。
 * 原batch仍限定32Mi UTF-16；恢复每份原行仅复制一次、增加一层转义，独立预留128Mi。
 * 这只是完整证据容量合同，不是吞吐、内存或生产恢复可执行的验收结论。
 */
@Component
@RequiredArgsConstructor
public class MesProductionFeedEvidenceCodec {
    public static final int MAX_BATCH_DOCUMENT = 32 * 1024 * 1024;
    public static final int MAX_RESTORE_DOCUMENT = 128 * 1024 * 1024;
    private static final int MAX_ROOT_OVERHEAD = 64 * 1024;
    private static final int MAX_ROW_OVERHEAD = 1024;
    private final MesPhysicalTransformCodec writer;
    private final ObjectMapper batchReader = reader(MAX_BATCH_DOCUMENT);
    private final ObjectMapper restoreReader = reader(MAX_RESTORE_DOCUMENT);

    /** 原262固定32Mi协议；不被Jackson默认20,000,000字符的单字符串限制截断。 */
    public JsonNode batchDocument(String raw) {
        return object(raw, MAX_BATCH_DOCUMENT, batchReader);
    }

    /** 恢复原文读取仍严格拒绝重复字段、尾随内容、空对象类型和超量正文。 */
    public JsonNode restoreDocument(String raw) {
        return object(raw, MAX_RESTORE_DOCUMENT, restoreReader);
    }

    /**
     * 在任何必要写之前检查恢复全集容量，并用同一专用读取器读回完整生成原文。
     * 三类原行在已验证原32Mi batch各出现一次；新增字符串转义最多放大为64Mi。
     * 最多1000+5000+50000行，每行额外字段含空原文槽位最多1Ki，加根64Ki及数组分隔符，
     * 总上界小于128Mi。禁止嵌套整个原batch或给同一原行复制多个恢复成员。
     */
    public String freezeRestore(Map<String, Object> document) {
        var header = new LinkedHashMap<>(document);
        checkRows(header, "outputs", "originalOutputSnapshot", 1000);
        checkRows(header, "lines", "originalConsumptionSnapshot", 5000);
        checkRows(header, "shares", "originalShareSnapshot", 50000);
        require(writer.snapshot(header).length() <= MAX_ROOT_OVERHEAD, "恢复根固定字段超过完整容量合同，未恢复耗料");
        String raw = writer.snapshot(document);
        restoreDocument(raw);
        return raw;
    }

    /** 保留原文字段名与空字符串语法计算开销，不能遗漏56,000个槽位的字段名/引号。 */
    private void checkRows(Map<String, Object> header, String field, String originalField, int maxRows) {
        Object value = header.get(field);
        require(value instanceof List<?>, "恢复完整" + field + "必须是数组");
        var rows = (List<?>) value;
        require(rows.size() <= maxRows, "恢复完整" + field + "超量，禁止截断");
        for (Object row : rows) {
            require(row instanceof Map<?, ?>, "恢复固定行必须是完整对象");
            Map<Object, Object> fields = new LinkedHashMap<>((Map<?, ?>) row);
            require(fields.get(originalField) instanceof String, "恢复固定行缺原事实原文");
            fields.put(originalField, "");
            require(writer.snapshot(fields).length() <= MAX_ROW_OVERHEAD, "恢复固定行新增字段超过容量合同，未恢复耗料");
        }
        header.put(field, List.of());
    }

    /** 仅提高这两份已显式有界协议的单字符串上限；深度/数字约束保持Jackson默认值。 */
    private static ObjectMapper reader(int maxDocument) {
        var factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(maxDocument).build()).build();
        return new ObjectMapper(factory)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private JsonNode object(String raw, int maxLength, ObjectMapper json) {
        require(raw != null && !raw.isBlank() && raw.length() <= maxLength, "总耗料/恢复原文为空或超过完整容量合同");
        try {
            JsonNode result = json.readTree(raw);
            require(result != null && result.isObject(), "总耗料/恢复证据须为完整JSON对象");
            return result;
        } catch (CheckedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CheckedException("总耗料/恢复原文格式非法、字段重复、尾随内容或超读取容量");
        }
    }
}
