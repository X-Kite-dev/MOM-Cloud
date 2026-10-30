package com.kitex.mom.tpm.agent.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.tpm.agent.dto.TpmRepairInvestigationDTO;
import com.kitex.mom.tpm.dto.TpmRepairRecoveryEvidence;
import com.kitex.mom.tpm.entity.TpmRepairTaskEntity;
import com.kitex.mom.tpm.entity.TpmRepairVerification;
import com.kitex.mom.tpm.entity.TpmRepairVerificationOperation;
import com.kitex.mom.tpm.entity.TpmRepairSpareStockReceiptEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsApplicationEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsInOrOutRecordEntity;
import com.kitex.mom.tpm.enums.RepairTaskTypeEnum;
import com.kitex.mom.tpm.enums.SparePartsApplicationSourceEnum;
import com.kitex.mom.tpm.mapper.TpmRepairTaskMapper;
import com.kitex.mom.tpm.mapper.TpmRepairVerificationMapper;
import com.kitex.mom.tpm.mapper.TpmRepairVerificationOperationMapper;
import com.kitex.mom.tpm.mapper.TpmRepairSpareStockReceiptMapper;
import com.kitex.mom.tpm.mapper.TpmSparePartsApplicationMapper;
import com.kitex.mom.tpm.mapper.TpmSparePartsInOrOutRecordMapper;
import com.kitex.mom.tpm.service.impl.TpmRepairVerificationService;
import com.kitex.common.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 对一条真实维修任务读取故障、维修与异岗验证事实；不执行派工、验收或设备放行。
 */
@Service
@RequiredArgsConstructor
public class TpmRepairInvestigationQueryService {

    public static final String TOOL_CODE = "TPM_REPAIR_TASK_INVESTIGATION_QUERY";

    private final TpmAgentFactoryAccessService factoryAccess;
    private final TpmRepairTaskMapper tasks;
    private final TpmRepairVerificationMapper versions;
    private final TpmRepairVerificationOperationMapper operations;
    private final TpmSparePartsApplicationMapper spareApplications;
    private final TpmRepairSpareStockReceiptMapper spareReceipts;
    private final TpmSparePartsInOrOutRecordMapper spareRecords;
    private final TpmRepairVerificationService recoveryService;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TpmRepairInvestigationDTO.Evidence query(TpmRepairInvestigationDTO.Query query) {
        if (query == null || query.getRepairTaskId() == null
                || !query.getRepairTaskId().matches("[1-9][0-9]{0,18}")) {
            throw new CheckedException("维修调查必须提供精确的正整数维修任务主键");
        }
        String factory = factoryAccess.requireRead(query.getFactoryCode());
        Long taskId;
        try {
            taskId = Long.valueOf(query.getRepairTaskId());
        } catch (NumberFormatException overflow) {
            throw new CheckedException("维修任务主键超出数据库范围");
        }
        Long tenantId = TenantContextHolder.getTenantId();
        TpmRepairTaskEntity task = tasks.selectOne(new LambdaQueryWrapper<TpmRepairTaskEntity>()
                .eq(TpmRepairTaskEntity::getId, taskId)
                .eq(TpmRepairTaskEntity::getTenantId, tenantId)
                .eq(TpmRepairTaskEntity::getFactoryCode, factory).last("LIMIT 1"));
        if (task == null) throw new CheckedException("当前租户及工厂没有该维修任务");
        requireTaskView(task.getType());

        TpmRepairVerification version = versions.selectOne(new LambdaQueryWrapper<TpmRepairVerification>()
                .eq(TpmRepairVerification::getTenantId, tenantId)
                .eq(TpmRepairVerification::getFactoryCode, factory)
                .eq(TpmRepairVerification::getTaskId, taskId)
                .orderByDesc(TpmRepairVerification::getId).last("LIMIT 1"));
        TpmRepairVerificationOperation verificationOperation = version == null ? null : operations.selectOne(
                new LambdaQueryWrapper<TpmRepairVerificationOperation>()
                        .eq(TpmRepairVerificationOperation::getTenantId, tenantId)
                        .eq(TpmRepairVerificationOperation::getFactoryCode, factory)
                        .eq(TpmRepairVerificationOperation::getVerificationId, version.getId())
                        .eq(TpmRepairVerificationOperation::getAction, "VERIFY").last("LIMIT 1"));

        TpmRepairInvestigationDTO.Evidence evidence = new TpmRepairInvestigationDTO.Evidence()
                .setToolCode(TOOL_CODE).setFactoryCode(factory)
                .setRepairTaskId(query.getRepairTaskId()).setObservedAt(LocalDateTime.now())
                .setTask(taskFact(task)).setVerification(verificationFact(version, verificationOperation))
                .setDeviceAvailabilityVerified(false).setVerifiedPartsAvailable(false);
        evidence.setDeviceHoldRecorded(versions.deviceHold(tenantId, factory, taskId) != null);
        List<java.util.Map<String, Object>> stops = versions.arrangeStops(tenantId, factory, taskId);
        evidence.setAffectedScheduleSegmentCount(stops.size());
        evidence.getEvidenceRefs().add("TPM:tpm_repair_task:" + taskId);
        if (version != null) evidence.getEvidenceRefs().add("TPM:tpm_repair_verification:" + version.getId());
        if (verificationOperation != null)
            evidence.getEvidenceRefs().add("TPM:tpm_repair_verification_operation:" + verificationOperation.getId());
        if (Boolean.TRUE.equals(evidence.getDeviceHoldRecorded()))
            evidence.getEvidenceRefs().add("TPM:tpm_repair_device_hold:task:" + taskId);
        if (!stops.isEmpty()) evidence.getEvidenceRefs().add("TPM:mes_repair_arrange_stop:task:" + taskId);

        boolean formal = "1".equals(task.getState()) && version != null
                && "VERIFIED".equals(version.getStatus()) && verificationOperation != null
                && version.getApplicantId() != null && version.getVerifierId() != null
                && verificationOperation.getOperatorId() != null
                && Objects.equals(version.getVerifierId(), verificationOperation.getOperatorId())
                && !Objects.equals(version.getApplicantId(), version.getVerifierId())
                && Boolean.TRUE.equals(verificationOperation.getFaultResolved())
                && Boolean.TRUE.equals(verificationOperation.getTrialVerified())
                && Boolean.TRUE.equals(verificationOperation.getProtectionVerified());
        evidence.setRepairClosureVerified(formal);
        if (formal) {
            readVerifiedParts(evidence, version, task);
            readSpareStockReconciliation(evidence, task);
            try {
                TpmRepairRecoveryEvidence recovery = recoveryService.recoveryEvidence(version.getId());
                if (recovery.getBlockers() != null) evidence.getRecoveryBlockers().addAll(recovery.getBlockers());
            } catch (RuntimeException invalid) {
                evidence.getDataGaps().add("TPM维修恢复证明无法核验，不能认定当前设备可恢复使用");
            }
        } else if ("1".equals(task.getState())) {
            evidence.getDataGaps().add("维修任务标记完成，但缺少同任务的异岗验证永久回执");
        } else {
            evidence.getRecoveryBlockers().add("维修任务尚未通过独立现场验证");
        }
        if (StrUtil.isBlank(task.getDeviceCode())) evidence.getDataGaps().add("维修任务缺少设备编码");
        if (StrUtil.isBlank(task.getFaultDesc())) evidence.getDataGaps().add("报修缺少故障描述");
        if (!formal && task.getExpectedRecoveryTime() == null)
            evidence.getDataGaps().add("未填写预计恢复时间，无法据此评估排程影响时长");
        if (!formal && task.getExpectedRecoveryTime() != null
                && task.getExpectedRecoveryTime().isBefore(evidence.getObservedAt()))
            evidence.getRecoveryBlockers().add("预计恢复时间已过，须由维修岗位更新预测");
        // TPM证明只证明该维修版本及其封锁状态，MDM当前设备资格仍须单独查询或现场核实。
        evidence.getLimitations().add("尚未核验MDM当前设备资格及MES复产许可；维修闭环不等于设备可开机");
        evidence.getLimitations().add("维修登记用量与TPM备件库存回执分别取证；未形成不可变回执的历史审批不推断为已出入库");
        evidence.setFactFingerprint(fingerprint(evidence));
        return evidence;
    }

    private TpmRepairInvestigationDTO.RepairTask taskFact(TpmRepairTaskEntity task) {
        return new TpmRepairInvestigationDTO.RepairTask()
                .setTaskCode(task.getTaskCode()).setRepairType(task.getType())
                .setDeviceCode(task.getDeviceCode()).setDeviceName(task.getDeviceName())
                .setFaultClassName(task.getFaultClassName()).setFaultDescription(bounded(task.getFaultDesc()))
                .setFaultReason(bounded(task.getFaultReason())).setRepairMethod(bounded(task.getRepairMethod()))
                .setRepairResult(bounded(task.getRepairResult())).setState(task.getState())
                .setSubmittedAt(task.getSubmitTime()).setExpectedRecoveryAt(task.getExpectedRecoveryTime())
                .setRecoveryForecastReason(bounded(task.getRecoveryForecastReason()))
                .setRepairedAt(task.getRepairTime());
    }

    /**
     * 任务详情按内修/委外分别授权；控制器的任一查看权仅允许进入本方法，不能代替逐行核权。
     */
    private void requireTaskView(String repairType) {
        String permission;
        if (RepairTaskTypeEnum.INTERNAL.getCode().equals(repairType)) {
            permission = "tpm_tpmRepairTask_internal_view";
        } else if (RepairTaskTypeEnum.OUTSOURCING.getCode().equals(repairType)) {
            permission = "tpm_tpmRepairTask_outsourcing_view";
        } else {
            throw new CheckedException("维修任务类型未纳入智能体授权合同");
        }
        var authentication = SecurityUtils.getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null
                || authentication.getAuthorities().stream().noneMatch(authority ->
                permission.equals(authority.getAuthority()) || "*".equals(authority.getAuthority()))) {
            throw new CheckedException("当前岗位没有该类型维修任务的查看权限");
        }
    }

    private TpmRepairInvestigationDTO.Verification verificationFact(TpmRepairVerification version,
                                                                      TpmRepairVerificationOperation operation) {
        if (version == null) return null;
        return new TpmRepairInvestigationDTO.Verification()
                .setVersionId(String.valueOf(version.getId())).setStatus(version.getStatus())
                .setSourceFingerprint(version.getSourceFingerprint())
                .setVerificationOperationId(operation == null ? null : String.valueOf(operation.getId()))
                .setDifferentVerifier(version.getVerifierId() != null
                        && version.getApplicantId() != null
                        && !Objects.equals(version.getApplicantId(), version.getVerifierId()))
                .setFaultResolved(operation == null ? null : operation.getFaultResolved())
                .setTrialVerified(operation == null ? null : operation.getTrialVerified())
                .setProtectionVerified(operation == null ? null : operation.getProtectionVerified());
    }

    private String bounded(String value) {
        if (value != null && value.length() > 2000)
            throw new CheckedException("维修文本超过单对象Agent取证上限，不能静默截断");
        return value;
    }

    /**
     * 只读取同租户、同工厂、同任务已验收版本冻结的维修明细；不按全局 taskCode 裸查无租户字段的历史明细表。
     */
    private void readVerifiedParts(TpmRepairInvestigationDTO.Evidence evidence,
                                   TpmRepairVerification version, TpmRepairTaskEntity task) {
        try {
            if (version.getWorkJson() == null || version.getWorkJson().length() > 1_000_000)
                throw new IllegalArgumentException("维修版本快照无效");
            JsonNode work = mapper.readTree(version.getWorkJson());
            JsonNode rows = work.path("detailsList");
            if (!Objects.equals(work.path("id").asText(), String.valueOf(task.getId()))
                    || !Objects.equals(work.path("tenantId").asText(), String.valueOf(task.getTenantId()))
                    || !Objects.equals(work.path("factoryCode").asText(), task.getFactoryCode())
                    || !Objects.equals(work.path("taskCode").asText(), task.getTaskCode())
                    || !rows.isArray() || rows.size() > 100)
                throw new IllegalArgumentException("维修版本与任务不一致");
            List<TpmRepairInvestigationDTO.VerifiedPart> parts = new ArrayList<>();
            for (JsonNode row : rows) {
                JsonNode quantity = row.path("useNum");
                String code = row.path("sparePartsCode").asText("");
                String name = row.path("sparePartsName").asText("");
                String unit = row.path("unit").asText("");
                if (StrUtil.isBlank(code) || code.length() > 100 || StrUtil.isBlank(unit)
                        || unit.length() > 100 || name.length() > 200 || !quantity.isNumber()
                        || quantity.decimalValue().compareTo(BigDecimal.ZERO) <= 0)
                    throw new IllegalArgumentException("维修备件明细无效");
                parts.add(new TpmRepairInvestigationDTO.VerifiedPart()
                        .setSparePartsCode(code).setSparePartsName(name)
                        .setUnit(unit).setUseNum(quantity.decimalValue()));
            }
            evidence.setVerifiedParts(parts).setVerifiedPartsAvailable(true);
        } catch (Exception invalid) {
            evidence.getDataGaps().add("已验证维修版本的备件用量快照无法核对，不能推断实际备件消耗");
        }
    }

    /**
     * 以原TPM备件审批事务自动生成的不可变回执核对出库、退回和维修登记净用量。
     */
    private void readSpareStockReconciliation(TpmRepairInvestigationDTO.Evidence evidence,
                                              TpmRepairTaskEntity task) {
        var result = new TpmRepairInvestigationDTO.SpareStockReconciliation()
                .setReceiptEvidenceAvailable(false).setQuantitiesReconciled(false);
        evidence.setSpareStockReconciliation(result);
        try {
            List<TpmSparePartsApplicationEntity> applications = spareApplications.selectList(
                    new LambdaQueryWrapper<TpmSparePartsApplicationEntity>()
                            .eq(TpmSparePartsApplicationEntity::getTenantId, task.getTenantId())
                            .eq(TpmSparePartsApplicationEntity::getFactoryCode, task.getFactoryCode())
                            .eq(TpmSparePartsApplicationEntity::getSourceCode, task.getTaskCode())
                            .eq(TpmSparePartsApplicationEntity::getApprovalStatus, "1")
                            .in(TpmSparePartsApplicationEntity::getSource,
                                    SparePartsApplicationSourceEnum.FAULT.getCode(),
                                    SparePartsApplicationSourceEnum.FAULT_RETURN.getCode())
                            .orderByAsc(TpmSparePartsApplicationEntity::getId));
            if (applications.size() > 100)
                throw new IllegalArgumentException("维修备件审批数量超过取证上限");
            Map<Long, TpmSparePartsApplicationEntity> applicationById = new HashMap<>();
            applications.forEach(application -> applicationById.put(application.getId(), application));
            List<TpmRepairSpareStockReceiptEntity> receipts = spareReceipts.selectList(
                    new LambdaQueryWrapper<TpmRepairSpareStockReceiptEntity>()
                            .eq(TpmRepairSpareStockReceiptEntity::getTenantId, task.getTenantId())
                            .eq(TpmRepairSpareStockReceiptEntity::getFactoryCode, task.getFactoryCode())
                            .eq(TpmRepairSpareStockReceiptEntity::getRepairTaskId, task.getId())
                            .orderByAsc(TpmRepairSpareStockReceiptEntity::getId));
            if (receipts.size() > 100)
                throw new IllegalArgumentException("维修备件库存回执数量超过取证上限");
            List<Long> receiptIds = receipts.stream().map(TpmRepairSpareStockReceiptEntity::getId).toList();
            List<TpmSparePartsInOrOutRecordEntity> records = receiptIds.isEmpty() ? List.of()
                    : spareRecords.selectList(new LambdaQueryWrapper<TpmSparePartsInOrOutRecordEntity>()
                    .eq(TpmSparePartsInOrOutRecordEntity::getTenantId, task.getTenantId())
                    .eq(TpmSparePartsInOrOutRecordEntity::getFactoryCode, task.getFactoryCode())
                    .in(TpmSparePartsInOrOutRecordEntity::getRepairSpareReceiptId, receiptIds)
                    .orderByAsc(TpmSparePartsInOrOutRecordEntity::getId));
            if (records.size() > 1000)
                throw new IllegalArgumentException("维修备件库存流水数量超过取证上限");
            Map<Long, List<TpmSparePartsInOrOutRecordEntity>> recordsByReceipt = records.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            TpmSparePartsInOrOutRecordEntity::getRepairSpareReceiptId));
            Map<String, BigDecimal> issued = new HashMap<>();
            Map<String, BigDecimal> returned = new HashMap<>();
            Map<String, String> names = new HashMap<>();
            Set<Long> validApplicationIds = new HashSet<>();
            boolean allReceiptsValid = true;
            for (TpmRepairSpareStockReceiptEntity receipt : receipts) {
                TpmSparePartsApplicationEntity application = applicationById.get(receipt.getApplicationId());
                List<TpmSparePartsInOrOutRecordEntity> receiptRecords = recordsByReceipt
                        .getOrDefault(receipt.getId(), List.of());
                if (!validReceipt(receipt, application, task, receiptRecords)) {
                    allReceiptsValid = false;
                    result.getDataGaps().add("备件库存回执" + receipt.getId() + "与原申请、流水或冻结指纹不一致");
                    continue;
                }
                validApplicationIds.add(receipt.getApplicationId());
                result.getReceipts().add(new TpmRepairInvestigationDTO.SpareStockReceipt()
                        .setReceiptId(String.valueOf(receipt.getId()))
                        .setApplicationId(String.valueOf(receipt.getApplicationId()))
                        .setApplyCode(receipt.getApplyCode()).setMovementType(receipt.getMovementType())
                        .setApprovedByName(receipt.getApprovedByName()).setApprovedTime(receipt.getApprovedTime())
                        .setPayloadFingerprint(receipt.getPayloadFingerprint())
                        .setRecordCount(receiptRecords.size()));
                if (evidence.getEvidenceRefs().size() < 200)
                    evidence.getEvidenceRefs().add("TPM:tpm_repair_spare_stock_receipt:" + receipt.getId());
                for (TpmSparePartsInOrOutRecordEntity record : receiptRecords) {
                    String key = partKey(record.getSparePartsCode(), record.getSparePartsUnit());
                    names.putIfAbsent(key, record.getSparePartsName());
                    ("OUT".equals(receipt.getMovementType()) ? issued : returned)
                            .merge(key, record.getOperateNum(), BigDecimal::add);
                    if (evidence.getEvidenceRefs().size() < 200)
                        evidence.getEvidenceRefs().add("TPM:tpm_spare_parts_in_or_out_record:" + record.getId());
                }
            }
            Set<Long> approvedIds = applicationById.keySet();
            if (!approvedIds.equals(validApplicationIds)) {
                Set<Long> missing = new HashSet<>(approvedIds);
                missing.removeAll(validApplicationIds);
                result.getDataGaps().add("有" + missing.size() + "条已通过维修备件申请缺少可核验的不可变库存回执");
            }
            result.setReceiptEvidenceAvailable(allReceiptsValid && approvedIds.equals(validApplicationIds)
                    && receipts.size() == approvedIds.size());
            reconcilePartBalances(evidence, result, issued, returned, names);
        } catch (RuntimeException invalid) {
            result.getDataGaps().add("TPM维修备件库存回执暂不可完整读取，不能推断实际净消耗");
        }
    }

    private boolean validReceipt(TpmRepairSpareStockReceiptEntity receipt,
                                 TpmSparePartsApplicationEntity application,
                                 TpmRepairTaskEntity task,
                                 List<TpmSparePartsInOrOutRecordEntity> records) {
        try {
            if (application == null || receipt.getPayloadJson() == null
                    || receipt.getPayloadJson().length() > 1_000_000
                    || !Objects.equals(receipt.getPayloadFingerprint(), hash(receipt.getPayloadJson()))
                    || !Objects.equals(receipt.getApplyCode(), application.getApplyCode())
                    || !Objects.equals(receipt.getRepairTaskId(), task.getId())
                    || !Objects.equals(receipt.getRepairTaskCode(), task.getTaskCode())
                    || !Objects.equals(receipt.getApprovedBy(), application.getApprovalPerson())
                    || !Objects.equals(receipt.getApprovedTime(), application.getApprovalTime())
                    || !Objects.equals(receipt.getMovementType(), application.getType().toUpperCase())
                    || !("OUT".equals(receipt.getMovementType()) || "IN".equals(receipt.getMovementType())))
                return false;
            JsonNode payload = mapper.readTree(receipt.getPayloadJson());
            JsonNode items = payload.path("items");
            if (!"TPM_REPAIR_SPARE_STOCK_V1".equals(payload.path("contract").asText())
                    || !String.valueOf(task.getTenantId()).equals(payload.path("tenantId").asText())
                    || !task.getFactoryCode().equals(payload.path("factoryCode").asText())
                    || !String.valueOf(application.getId()).equals(payload.path("applicationId").asText())
                    || !application.getApplyCode().equals(payload.path("applyCode").asText())
                    || !String.valueOf(task.getId()).equals(payload.path("repairTaskId").asText())
                    || !task.getTaskCode().equals(payload.path("repairTaskCode").asText())
                    || !receipt.getMovementType().equals(payload.path("movementType").asText())
                    || !Objects.equals(receipt.getApprovedBy(), payload.path("approvedBy").asText(null))
                    || !Objects.equals(receipt.getApprovedTime(),
                    LocalDateTime.parse(payload.path("approvedTime").asText()))
                    || !items.isArray() || items.size() != records.size()) return false;
            Map<String, TpmSparePartsInOrOutRecordEntity> recordById = new HashMap<>();
            records.forEach(record -> recordById.put(String.valueOf(record.getId()), record));
            Set<String> seen = new HashSet<>();
            for (JsonNode item : items) {
                String recordId = item.path("recordId").asText("");
                TpmSparePartsInOrOutRecordEntity record = recordById.get(recordId);
                if (record == null || !seen.add(recordId)
                        || !Objects.equals(record.getRepairSpareReceiptId(), receipt.getId())
                        || !Objects.equals(record.getOperateType().toUpperCase(), receipt.getMovementType())
                        || !Objects.equals(record.getSparePartsCode(), item.path("sparePartsCode").asText())
                        || !Objects.equals(record.getSparePartsName(), item.path("sparePartsName").asText(null))
                        || !Objects.equals(record.getSparePartsUnit(), item.path("unit").asText())
                        || !Objects.equals(record.getStoreroomCode(), item.path("storeroomCode").asText())
                        || !Objects.equals(record.getLocationCode(), item.path("locationCode").asText())
                        || !item.path("quantity").isNumber()
                        || record.getOperateNum() == null
                        || record.getOperateNum().compareTo(item.path("quantity").decimalValue()) != 0)
                    return false;
            }
            return seen.size() == records.size();
        } catch (Exception invalid) {
            return false;
        }
    }

    private void reconcilePartBalances(TpmRepairInvestigationDTO.Evidence evidence,
                                       TpmRepairInvestigationDTO.SpareStockReconciliation result,
                                       Map<String, BigDecimal> issued,
                                       Map<String, BigDecimal> returned,
                                       Map<String, String> names) {
        Map<String, BigDecimal> used = new HashMap<>();
        for (TpmRepairInvestigationDTO.VerifiedPart part : evidence.getVerifiedParts()) {
            String key = partKey(part.getSparePartsCode(), part.getUnit());
            used.merge(key, part.getUseNum(), BigDecimal::add);
            names.putIfAbsent(key, part.getSparePartsName());
        }
        Set<String> keys = new HashSet<>();
        keys.addAll(issued.keySet());
        keys.addAll(returned.keySet());
        keys.addAll(used.keySet());
        boolean reconciled = Boolean.TRUE.equals(result.getReceiptEvidenceAvailable());
        for (String key : keys.stream().sorted().toList()) {
            String[] identity = key.split("\\u001f", -1);
            BigDecimal out = issued.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal in = returned.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal net = out.subtract(in);
            BigDecimal actual = used.getOrDefault(key, BigDecimal.ZERO);
            boolean lineReconciled = net.signum() >= 0 && net.compareTo(actual) == 0;
            result.getBalances().add(new TpmRepairInvestigationDTO.SpareStockBalance()
                    .setSparePartsCode(identity[0]).setUnit(identity[1]).setSparePartsName(names.get(key))
                    .setIssuedNum(out).setReturnedNum(in).setNetIssuedNum(net)
                    .setVerifiedUseNum(actual).setReconciled(lineReconciled));
            if (!lineReconciled) {
                reconciled = false;
                result.getDataGaps().add("备件" + identity[0] + "净出库" + net
                        + "与已验证维修用量" + actual + "不一致");
            }
        }
        result.setQuantitiesReconciled(reconciled);
    }

    private String partKey(String code, String unit) {
        return code + '\u001f' + unit;
    }

    private String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String fingerprint(TpmRepairInvestigationDTO.Evidence evidence) {
        try {
            String facts = mapper.writeValueAsString(Arrays.asList(evidence.getTask(), evidence.getVerification(),
                    evidence.getRepairClosureVerified(), evidence.getDeviceHoldRecorded(),
                    evidence.getAffectedScheduleSegmentCount(), evidence.getVerifiedPartsAvailable(),
                    evidence.getVerifiedParts(), evidence.getSpareStockReconciliation(),
                    evidence.getRecoveryBlockers(), evidence.getDataGaps()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new CheckedException("维修事实无法计算完整指纹");
        }
    }
}
