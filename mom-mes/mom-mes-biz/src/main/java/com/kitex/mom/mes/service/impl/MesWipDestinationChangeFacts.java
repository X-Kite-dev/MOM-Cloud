package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesWipTransferCommandCodec.require;

import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesWipDestinationChangeMapper;

import java.util.*;

/** 页内批量事实：只归组，不替代Service对每份原件的业务验真。 */
record MesWipDestinationChangeFacts(
        Map<Long, MesWarehouseLiveStockEntity> stocks,
        Map<Long, List<MesWipTransferEntity>> issues,
        Map<Long, List<MesWipTransferOperationEntity>> originals) {
    static MesWipDestinationChangeFacts load(
            MesWipDestinationChangeMapper mapper,
            Long tenant,
            String factory,
            List<Long> stockIds) {
        require(
                stockIds != null
                        && stockIds.size() <= 20
                        && new HashSet<>(stockIds).size() == stockIds.size()
                        && stockIds.stream().allMatch(id -> id != null && id > 0),
                "批量签收库存ID不完整、重复或超过20条");
        if (stockIds.isEmpty())
            return new MesWipDestinationChangeFacts(Map.of(), Map.of(), Map.of());
        var expected = new HashSet<>(stockIds);
        var stocks = new HashMap<Long, MesWarehouseLiveStockEntity>();
        var current = mapper.stocks(tenant, factory, stockIds);
        require(current != null && current.size() <= stockIds.size(), "批量库存返回超出范围");
        for (var row : current) {
            require(
                    row != null
                            && expected.contains(row.getId())
                            && Objects.equals(tenant, row.getTenantId())
                            && Objects.equals(factory, row.getFactoryCode())
                            && "0".equals(row.getDelFlag())
                            && stocks.putIfAbsent(row.getId(), row) == null,
                    "批量库存身份、范围或唯一性不一致");
        }
        var transfers = mapper.issuesForStocks(tenant, factory, stockIds);
        require(transfers != null && transfers.size() <= stockIds.size() * 6, "后续转运超出每库存6条范围");
        var issues = new LinkedHashMap<Long, List<MesWipTransferEntity>>();
        var seen = new HashSet<Long>();
        for (var row : transfers) {
            require(
                    row != null
                            && row.getId() != null
                            && row.getId() > 0
                            && expected.contains(row.getSourceStockId())
                            && Objects.equals(tenant, row.getTenantId())
                            && Objects.equals(factory, row.getFactoryCode())
                            && "0".equals(row.getDelFlag())
                            && seen.add(row.getId()),
                    "批量后续转运身份、范围或唯一性不一致");
            var group = issues.computeIfAbsent(row.getSourceStockId(), id -> new ArrayList<>());
            require(
                    group.size() < 6
                            && (group.isEmpty()
                                    || group.get(group.size() - 1).getId() > row.getId()),
                    "后续转运分组数量或顺序不一致");
            group.add(row);
        }
        var visibleIds = new ArrayList<Long>();
        issues.values()
                .forEach(
                        rows -> rows.stream().limit(5).forEach(row -> visibleIds.add(row.getId())));
        var originals = new HashMap<Long, List<MesWipTransferOperationEntity>>();
        if (!visibleIds.isEmpty()) {
            var expectedTransfers = new HashSet<>(visibleIds);
            var dispatches = mapper.dispatches(tenant, factory, visibleIds);
            require(
                    dispatches != null && dispatches.size() <= visibleIds.size() * 2,
                    "后续原发出原件超出范围");
            for (var op : dispatches) {
                require(
                        op != null
                                && op.getId() != null
                                && op.getId() > 0
                                && expectedTransfers.contains(op.getTransferId())
                                && Objects.equals(tenant, op.getTenantId())
                                && Objects.equals(factory, op.getFactoryCode())
                                && "0".equals(op.getDelFlag())
                                && Long.valueOf(1).equals(op.getSequenceNo()),
                        "批量原发出身份或范围不一致");
                var group = originals.computeIfAbsent(op.getTransferId(), id -> new ArrayList<>());
                require(group.size() < 2, "同一后续转运原发出超过2份");
                group.add(op);
            }
        }
        issues.replaceAll((id, rows) -> List.copyOf(rows));
        originals.replaceAll((id, rows) -> List.copyOf(rows));
        return new MesWipDestinationChangeFacts(
                Map.copyOf(stocks), Map.copyOf(issues), Map.copyOf(originals));
    }
}
