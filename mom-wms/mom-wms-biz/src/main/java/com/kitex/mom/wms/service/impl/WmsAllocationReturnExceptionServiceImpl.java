package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsAllocationReturnExceptionDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.entity.WmsAllocationReturnExceptionEntity;
import com.kitex.mom.wms.entity.WmsAllocationReturnExceptionLineEntity;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.mapper.WmsAllocationBillMapper;
import com.kitex.mom.wms.mapper.WmsAllocationReturnExceptionLineMapper;
import com.kitex.mom.wms.mapper.WmsAllocationReturnExceptionMapper;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.service.WmsAllocationReturnExceptionService;
import com.kitex.mom.wms.service.WmsLiveStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WmsAllocationReturnExceptionServiceImpl implements WmsAllocationReturnExceptionService {
    private static final Set<String> REASONS = Set.of("TRANSPORT_LOSS", "DAMAGE", "REFUSED", "OTHER");
    private static final Set<String> DISPOSITIONS = Set.of("LOSS", "CLAIM", "EXTERNAL_CUSTODY");
    private static final String RETURN_PENDING = "RETURN_PENDING";
    private static final String EXCEPTION_PENDING = "RETURN_EXCEPTION_PENDING";
    private static final String EXCEPTION_CLOSED = "RETURN_EXCEPTION_CLOSED";

    private final WmsAllocationBillMapper billMapper;
    private final WmsAllocationReturnExceptionMapper caseMapper;
    private final WmsAllocationReturnExceptionLineMapper lineMapper;
    private final WmsInoutFlowMapper flowMapper;
    private final WmsLiveStockService stockService;
    private final PermissionService permissionService;

    @Override
    public Page<WmsAllocationReturnExceptionEntity> page(Page<WmsAllocationReturnExceptionEntity> page,
            WmsAllocationReturnExceptionDTO.CaseQuery query) {
        String factory = FactoryContextHolder.getFactoryCode();
        requireScope(factory);
        require(permissionService.hasPermission("wms_transferVariance_dispose"),
                "无权查看退回异常审核任务");
        LambdaQueryWrapper<WmsAllocationReturnExceptionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WmsAllocationReturnExceptionEntity::getFactoryCode, factory)
                .like(query != null && StrUtil.isNotBlank(query.getCaseNo()),
                        WmsAllocationReturnExceptionEntity::getCaseNo, query == null ? null : query.getCaseNo())
                .eq(query != null && StrUtil.isNotBlank(query.getStatus()),
                        WmsAllocationReturnExceptionEntity::getStatus, query == null ? null : query.getStatus())
                .eq(query != null && query.getAllocationBillId() != null,
                        WmsAllocationReturnExceptionEntity::getAllocationBillId,
                        query == null ? null : query.getAllocationBillId())
                .orderByAsc(WmsAllocationReturnExceptionEntity::getStatus)
                .orderByDesc(WmsAllocationReturnExceptionEntity::getReportedTime);
        return caseMapper.selectPage(page, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsAllocationReturnExceptionEntity report(WmsAllocationReturnExceptionDTO.Report command) {
        require(command != null && command.getAllocationBillId() != null, "待退回调拨单不能为空");
        String requestId = requiredText(command.getRequestId(), 64, "异常申报请求号");
        require(REASONS.contains(command.getReasonCode()), "退回异常原因无效");
        String description = requiredText(command.getReasonDescription(), 500, "异常说明");
        String evidence = requiredText(command.getEvidence(), 500, "异常证据");
        String snapshot = JSONUtil.toJsonStr(Map.of("billId", command.getAllocationBillId(),
                "reasonCode", command.getReasonCode(), "description", description, "evidence", evidence));
        WmsAllocationBillEntity bill = lockBill(command.getAllocationBillId());
        requireScope(bill.getFactoryCode());
        require(permissionService.hasPermission("wms_wmsAllocationBill_cancel",
                "wms_wmsAllocationBill_cancel_" + bill.getOldWarehouseCode()), "无权申报该源仓的退回异常");
        WmsAllocationReturnExceptionEntity duplicate = caseMapper.selectOne(
                new LambdaQueryWrapper<WmsAllocationReturnExceptionEntity>()
                        .eq(WmsAllocationReturnExceptionEntity::getReportRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getAllocationBillId(), bill.getId())
                    && Objects.equals(duplicate.getFactoryCode(), bill.getFactoryCode())
                    && Objects.equals(duplicate.getReporterCode(), SecurityUtils.getUser().getUsername())
                    && Objects.equals(duplicate.getReportSnapshot(), snapshot),
                    "异常申报请求号已被其他事实使用");
            return duplicate;
        }
        require(RETURN_PENDING.equals(bill.getAllocationState()) && Boolean.TRUE.equals(bill.getSourceDeducted()),
                "仅待实物退回的调拨单可申报异常");
        List<WmsAllocationReturnExceptionLineEntity> frozen = freezeOutstanding(bill);
        MomUser user = SecurityUtils.getUser();
        WmsAllocationReturnExceptionEntity exception = new WmsAllocationReturnExceptionEntity()
                .setCaseNo("TRE-" + IdUtil.getSnowflakeNextIdStr())
                .setAllocationBillId(bill.getId()).setReportRequestId(requestId).setReportSnapshot(snapshot)
                .setReasonCode(command.getReasonCode()).setReasonDescription(description).setEvidence(evidence)
                .setStatus("PENDING_REVIEW").setReporterCode(user.getUsername())
                .setReporterName(operatorName(user)).setReportedTime(LocalDateTime.now())
                .setFactoryCode(bill.getFactoryCode());
        require(caseMapper.insert(exception) == 1, "退回异常申报保存失败");
        for (WmsAllocationReturnExceptionLineEntity line : frozen) {
            line.setCaseId(exception.getId());
            require(lineMapper.insert(line) == 1, "退回异常明细保存失败");
        }
        bill.setAllocationState(EXCEPTION_PENDING).setReturnExceptionCaseId(exception.getId());
        require(billMapper.updateById(bill) == 1, "退回异常状态更新失败");
        return exception;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsAllocationReturnExceptionEntity review(WmsAllocationReturnExceptionDTO.Review command) {
        require(command != null && command.getCaseId() != null, "退回异常单不能为空");
        String requestId = requiredText(command.getRequestId(), 64, "审核请求号");
        require(Set.of("APPROVE", "REJECT").contains(command.getDecision()), "审核结论无效");
        String comment = requiredText(command.getComment(), 500, "审核意见");
        String disposition = "APPROVE".equals(command.getDecision()) ? command.getDispositionCode() : null;
        String reference = "APPROVE".equals(command.getDecision())
                ? requiredText(command.getReferenceNo(), 100, "外部处置单号") : null;
        if ("APPROVE".equals(command.getDecision())) {
            require(DISPOSITIONS.contains(disposition), "退回异常处置方式无效");
        }
        String snapshot = JSONUtil.toJsonStr(Map.of("caseId", command.getCaseId(),
                "decision", command.getDecision(), "comment", comment,
                "disposition", Objects.toString(disposition, ""), "reference", Objects.toString(reference, "")));
        WmsAllocationReturnExceptionEntity preview = caseMapper.selectById(command.getCaseId());
        require(preview != null, "退回异常单不存在");
        WmsAllocationBillEntity bill = lockBill(preview.getAllocationBillId());
        requireScope(bill.getFactoryCode());
        require(permissionService.hasPermission("wms_transferVariance_dispose"), "无权审核调拨退回异常");
        WmsAllocationReturnExceptionEntity exception = lockCase(command.getCaseId());
        require(Objects.equals(exception.getAllocationBillId(), bill.getId())
                && Objects.equals(exception.getFactoryCode(), bill.getFactoryCode()),
                "异常单与调拨单范围不一致");
        if (Objects.equals(exception.getReviewRequestId(), requestId)) {
            require(Objects.equals(exception.getReviewSnapshot(), snapshot)
                    && Objects.equals(exception.getReviewerCode(), SecurityUtils.getUser().getUsername()),
                    "异常审核请求号已被其他事实使用");
            return exception;
        }
        require("PENDING_REVIEW".equals(exception.getStatus())
                && EXCEPTION_PENDING.equals(bill.getAllocationState())
                && Objects.equals(bill.getReturnExceptionCaseId(), exception.getId()),
                "退回异常单不在待审核状态");
        require(!Objects.equals(exception.getReporterCode(), SecurityUtils.getUser().getUsername()),
                "异常申报人不能审核自己的单据");
        if ("APPROVE".equals(command.getDecision())) {
            assertFrozenQuantity(bill, exception.getId());
        }
        MomUser user = SecurityUtils.getUser();
        LocalDateTime now = LocalDateTime.now();
        exception.setReviewRequestId(requestId).setReviewSnapshot(snapshot)
                .setDecision(command.getDecision()).setReviewComment(comment)
                .setReviewerCode(user.getUsername()).setReviewerName(operatorName(user)).setReviewedTime(now)
                .setDispositionCode(disposition).setReferenceNo(reference);
        if ("APPROVE".equals(command.getDecision())) {
            exception.setStatus("CUSTODY_CLOSED");
            bill.setAllocationState(EXCEPTION_CLOSED).setFinishTime(now).setFinishByName(operatorName(user));
            List<WmsInoutFlowEntity> issues = issueFlows(bill);
            issues.forEach(stockService::releaseTransferSourceContainer);
        } else {
            exception.setStatus("REJECTED");
            bill.setAllocationState(RETURN_PENDING);
        }
        require(caseMapper.updateById(exception) == 1 && billMapper.updateById(bill) == 1,
                "退回异常审核保存失败");
        return exception;
    }

    @Override
    public WmsAllocationReturnExceptionEntity detail(Long caseId) {
        require(caseId != null, "退回异常单不能为空");
        WmsAllocationReturnExceptionEntity exception = caseMapper.selectById(caseId);
        require(exception != null, "退回异常单不存在");
        WmsAllocationBillEntity bill = billMapper.selectById(exception.getAllocationBillId());
        require(bill != null && Objects.equals(bill.getFactoryCode(), exception.getFactoryCode()),
                "退回异常单与调拨单范围不一致");
        requireScope(bill.getFactoryCode());
        require(permissionService.hasPermission("wms_transferVariance_dispose", "wms_wmsAllocationBill_cancel",
                "wms_wmsAllocationBill_cancel_" + bill.getOldWarehouseCode()), "无权查看退回异常单");
        return exception;
    }

    @Override
    public List<WmsAllocationReturnExceptionLineEntity> lines(Long caseId) {
        detail(caseId);
        return lineMapper.selectList(new LambdaQueryWrapper<WmsAllocationReturnExceptionLineEntity>()
                .eq(WmsAllocationReturnExceptionLineEntity::getCaseId, caseId)
                .orderByAsc(WmsAllocationReturnExceptionLineEntity::getSourceFlowId));
    }

    private List<WmsAllocationReturnExceptionLineEntity> freezeOutstanding(WmsAllocationBillEntity bill) {
        List<WmsInoutFlowEntity> issues = issueFlows(bill);
        Map<Long, BigDecimal> received = flowTotals(bill.getId(), "RECEIVE");
        Map<Long, BigDecimal> returned = flowTotals(bill.getId(), "CANCEL_RETURN");
        Set<Long> issueIds = new HashSet<>();
        for (WmsInoutFlowEntity issue : issues) {
            require(issue.getId() != null && issueIds.add(issue.getId())
                    && StrUtil.isNotBlank(issue.getMatCode()), "调拨发出流水身份或物料异常");
        }
        require(issueIds.containsAll(received.keySet()) && issueIds.containsAll(returned.keySet()),
                "调拨收退流水引用了未知发出流水，须先核账");
        List<WmsAllocationReturnExceptionLineEntity> frozen = new ArrayList<>();
        for (WmsInoutFlowEntity issue : issues) {
            BigDecimal issued = quantity(issue);
            require(issued.signum() > 0 && issue.getId() != null, "调拨发出流水数量或ID异常");
            BigDecimal receivedQuantity = received.getOrDefault(issue.getId(), BigDecimal.ZERO);
            BigDecimal returnedQuantity = returned.getOrDefault(issue.getId(), BigDecimal.ZERO);
            BigDecimal outstanding = issued.subtract(receivedQuantity).subtract(returnedQuantity);
            require(outstanding.signum() >= 0, "调拨实收与退回超过发出数量，须先核账");
            if (outstanding.signum() == 0) continue;
            frozen.add(new WmsAllocationReturnExceptionLineEntity()
                    .setSourceFlowId(issue.getId()).setMaterialCode(issue.getMatCode())
                    .setMaterialBatch(issue.getMatBatch()).setMaterialBarcode(issue.getMatBarcode())
                    .setSerialManaged(issue.getSerialManaged())
                    .setStockOwnerType(issue.getStockOwnerType()).setStockOwnerCode(issue.getStockOwnerCode())
                    .setSourceBusinessNo(issue.getSourceBusinessNo()).setIssuedQuantity(issued)
                    .setReceivedQuantity(receivedQuantity).setReturnedQuantity(returnedQuantity)
                    .setExceptionQuantity(outstanding).setFactoryCode(bill.getFactoryCode()));
        }
        require(!frozen.isEmpty(), "调拨已无待退回数量");
        return frozen;
    }

    private List<WmsInoutFlowEntity> issueFlows(WmsAllocationBillEntity bill) {
        List<WmsInoutFlowEntity> issues = flowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>()
                .eq(WmsInoutFlowEntity::getBillId, bill.getId())
                .eq(WmsInoutFlowEntity::getTransferAction, "ISSUE")
                .orderByAsc(WmsInoutFlowEntity::getId));
        require(issues != null && !issues.isEmpty(), "调拨发出流水缺失");
        return issues;
    }

    private void assertFrozenQuantity(WmsAllocationBillEntity bill, Long caseId) {
        List<WmsAllocationReturnExceptionLineEntity> saved = lineMapper.selectList(
                new LambdaQueryWrapper<WmsAllocationReturnExceptionLineEntity>()
                        .eq(WmsAllocationReturnExceptionLineEntity::getCaseId, caseId));
        Map<Long, WmsAllocationReturnExceptionLineEntity> expected = new HashMap<>();
        for (WmsAllocationReturnExceptionLineEntity line : saved) {
            require(line.getSourceFlowId() != null && line.getExceptionQuantity() != null
                    && expected.putIfAbsent(line.getSourceFlowId(), line) == null,
                    "退回异常冻结明细无效");
        }
        Map<Long, WmsAllocationReturnExceptionLineEntity> actual = new HashMap<>();
        for (WmsAllocationReturnExceptionLineEntity line : freezeOutstanding(bill)) {
            actual.put(line.getSourceFlowId(), line);
        }
        require(expected.keySet().equals(actual.keySet()) && expected.entrySet().stream()
                        .allMatch(entry -> sameFrozenLine(entry.getValue(), actual.get(entry.getKey()))),
                "退回异常流水或数量已变化，禁止按过期快照审核");
    }

    private boolean sameFrozenLine(WmsAllocationReturnExceptionLineEntity saved,
            WmsAllocationReturnExceptionLineEntity current) {
        return current != null
                && Objects.equals(saved.getMaterialCode(), current.getMaterialCode())
                && Objects.equals(saved.getMaterialBatch(), current.getMaterialBatch())
                && Objects.equals(saved.getMaterialBarcode(), current.getMaterialBarcode())
                && Objects.equals(saved.getSerialManaged(), current.getSerialManaged())
                && Objects.equals(saved.getStockOwnerType(), current.getStockOwnerType())
                && Objects.equals(saved.getStockOwnerCode(), current.getStockOwnerCode())
                && Objects.equals(saved.getSourceBusinessNo(), current.getSourceBusinessNo())
                && sameQuantity(saved.getIssuedQuantity(), current.getIssuedQuantity())
                && sameQuantity(saved.getReceivedQuantity(), current.getReceivedQuantity())
                && sameQuantity(saved.getReturnedQuantity(), current.getReturnedQuantity())
                && sameQuantity(saved.getExceptionQuantity(), current.getExceptionQuantity());
    }

    private boolean sameQuantity(BigDecimal saved, BigDecimal current) {
        return saved != null && current != null && saved.compareTo(current) == 0;
    }

    private Map<Long, BigDecimal> flowTotals(Long billId, String action) {
        Map<Long, BigDecimal> totals = new HashMap<>();
        List<WmsInoutFlowEntity> flows = flowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>()
                .eq(WmsInoutFlowEntity::getBillId, billId)
                .eq(WmsInoutFlowEntity::getTransferAction, action));
        if (flows != null) {
            for (WmsInoutFlowEntity flow : flows) {
                require(flow.getSourceFlowId() != null && quantity(flow).signum() > 0,
                        "调拨接收或退回流水无效");
                totals.merge(flow.getSourceFlowId(), quantity(flow), BigDecimal::add);
            }
        }
        return totals;
    }

    private WmsAllocationBillEntity lockBill(Long id) {
        WmsAllocationBillEntity bill = billMapper.selectOne(new LambdaQueryWrapper<WmsAllocationBillEntity>()
                .eq(WmsAllocationBillEntity::getId, id).last("FOR UPDATE"));
        require(bill != null, "调拨单不存在");
        return bill;
    }

    private WmsAllocationReturnExceptionEntity lockCase(Long id) {
        WmsAllocationReturnExceptionEntity exception = caseMapper.selectOne(
                new LambdaQueryWrapper<WmsAllocationReturnExceptionEntity>()
                        .eq(WmsAllocationReturnExceptionEntity::getId, id).last("FOR UPDATE"));
        require(exception != null, "退回异常单不存在");
        return exception;
    }

    private void requireScope(String factory) {
        require(TenantContextHolder.getTenantId() != null
                && StrUtil.isNotBlank(FactoryContextHolder.getFactoryCode())
                && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && Objects.equals(FactoryContextHolder.getFactoryCode(), factory),
                "退回异常数据超出当前租户或工厂范围");
    }

    private String requiredText(String value, int limit, String field) {
        require(StrUtil.isNotBlank(value) && value.trim().length() <= limit,
                field + "不能为空且不能超过" + limit + "字");
        return value.trim();
    }

    private BigDecimal quantity(WmsInoutFlowEntity flow) {
        require(flow.getMatNum() != null && Double.isFinite(flow.getMatNum()) && flow.getMatNum() > 0,
                "调拨流水数量无效");
        return BigDecimal.valueOf(flow.getMatNum());
    }

    private String operatorName(MomUser user) {
        return StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
