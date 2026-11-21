package com.kitex.mom.mes.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmCustomerPartEntity;
import com.kitex.mom.mdm.feign.RemoteCustomerPartService;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.MesReportChannelEnum;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.enums.ReportStatusEnum;
import com.kitex.mom.mes.mapper.MesStationPassRecordMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.mapper.MesToolingExecutionMapper;
import com.kitex.mom.mes.mapper.MesTravelerMapper;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;
import com.kitex.mom.mes.util.MesPredecessorPassEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 所有报工方式共用的工位防错门。模式只决定标识和计量规则，不得绕过人员、工位、工装和工序顺序。
 */
@Service
@RequiredArgsConstructor
public class MesStationPassService {
    private final MesStationPassRecordMapper passMapper;
    private final MesTravelerMapper travelerMapper;
    private final MesTaskReportMapper reportMapper;
    private final MesToolingExecutionMapper toolingMapper;
    private final MesReportNodeGate reportNodeGate;
    private final MesTravelerQuantityService travelerQuantities;
    private final RemoteCustomerPartService customerPartService;
    private final MesStationPassAttemptWriter attemptWriter;

    public void validate(MesTaskReportVO command, MesTaskArrangeEntity arrange, MesTaskEntity task,
                         ReportModeEnum mode, MesReportChannelEnum channel) {
        boolean automatic = channel.isAutomatic();
        MomUser user = SecurityUtils.getUser();
        boolean delegatedOperator = automatic || channel == MesReportChannelEnum.INTEGRATION;
        String operatorCode = delegatedOperator
                ? command.getOperatorCode() : user == null ? null : user.getUsername();
        String operatorName = delegatedOperator
                ? command.getOperatorName() : user == null ? null : user.getName();
        String fingerprint = fingerprint(command, arrange, mode, operatorCode);
        command.setStationPassFingerprint(fingerprint);

        MesStationPassRecordEntity previous = passMapper.selectOne(
                new LambdaQueryWrapper<MesStationPassRecordEntity>()
                        .eq(MesStationPassRecordEntity::getFactoryCode, arrange.getFactoryCode())
                        .eq(MesStationPassRecordEntity::getRequestId, command.getRequestId()));
        if (previous != null) {
            if (Objects.equals(previous.getRequestFingerprint(), fingerprint)
                    && "REJECTED".equals(previous.getResult())) {
                throw new CheckedException(previous.getReasonMessage());
            }
            throw new CheckedException("过站请求号已被其他业务使用，请生成新请求号后提交");
        }

        MesExecutionNodeReader.NodeContext nodeContext;
        try {
            nodeContext = reportNodeGate.validate(arrange, mode, command.getTransferCode(),
                    MesIdentifierRouteReturnCoordinator.ROUTE_RETURN.equals(command.getExecutionKind()));
        } catch (CheckedException exception) {
            reject(command, arrange, mode, fingerprint, operatorCode, operatorName,
                    "NODE_PREDECESSOR", exception.getMessage());
            return;
        }

        if (!automatic
                && (StrUtil.isBlank(operatorCode) || !split(arrange.getWorkerCode()).contains(operatorCode))) {
            String message = channel == MesReportChannelEnum.INTEGRATION
                    ? "集成报工必须提供派工任务指定的实际执行人员"
                    : "当前用户不是派工任务指定执行员工，禁止报工";
            reject(command, arrange, mode, fingerprint, operatorCode, operatorName,
                    "WRONG_OPERATOR", message);
        }
        if (channel == MesReportChannelEnum.INTEGRATION && StrUtil.isBlank(operatorName)) {
            reject(command, arrange, mode, fingerprint, operatorCode, operatorName,
                    "OPERATOR_NAME_REQUIRED", "集成报工必须提供实际执行人员名称");
        }

        String actualWorksite = StrUtil.blankToDefault(arrange.getActualWorksiteCode(), null);
        String submittedWorksite = StrUtil.blankToDefault(command.getWorksiteCode(), actualWorksite);
        if (StrUtil.isBlank(actualWorksite) || !Objects.equals(actualWorksite, submittedWorksite)) {
            reject(command, arrange, mode, fingerprint, operatorCode, operatorName,
                    "WRONG_WORKSITE", "报工工位与任务接收时确认的实际工位不一致");
        }
        command.setWorksiteCode(submittedWorksite);

        Set<String> plannedToolings = new LinkedHashSet<>(split(arrange.getPlannedToolingCodes()));
        if (!plannedToolings.isEmpty()) {
            List<MesToolingExecutionEntity> executions = toolingMapper.selectList(
                    new LambdaQueryWrapper<MesToolingExecutionEntity>()
                            .eq(MesToolingExecutionEntity::getFactoryCode, arrange.getFactoryCode())
                            .eq(MesToolingExecutionEntity::getArrangeCode, arrange.getCode())
                            .in(MesToolingExecutionEntity::getToolingCode, plannedToolings));
            Set<String> inUse = executions.stream()
                    .filter(item -> "IN_USE".equals(item.getStatus()))
                    .map(MesToolingExecutionEntity::getToolingCode)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!inUse.equals(plannedToolings)) {
                reject(command, arrange, mode, fingerprint, operatorCode, operatorName,
                        "WRONG_TOOLING", "APS锁定工装未全部处于本派工使用中，计划["
                                + String.join(",", plannedToolings) + "]，在用[" + String.join(",", inUse) + "]");
            }
        }

        if (mode == ReportModeEnum.ONE_CODE || mode == ReportModeEnum.PACKAGE_ONE_CODE) {
            if (command.getPassNum() == null || command.getPassNum().compareTo(BigDecimal.ONE) != 0) {
                reject(command, arrange, mode, fingerprint, operatorCode, operatorName,
                        "INVALID_UNIT_QUANTITY", "一物一码报工每次必须且只能报工1件");
            }
            validateOneCode(command, arrange, mode, fingerprint, operatorCode, operatorName);
        }

        if (mode == ReportModeEnum.ONE_CODE || mode == ReportModeEnum.PACKAGE_ONE_CODE
                || mode == ReportModeEnum.TRANSFER_CARD) {
            validateTravelerAndRoute(command, arrange, task, mode, fingerprint, operatorCode, operatorName);
        }
        BigDecimal travelerAvailable = null;
        if (mode == ReportModeEnum.TRANSFER_CARD
                && !MesIdentifierRouteReturnCoordinator.ROUTE_RETURN.equals(command.getExecutionKind())) {
            try {
                travelerAvailable = travelerQuantities.requireAvailable(arrange, nodeContext.predecessors(), command.getTransferCode(),
                        command.getPassNum());
            } catch (CheckedException exception) {
                reject(command, arrange, mode, fingerprint, operatorCode, operatorName,
                        "TRAVELER_QUANTITY", exception.getMessage());
            }
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("version", command.getIdentifierBindingId() == null ? "MES_STATION_PASS_V2" : "MES_STATION_PASS_BINDING_V3");
        snapshot.put("mode", mode.getCode());
        snapshot.put("taskCode", task.getCode());
        snapshot.put("arrangeCode", arrange.getCode());
        snapshot.put("routeCode", arrange.getRouteCode());
        snapshot.put("predecessorCodes", split(arrange.getPredecessorCodes()));
        if (MesFrozenExecutionGraph.NODE_EXECUTION_V1.equals(arrange.getRouteExecutionSchemaVersion())) {
            snapshot.put("version", "MES_STATION_PASS_NODE_V4");
            snapshot.put("routeExecutionSchemaVersion", arrange.getRouteExecutionSchemaVersion());
            snapshot.put("sourceRouteNodeCode", nodeContext.current().getSourceRouteNodeCode());
            snapshot.put("produceTaskId", nodeContext.current().getId().toString());
            snapshot.put("produceTaskCode", nodeContext.current().getCode());
            snapshot.put("predecessorNodes", nodeContext.predecessors().stream().map(parent -> Map.of(
                    "produceTaskId", parent.getId().toString(), "produceTaskCode", parent.getCode(),
                    "sourceRouteNodeCode", parent.getSourceRouteNodeCode(), "processCode", parent.getRouteCode())).toList());
        }
        snapshot.put("worksiteCode", submittedWorksite);
        snapshot.put("operatorCode", operatorCode);
        snapshot.put("deviceCode", command.getDeviceCode());
        snapshot.put("toolingCodes", plannedToolings);
        snapshot.put("transferCode", command.getTransferCode());
        snapshot.put("quantity", command.getPassNum());
        if (travelerAvailable != null) {
            snapshot.put("travelerAvailableBefore", travelerAvailable.stripTrailingZeros().toPlainString());
        }
        snapshot.put("executionKind", command.getExecutionKind());
        snapshot.put("routeReturnId", command.getRouteReturnId());
        snapshot.put("identifierRouteVersion", command.getIdentifierRouteVersion());
        if (command.getIdentifierBindingId() != null) {
            snapshot.put("identifierBindingId", command.getIdentifierBindingId().toString());
            snapshot.put("identifierBindingVersion", command.getIdentifierBindingVersion());
            snapshot.put("identifierNodeAction", command.getIdentifierNodeAction());
            snapshot.put("identifierBindingSnapshot", command.getIdentifierBindingSnapshot());
        }
        command.setStationPassValidationSnapshot(JSONUtil.toJsonStr(snapshot));
    }

    public void recordAccepted(MesTaskReportVO command, MesTaskArrangeEntity arrange, MesTaskEntity task,
                               ReportModeEnum mode, MesTaskReportEntity report) {
        MesStationPassRecordEntity record = baseRecord(command, arrange, mode,
                report.getWorkerCode(), report.getWorkerName());
        record.setId(IdWorker.getId());
        record.setReportId(report.getId());
        record.setResult("PASSED");
        record.setReasonCode("PASSED");
        record.setReasonMessage("过站校验通过并完成报工事务");
        record.setValidationSnapshot(command.getStationPassValidationSnapshot());
        passMapper.insert(record);

        if (mode != ReportModeEnum.TRANSFER_CARD) {
            return;
        }
        MesTravelerEntity traveler = travelerMapper.selectOne(new LambdaQueryWrapper<MesTravelerEntity>()
                .eq(MesTravelerEntity::getTenantId, arrange.getTenantId())
                .eq(MesTravelerEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesTravelerEntity::getTravelerCode, command.getTransferCode()).last("FOR UPDATE"));
        if (traveler == null) {
            traveler = new MesTravelerEntity()
                    .setTravelerCode(command.getTransferCode())
                    .setTaskCode(task.getCode())
                    .setMatCode(task.getMatCode())
                    .setMatName(task.getMatName())
                    .setInitialQuantity(command.getPassNum())
                    .setStatus(Boolean.TRUE.equals(arrange.getLastRoute()) ? "COMPLETED" : "ACTIVE")
                    .setFirstRouteCode(arrange.getRouteCode())
                    .setLatestRouteCode(arrange.getRouteCode())
                    .setLatestRouteOrder(arrange.getRouteOrder())
                    .setBindTime(LocalDateTime.now())
                    .setCompleteTime(Boolean.TRUE.equals(arrange.getLastRoute()) ? LocalDateTime.now() : null)
                    .setFactoryCode(arrange.getFactoryCode());
            traveler.setId(IdWorker.getId());
            traveler.setTenantId(arrange.getTenantId());
            travelerMapper.insert(traveler);
        } else {
            traveler.setLatestRouteCode(arrange.getRouteCode());
            traveler.setLatestRouteOrder(arrange.getRouteOrder());
            if (Boolean.TRUE.equals(arrange.getLastRoute())) {
                traveler.setStatus("COMPLETED");
                traveler.setCompleteTime(LocalDateTime.now());
            }
            travelerMapper.updateById(traveler);
        }
    }

    private void validateOneCode(MesTaskReportVO command, MesTaskArrangeEntity arrange, ReportModeEnum mode,
                                 String fingerprint, String operatorCode, String operatorName) {
        List<MdmCustomerPartEntity> rules = null;
        try {
            var response = customerPartService.getPartCodeByMatCode(arrange.getMatCode());
            rules = response == null ? null : response.getData();
        } catch (RuntimeException exception) {
            reject(command, arrange, mode, fingerprint, operatorCode, operatorName,
                    "BARCODE_RULE_UNAVAILABLE", "物料条码规则服务不可用，禁止跳过错料校验");
        }
        boolean matched;
        try {
            matched = CollUtil.isNotEmpty(rules) && rules.stream().anyMatch(rule ->
                    StrUtil.isNotBlank(rule.getRegular()) && command.getTransferCode().matches(rule.getRegular()));
        } catch (RuntimeException exception) {
            reject(command, arrange, mode, fingerprint, operatorCode, operatorName,
                    "BARCODE_RULE_INVALID", "当前物料条码规则无效，请先修正规则");
            return;
        }
        if (!matched) {
            reject(command, arrange, mode, fingerprint, operatorCode, operatorName,
                    "WRONG_MATERIAL", "扫描标识不符合当前生产物料的条码规则");
        }
    }

    private void validateTravelerAndRoute(MesTaskReportVO command, MesTaskArrangeEntity arrange, MesTaskEntity task,
                                          ReportModeEnum mode, String fingerprint,
                                          String operatorCode, String operatorName) {
        String identifier = StrUtil.blankToDefault(command.getTransferCode(), "").trim();
        if (identifier.isEmpty()) {
            reject(command, arrange, mode, fingerprint, operatorCode, operatorName,
                    "IDENTIFIER_REQUIRED", mode.getName() + "必须扫描条码或流转卡");
        }
        command.setTransferCode(identifier);

        LambdaQueryWrapper<MesTaskReportEntity> duplicateWrapper = new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, arrange.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesTaskReportEntity::getTaskCode, arrange.getTaskCode())
                .eq(MesTaskReportEntity::getProduceTaskCode, arrange.getProduceTaskCode())
                .eq(MesTaskReportEntity::getTransferCode, identifier)
                .and(item -> item.isNull(MesTaskReportEntity::getStatus)
                        .or().ne(MesTaskReportEntity::getStatus, ReportStatusEnum.REVERSED.name()));
        if (MesIdentifierRouteReturnCoordinator.ROUTE_RETURN.equals(command.getExecutionKind())) {
            duplicateWrapper.eq(MesTaskReportEntity::getRouteReturnId, command.getRouteReturnId());
        } else {
            duplicateWrapper.isNull(MesTaskReportEntity::getRouteReturnId);
        }
        boolean duplicate = reportMapper.selectList(duplicateWrapper).stream()
                .anyMatch(MesPredecessorPassEvidence::effective);
        if (duplicate) {
            reject(command, arrange, mode, fingerprint, operatorCode, operatorName,
                    "DUPLICATE_PASS", "该标识已通过当前工序，禁止重复过站");
        }

        if (mode == ReportModeEnum.TRANSFER_CARD) {
            MesTravelerEntity traveler = travelerMapper.selectOne(new LambdaQueryWrapper<MesTravelerEntity>()
                    .eq(MesTravelerEntity::getTenantId, arrange.getTenantId())
                    .eq(MesTravelerEntity::getFactoryCode, arrange.getFactoryCode())
                    .eq(MesTravelerEntity::getTravelerCode, identifier).last("FOR UPDATE"));
            if (traveler != null && (!Objects.equals(traveler.getTaskCode(), task.getCode())
                    || !Objects.equals(traveler.getMatCode(), task.getMatCode()))) {
                reject(command, arrange, mode, fingerprint, operatorCode, operatorName,
                        "WRONG_ORDER", "流转卡已绑定其他生产工单或物料，禁止串单过站");
            }
            if (traveler != null && traveler.getInitialQuantity() != null
                    && command.getPassNum().compareTo(traveler.getInitialQuantity()) > 0) {
                reject(command, arrange, mode, fingerprint, operatorCode, operatorName,
                        "QUANTITY_OVER_TRAVELER", "本次报工数量超过流转卡首工序绑定数量");
            }
        }

        // 实际节点前序已由reportNodeGate统一核验；批准退回仍使用原协调器的下一站/轮次。
    }

    private void reject(MesTaskReportVO command, MesTaskArrangeEntity arrange, ReportModeEnum mode,
                        String fingerprint, String operatorCode, String operatorName,
                        String reasonCode, String reasonMessage) {
        MesStationPassRecordEntity record = baseRecord(command, arrange, mode, operatorCode, operatorName);
        record.setResult("REJECTED");
        record.setReasonCode(reasonCode);
        record.setReasonMessage(reasonMessage);
        // 拒绝履历使用独立事务保存。载具批量操作尚在外层事务内，不能让独立事务引用
        // 尚未提交的载具回执外键；载具上下文写入快照，成功记录仍保存正式外键。
        record.setCarrierOperationId(null);
        record.setCarrierCode(null);
        Map<String, Object> rejectedSnapshot = new LinkedHashMap<>();
        rejectedSnapshot.put("version", "MES_STATION_PASS_REJECT_V2");
        rejectedSnapshot.put("reasonCode", reasonCode);
        rejectedSnapshot.put("reasonMessage", reasonMessage);
        rejectedSnapshot.put("fingerprint", fingerprint);
        rejectedSnapshot.put("carrierOperationId", command.getCarrierOperationId());
        rejectedSnapshot.put("carrierCode", command.getCarrierCode());
        record.setValidationSnapshot(JSONUtil.toJsonStr(rejectedSnapshot));
        attemptWriter.saveRejected(record);
        throw new CheckedException(reasonMessage);
    }

    private MesStationPassRecordEntity baseRecord(MesTaskReportVO command, MesTaskArrangeEntity arrange,
                                                  ReportModeEnum mode, String operatorCode, String operatorName) {
        return new MesStationPassRecordEntity()
                .setRequestId(command.getRequestId())
                .setRequestFingerprint(command.getStationPassFingerprint())
                .setReportMode(mode.getCode())
                .setTransferCode(StrUtil.blankToDefault(command.getTransferCode(), null))
                .setPassQuantity(command.getPassNum())
                .setTaskCode(arrange.getTaskCode())
                .setProduceTaskCode(arrange.getProduceTaskCode())
                .setArrangeCode(arrange.getCode())
                .setRouteCode(arrange.getRouteCode())
                .setRouteName(arrange.getRouteName())
                .setRouteOrder(arrange.getRouteOrder())
                .setPredecessorCodes(arrange.getPredecessorCodes())
                .setWorksiteCode(command.getWorksiteCode())
                .setDeviceCode(command.getDeviceCode())
                .setToolingCodes(arrange.getPlannedToolingCodes())
                .setOperatorCode(operatorCode)
                .setOperatorName(operatorName)
                .setExecutionKind(command.getExecutionKind())
                .setRouteReturnId(command.getRouteReturnId())
                .setIdentifierRouteVersion(command.getIdentifierRouteVersion())
                .setCarrierOperationId(command.getCarrierOperationId())
                .setCarrierCode(command.getCarrierCode())
                .setCheckedTime(LocalDateTime.now())
                .setFactoryCode(arrange.getFactoryCode());
    }

    private String fingerprint(MesTaskReportVO command, MesTaskArrangeEntity arrange,
                               ReportModeEnum mode, String operatorCode) {
        String canonical = String.join("|", mode.getCode(), arrange.getCode(),
                Objects.toString(command.getTransferCode(), ""),
                command.getPassNum() == null ? "" : command.getPassNum().stripTrailingZeros().toPlainString(),
                Objects.toString(command.getWorksiteCode(), ""), Objects.toString(command.getDeviceCode(), ""),
                Objects.toString(operatorCode, ""), Objects.toString(command.getExecutionKind(), "NORMAL"),
                Objects.toString(command.getRouteReturnId(), ""),
                Objects.toString(command.getIdentifierRouteVersion(), "1"),
                Objects.toString(command.getCarrierOperationId(), ""),
                Objects.toString(command.getCarrierCode(), ""));
        if (command.getIdentifierBindingId() != null) {
            // 原NONE算法不变；动态链以结构化版本事实区分具体生命周期，避免分隔符歧义。
            canonical = JSONUtil.toJsonStr(Arrays.asList("MES_STATION_PASS_BINDING_V3", canonical,
                    command.getIdentifierBindingId().toString(), command.getIdentifierBindingVersion(),
                    command.getIdentifierNodeAction(), command.getIdentifierBindingSnapshot()));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK缺少SHA-256算法", exception);
        }
    }

    private List<String> split(String csv) {
        if (StrUtil.isBlank(csv)) {
            return Collections.emptyList();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }
}
