package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesWipResidenceQuery;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.entity.MesWipTimeoutHandleEntity;
import com.kitex.mom.mes.mapper.MesWarehouseLiveStockMapper;
import com.kitex.mom.mes.mapper.MesWipResidenceMapper;
import com.kitex.mom.mes.mapper.MesWipTimeoutHandleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@PreAuthorize("@pms.hasPermission('mes_wipResidence_view')")
public class MesWipResidenceService {
    private final MesActualFactoryAccessService access;
    private final MesWipResidenceMapper mapper;
    private final MesWarehouseLiveStockMapper stocks;
    private final MesWipTimeoutHandleMapper events;

    private static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> view(MesWipResidenceQuery query) {
        String factory = access.require("read");
        normalize(query);
        LocalDateTime now = LocalDateTime.now();
        var population = mapper.snapshot(query, TenantContextHolder.getTenantId(), factory, now);
        require(population.size() <= 5000, "筛选范围超过5000条在制库存，请先按物料/工单/工序/工位缩小范围；不提供截断的总量");
        var rows = population.stream().filter(r -> matches(r, query.getFocus())).collect(Collectors.toCollection(ArrayList::new));
        rows.sort(Comparator.<Map<String, Object>>comparingInt(this::priority)
                .thenComparing(r -> number(r, "residenceSeconds"), Comparator.reverseOrder()).thenComparing(r -> String.valueOf(r.get("stockId"))));
        var ages = rows.stream().filter(r -> "VALID".equals(r.get("timeState"))).map(r -> number(r, "residenceSeconds")).sorted().toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stockLines", rows.size());
        summary.put("timeUnknownLines", rows.stream().filter(r -> !"VALID".equals(r.get("timeState"))).count());
        summary.put("sourceGapLines", rows.stream().filter(r -> !"MATCHED".equals(r.get("sourceState"))).count());
        summary.put("nodeGapLines", rows.stream().filter(r -> !verifiedNodeSource(r)).count());
        summary.put("timeoutLines", rows.stream().filter(r -> number(r, "activeTimeouts") > 0).count());
        summary.put("promiseDueLines", rows.stream().filter(r -> number(r, "promiseDueCount") > 0).count());
        summary.put("observedP50Seconds", ages.isEmpty() ? null : ages.get((int) Math.ceil(ages.size() * 0.5) - 1));
        summary.put("observedP95Seconds", ages.isEmpty() ? null : ages.get((int) Math.ceil(ages.size() * 0.95) - 1));
        summary.put("ageBands", List.of(
                band(rows, "0–2小时", 0, 7200), band(rows, "2–8小时", 7200, 28800), band(rows, "8–24小时", 28800, 86400), band(rows, "24小时及以上", 86400, Long.MAX_VALUE)));
        // 仅同物料、同单位相加；来源缺口逐库存行保留，PROCESS也不能将缺口合并为有效来源。
        Map<List<String>, Map<String, Object>> groups = new LinkedHashMap<>();
        for (var row : rows) {
            var key = groupKey(row, query.getGroupBy());
            var group = groups.computeIfAbsent(key, k -> {
                var g = new LinkedHashMap<String, Object>();
                g.put("groupBy", query.getGroupBy());
                g.put("worksiteCode", row.get("sourceWorksiteCode"));
                g.put("routeCode", row.get("routeCode"));
                g.put("matCode", row.get("matCode"));
                g.put("unit", row.get("unit"));
                g.put("nodeState", row.get("nodeState"));
                g.put("sourceState", row.get("sourceState"));
                g.put("stockId", verifiedSource(row) ? null : row.get("stockId"));
                g.put("processRouteCode", "PROCESS".equals(query.getGroupBy()) ? null : row.get("processRouteCode"));
                g.put("sourceRouteNodeCode", "PROCESS".equals(query.getGroupBy()) ? null : row.get("sourceRouteNodeCode"));
                g.put("produceTaskCode", verifiedNodeSource(row) ? row.get("produceTaskCode") : null);
                g.put("stockLines", 0L);
                g.put("quantity", value(row, "matCode").isBlank() || value(row, "unit").isBlank() ? null : BigDecimal.ZERO);
                g.put("maxResidenceSeconds", null);
                return g;
            });
            // 多实际任务或新旧合同汇总后，不把首行身份当成整个分组的身份。
            if (!Objects.equals(group.get("produceTaskCode"), row.get("produceTaskCode")))
                group.put("produceTaskCode", null);
            if (!Objects.equals(group.get("nodeState"), row.get("nodeState"))) group.put("nodeState", null);
            group.put("stockLines", number(group, "stockLines") + 1);
            if (group.get("quantity") != null)
                group.put("quantity", ((BigDecimal) group.get("quantity")).add(new BigDecimal(String.valueOf(row.get("quantity")))));
            if ("VALID".equals(row.get("timeState")))
                group.put("maxResidenceSeconds", Math.max(number(group, "maxResidenceSeconds"), number(row, "residenceSeconds")));
        }
        var rankedGroups = new ArrayList<>(groups.values());
        rankedGroups.sort(Comparator.<Map<String, Object>>comparingLong(g -> number(g, "maxResidenceSeconds")).reversed());
        rankedGroups.forEach(g -> {
            if (g.get("quantity") instanceof BigDecimal quantity) g.put("quantity", quantity.toPlainString());
        });
        int from = (query.getCurrent() - 1) * query.getSize(), to = Math.min(from + query.getSize(), rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("observedAt", now);
        result.put("factoryCode", factory);
        result.put("summary", summary);
        result.put("groupBy", query.getGroupBy());
        result.put("groupNotice", "PROCESS".equals(query.getGroupBy())
                ? "按主工序汇总已核实的节点及历史合同，不能据此判断单一执行节点；来源缺口逐库存行展示。分组最多展示前100项。"
                : "按原冻结节点分组；历史合同按实际工序任务分开，来源缺口逐库存行展示。分组最多展示前100项。");
        result.put("groups", rankedGroups.subList(0, Math.min(100, rankedGroups.size())));
        result.put("groupCount", rankedGroups.size());
        result.put("groupsTruncated", rankedGroups.size() > 100);
        result.put("total", rows.size());
        result.put("current", query.getCurrent());
        result.put("size", query.getSize());
        result.put("records", from >= rows.size() ? List.of() : rows.subList(from, to));
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> incidents(Long stockId, long current) {
        String factory = access.require("read");
        require(stockId != null && current >= 1 && current <= 100000, "库存或页码不合法");
        var stock = stocks.selectOne(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>().eq(MesWarehouseLiveStockEntity::getId, stockId)
                .eq(MesWarehouseLiveStockEntity::getFactoryCode, factory).eq(MesWarehouseLiveStockEntity::getMatDetailType, "WIP"));
        require(stock != null, "当前工厂在制库存不存在，不猜测替代来源");
        // 返回所有同库存行历史进站周期，前端逐项标识是否属于当前inTime，不把旧闭环当成当前放行。
        var history = events.selectPage(new Page<>(current, 20), new LambdaQueryWrapper<MesWipTimeoutHandleEntity>()
                .eq(MesWipTimeoutHandleEntity::getFactoryCode, factory).eq(MesWipTimeoutHandleEntity::getSourceStockId, stockId)
                .orderByDesc(MesWipTimeoutHandleEntity::getId));
        var currentCycle = history.getRecords().stream().filter(e -> stock.getInTime() != null && Objects.equals(e.getEnterTime(), stock.getInTime()))
                .map(e -> String.valueOf(e.getId())).toList();
        var query = new MesWipResidenceQuery();
        query.setStockId(stockId);
        var source = mapper.snapshot(query, TenantContextHolder.getTenantId(), factory, LocalDateTime.now());
        return Map.of("stock", stock, "currentQuantity", stock.getNum() == null ? "未知" : stock.getNum().toPlainString(), "events", history, "currentCycleEventIds", currentCycle, "source", source.isEmpty() ? Map.of() : source.get(0), "observedAt", LocalDateTime.now());
    }

    private void normalize(MesWipResidenceQuery q) {
        require(q != null, "查询不能为空");
        q.setMatCode(clean(q.getMatCode()));
        q.setMatBatch(clean(q.getMatBatch()));
        q.setBarcode(clean(q.getBarcode()));
        q.setTaskCode(clean(q.getTaskCode()));
        q.setProduceTaskCode(clean(q.getProduceTaskCode()));
        q.setProcessRouteCode(clean(q.getProcessRouteCode()));
        q.setSourceRouteNodeCode(clean(q.getSourceRouteNodeCode()));
        q.setRouteCode(clean(q.getRouteCode()));
        q.setWorksiteCode(clean(q.getWorksiteCode()));
        q.setGroupBy(clean(q.getGroupBy()));
        if (q.getGroupBy() == null) q.setGroupBy("NODE");
        require(Set.of("NODE", "PROCESS").contains(q.getGroupBy()), "不支持的在制分组模式");
        require(q.getCurrent() >= 1 && q.getCurrent() <= 100000 && q.getSize() >= 1 && q.getSize() <= 100, "页码或每页数量不合法");
        require(q.getMinHours() == null || q.getMinHours() >= 0 && q.getMinHours() <= 87600, "驻留下限须为0至87600小时");
        require(q.getFocus() != null && Set.of("ALL", "SOURCE_GAP", "TIME_UNKNOWN", "TIMEOUT", "PROMISE_DUE").contains(q.getFocus()), "不支持的关注类型");
    }

    private String clean(String s) {
        if (StrUtil.isBlank(s)) return null;
        require(s.length() <= 200, "查询编码不能超过200字");
        return s.trim();
    }

    /**
     * 旧NULL执行协议可核实实际任务来源，但不能据参数节点冒充原生执行节点。
     */
    private boolean verifiedNodeSource(Map<String, Object> row) {
        return "MATCHED".equals(row.get("nodeState")) || "LEGACY".equals(row.get("nodeState"));
    }

    private boolean verifiedSource(Map<String, Object> row) {
        return "MATCHED".equals(row.get("sourceState")) && verifiedNodeSource(row);
    }

    private List<String> groupKey(Map<String, Object> row, String groupBy) {
        if (!verifiedSource(row)) return List.of("STOCK", value(row, "stockId"));
        var key = new ArrayList<>(List.of(groupBy, value(row, "sourceWorksiteCode"), value(row, "routeCode"),
                value(row, "matCode"), value(row, "unit")));
        if ("NODE".equals(groupBy)) {
            key.add(value(row, "nodeState"));
            if ("LEGACY".equals(row.get("nodeState"))) {
                key.add(value(row, "produceTaskCode"));
            } else {
                key.add(value(row, "processRouteCode"));
                key.add(value(row, "sourceRouteNodeCode"));
            }
        }
        return key;
    }

    private boolean matches(Map<String, Object> r, String focus) {
        return switch (focus) {
            case "SOURCE_GAP" -> !"MATCHED".equals(r.get("sourceState"));
            case "TIME_UNKNOWN" -> !"VALID".equals(r.get("timeState"));
            case "TIMEOUT" -> number(r, "activeTimeouts") > 0;
            case "PROMISE_DUE" -> number(r, "promiseDueCount") > 0;
            default -> true;
        };
    }

    private int priority(Map<String, Object> r) {
        if (!"MATCHED".equals(r.get("sourceState")) || !"VALID".equals(r.get("timeState"))) return 0;
        if (number(r, "promiseDueCount") > 0) return 1;
        if (number(r, "activeTimeouts") > 0) return 2;
        return 3;
    }

    private Map<String, Object> band(List<Map<String, Object>> rows, String name, long start, long end) {
        return Map.of("name", name, "lines", rows.stream().filter(r -> "VALID".equals(r.get("timeState")) && number(r, "residenceSeconds") >= start && number(r, "residenceSeconds") < end).count());
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private String value(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }
}
