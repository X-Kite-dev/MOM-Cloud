package com.kitex.mom.pms.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.pms.dto.PmsContractRevisionDTO;
import com.kitex.mom.pms.entity.*;
import com.kitex.mom.pms.enums.PmsContractStatusEnum;
import com.kitex.mom.pms.enums.PmsOrderStatusEnum;
import com.kitex.mom.pms.mapper.*;
import com.kitex.mom.pms.service.PmsContractRevisionService;
import com.kitex.mom.wms.enums.PurchaseReceiptStateEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 采购合同正文的版本化变更。商业数量、单价和供应商仍由采购订单受控流程决定；本流程只改变
 * 已签合同的法律正文与签约资料，并以完整内容快照、异岗复核和不可变版本保证可追溯。
 */
@Service
@RequiredArgsConstructor
public class PmsContractRevisionServiceImpl implements PmsContractRevisionService {
    private static final Set<String> REVISIONABLE = Set.of(PmsContractStatusEnum.SIGN.getCode(),
            PmsContractStatusEnum.EXECUTION.getCode());
    private static final Set<String> ORDER_ALLOWED = Set.of(PmsOrderStatusEnum.CHECK_SUCCESS.getCode(),
            PmsOrderStatusEnum.RECEIVING.getCode(), PmsOrderStatusEnum.PART_RECEIVE.getCode(),
            PmsOrderStatusEnum.COMPLETE.getCode());

    private final PmsContractRevisionRequestMapper requestMapper;
    private final PmsContractRevisionOperationMapper operationMapper;
    private final PmsContractVersionMapper versionMapper;
    private final PmsContractMapper contractMapper;
    private final PmsContractDetailMapper detailMapper;
    private final PmsOrderMapper orderMapper;
    private final WmsPurchaseReceiptMapper receiptMapper;
    private final PmsBusinessReconciliationMapper reconciliationMapper;
    private final PmsInvoiceReconciliationMapper invoiceMapper;
    private final PmsPaymentReconciliationMapper paymentMapper;
    private final PmsContractTerminationRequestMapper terminationRequestMapper;
    private final ObjectMapper objectMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    public PmsContractRevisionDTO.Preview preview(PmsContractRevisionDTO.ContentCommand command) {
        require(command != null && command.getContractId() != null, "合同正文变更必须指定合同");
        PmsContractEntity contract = contractMapper.selectById(command.getContractId());
        requireScope(contract);
        return capture(contract, normalized(command), null, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsContractRevisionDTO.Detail submit(PmsContractRevisionDTO.SubmitCommand command) {
        require(command != null && command.getContractId() != null && StrUtil.isNotBlank(command.getRequestId())
                        && StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint()) && StrUtil.isNotBlank(command.getReason()),
                "合同正文变更申请必须提供合同、请求号、证据指纹和原因");
        String requestId = bounded(command.getRequestId(), 128, "提交请求号");
        String reason = bounded(command.getReason(), 500, "变更原因");
        String requestFingerprint = hash(command.getContractId() + "|REVISION|"
                + command.getExpectedEvidenceFingerprint().trim() + '|' + reason);
        requestMapper.lockKey("SUBMIT:" + requestId);
        PmsContractRevisionOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<PmsContractRevisionOperationEntity>()
                        .eq(PmsContractRevisionOperationEntity::getRequestId, requestId));
        if (replay != null) {
            require("SUBMIT".equals(replay.getOperationType())
                            && Objects.equals(replay.getRequestFingerprint(), requestFingerprint),
                    "合同正文变更提交请求号已被不同动作或载荷使用");
            return detail(replay.getRevisionRequestId());
        }
        PmsContractEntity contract = lockContract(command.getContractId());
        PmsContractRevisionDTO.Preview preview = capture(contract, normalized(command), null, true);
        require(Objects.equals(preview.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "合同、正文或下游事实已变化，请刷新差异预览后重试");
        require(Boolean.TRUE.equals(preview.getEligible()), String.join("；", preview.getBlockers()));
        MomUser user = currentUser();
        PmsContractRevisionRequestEntity request = new PmsContractRevisionRequestEntity()
                .setRequestNo("PCR-" + IdUtil.getSnowflakeNextIdStr()).setContractId(contract.getId())
                .setContractNo(contract.getContractNo()).setPmsOrderNo(contract.getPmsOrderNo())
                .setOriginalContractStatus(contract.getStatus()).setBaseVersion(preview.getBaseVersion())
                .setTargetVersion(preview.getTargetVersion()).setStatus("SUBMITTED")
                .setSubmitRequestId(requestId).setSubmitFingerprint(requestFingerprint)
                .setEvidenceFingerprint(preview.getEvidenceFingerprint()).setEvidenceSnapshot(preview.getEvidenceSnapshot())
                .setOriginalContentSnapshot(preview.getOriginalContentSnapshot())
                .setOriginalContentFingerprint(preview.getOriginalContentFingerprint())
                .setTargetContentSnapshot(preview.getTargetContentSnapshot())
                .setTargetContentFingerprint(preview.getTargetContentFingerprint())
                .setChangeSummary(preview.getChangeSummary()).setReason(reason)
                .setReceiptCount(preview.getReceiptCount()).setActiveReceiptCount(preview.getActiveReceiptCount())
                .setSettlementCount(preview.getSettlementCount()).setApplicantCode(userCode(user))
                .setApplicantName(userName(user)).setSubmittedTime(LocalDateTime.now())
                .setFactoryCode(contract.getFactoryCode());
        require(requestMapper.insert(request) == 1, "合同正文变更申请保存失败");
        operation(request, requestId, requestFingerprint, "SUBMIT", null, "SUBMITTED",
                reason + "；目标版本V" + preview.getTargetVersion() + "；" + preview.getChangeSummary(), user);
        return detail(request.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsContractRevisionDTO.Detail review(PmsContractRevisionDTO.ReviewCommand command) {
        require(command != null && command.getRevisionRequestId() != null && StrUtil.isNotBlank(command.getRequestId())
                        && StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint()) && command.getApproved() != null
                        && StrUtil.isNotBlank(command.getReason()),
                "合同正文变更复核必须提供申请、请求号、证据指纹、决定和意见");
        String requestId = bounded(command.getRequestId(), 128, "复核请求号");
        String reason = bounded(command.getReason(), 500, "复核意见");
        String requestFingerprint = hash(command.getRevisionRequestId() + "|" + command.getApproved() + "|"
                + command.getExpectedEvidenceFingerprint().trim() + "|" + reason);
        requestMapper.lockKey("REVIEW:" + requestId);
        PmsContractRevisionOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<PmsContractRevisionOperationEntity>()
                        .eq(PmsContractRevisionOperationEntity::getRequestId, requestId));
        if (replay != null) {
            require(Objects.equals(replay.getRevisionRequestId(), command.getRevisionRequestId())
                            && Objects.equals(replay.getRequestFingerprint(), requestFingerprint),
                    "合同正文变更复核请求号已被不同申请或载荷使用");
            return detail(command.getRevisionRequestId());
        }
        PmsContractRevisionRequestEntity request = requireRequest(command.getRevisionRequestId(), true);
        require("SUBMITTED".equals(request.getStatus()), "仅待复核的合同正文变更允许审批");
        require(Objects.equals(request.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "合同正文变更申请证据指纹不一致，请刷新后重试");
        MomUser reviewer = currentUser();
        require(!Objects.equals(request.getApplicantCode(), userCode(reviewer)), "合同正文变更申请人与复核人必须分离");
        request.setReviewRequestId(requestId).setReviewFingerprint(requestFingerprint)
                .setApproved(command.getApproved()).setReviewReason(reason)
                .setReviewerCode(userCode(reviewer)).setReviewerName(userName(reviewer)).setReviewedTime(LocalDateTime.now());
        if (!Boolean.TRUE.equals(command.getApproved())) {
            request.setStatus("REJECTED");
            require(requestMapper.updateById(request) == 1, "合同正文变更驳回状态保存失败");
            operation(request, requestId, requestFingerprint, "REVIEW_REJECT", "SUBMITTED", "REJECTED", reason, reviewer);
            return detail(request.getId());
        }
        PmsContractEntity contract = lockContract(request.getContractId());
        PmsContractRevisionDTO.ContentCommand target = commandFromSnapshot(request.getTargetContentSnapshot());
        PmsContractRevisionDTO.Preview current = capture(contract, target, request.getId(), true);
        if (!Objects.equals(request.getEvidenceFingerprint(), current.getEvidenceFingerprint())
                || !Objects.equals(request.getTargetContentFingerprint(), current.getTargetContentFingerprint())
                || !Boolean.TRUE.equals(current.getEligible())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("blockers", current.getBlockers());
            result.put("currentEvidenceFingerprint", current.getEvidenceFingerprint());
            result.put("currentTargetContentFingerprint", current.getTargetContentFingerprint());
            request.setStatus("STALE").setApproved(false).setResultSnapshot(json(result));
            require(requestMapper.updateById(request) == 1, "合同正文变更失效状态保存失败");
            operation(request, requestId, requestFingerprint, "REVIEW_STALE", "SUBMITTED", "STALE",
                    current.getBlockers().isEmpty() ? "合同、正文或下游事实已变化" : String.join("；", current.getBlockers()), reviewer);
            return detail(request.getId());
        }

        List<PmsContractVersionEntity> activeVersions = versionMapper.selectList(
                new LambdaQueryWrapper<PmsContractVersionEntity>().eq(PmsContractVersionEntity::getContractId, contract.getId())
                        .eq(PmsContractVersionEntity::getStatus, "ACTIVE").last("FOR UPDATE"));
        require(activeVersions.size() <= 1, "合同存在多个活动正文版本，须先完成数据治理");
        LocalDateTime effectiveTime = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        if (activeVersions.isEmpty()) {
            PmsContractVersionEntity baseline = new PmsContractVersionEntity().setContractId(contract.getId())
                    .setContractNo(contract.getContractNo()).setVersionNo(request.getBaseVersion()).setSourceType("BASELINE")
                    .setStatus("SUPERSEDED").setContentSnapshot(request.getOriginalContentSnapshot())
                    .setContentFingerprint(request.getOriginalContentFingerprint())
                    .setEffectiveTime(Objects.requireNonNullElse(contract.getContractDate(),
                            Objects.requireNonNullElse(contract.getCreateTime(), effectiveTime)))
                    .setSupersededTime(effectiveTime).setApprovedBy("LEGACY_BASELINE")
                    .setApprovedByName("历史基线固化").setFactoryCode(contract.getFactoryCode());
            require(versionMapper.insert(baseline) == 1, "合同历史基线版本保存失败");
        } else {
            PmsContractVersionEntity active = activeVersions.get(0);
            require(Objects.equals(active.getVersionNo(), request.getBaseVersion())
                            && Objects.equals(active.getContentFingerprint(), request.getOriginalContentFingerprint()),
                    "合同活动版本与申请基线不一致");
            active.setStatus("SUPERSEDED").setSupersededTime(effectiveTime);
            require(versionMapper.updateById(active) == 1, "合同旧正文版本封存失败");
        }

        applyTarget(contract, target);
        contract.setCurrentVersion(request.getTargetVersion());
        contract.setCurrentContentFingerprint(request.getTargetContentFingerprint());
        contract.setLastRevisionRequestId(request.getId());
        contract.setLastRevisedTime(effectiveTime);
        require(contractMapper.updateById(contract) == 1, "合同当前正文版本更新失败");
        PmsContractVersionEntity version = new PmsContractVersionEntity().setContractId(contract.getId())
                .setContractNo(contract.getContractNo()).setVersionNo(request.getTargetVersion()).setSourceType("REVISION")
                .setRevisionRequestId(request.getId()).setStatus("ACTIVE")
                .setContentSnapshot(request.getTargetContentSnapshot())
                .setContentFingerprint(request.getTargetContentFingerprint()).setEffectiveTime(effectiveTime)
                .setApprovedBy(userCode(reviewer)).setApprovedByName(userName(reviewer)).setFactoryCode(contract.getFactoryCode());
        require(versionMapper.insert(version) == 1, "合同新正文版本保存失败");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractId", contract.getId());
        result.put("contractNo", contract.getContractNo());
        result.put("baseVersion", request.getBaseVersion());
        result.put("activeVersion", request.getTargetVersion());
        result.put("contentFingerprint", request.getTargetContentFingerprint());
        result.put("effectiveTime", effectiveTime);
        request.setStatus("SUCCEEDED").setCompletedTime(effectiveTime).setResultSnapshot(json(result));
        require(requestMapper.updateById(request) == 1, "合同正文变更执行结果保存失败");
        operation(request, requestId, requestFingerprint, "REVIEW_APPROVE_EXECUTE", "SUBMITTED", "SUCCEEDED",
                reason + "；V" + request.getBaseVersion() + "→V" + request.getTargetVersion() + "已生效，旧正文永久封存", reviewer);
        return detail(request.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsContractRevisionDTO.Detail withdraw(PmsContractRevisionDTO.WithdrawCommand command) {
        require(command != null && command.getRevisionRequestId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "撤回正文变更必须提供申请、请求号和原因");
        String requestId = bounded(command.getRequestId(), 128, "撤回请求号");
        String reason = bounded(command.getReason(), 500, "撤回原因");
        String fingerprint = hash(command.getRevisionRequestId() + "|WITHDRAW|" + reason);
        requestMapper.lockKey("WITHDRAW:" + requestId);
        PmsContractRevisionOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<PmsContractRevisionOperationEntity>()
                        .eq(PmsContractRevisionOperationEntity::getRequestId, requestId));
        if (replay != null) {
            require(Objects.equals(replay.getRevisionRequestId(), command.getRevisionRequestId())
                            && Objects.equals(replay.getRequestFingerprint(), fingerprint),
                    "合同正文变更撤回请求号已被不同申请或载荷使用");
            return detail(command.getRevisionRequestId());
        }
        PmsContractRevisionRequestEntity request = requireRequest(command.getRevisionRequestId(), true);
        require("SUBMITTED".equals(request.getStatus()), "只有待复核的正文变更申请允许撤回");
        MomUser user = currentUser();
        require(Objects.equals(request.getApplicantCode(), userCode(user)), "只有申请人可以撤回待复核正文变更");
        request.setStatus("WITHDRAWN").setWithdrawRequestId(requestId).setWithdrawFingerprint(fingerprint)
                .setWithdrawReason(reason).setWithdrawnTime(LocalDateTime.now());
        require(requestMapper.updateById(request) == 1, "合同正文变更撤回状态保存失败");
        operation(request, requestId, fingerprint, "WITHDRAW", "SUBMITTED", "WITHDRAWN", reason, user);
        return detail(request.getId());
    }

    @Override
    public PmsContractRevisionDTO.Detail detail(Long requestId) {
        PmsContractRevisionRequestEntity request = requireRequest(requestId, false);
        PmsContractRevisionDTO.Detail result = new PmsContractRevisionDTO.Detail();
        org.springframework.beans.BeanUtils.copyProperties(request, result);
        result.setOperations(operationMapper.selectList(new LambdaQueryWrapper<PmsContractRevisionOperationEntity>()
                .eq(PmsContractRevisionOperationEntity::getRevisionRequestId, requestId)
                .orderByAsc(PmsContractRevisionOperationEntity::getOperationTime)
                .orderByAsc(PmsContractRevisionOperationEntity::getId)));
        return result;
    }

    @Override
    public List<PmsContractRevisionRequestEntity> listByContract(Long contractId) {
        require(contractId != null, "采购合同不能为空");
        requireScope(contractMapper.selectById(contractId));
        return requestMapper.selectList(new LambdaQueryWrapper<PmsContractRevisionRequestEntity>()
                .eq(PmsContractRevisionRequestEntity::getContractId, contractId)
                .orderByDesc(PmsContractRevisionRequestEntity::getSubmittedTime)
                .orderByDesc(PmsContractRevisionRequestEntity::getId));
    }

    @Override
    public List<PmsContractVersionEntity> versions(Long contractId) {
        require(contractId != null, "采购合同不能为空");
        requireScope(contractMapper.selectById(contractId));
        return versionMapper.selectList(new LambdaQueryWrapper<PmsContractVersionEntity>()
                .eq(PmsContractVersionEntity::getContractId, contractId)
                .orderByDesc(PmsContractVersionEntity::getVersionNo));
    }

    private PmsContractRevisionDTO.Preview capture(PmsContractEntity contract,
                                                   PmsContractRevisionDTO.ContentCommand command,
                                                   Long excludeRequestId, boolean lockFacts) {
        requireScope(contract);
        List<String> blockers = new ArrayList<>();
        if (!REVISIONABLE.contains(contract.getStatus())) blockers.add("只有已签订或执行中的合同允许正文版本变更");
        if (contract.getTerminationRequestId() != null || PmsContractStatusEnum.TERMINATED.getCode().equals(contract.getStatus()))
            blockers.add("已终止合同禁止正文变更");
        validateTarget(command, blockers);
        int baseVersion = Objects.requireNonNullElse(contract.getCurrentVersion(), 1);

        List<PmsContractDetailEntity> details = detailMapper.selectList(new LambdaQueryWrapper<PmsContractDetailEntity>()
                .eq(PmsContractDetailEntity::getContractId, contract.getId())
                .orderByAsc(PmsContractDetailEntity::getId).last(lockFacts ? "FOR UPDATE" : ""));
        if (details.isEmpty()) blockers.add("合同缺少产品条款明细，须先完成数据治理");
        String originalContent = contentSnapshot(contract, details, null);
        String originalFingerprint = hash(originalContent);
        String targetContent = contentSnapshot(contract, details, command);
        String targetFingerprint = hash(targetContent);
        if (StrUtil.isNotBlank(contract.getCurrentContentFingerprint())
                && !Objects.equals(contract.getCurrentContentFingerprint(), originalFingerprint))
            blockers.add("合同当前正文与登记指纹不一致，禁止在未治理的事实之上继续变更");

        List<String> changeItems = changes(contract, command);
        if (changeItems.isEmpty()) blockers.add("目标正文与当前有效正文完全一致，无需提交版本变更");

        long activeRevisionCount = requestMapper.selectCount(new LambdaQueryWrapper<PmsContractRevisionRequestEntity>()
                .eq(PmsContractRevisionRequestEntity::getContractId, contract.getId())
                .eq(PmsContractRevisionRequestEntity::getStatus, "SUBMITTED")
                .ne(excludeRequestId != null, PmsContractRevisionRequestEntity::getId, excludeRequestId));
        if (activeRevisionCount > 0) blockers.add("合同已有待复核正文变更申请");
        long activeTerminationCount = terminationRequestMapper.selectCount(
                new LambdaQueryWrapper<PmsContractTerminationRequestEntity>()
                        .eq(PmsContractTerminationRequestEntity::getContractId, contract.getId())
                        .eq(PmsContractTerminationRequestEntity::getStatus, "SUBMITTED"));
        if (activeTerminationCount > 0) blockers.add("合同已有待复核终止申请，禁止并行修改正文");

        PmsOrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<PmsOrderEntity>()
                .eq(PmsOrderEntity::getPmsOrderNo, contract.getPmsOrderNo()).last(lockFacts ? "FOR UPDATE" : ""));
        if (order == null) blockers.add("合同关联采购订单不存在");
        else {
            if (!Objects.equals(order.getFactoryCode(), contract.getFactoryCode()))
                blockers.add("合同与采购订单工厂不一致");
            if (!Objects.equals(order.getSupplierCode(), contract.getSupplierCode()))
                blockers.add("合同与采购订单供应商不一致");
            if (!Boolean.TRUE.equals(order.getContractStatus())) blockers.add("采购订单合同关联标记缺失");
            if (!ORDER_ALLOWED.contains(order.getStatus())) blockers.add("采购订单已驳回或取消，禁止变更合同正文");
        }

        List<WmsPurchaseReceiptEntity> receipts = receiptMapper.selectList(new LambdaQueryWrapper<WmsPurchaseReceiptEntity>()
                .eq(WmsPurchaseReceiptEntity::getPmsOrderNo, contract.getPmsOrderNo())
                .orderByAsc(WmsPurchaseReceiptEntity::getId).last(lockFacts ? "FOR UPDATE" : ""));
        long activeReceiptCount = receipts.stream()
                .filter(value -> !PurchaseReceiptStateEnum.COMPLETED.getCode().equals(value.getState())).count();
        if (activeReceiptCount > 0) blockers.add("存在活动收料/IQC/待入库事实，须形成清晰业务切点后再变更正文");
        long reconciliationCount = reconciliationMapper.selectCount(new LambdaQueryWrapper<PmsBusinessReconciliationEntity>()
                .eq(PmsBusinessReconciliationEntity::getPmsOrderNo, contract.getPmsOrderNo()));
        long invoiceCount = invoiceMapper.selectCount(new LambdaQueryWrapper<PmsInvoiceReconciliationEntity>()
                .eq(PmsInvoiceReconciliationEntity::getPmsOrderNo, contract.getPmsOrderNo()));
        long paymentCount = paymentMapper.selectCount(new LambdaQueryWrapper<PmsPaymentReconciliationEntity>()
                .eq(PmsPaymentReconciliationEntity::getPmsOrderNo, contract.getPmsOrderNo()));
        long settlementCount = reconciliationCount + invoiceCount + paymentCount;
        if (settlementCount > 0) blockers.add("合同已进入对账、发票或付款链路，正文变化须走争议/补充协议流程");

        List<PmsContractVersionEntity> activeVersions = versionMapper.selectList(
                new LambdaQueryWrapper<PmsContractVersionEntity>().eq(PmsContractVersionEntity::getContractId, contract.getId())
                        .eq(PmsContractVersionEntity::getStatus, "ACTIVE").orderByAsc(PmsContractVersionEntity::getId));
        if (activeVersions.size() > 1) blockers.add("合同存在多个活动正文版本");
        if (activeVersions.isEmpty() && (baseVersion != 1 || StrUtil.isNotBlank(contract.getCurrentContentFingerprint())
                || contract.getLastRevisionRequestId() != null))
            blockers.add("合同主表已有变更结果但缺少活动版本账，须先完成数据治理");
        if (activeVersions.size() == 1 && (!Objects.equals(activeVersions.get(0).getVersionNo(), baseVersion)
                || !Objects.equals(activeVersions.get(0).getContentFingerprint(), originalFingerprint)))
            blockers.add("合同活动版本、主表版本与正文事实不一致");

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("contract", Arrays.asList(contract.getId(), contract.getContractNo(), contract.getStatus(),
                contract.getPmsOrderNo(), contract.getSupplierCode(), contract.getContractAmount(), contract.getContractDate(),
                baseVersion, contract.getCurrentContentFingerprint(), contract.getLastRevisionRequestId(),
                contract.getTerminationRequestId(), contract.getFactoryCode()));
        evidence.put("originalContentFingerprint", originalFingerprint);
        evidence.put("targetContentFingerprint", targetFingerprint);
        evidence.put("order", order == null ? null : Arrays.asList(order.getId(), order.getStatus(), order.getSupplierCode(),
                order.getContractStatus(), order.getLastChangeRequestId(), order.getChangeVersion(), order.getFactoryCode()));
        evidence.put("receipts", receipts.stream().map(value -> Arrays.asList(value.getId(), value.getBillNo(), value.getState())).toList());
        evidence.put("settlement", List.of(reconciliationCount, invoiceCount, paymentCount));
        evidence.put("activeRevisionCount", activeRevisionCount);
        evidence.put("activeTerminationCount", activeTerminationCount);
        evidence.put("activeVersions", activeVersions.stream().map(value -> Arrays.asList(value.getId(), value.getVersionNo(),
                value.getStatus(), value.getContentFingerprint())).toList());
        String evidenceJson = json(evidence);

        PmsContractRevisionDTO.Preview result = new PmsContractRevisionDTO.Preview();
        result.setContractId(contract.getId());
        result.setContractNo(contract.getContractNo());
        result.setContractStatus(contract.getStatus());
        result.setBaseVersion(baseVersion);
        result.setTargetVersion(baseVersion + 1);
        result.setEligible(blockers.isEmpty());
        result.setBlockers(blockers);
        result.setChangeItems(changeItems);
        result.setChangeSummary(String.join("、", changeItems));
        result.setEvidenceSnapshot(evidenceJson);
        result.setEvidenceFingerprint(hash(evidenceJson));
        result.setOriginalContentSnapshot(originalContent);
        result.setOriginalContentFingerprint(originalFingerprint);
        result.setTargetContentSnapshot(targetContent);
        result.setTargetContentFingerprint(targetFingerprint);
        result.setReceiptCount(receipts.size());
        result.setActiveReceiptCount((int) activeReceiptCount);
        result.setSettlementCount((int) settlementCount);
        return result;
    }

    private String contentSnapshot(PmsContractEntity contract, List<PmsContractDetailEntity> details,
                                   PmsContractRevisionDTO.ContentCommand target) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("contractId", contract.getId());
        header.put("contractNo", contract.getContractNo());
        header.put("pmsOrderNo", contract.getPmsOrderNo());
        header.put("templateCode", contract.getTemplateCode());
        header.put("templateName", contract.getTemplateName());
        header.put("supplierCode", contract.getSupplierCode());
        header.put("supplierName", contract.getSupplierName());
        header.put("contractDate", contract.getContractDate());
        header.put("contractAmount", normalize(contract.getContractAmount()));
        header.put("title", value(target, PmsContractRevisionDTO.ContentCommand::getTitle, contract.getTitle()));
        header.put("contractName", value(target, PmsContractRevisionDTO.ContentCommand::getContractName, contract.getContractName()));
        header.put("contractManager", value(target, PmsContractRevisionDTO.ContentCommand::getContractManager, contract.getContractManager()));
        header.put("contractDesc", value(target, PmsContractRevisionDTO.ContentCommand::getContractDesc, contract.getContractDesc()));
        header.put("contractDemand", value(target, PmsContractRevisionDTO.ContentCommand::getContractDemand, contract.getContractDemand()));
        header.put("contractBody", value(target, PmsContractRevisionDTO.ContentCommand::getContractBody, contract.getContractBody()));
        header.put("bodyFormat", value(target, PmsContractRevisionDTO.ContentCommand::getBodyFormat,
                StrUtil.blankToDefault(contract.getBodyFormat(), "PLAIN")));
        header.put("legalRepresentative", value(target, PmsContractRevisionDTO.ContentCommand::getLegalRepresentative, contract.getLegalRepresentative()));
        header.put("address", value(target, PmsContractRevisionDTO.ContentCommand::getAddress, contract.getAddress()));
        header.put("bank", value(target, PmsContractRevisionDTO.ContentCommand::getBank, contract.getBank()));
        header.put("account", value(target, PmsContractRevisionDTO.ContentCommand::getAccount, contract.getAccount()));
        header.put("contactPerson", value(target, PmsContractRevisionDTO.ContentCommand::getContactPerson, contract.getContactPerson()));
        header.put("phoneNumber", value(target, PmsContractRevisionDTO.ContentCommand::getPhoneNumber, contract.getPhoneNumber()));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("header", header);
        content.put("details", details.stream().map(value -> Arrays.asList(value.getId(), value.getMatCode(),
                value.getMatName(), value.getMatUnit(), value.getMatSpec(), normalizeDouble(value.getOrderNum()),
                normalize(value.getPrice()))).toList());
        return json(content);
    }

    private PmsContractRevisionDTO.ContentCommand commandFromSnapshot(String snapshot) {
        try {
            JsonNode header = objectMapper.readTree(snapshot).get("header");
            require(header != null && header.isObject(), "合同目标正文快照缺少头信息");
            PmsContractRevisionDTO.ContentCommand command = new PmsContractRevisionDTO.ContentCommand();
            JsonNode contractId = header.get("contractId");
            command.setContractId(contractId == null || contractId.isNull() ? null : contractId.longValue());
            command.setTitle(jsonText(header, "title"));
            command.setContractName(jsonText(header, "contractName"));
            command.setContractManager(jsonText(header, "contractManager"));
            command.setContractDesc(jsonText(header, "contractDesc"));
            command.setContractDemand(jsonText(header, "contractDemand"));
            command.setContractBody(jsonText(header, "contractBody"));
            command.setBodyFormat(jsonText(header, "bodyFormat"));
            command.setLegalRepresentative(jsonText(header, "legalRepresentative"));
            command.setAddress(jsonText(header, "address"));
            command.setBank(jsonText(header, "bank"));
            command.setAccount(jsonText(header, "account"));
            command.setContactPerson(jsonText(header, "contactPerson"));
            command.setPhoneNumber(jsonText(header, "phoneNumber"));
            return normalized(command);
        } catch (CheckedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CheckedException("合同目标正文快照无法解析");
        }
    }

    private String jsonText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private void applyTarget(PmsContractEntity contract, PmsContractRevisionDTO.ContentCommand target) {
        contract.setTitle(target.getTitle());
        contract.setContractName(target.getContractName());
        contract.setContractManager(target.getContractManager());
        contract.setContractDesc(target.getContractDesc());
        contract.setContractDemand(target.getContractDemand());
        contract.setContractBody(target.getContractBody());
        contract.setBodyFormat(target.getBodyFormat());
        contract.setLegalRepresentative(target.getLegalRepresentative());
        contract.setAddress(target.getAddress());
        contract.setBank(target.getBank());
        contract.setAccount(target.getAccount());
        contract.setContactPerson(target.getContactPerson());
        contract.setPhoneNumber(target.getPhoneNumber());
    }

    private List<String> changes(PmsContractEntity contract, PmsContractRevisionDTO.ContentCommand target) {
        List<String> result = new ArrayList<>();
        changed(result, "标题", contract.getTitle(), target.getTitle());
        changed(result, "合同名称", contract.getContractName(), target.getContractName());
        changed(result, "签约主体", contract.getContractManager(), target.getContractManager());
        changed(result, "重要条款", contract.getContractDesc(), target.getContractDesc());
        changed(result, "需求说明", contract.getContractDemand(), target.getContractDemand());
        changed(result, "正文全文", contract.getContractBody(), target.getContractBody());
        changed(result, "正文格式", StrUtil.blankToDefault(contract.getBodyFormat(), "PLAIN"), target.getBodyFormat());
        changed(result, "法人", contract.getLegalRepresentative(), target.getLegalRepresentative());
        changed(result, "地址", contract.getAddress(), target.getAddress());
        changed(result, "开户行", contract.getBank(), target.getBank());
        changed(result, "账户", contract.getAccount(), target.getAccount());
        changed(result, "联系人", contract.getContactPerson(), target.getContactPerson());
        changed(result, "联系电话", contract.getPhoneNumber(), target.getPhoneNumber());
        return result;
    }

    private void validateTarget(PmsContractRevisionDTO.ContentCommand target, List<String> blockers) {
        if (target == null) {
            blockers.add("目标正文不能为空");
            return;
        }
        required(target.getTitle(), 500, "合同标题", blockers);
        required(target.getContractName(), 500, "合同名称", blockers);
        required(target.getContractManager(), 500, "签约主体", blockers);
        required(target.getContractDesc(), 5000, "重要条款说明", blockers);
        required(target.getContractDemand(), 5000, "合同需求说明", blockers);
        required(target.getContractBody(), 200000, "合同正文全文", blockers);
        if (!Set.of("PLAIN", "MARKDOWN").contains(target.getBodyFormat()))
            blockers.add("正文格式仅支持PLAIN或MARKDOWN");
        optional(target.getLegalRepresentative(), 300, "法人", blockers);
        optional(target.getAddress(), 1000, "地址", blockers);
        optional(target.getBank(), 500, "开户行", blockers);
        optional(target.getAccount(), 200, "账户", blockers);
        optional(target.getContactPerson(), 300, "联系人", blockers);
        optional(target.getPhoneNumber(), 100, "联系电话", blockers);
    }

    private PmsContractRevisionDTO.ContentCommand normalized(PmsContractRevisionDTO.ContentCommand source) {
        PmsContractRevisionDTO.ContentCommand value = new PmsContractRevisionDTO.ContentCommand();
        value.setContractId(source.getContractId());
        value.setTitle(text(source.getTitle()));
        value.setContractName(text(source.getContractName()));
        value.setContractManager(text(source.getContractManager()));
        value.setContractDesc(text(source.getContractDesc()));
        value.setContractDemand(text(source.getContractDemand()));
        value.setContractBody(body(source.getContractBody()));
        value.setBodyFormat(StrUtil.blankToDefault(text(source.getBodyFormat()), "PLAIN").toUpperCase(Locale.ROOT));
        value.setLegalRepresentative(text(source.getLegalRepresentative()));
        value.setAddress(text(source.getAddress()));
        value.setBank(text(source.getBank()));
        value.setAccount(text(source.getAccount()));
        value.setContactPerson(text(source.getContactPerson()));
        value.setPhoneNumber(text(source.getPhoneNumber()));
        return value;
    }

    private PmsContractEntity lockContract(Long id) {
        PmsContractEntity contract = contractMapper.selectOne(new LambdaQueryWrapper<PmsContractEntity>()
                .eq(PmsContractEntity::getId, id).last("FOR UPDATE"));
        requireScope(contract);
        return contract;
    }

    private PmsContractRevisionRequestEntity requireRequest(Long id, boolean lock) {
        require(id != null, "合同正文变更申请不能为空");
        PmsContractRevisionRequestEntity request = lock ? requestMapper.lockById(id) : requestMapper.selectById(id);
        require(request != null, "合同正文变更申请不存在");
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(factory) || Objects.equals(factory, request.getFactoryCode()), "合同正文变更申请超出当前工厂范围");
        return request;
    }

    private void requireScope(PmsContractEntity contract) {
        require(contract != null, "采购合同不存在");
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(factory) || Objects.equals(factory, contract.getFactoryCode()), "采购合同超出当前工厂范围");
    }

    private void operation(PmsContractRevisionRequestEntity request, String requestId, String fingerprint,
                           String type, String before, String after, String content, MomUser user) {
        require(operationMapper.insert(new PmsContractRevisionOperationEntity().setRevisionRequestId(request.getId())
                .setRequestId(requestId).setRequestFingerprint(fingerprint).setOperationType(type)
                .setBeforeStatus(before).setAfterStatus(after).setContent(compact(content, 1000))
                .setOperatorCode(userCode(user)).setOperatorName(userName(user)).setOperationTime(LocalDateTime.now())
                .setFactoryCode(request.getFactoryCode())) == 1, "合同正文变更操作履历保存失败");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new CheckedException("合同正文版本证据序列化失败");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("合同正文版本证据摘要生成失败");
        }
    }

    private BigDecimal normalize(BigDecimal value) {
        if (value == null) return null;
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private BigDecimal normalizeDouble(Double value) {
        return value == null ? null : normalize(BigDecimal.valueOf(value));
    }

    private String value(PmsContractRevisionDTO.ContentCommand target,
                         java.util.function.Function<PmsContractRevisionDTO.ContentCommand, String> getter, String current) {
        return target == null ? normalizeStored(current) : getter.apply(target);
    }

    private String normalizeStored(String value) {
        return value == null ? null : value.replace("\r\n", "\n").trim();
    }

    private String text(String value) {
        String text = normalizeStored(value);
        return StrUtil.isBlank(text) ? null : text;
    }

    private String body(String value) {
        return text(value);
    }

    private void changed(List<String> values, String label, String current, String target) {
        if (!Objects.equals(normalizeStored(current), target)) values.add(label);
    }

    private void required(String value, int max, String label, List<String> blockers) {
        if (StrUtil.isBlank(value)) blockers.add(label + "不能为空");
        else if (value.length() > max) blockers.add(label + "不能超过" + max + "字符");
    }

    private void optional(String value, int max, String label, List<String> blockers) {
        if (value != null && value.length() > max) blockers.add(label + "不能超过" + max + "字符");
    }

    private String bounded(String value, int max, String label) {
        String text = StrUtil.blankToDefault(value, "").trim();
        require(!text.isEmpty() && text.length() <= max, label + "不能为空且不能超过" + max + "字符");
        return text;
    }

    private String compact(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
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
