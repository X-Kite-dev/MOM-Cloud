package com.kitex.mom.mdm.dto;

import com.kitex.common.core.exception.CheckedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 游标按扫描到的源行推进，即使整页都是被语义核验排除的文本误报也不会漏扫后页。
 */
public final class MdmBomImpactPages {
    public static final int PAGE_SIZE = 200;

    private MdmBomImpactPages() {
    }

    public static void validate(MdmBomImpact.PageQuery q, Set<String> domains) {
        if (q == null || q.getBomIds() == null || q.getBomIds().isEmpty() || q.getBomIds().size() > 200
                || q.getBomIds().stream().anyMatch(id -> id == null || id <= 0)
                || q.getFactoryCode() == null || q.getFactoryCode().isBlank() || !domains.contains(q.getDomain())
                || (q.getAfterId() != null && q.getAfterId() < 0) || (q.getUpperId() != null && q.getUpperId() < 0)
                || (q.getAfterId() != null && q.getAfterId() > 0 && q.getUpperId() == null)
                || (q.getUpperId() != null && after(q) > q.getUpperId()))
            throw new CheckedException("BOM影响分页的工厂、版本范围、业务域或游标不合法");
    }

    public static long after(MdmBomImpact.PageQuery q) {
        return q.getAfterId() == null ? 0 : q.getAfterId();
    }

    public static <T> MdmBomImpact.Batch batch(MdmBomImpact.PageQuery q, long upper, List<T> raw,
                                               Function<T, Long> id, Function<T, MdmBomImpact.Row> convert) {
        List<MdmBomImpact.Row> rows = new ArrayList<>();
        int scanned = 0;
        long bytes = 0, cursor = after(q);
        for (T entity : raw.subList(0, Math.min(PAGE_SIZE, raw.size()))) {
            try {
                MdmBomImpact.Row row = convert.apply(entity);
                long size = row == null ? 0 : row.getEvidenceJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1024L;
                if (size > 4 * 1024 * 1024) throw new CheckedException("单条引用证据超过4MiB，请先核实来源容量");
                if (bytes + size > 4 * 1024 * 1024) break;
                bytes += size;
                if (row != null) rows.add(row);
                scanned++;
                cursor = id.apply(entity);
            } catch (RuntimeException e) {
                throw new CheckedException(q.getDomain() + "来源ID=" + id.apply(entity) + "核验失败：" + e.getMessage());
            }
        }
        return new MdmBomImpact.Batch().setDomain(q.getDomain()).setFactoryCode(q.getFactoryCode())
                .setUpperId(upper).setNextId(cursor)
                .setHasMore(raw.size() > scanned).setScannedCount(scanned).setRows(rows);
    }

    public static MdmBomImpact.Row row(MdmBomImpact.PageQuery q, Long id, String number, String status,
                                       String evidenceJson, String snapshot, Object... headerIds) {
        var match = MdmBomReferenceMatcher.match(q.getBomIds(), snapshot, headerIds);
        return match == null ? null : new MdmBomImpact.Row().setDomain(q.getDomain()).setId(id).setNumber(number)
                .setFactoryCode(q.getFactoryCode()).setStatus(status).setEvidenceJson(evidenceJson)
                .setMatchKind(match.kind()).setMatchedBomIds(match.bomIds());
    }
}
