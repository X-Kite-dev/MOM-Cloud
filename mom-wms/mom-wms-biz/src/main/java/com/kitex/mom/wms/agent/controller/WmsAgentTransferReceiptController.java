package com.kitex.mom.wms.agent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.wms.agent.service.WmsAgentFactoryAccessService;
import com.kitex.mom.wms.dto.WmsCrossWarehouseTransferDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.enums.InOutEnum;
import com.kitex.mom.wms.mapper.WmsAllocationBillMapper;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 调拨失联后的固定只读查证接口。不创建调拨单，不扣库存、不推进收货。
 * 永久号、MRP来源、仓库、物料、路线和实际出库批次数量全部一致才返回原回执。
 */
@RestController
@RequestMapping("/wmsAgentTransfer")
public class WmsAgentTransferReceiptController {
    private final WmsAgentFactoryAccessService access;
    private final WmsAllocationBillMapper bills;
    private final WmsInoutFlowMapper flows;
    private final RemoteMdmFactoryAccessService factories;
    private final JdbcTemplate jdbc;

    public WmsAgentTransferReceiptController(WmsAgentFactoryAccessService access, WmsAllocationBillMapper bills,
                                             WmsInoutFlowMapper flows, RemoteMdmFactoryAccessService factories, JdbcTemplate jdbc) {
        this.access = access;
        this.bills = bills;
        this.flows = flows;
        this.factories = factories;
        this.jdbc = jdbc;
    }

    @PostMapping("/receipt")
    @PreAuthorize("@pms.hasPermission('agent:approval:execute')")
    public R<WmsCrossWarehouseTransferDTO.Receipt> receipt(@RequestBody WmsCrossWarehouseTransferDTO.AgentCommand command) {
        requireFactory(command.getFactoryCode());
        require(command.getRequestId() != null && command.getMrpId() != null
                && command.getAllocations() != null && !command.getAllocations().isEmpty()
                && command.getTransferNum() != null && command.getTransferNum().signum() > 0, "调拨原命令证据不完整");
        WmsAllocationBillEntity bill = bills.selectOne(new LambdaQueryWrapper<WmsAllocationBillEntity>()
                .eq(WmsAllocationBillEntity::getFactoryCode, command.getFactoryCode())
                .eq(WmsAllocationBillEntity::getRequestId, command.getRequestId()).last("LIMIT 1"));
        require(bill != null, "尚未查到该永久请求的调拨单，不等于没有执行");
        require(Boolean.TRUE.equals(bill.getSourceDeducted())
                        && Objects.equals(bill.getOldWarehouseCode(), command.getSourceWarehouseCode())
                        && Objects.equals(bill.getNewWarehouseCode(), command.getTargetWarehouseCode())
                        && Objects.equals(bill.getSourceMrpId(), command.getMrpId())
                        && Objects.equals(bill.getSourceBusinessNo(), command.getIssueNo())
                        && Objects.equals(bill.getSourceMaterialCode(), command.getMaterialCode())
                        && bill.getSourceShortageQuantity() != null
                        && bill.getSourceShortageQuantity().compareTo(command.getExpectedShortageNum()) == 0,
                "调拨来源、仓库或原需求数量不能权威确认");
        if (Boolean.TRUE.equals(command.getRoutePolicyRequired())) {
            require(Objects.equals(bill.getTransferRoutePolicyId(), command.getRoutePolicyId())
                    && Objects.equals(bill.getTransferRoutePolicyCode(), command.getRoutePolicyCode())
                    && Objects.equals(bill.getTransportMode(), command.getTransportMode())
                    && Objects.equals(bill.getUrgencyLevel(), command.getUrgencyLevel()), "调拨原路线证据不一致");
        }
        List<WmsInoutFlowEntity> issued = flows.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>()
                .eq(WmsInoutFlowEntity::getFactoryCode, command.getFactoryCode())
                .eq(WmsInoutFlowEntity::getBillId, bill.getId()).eq(WmsInoutFlowEntity::getTransferAction, "ISSUE")
                .eq(WmsInoutFlowEntity::getOperateType, InOutEnum.OUT.getCode())
                .orderByAsc(WmsInoutFlowEntity::getId).last("LIMIT 1001"));
        require(!issued.isEmpty() && issued.size() <= 1000, "调拨实际出库证据缺失或超出核查上限");
        Map<Long, BigDecimal> expected = new HashMap<>();
        for (WmsCrossWarehouseTransferDTO.StockAllocation item : command.getAllocations()) {
            require(item.getStockId() != null && item.getSuggestedNum() != null && item.getSuggestedNum().signum() > 0
                    && expected.putIfAbsent(item.getStockId(), item.getSuggestedNum()) == null, "原批次分配重复或数量不合法");
        }
        Map<Long, BigDecimal> actual = new HashMap<>();
        for (WmsInoutFlowEntity flow : issued) {
            require(Objects.equals(command.getMaterialCode(), flow.getMatCode())
                            && flow.getSourceStockId() != null && flow.getMatNum() != null && flow.getMatNum() > 0,
                    "实际调拨物料或批次数量证据不完整");
            actual.merge(flow.getSourceStockId(), BigDecimal.valueOf(flow.getMatNum()), BigDecimal::add);
        }
        require(expected.size() == actual.size() && expected.entrySet().stream()
                        .allMatch(entry -> actual.containsKey(entry.getKey()) && entry.getValue().compareTo(actual.get(entry.getKey())) == 0),
                "实际出库分配与原审批不一致");
        BigDecimal total = actual.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        require(total.compareTo(command.getTransferNum()) == 0, "实际调拨数量与原审批不一致");
        return R.ok(new WmsCrossWarehouseTransferDTO.Receipt().setAllocationBillId(bill.getId())
                .setBillNo(bill.getBillNo()).setAllocationState(bill.getAllocationState()).setRequestId(bill.getRequestId())
                .setSourceWarehouseCode(bill.getOldWarehouseCode()).setTargetWarehouseCode(bill.getNewWarehouseCode())
                .setMaterialCode(command.getMaterialCode()).setTransferNum(total));
    }

    /**
     * 只证明实际工厂下此永久号是否被调拨占用；不可达不能解释为ABSENT。
     */
    @PostMapping("/presence")
    @PreAuthorize("@pms.hasPermission('agent:approval:execute')")
    public R<Map<String, String>> presence(@RequestBody Presence query) {
        requireFactory(query.factoryCode());
        require(query.requestId() != null && !query.requestId().isBlank() && query.requestId().length() <= 100,
                "调拨永久请求号不合法");
        Long count = jdbc.queryForObject("SELECT count(*) FROM \"mom_wms\".wms_allocation_bill "
                        + "WHERE tenant_id=? AND factory_code=? AND request_id=?", Long.class,
                TenantContextHolder.getTenantId(), query.factoryCode(), query.requestId());
        require(count != null, "包含删除历史的调拨永久号占用核查未返回权威结果");
        boolean exists = count > 0;
        return R.ok(Map.of("status", exists ? "EXISTS" : "ABSENT", "factoryCode", query.factoryCode(),
                "requestId", query.requestId()));
    }

    private void requireFactory(String factory) {
        access.requireRead(factory);
        var user = SecurityUtils.getUser();
        require(Objects.equals(user.getTenantId(), TenantContextHolder.getTenantId()), "调拨回执身份租户与请求上下文不一致");
        R<Boolean> response = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
                .setFactoryCode(factory).setRequiredPermission("read"));
        require(response != null && response.isOk() && Boolean.TRUE.equals(response.getData()),
                "当前用户没有该工厂真实授权，不能凭请求头查询调拨证据");
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }

    public record Presence(String factoryCode, String requestId) {
    }
}
