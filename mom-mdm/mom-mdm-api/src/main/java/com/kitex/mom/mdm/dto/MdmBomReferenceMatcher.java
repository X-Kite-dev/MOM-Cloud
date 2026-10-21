package com.kitex.mom.mdm.dto;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;

import java.math.BigDecimal;
import java.util.*;

/**
 * 只识别BOM语义字段，不把备注、数量、物料ID或字符串前缀当成BOM版本引用。
 */
public final class MdmBomReferenceMatcher {
    private MdmBomReferenceMatcher() {
    }

    public static Match match(List<Long> ids, String snapshot, Object... headerIds) {
        Set<String> expected = new HashSet<>();
        ids.forEach(id -> expected.add(id.toString()));
        SortedSet<String> found = new TreeSet<>();
        for (Object id : headerIds) {
            String value = id(id);
            if (expected.contains(value)) found.add(value);
        }
        if (!found.isEmpty()) return new Match("HEADER", new ArrayList<>(found));
        if (snapshot == null || snapshot.isBlank()) return null;
        boolean candidate = expected.stream().anyMatch(snapshot::contains);
        if (!candidate) return null;
        if (snapshot.length() > 2_000_000) throw new CheckedException("BOM快照超过单条核验容量，请先治理原始证据");
        Object root;
        String text = snapshot.trim();
        if (!(text.startsWith("{") && text.endsWith("}")) && !(text.startsWith("[") && text.endsWith("]")))
            throw new CheckedException("候选BOM快照不是结构化JSON，不得当作无引用跳过");
        try {
            root = JSONUtil.parse(text);
        } catch (RuntimeException e) {
            throw new CheckedException("候选BOM快照解析失败，请核实原始数据");
        }
        boolean[] known = {false};
        walk(root, expected, found, known, 0, new int[]{0});
        if (!known[0]) throw new CheckedException("候选快照缺少可识别的BOM语义字段，不能把未知格式视为无影响");
        return found.isEmpty() ? null : new Match("SNAPSHOT", new ArrayList<>(found));
    }

    private static void walk(Object value, Set<String> expected, Set<String> found, boolean[] known, int depth, int[] nodes) {
        if (depth > 80 || ++nodes[0] > 100_000) throw new CheckedException("BOM快照深度或节点数异常，不能截断核验");
        if (value instanceof Map<?, ?> object) {
            for (var entry : object.entrySet()) {
                String key = entry.getKey().toString();
                boolean reference = Set.of("bomId", "subitemBomId", "bom_id", "subitem_bom_id").contains(key)
                        || ("id".equals(key) && object.containsKey("productCode") && object.containsKey("version")
                        && (object.containsKey("mdmBomChildList") || object.containsKey("isDefault") || object.containsKey("releaseState")));
                if (reference) {
                    known[0] = true;
                    String id = id(entry.getValue());
                    if (entry.getValue() != null && !"null".equals(entry.getValue().toString()) && id == null)
                        throw new CheckedException("候选快照的BOM版本ID字段格式不正确，不能忽略后继续审批");
                    if (expected.contains(id)) found.add(id);
                }
                if (entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof Collection<?>)
                    walk(entry.getValue(), expected, found, known, depth + 1, nodes);
            }
        } else if (value instanceof Collection<?> values) {
            for (Object item : values) walk(item, expected, found, known, depth + 1, nodes);
        }
    }

    private static String id(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString().trim()).toBigIntegerExact().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record Match(String kind, List<String> bomIds) {
    }
}
