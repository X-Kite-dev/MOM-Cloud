package com.kitex.mom.pms.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.pms.dto.PmsContractTerminationDTO;
import com.kitex.mom.pms.entity.*;
import com.kitex.mom.pms.enums.PmsContractStatusEnum;
import com.kitex.mom.pms.enums.PmsOrderStatusEnum;
import com.kitex.mom.pms.mapper.*;
import com.kitex.mom.pms.service.PmsContractTerminationService;
import com.kitex.mom.wms.enums.PurchaseReceiptStateEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 采购合同受控终止：保留合同及条款历史，只在无活动收料和无结算事实时，经异岗复核进入终止态。
 */
@Service
@RequiredArgsConstructor
public class PmsContractTerminationServiceImpl implements PmsContractTerminationService {
    private static final Set<String> TERMINABLE_CONTRACT_STATUS = Set.of(
            PmsContractStatusEnum.SIGN.getCode(), PmsContractStatusEnum.EXECUTION.getCode(),
            PmsContractStatusEnum.CHANGE.getCode());
    private static final Set<String> TERMINABLE_ORDER_STATUS = Set.of(
            PmsOrderStatusEnum.CHECK_SUCCESS.getCode(), PmsOrderStatusEnum.RECEIVING.getCode(),
            PmsOrderStatusEnum.PART_RECEIVE.getCode());

    private final PmsContractTerminationRequestMapper requestMapper;
    private final PmsContractTerminationOperationMapper operationMapper;
    private final PmsContractMapper contractMapper;
    private final PmsContractDetailMapper contractDetailMapper;
    private final PmsOrderMapper orderMapper;
    private final WmsPurchaseReceiptMapper receiptMapper;
    private final PmsBusinessReconciliationMapper reconciliationMapper;
    private final PmsInvoiceReconciliationMapper invoiceReconciliationMapper;
    private final PmsPaymentReconciliationMapper paymentReconciliationMapper;
    private final PmsContractRevisionRequestMapper revisionRequestMapper;
    private final ObjectMapper objectMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    public PmsContractTerminationDTO.Preview preview(Long contractId) {
        require(contractId != null, "采购合同不能为空");
        PmsContractEntity contract = contractMapper.selectById(contractId);
        requireContractScope(contract);
        return capture(contract, null, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsContractTerminationDTO.Detail submit(PmsContractTerminationDTO.SubmitCommand command) {
        require(command != null && command.getContractId() != null && StrUtil.isNotBlank(command.getRequestId())
                        && StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint()) && StrUtil.isNotBlank(command.getReason()),
                "合同终止申请必须提供合同、请求号、证据指纹和原因");
        String requestId = bounded(command.getRequestId(), 128, "提交请求号");
        String reason = bounded(command.getReason(), 500, "申请原因");
        String requestFingerprint = hash(command.getContractId() + "|TERMINATE|"
                + command.getExpectedEvidenceFingerprint().trim() + "|" + reason);
        requestMapper.lockKey("SUBMIT:" + requestId);
        PmsContractTerminationOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<PmsContractTerminationOperationEntity>()
                        .eq(PmsContractTerminationOperationEntity::getRequestId, requestId));
        if (replay != null) {
            require("SUBMIT".equals(replay.getOperationType())
                            && Objects.equals(replay.getRequestFingerprint(), requestFingerprint),
                    "合同终止提交请求号已被不同动作或载荷使用");
            return detail(replay.getTerminationRequestId());
        }
        PmsContractEntity contract = lockContract(command.getContractId());
        PmsContractTerminationDTO.Preview preview = capture(contract, null, true);
        require(Objects.equals(preview.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "合同、订单或下游事实已变化，请刷新终止预览后重试");
        require(Boolean.TRUE.equals(preview.getEligible()), String.join("；", preview.getBlockers()));
        MomUser user = currentUser();
        PmsContractTerminationRequestEntity request = new PmsContractTerminationRequestEntity()
                .setRequestNo("PCT-" + IdUtil.getSnowflakeNextIdStr()).setContractId(contract.getId())
                .setContractNo(contract.getContractNo()).setPmsOrderId(preview.getPmsOrderId())
                .setPmsOrderNo(preview.getPmsOrderNo()).setOriginalContractStatus(contract.getStatus())
                .setOriginalOrderStatus(preview.getOrderStatus()).setStatus("SUBMITTED")
                .setSubmitRequestId(requestId).setSubmitFingerprint(requestFingerprint)
                .setEvidenceFingerprint(preview.getEvidenceFingerprint()).setEvidenceSnapshot(preview.getEvidenceSnapshot())
                .setReason(reason).setReceiptCount(preview.getReceiptCount())
                .setActiveReceiptCount(preview.getActiveReceiptCount()).setSettlementCount(preview.getSettlementCount())
                .setApplicantCode(userCode(user)).setApplicantName(userName(user))
                .setSubmittedTime(LocalDateTime.now()).setFactoryCode(contract.getFactoryCode());
        require(requestMapper.insert(request) == 1, "合同终止申请保存失败");
        operation(request, requestId, requestFingerprint, "SUBMIT", null, "SUBMITTED", reason, user);
        return detail(request.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsContractTerminationDTO.Detail review(PmsContractTerminationDTO.ReviewCommand command) {
        require(command != null && command.getTerminationRequestId() != null && StrUtil.isNotBlank(command.getRequestId())
                        && StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint()) && command.getApproved() != null
                        && StrUtil.isNotBlank(command.getReason()),
                "合同终止复核必须提供申请、请求号、证据指纹、决定和意见");
        String requestId = bounded(command.getRequestId(), 128, "复核请求号");
        String reason = bounded(command.getReason(), 500, "复核意见");
        String requestFingerprint = hash(command.getTerminationRequestId() + "|" + command.getApproved() + "|"
                + command.getExpectedEvidenceFingerprint().trim() + "|" + reason);
        requestMapper.lockKey("REVIEW:" + requestId);
        PmsContractTerminationOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<PmsContractTerminationOperationEntity>()
                        .eq(PmsContractTerminationOperationEntity::getRequestId, requestId));
        if (replay != null) {
            require(Objects.equals(replay.getTerminationRequestId(), command.getTerminationRequestId())
                            && Objects.equals(replay.getRequestFingerprint(), requestFingerprint),
                    "合同终止复核请求号已被不同申请或载荷使用");
            return detail(command.getTerminationRequestId());
        }
        PmsContractTerminationRequestEntity request = requireRequest(command.getTerminationRequestId(), true);
        require("SUBMITTED".equals(request.getStatus()), "仅待复核的合同终止申请允许审批");
        require(Objects.equals(request.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "合同终止申请证据指纹不一致，请刷新后重试");
        MomUser reviewer = currentUser();
        require(!Objects.equals(request.getApplicantCode(), userCode(reviewer)), "合同终止申请人与复核人必须分离");
        request.setReviewRequestId(requestId).setReviewFingerprint(requestFingerprint)
                .setApproved(command.getApproved()).setReviewReason(reason)
                .setReviewerCode(userCode(reviewer)).setReviewerName(userName(reviewer)).setReviewedTime(LocalDateTime.now());
        if (!Boolean.TRUE.equals(command.getApproved())) {
            request.setStatus("REJECTED");
            require(requestMapper.updateById(request) == 1, "合同终止驳回状态保存失败");
            operation(request, requestId, requestFingerprint, "REVIEW_REJECT", "SUBMITTED", "REJECTED", reason, reviewer);
            return detail(request.getId());
        }
        PmsContractEntity contract = lockContract(request.getContractId());
        PmsContractTerminationDTO.Preview current = capture(contract, request.getId(), true);
        if (!Objects.equals(request.getEvidenceFingerprint(), current.getEvidenceFingerprint())
                || !Boolean.TRUE.equals(current.getEligible())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("blockers", current.getBlockers());
            result.put("currentEvidenceFingerprint", current.getEvidenceFingerprint());
            request.setStatus("STALE").setApproved(false).setResultSnapshot(json(result));
            require(requestMapper.updateById(request) == 1, "合同终止失效状态保存失败");
            operation(request, requestId, requestFingerprint, "REVIEW_STALE", "SUBMITTED", "STALE",
                    current.getBlockers().isEmpty() ? "合同、订单或下游事实已变化" : String.join("；", current.getBlockers()), reviewer);
            return detail(request.getId());
        }
        contract.setStatus(PmsContractStatusEnum.TERMINATED.getCode());
        contract.setTerminationRequestId(request.getId());
        contract.setTerminatedTime(LocalDateTime.now());
        contract.setTerminationReason(request.getReason());
        require(contractMapper.updateById(contract) == 1, "采购合同终止状态保存失败");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractStatus", contract.getStatus());
        result.put("contractId", contract.getId());
        result.put("pmsOrderId", current.getPmsOrderId());
        result.put("pmsOrderNo", current.getPmsOrderNo());
        result.put("receiptCount", current.getReceiptCount());
        request.setStatus("SUCCEEDED").setCompletedTime(LocalDateTime.now()).setResultSnapshot(json(result));
        require(requestMapper.updateById(request) == 1, "合同终止执行结果保存失败");
        operation(request, requestId, requestFingerprint, "REVIEW_APPROVE_EXECUTE", "SUBMITTED", "SUCCEEDED",
                reason + "；合同已进入不可逆终止态，原合同及条款历史保留", reviewer);
        return detail(request.getId());
    }

    @Override
    public PmsContractTerminationDTO.Detail detail(Long requestId) {
        PmsContractTerminationRequestEntity request = requireRequest(requestId, false);
        PmsContractTerminationDTO.Detail result = new PmsContractTerminationDTO.Detail();
        org.springframework.beans.BeanUtils.copyProperties(request, result);
        result.setOperations(operationMapper.selectList(new LambdaQueryWrapper<PmsContractTerminationOperationEntity>()
                .eq(PmsContractTerminationOperationEntity::getTerminationRequestId, requestId)
                .orderByAsc(PmsContractTerminationOperationEntity::getOperationTime)
                .orderByAsc(PmsContractTerminationOperationEntity::getId)));
        return result;
    }

    @Override
    public List<PmsContractTerminationRequestEntity> listByContract(Long contractId) {
        require(contractId != null, "采购合同不能为空");
        requireContractScope(contractMapper.selectById(contractId));
        return requestMapper.selectList(new LambdaQueryWrapper<PmsContractTerminationRequestEntity>()
                .eq(PmsContractTerminationRequestEntity::getContractId, contractId)
                .orderByDesc(PmsContractTerminationRequestEntity::getSubmittedTime)
                .orderByDesc(PmsContractTerminationRequestEntity::getId));
    }

    private PmsContractTerminationDTO.Preview capture(PmsContractEntity contract, Long excludeRequestId, boolean lockFacts) {
        requireContractScope(contract);
        List<String> blockers = new ArrayList<>();
        if (StrUtil.isBlank(contract.getContractNo())) blockers.add("合同缺少正式合同编号");
        if (StrUtil.isBlank(contract.getPmsOrderNo())) blockers.add("合同缺少关联采购订单号");
        if (!TERMINABLE_CONTRACT_STATUS.contains(contract.getStatus()))
            blockers.add("只有已签订、执行中或变更中的合同允许申请终止");
        PmsOrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<PmsOrderEntity>()
                .eq(PmsOrderEntity::getPmsOrderNo, contract.getPmsOrderNo()).last(lockFacts ? "FOR UPDATE" : ""));
        if (order == null) blockers.add("合同关联采购订单不存在");
        else {
            if (!Objects.equals(order.getFactoryCode(), contract.getFactoryCode()))
                blockers.add("合同与采购订单工厂不一致");
            if (!Objects.equals(order.getSupplierCode(), contract.getSupplierCode()))
                blockers.add("合同与采购订单供应商不一致");
            if (!Boolean.TRUE.equals(order.getContractStatus()))
                blockers.add("采购订单合同关联标记缺失，须先完成数据治理");
            if (!TERMINABLE_ORDER_STATUS.contains(order.getStatus()))
                blockers.add("采购订单已完成、驳回或取消，合同不得通过本流程终止");
        }
        long activeRequestCount = requestMapper.selectCount(new LambdaQueryWrapper<PmsContractTerminationRequestEntity>()
                .eq(PmsContractTerminationRequestEntity::getContractId, contract.getId())
                .eq(PmsContractTerminationRequestEntity::getStatus, "SUBMITTED")
                .ne(excludeRequestId != null, PmsContractTerminationRequestEntity::getId, excludeRequestId));
        if (activeRequestCount > 0) blockers.add("合同已有待复核终止申请");
        long activeRevisionCount = revisionRequestMapper.selectCount(
                new LambdaQueryWrapper<PmsContractRevisionRequestEntity>()
                        .eq(PmsContractRevisionRequestEntity::getContractId, contract.getId())
                        .eq(PmsContractRevisionRequestEntity::getStatus, "SUBMITTED"));
        if (activeRevisionCount > 0) blockers.add("合同已有待复核正文变更申请，禁止并行终止");

        List<WmsPurchaseReceiptEntity> receipts = receiptMapper.selectList(new LambdaQueryWrapper<WmsPurchaseReceiptEntity>()
                .eq(WmsPurchaseReceiptEntity::getPmsOrderNo, contract.getPmsOrderNo())
                .orderByAsc(WmsPurchaseReceiptEntity::getId).last(lockFacts ? "FOR UPDATE" : ""));
        long activeReceiptCount = receipts.stream()
                .filter(value -> !PurchaseReceiptStateEnum.COMPLETED.getCode().equals(value.getState())).count();
        if (activeReceiptCount > 0) blockers.add("存在待处理、收料中、质检中或待入库收料单，须先关闭现场流程");
        long reconciliationCount = reconciliationMapper.selectCount(new LambdaQueryWrapper<PmsBusinessReconciliationEntity>()
                .eq(PmsBusinessReconciliationEntity::getPmsOrderNo, contract.getPmsOrderNo()));
        long invoiceCount = invoiceReconciliationMapper.selectCount(new LambdaQueryWrapper<PmsInvoiceReconciliationEntity>()
                .eq(PmsInvoiceReconciliationEntity::getPmsOrderNo, contract.getPmsOrderNo()));
        long paymentCount = paymentReconciliationMapper.selectCount(new LambdaQueryWrapper<PmsPaymentReconciliationEntity>()
                .eq(PmsPaymentReconciliationEntity::getPmsOrderNo, contract.getPmsOrderNo()));
        long settlementCount = reconciliationCount + invoiceCount + paymentCount;
        if (settlementCount > 0) blockers.add("采购订单已进入对账、发票或付款链路，应走结算/争议处置而非直接终止合同");

        List<PmsContractDetailEntity> details = contractDetailMapper.selectList(
                new LambdaQueryWrapper<PmsContractDetailEntity>().eq(PmsContractDetailEntity::getContractId, contract.getId())
                        .orderByAsc(PmsContractDetailEntity::getId).last(lockFacts ? "FOR UPDATE" : ""));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("contract", Arrays.asList(contract.getId(), contract.getContractNo(), contract.getStatus(),
                contract.getPmsOrderNo(), contract.getSupplierCode(), contract.getContractAmount(), contract.getContractDate(),
                contract.getTerminationRequestId(), contract.getCurrentVersion(), contract.getCurrentContentFingerprint(),
                contract.getLastRevisionRequestId(), contract.getLastRevisedTime(), contract.getBodyFormat(),
                hash(Objects.toString(contract.getContractBody(), "")), contract.getFactoryCode()));
        snapshot.put("contractDetails", details.stream().map(value -> Arrays.asList(value.getId(), value.getMatCode(),
                value.getOrderNum(), value.getPrice())).toList());
        snapshot.put("order", order == null ? null : Arrays.asList(order.getId(), order.getPmsOrderNo(), order.getStatus(),
                order.getSupplierCode(), order.getContractStatus(), order.getLastChangeRequestId(), order.getFactoryCode()));
        snapshot.put("receipts", receipts.stream().map(value -> Arrays.asList(value.getId(), value.getBillNo(), value.getState())).toList());
        snapshot.put("settlement", List.of(reconciliationCount, invoiceCount, paymentCount));
        snapshot.put("activeRequestCount", activeRequestCount);
        snapshot.put("activeRevisionCount", activeRevisionCount);
        String snapshotJson = json(snapshot);
        PmsContractTerminationDTO.Preview preview = new PmsContractTerminationDTO.Preview();
        preview.setContractId(contract.getId());
        preview.setContractNo(contract.getContractNo());
        preview.setContractStatus(contract.getStatus());
        preview.setPmsOrderId(order == null ? null : order.getId());
        preview.setPmsOrderNo(contract.getPmsOrderNo());
        preview.setOrderStatus(order == null ? null : order.getStatus());
        preview.setEligible(blockers.isEmpty());
        preview.setBlockers(blockers);
        preview.setEvidenceSnapshot(snapshotJson);
        preview.setEvidenceFingerprint(hash(snapshotJson));
        preview.setReceiptCount(receipts.size());
        preview.setActiveReceiptCount((int) activeReceiptCount);
        preview.setSettlementCount((int) settlementCount);
        return preview;
    }

    private PmsContractEntity lockContract(Long id) {
        PmsContractEntity contract = contractMapper.selectOne(new LambdaQueryWrapper<PmsContractEntity>()
                .eq(PmsContractEntity::getId, id).last("FOR UPDATE"));
        requireContractScope(contract);
        return contract;
    }

    private void requireContractScope(PmsContractEntity contract) {
        require(contract != null, "采购合同不存在");
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(factory) || Objects.equals(factory, contract.getFactoryCode()), "采购合同超出当前工厂数据范围");
    }

    private PmsContractTerminationRequestEntity requireRequest(Long id, boolean lock) {
        require(id != null, "合同终止申请不能为空");
        PmsContractTerminationRequestEntity request = lock ? requestMapper.lockById(id) : requestMapper.selectById(id);
        require(request != null, "合同终止申请不存在");
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(factory) || Objects.equals(factory, request.getFactoryCode()), "合同终止申请超出当前工厂数据范围");
        return request;
    }

    private void operation(PmsContractTerminationRequestEntity request, String requestId, String fingerprint,
                           String type, String before, String after, String content, MomUser user) {
        require(operationMapper.insert(new PmsContractTerminationOperationEntity()
                .setTerminationRequestId(request.getId()).setRequestId(requestId)
                .setRequestFingerprint(fingerprint).setOperationType(type).setBeforeStatus(before)
                .setAfterStatus(after).setContent(bounded(content, 1000, "操作说明"))
                .setOperatorCode(userCode(user)).setOperatorName(userName(user)).setOperationTime(LocalDateTime.now())
                .setFactoryCode(request.getFactoryCode())) == 1, "合同终止操作履历保存失败");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new CheckedException("合同终止证据序列化失败");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("合同终止证据摘要生成失败");
        }
    }

    private String bounded(String value, int max, String field) {
        String text = StrUtil.blankToDefault(value, "").trim();
        require(!text.isEmpty() && text.length() <= max, field + "不能为空且不能超过" + max + "字符");
        return text;
    }

    private MomUser currentUser() {
        return SecurityUtils.getUser();
    }

    private String userCode(MomUser user) {
        return user == null ? "UNKNOWN" : user.getUsername();
    }

    private String userName(MomUser user) {
        return user == null ? "未知用户" : StrUtil.blankToDefault(user.getName(), user.getUsername());
    }
}
