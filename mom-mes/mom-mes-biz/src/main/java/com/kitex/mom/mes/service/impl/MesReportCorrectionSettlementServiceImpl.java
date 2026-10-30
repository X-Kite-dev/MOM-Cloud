package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesReportCorrectionSettlementDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.ReportStatusEnum;
import com.kitex.mom.mes.mapper.MesReportCorrectionSettlementMapper;
import com.kitex.mom.mes.mapper.MesReportCorrectionSettlementOperationMapper;
import com.kitex.mom.mes.mapper.MesReportReverseOperationMapper;
import com.kitex.mom.mes.service.MesReportCorrectionSettlementService;
import com.kitex.mom.mes.service.MesReportReverseRequestService;
import com.kitex.mom.mes.service.MesTaskReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 聚合报工部分纠正差异结案实现。
 *
 * <p>只允许已批准的PARTIAL_QUANTITY纠正进入本流程。系统同时核对许可用量和许可来源报工，
 * 防止以“已使用许可”冒充仍然有效的生产实绩；批准计数错误结案时会在同一事务注销剩余许可。</p>
 */
@Service
@RequiredArgsConstructor
public class MesReportCorrectionSettlementServiceImpl
        extends ServiceImpl<MesReportCorrectionSettlementMapper, MesReportCorrectionSettlementEntity>
        implements MesReportCorrectionSettlementService {

    private static final Set<String> RESOLUTION_TYPES =
            Set.of("REPORTED_COMPLETE", "COUNTING_ERROR_CONFIRMED");

    private final MesReportCorrectionSettlementOperationMapper operationMapper;
    private final MesReportReverseOperationMapper reverseOperationMapper;
    private final MesReportReverseRequestService reverseRequestService;
    private final MesTaskReportService reportService;
    private final MesActualFactoryAccessService factoryAccess;

    @Override
    @Transactional(readOnly = true)
    public IPage<MesReportCorrectionSettlementEntity> pageSettlements(
            Page<MesReportCorrectionSettlementEntity> page, MesReportCorrectionSettlementEntity query) {
        String factory = factoryAccess.require("read");
        require(page.getCurrent() > 0 && page.getSize() > 0 && page.getSize() <= 100,
                "页码或分页大小无效");
        MesReportCorrectionSettlementEntity filter = query == null
                ? new MesReportCorrectionSettlementEntity() : query;
        return page(page, new LambdaQueryWrapper<MesReportCorrectionSettlementEntity>()
                .eq(MesReportCorrectionSettlementEntity::getFactoryCode, factory)
                .eq(StrUtil.isNotBlank(filter.getStatus()), MesReportCorrectionSettlementEntity::getStatus,
                        StrUtil.trim(filter.getStatus()))
                .eq(StrUtil.isNotBlank(filter.getResolutionType()),
                        MesReportCorrectionSettlementEntity::getResolutionType,
                        StrUtil.trim(filter.getResolutionType()))
                .like(StrUtil.isNotBlank(filter.getTaskCode()), MesReportCorrectionSettlementEntity::getTaskCode,
                        StrUtil.trim(filter.getTaskCode()))
                .like(StrUtil.isNotBlank(filter.getArrangeCode()),
                        MesReportCorrectionSettlementEntity::getArrangeCode,
                        StrUtil.trim(filter.getArrangeCode()))
                .orderByDesc(MesReportCorrectionSettlementEntity::getSubmittedTime)
                .orderByDesc(MesReportCorrectionSettlementEntity::getId));
    }

    @Override
    @Transactional(readOnly = true)
    public MesReportCorrectionSettlementDTO.Evidence preview(Long reverseRequestId) {
        String factory = factoryAccess.require("read");
        SettlementEvidence evidence = evidence(reverseRequestId, factory, false);
        return toEvidence(evidence, activeSettlement(reverseRequestId, false));
    }

    @Override
    @Transactional(readOnly = true)
    public MesReportCorrectionSettlementDTO.Detail detail(Long settlementId) {
        String factory = factoryAccess.require("read");
        MesReportCorrectionSettlementEntity settlement = settlement(settlementId, factory, false);
        SettlementEvidence current = evidence(settlement.getReverseRequestId(), factory, false);
        List<MesReportCorrectionSettlementOperationEntity> operations = operationMapper.selectList(
                new LambdaQueryWrapper<MesReportCorrectionSettlementOperationEntity>()
                        .eq(MesReportCorrectionSettlementOperationEntity::getSettlementId, settlementId)
                        .orderByAsc(MesReportCorrectionSettlementOperationEntity::getId));
        return new MesReportCorrectionSettlementDTO.Detail().setSettlement(settlement)
                .setCurrentEvidence(toEvidence(current, activeSettlement(settlement.getReverseRequestId(), false)))
                .setOperations(operations);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesReportCorrectionSettlementEntity submit(MesReportCorrectionSettlementDTO.SubmitCommand command) {
        String factory = factoryAccess.require("write");
        validateSubmit(command);
        MomUser user = actor();
        String resolutionType = normalizeResolutionType(command.getResolutionType());
        String requestId = command.getRequestId().trim();
        String reference = StrUtil.trim(command.getEvidenceReference());
        String description = command.getEvidenceDescription().trim();
        String commandFingerprint = hash(Arrays.asList(command.getReverseRequestId(), resolutionType,
                command.getExpectedEvidenceFingerprint().trim(), reference, description, user.getUsername()));

        baseMapper.lockKey("COMMAND:" + requestId);
        MesReportCorrectionSettlementEntity replay = getOne(
                new LambdaQueryWrapper<MesReportCorrectionSettlementEntity>()
                        .eq(MesReportCorrectionSettlementEntity::getSubmitRequestId, requestId), false);
        if (replay != null) {
            require(Objects.equals(replay.getFactoryCode(), factory),
                    "差异结案提交请求号已在其他工厂使用");
            require(Objects.equals(replay.getSubmitFingerprint(), commandFingerprint),
                    "差异结案提交请求号已被不同命令使用");
            return replay;
        }

        SettlementEvidence current = evidence(command.getReverseRequestId(), factory, true);
        require(current.blockers().isEmpty(), String.join("；", current.blockers()));
        require(Objects.equals(current.fingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "许可或实际重报事实已变化，请刷新预览后重新申请");
        require(activeSettlement(command.getReverseRequestId(), true) == null,
                "该部分纠正已有待复核或已批准的差异结案单");
        validateResolution(resolutionType, reference, current);

        LocalDateTime now = LocalDateTime.now();
        MesReportReverseRequestEntity reverse = current.reverseRequest();
        MesReportCorrectionSettlementEntity settlement = new MesReportCorrectionSettlementEntity()
                .setSettlementNo(settlementNo(requestId, now)).setReverseRequestId(reverse.getId())
                .setReverseRequestNo(reverse.getRequestNo()).setReportId(reverse.getReportId())
                .setTaskCode(reverse.getTaskCode()).setProduceTaskCode(reverse.getProduceTaskCode())
                .setArrangeCode(reverse.getArrangeCode()).setCorrectionQuantity(current.correctionQuantity())
                .setReReportedQuantity(current.reReportedQuantity())
                .setUnresolvedQuantity(current.unresolvedQuantity()).setResolutionType(resolutionType)
                .setEvidenceReference(reference).setEvidenceDescription(description)
                .setEvidenceFingerprint(current.fingerprint()).setEvidenceSnapshot(current.json())
                .setStatus("SUBMITTED").setSubmitRequestId(requestId)
                .setSubmitFingerprint(commandFingerprint).setApplicantId(user.getId())
                .setApplicantCode(user.getUsername()).setApplicantName(name(user))
                .setSubmittedTime(now).setFactoryCode(factory);
        require(save(settlement), "聚合报工差异结案申请保存失败");
        saveOperation(settlement, requestId, commandFingerprint, "SUBMIT", null, "SUBMITTED",
                description, user, now);
        return settlement;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesReportCorrectionSettlementEntity review(MesReportCorrectionSettlementDTO.ReviewCommand command) {
        String factory = factoryAccess.require("write");
        validateReview(command);
        MomUser user = actor();
        String requestId = command.getOperationRequestId().trim();
        String commandFingerprint = hash(Arrays.asList(command.getSettlementId(), command.getApproved(),
                command.getExpectedEvidenceFingerprint().trim(), command.getReason().trim(), user.getUsername()));
        baseMapper.lockKey("COMMAND:" + requestId);
        MesReportCorrectionSettlementOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<MesReportCorrectionSettlementOperationEntity>()
                        .eq(MesReportCorrectionSettlementOperationEntity::getRequestId, requestId), false);
        if (replay != null) {
            require(Objects.equals(replay.getFactoryCode(), factory),
                    "差异结案复核请求号已在其他工厂使用");
            require(Objects.equals(replay.getRequestFingerprint(), commandFingerprint),
                    "差异结案复核请求号已被不同命令使用");
            return settlement(replay.getSettlementId(), factory, false);
        }

        MesReportCorrectionSettlementEntity settlement = settlement(command.getSettlementId(), factory, true);
        require("SUBMITTED".equals(settlement.getStatus()), "只有待复核差异结案单可审批");
        require(!Objects.equals(settlement.getApplicantId(), user.getId())
                        && !Objects.equals(settlement.getApplicantCode(), user.getUsername()),
                "差异结案申请人不能审批自己的申请");
        require(Objects.equals(settlement.getEvidenceFingerprint(),
                        command.getExpectedEvidenceFingerprint().trim()),
                "复核依据与申请冻结事实不一致");
        LocalDateTime now = LocalDateTime.now();
        if (!Boolean.TRUE.equals(command.getApproved())) {
            fillReview(settlement, false, command, commandFingerprint, user, now);
            settlement.setStatus("REJECTED").setResultMessage("差异结案申请已驳回");
            require(updateById(settlement), "差异结案驳回保存失败");
            saveOperation(settlement, requestId, commandFingerprint, "REJECT", "SUBMITTED", "REJECTED",
                    command.getReason().trim(), user, now);
            return settlement;
        }

        SettlementEvidence current = evidence(settlement.getReverseRequestId(), factory, true);
        if (!current.blockers().isEmpty()
                || !Objects.equals(current.fingerprint(), settlement.getEvidenceFingerprint())) {
            fillReview(settlement, false, command, commandFingerprint, user, now);
            settlement.setStatus("STALE").setResultMessage("批准前许可或实际重报事实已变化："
                    + (current.blockers().isEmpty() ? "事实指纹不一致" : String.join("；", current.blockers())));
            require(updateById(settlement), "差异结案失效状态保存失败");
            saveOperation(settlement, requestId, commandFingerprint, "MARK_STALE", "SUBMITTED", "STALE",
                    settlement.getResultMessage(), user, now);
            return settlement;
        }
        validateResolution(settlement.getResolutionType(), settlement.getEvidenceReference(), current);

        MesReportReverseRequestEntity reverse = current.reverseRequest();
        String permitBefore = reverse.getReReportPermitStatus();
        if ("COUNTING_ERROR_CONFIRMED".equals(settlement.getResolutionType())
                && current.unresolvedQuantity().signum() > 0 && "ACTIVE".equals(permitBefore)) {
            reverse.setReReportPermitStatus("CANCELLED");
            require(reverseRequestService.updateById(reverse), "未使用重报许可注销失败");
            savePermitCancelOperation(reverse, settlement, user, now);
        }
        fillReview(settlement, true, command, commandFingerprint, user, now);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("correctionQuantity", current.correctionQuantity());
        result.put("reReportedQuantity", current.reReportedQuantity());
        result.put("unresolvedQuantity", current.unresolvedQuantity());
        result.put("reReportCount", current.reports().size());
        result.put("permitStatusBefore", permitBefore);
        result.put("permitStatusAfter", reverse.getReReportPermitStatus());
        result.put("resolutionType", settlement.getResolutionType());
        settlement.setStatus("APPROVED").setResultSnapshot(JSONUtil.toJsonStr(result))
                .setResultMessage("REPORTED_COMPLETE".equals(settlement.getResolutionType())
                        ? "纠正数量已全部通过受控许可重新报工，差异闭环完成"
                        : "剩余差异已确认为原报工计数错误，未使用重报许可已收口");
        require(updateById(settlement), "差异结案批准回执保存失败");
        saveOperation(settlement, requestId, commandFingerprint, "APPROVE", "SUBMITTED", "APPROVED",
                command.getReason().trim(), user, now);
        return settlement;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesReportCorrectionSettlementEntity withdraw(MesReportCorrectionSettlementDTO.WithdrawCommand command) {
        String factory = factoryAccess.require("write");
        require(command != null && command.getSettlementId() != null
                        && validRequestId(command.getOperationRequestId()) && validText(command.getReason(), 500),
                "撤回必须提供结案申请、永久请求号和1至500字原因");
        MomUser user = actor();
        String requestId = command.getOperationRequestId().trim();
        String commandFingerprint = hash(Arrays.asList(command.getSettlementId(),
                command.getReason().trim(), user.getUsername()));
        baseMapper.lockKey("COMMAND:" + requestId);
        MesReportCorrectionSettlementOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<MesReportCorrectionSettlementOperationEntity>()
                        .eq(MesReportCorrectionSettlementOperationEntity::getRequestId, requestId), false);
        if (replay != null) {
            require(Objects.equals(replay.getFactoryCode(), factory),
                    "差异结案撤回请求号已在其他工厂使用");
            require(Objects.equals(replay.getRequestFingerprint(), commandFingerprint),
                    "差异结案撤回请求号已被不同命令使用");
            return settlement(replay.getSettlementId(), factory, false);
        }
        MesReportCorrectionSettlementEntity settlement = settlement(command.getSettlementId(), factory, true);
        require("SUBMITTED".equals(settlement.getStatus()), "只有待复核差异结案单可撤回");
        require(Objects.equals(settlement.getApplicantId(), user.getId())
                        && Objects.equals(settlement.getApplicantCode(), user.getUsername()),
                "只有原申请人可撤回差异结案申请");
        LocalDateTime now = LocalDateTime.now();
        settlement.setStatus("WITHDRAWN").setResultMessage("申请人已撤回差异结案申请");
        require(updateById(settlement), "差异结案申请撤回失败");
        saveOperation(settlement, requestId, commandFingerprint, "WITHDRAW", "SUBMITTED", "WITHDRAWN",
                command.getReason().trim(), user, now);
        return settlement;
    }

    private SettlementEvidence evidence(Long reverseRequestId, String factory, boolean lock) {
        require(reverseRequestId != null, "请选择部分纠正申请");
        MesReportReverseRequestEntity reverse = reverseRequestService.getOne(
                new LambdaQueryWrapper<MesReportReverseRequestEntity>()
                        .eq(MesReportReverseRequestEntity::getId, reverseRequestId)
                        .eq(MesReportReverseRequestEntity::getFactoryCode, factory)
                        .last(lock ? "FOR UPDATE" : ""), false);
        require(reverse != null, "当前租户和工厂下不存在该部分纠正申请");
        List<String> blockers = new ArrayList<>();
        if (!"APPROVED".equals(reverse.getStatus()) || !"PARTIAL_QUANTITY".equals(reverse.getCorrectionMode())) {
            blockers.add("只有已批准并执行的聚合报工部分数量纠正可进入差异结案");
        }
        BigDecimal correction = Objects.requireNonNullElse(reverse.getCorrectionQuantity(), BigDecimal.ZERO);
        BigDecimal used = Boolean.TRUE.equals(reverse.getCreateReReportPermit())
                ? Objects.requireNonNullElse(reverse.getReReportUsedQuantity(), BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal unresolved = correction.subtract(used);
        if (correction.signum() <= 0 || used.signum() < 0 || unresolved.signum() < 0) {
            blockers.add("纠正数量或重报许可用量不守恒");
        }
        List<MesTaskReportEntity> reports = Boolean.TRUE.equals(reverse.getCreateReReportPermit())
                ? reportService.list(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getCorrectionPermitId, reverse.getId())
                .eq(MesTaskReportEntity::getFactoryCode, factory)
                .orderByAsc(MesTaskReportEntity::getReportTime).orderByAsc(MesTaskReportEntity::getId)
                .last(lock ? "LIMIT 1001 FOR UPDATE" : "LIMIT 1001")) : List.of();
        if (reports.size() > 1000) {
            blockers.add("单张重报许可来源报工超过1000笔，禁止截断结案，请先合并核对业务范围");
        }
        BigDecimal reported = reports.stream().map(MesTaskReportEntity::getReportNum)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal effective = reports.stream().map(MesTaskReportEntity::getEffectiveReportNum)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (reported.compareTo(used) != 0) {
            blockers.add("许可已使用量与许可来源报工原始数量不一致");
        }
        if (effective.compareTo(used) != 0 || reports.stream()
                .anyMatch(item -> !ReportStatusEnum.EFFECTIVE.name().equals(item.getStatus()))) {
            blockers.add("许可来源报工已被冲销或再次纠正，须先处理其业务归属后再结案");
        }
        MesTaskReportEntity original = reportService.getOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getId, reverse.getReportId())
                .eq(MesTaskReportEntity::getFactoryCode, factory).last(lock ? "FOR UPDATE" : ""), false);
        if (original == null || original.getCorrectedReportNum() == null
                || original.getCorrectedReportNum().compareTo(correction) < 0) {
            blockers.add("原报工当前纠正谱系无法覆盖该申请数量");
        }

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("version", "MES_REPORT_CORRECTION_SETTLEMENT_V1");
        facts.put("reverseRequest", Arrays.asList(reverse.getId(), reverse.getRequestNo(), reverse.getStatus(),
                reverse.getReportId(), reverse.getTaskCode(), reverse.getProduceTaskCode(), reverse.getArrangeCode(),
                reverse.getCorrectionQuantity(), reverse.getReReportPermitNo(), reverse.getReReportAllowedQuantity(),
                reverse.getReReportUsedQuantity(), reverse.getReReportPermitStatus(), reverse.getReReportExpireTime()));
        facts.put("originalReport", original == null ? null : Arrays.asList(original.getId(), original.getStatus(),
                original.getReportNum(), original.getEffectiveReportNum(), original.getCorrectedReportNum(),
                original.getLastCorrectionTime()));
        facts.put("permitReports", reports.stream().limit(1000).map(item -> Arrays.asList(item.getId(), item.getRequestId(),
                item.getStatus(), item.getReportNum(), item.getEffectiveReportNum(), item.getCorrectedReportNum(),
                item.getTaskCode(), item.getProduceTaskCode(), item.getArrangeCode(), item.getReportMode(),
                item.getReportTime())).toList());
        String json = JSONUtil.toJsonStr(facts);
        return new SettlementEvidence(reverse, original, List.copyOf(reports), correction, used, unresolved,
                List.copyOf(blockers), hash(json), json);
    }

    private MesReportCorrectionSettlementDTO.Evidence toEvidence(
            SettlementEvidence evidence, MesReportCorrectionSettlementEntity active) {
        boolean clean = evidence.blockers().isEmpty();
        String permit = evidence.reverseRequest().getReReportPermitStatus();
        boolean complete = clean && evidence.unresolvedQuantity().signum() == 0 && "USED".equals(permit);
        boolean counting = clean && evidence.unresolvedQuantity().signum() > 0
                && (permit == null || Set.of("ACTIVE", "EXPIRED", "CANCELLED").contains(permit));
        return new MesReportCorrectionSettlementDTO.Evidence()
                .setReverseRequest(evidence.reverseRequest())
                .setCorrectionQuantity(evidence.correctionQuantity())
                .setReReportedQuantity(evidence.reReportedQuantity())
                .setUnresolvedQuantity(evidence.unresolvedQuantity())
                .setReReportCount(evidence.reports().size()).setPermitStatus(permit)
                .setEvidenceFingerprint(evidence.fingerprint()).setReportedCompleteAllowed(complete)
                .setCountingErrorAllowed(counting).setBlockers(evidence.blockers())
                .setActiveSettlement(active);
    }

    private void validateResolution(String resolutionType, String reference, SettlementEvidence evidence) {
        if ("REPORTED_COMPLETE".equals(resolutionType)) {
            require(evidence.unresolvedQuantity().signum() == 0
                            && "USED".equals(evidence.reverseRequest().getReReportPermitStatus()),
                    "只有纠正数量已全部通过许可重新报工且许可状态为USED时，才可按全部重报结案");
            return;
        }
        require(evidence.unresolvedQuantity().signum() > 0,
                "当前已无未重报差异，应按全部重报结案");
        require(StrUtil.isNotBlank(reference) && reference.length() <= 200,
                "确认原报工计数错误必须提供1至200字盘点单、班次记录或附件编号");
        require(!"PENDING_APPROVAL".equals(evidence.reverseRequest().getReReportPermitStatus())
                        && !"USED".equals(evidence.reverseRequest().getReReportPermitStatus()),
                "当前重报许可状态不允许按计数错误结案");
    }

    private MesReportCorrectionSettlementEntity activeSettlement(Long reverseRequestId, boolean lock) {
        return getOne(new LambdaQueryWrapper<MesReportCorrectionSettlementEntity>()
                .eq(MesReportCorrectionSettlementEntity::getReverseRequestId, reverseRequestId)
                .in(MesReportCorrectionSettlementEntity::getStatus, "SUBMITTED", "APPROVED")
                .orderByDesc(MesReportCorrectionSettlementEntity::getId)
                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"), false);
    }

    private MesReportCorrectionSettlementEntity settlement(Long id, String factory, boolean lock) {
        require(id != null, "请选择差异结案单");
        MesReportCorrectionSettlementEntity settlement = getOne(
                new LambdaQueryWrapper<MesReportCorrectionSettlementEntity>()
                        .eq(MesReportCorrectionSettlementEntity::getId, id)
                        .eq(MesReportCorrectionSettlementEntity::getFactoryCode, factory)
                        .last(lock ? "FOR UPDATE" : ""), false);
        require(settlement != null, "当前租户和工厂下不存在该差异结案单");
        return settlement;
    }

    private void fillReview(MesReportCorrectionSettlementEntity settlement, boolean approved,
                            MesReportCorrectionSettlementDTO.ReviewCommand command, String fingerprint,
                            MomUser user, LocalDateTime now) {
        settlement.setReviewRequestId(command.getOperationRequestId().trim()).setReviewFingerprint(fingerprint)
                .setApproved(approved).setReviewReason(command.getReason().trim()).setReviewerId(user.getId())
                .setReviewerCode(user.getUsername()).setReviewerName(name(user)).setReviewedTime(now);
    }

    private void saveOperation(MesReportCorrectionSettlementEntity settlement, String requestId,
                               String fingerprint, String type, String before, String after, String content,
                               MomUser user, LocalDateTime now) {
        MesReportCorrectionSettlementOperationEntity operation =
                new MesReportCorrectionSettlementOperationEntity().setSettlementId(settlement.getId())
                        .setRequestId(requestId).setRequestFingerprint(fingerprint).setOperationType(type)
                        .setBeforeStatus(before).setAfterStatus(after).setContent(content)
                        .setOperatorId(user.getId()).setOperatorCode(user.getUsername())
                        .setOperatorName(name(user)).setOperationTime(now)
                        .setFactoryCode(settlement.getFactoryCode());
        require(operationMapper.insert(operation) == 1, "差异结案操作履历保存失败");
    }

    /**
     * 在原纠正申请履历中同步留下许可注销证据，便于从任一单据方向完成审计。
     */
    private void savePermitCancelOperation(MesReportReverseRequestEntity reverse,
                                           MesReportCorrectionSettlementEntity settlement,
                                           MomUser user, LocalDateTime now) {
        String operationRequestId = "RRP-CANCEL-" + settlement.getId();
        String fingerprint = hash(Arrays.asList(reverse.getId(), reverse.getReReportPermitNo(),
                reverse.getReReportAllowedQuantity(), reverse.getReReportUsedQuantity(), settlement.getId(),
                settlement.getEvidenceFingerprint()));
        MesReportReverseOperationEntity operation = new MesReportReverseOperationEntity()
                .setReverseRequestId(reverse.getId()).setRequestId(operationRequestId)
                .setRequestFingerprint(fingerprint).setOperationType("PERMIT_CANCEL")
                .setBeforeStatus("ACTIVE").setAfterStatus("CANCELLED")
                .setContent("差异结案[" + settlement.getSettlementNo() + "]批准，注销剩余受控重报许可；允许数量="
                        + reverse.getReReportAllowedQuantity() + "，已使用数量=" + reverse.getReReportUsedQuantity())
                .setOperatorId(user.getId()).setOperatorCode(user.getUsername()).setOperatorName(name(user))
                .setOperationTime(now).setFactoryCode(reverse.getFactoryCode());
        require(reverseOperationMapper.insert(operation) == 1, "重报许可注销履历保存失败");
    }

    private void validateSubmit(MesReportCorrectionSettlementDTO.SubmitCommand command) {
        require(command != null && command.getReverseRequestId() != null
                        && validRequestId(command.getRequestId())
                        && validFingerprint(command.getExpectedEvidenceFingerprint())
                        && validText(command.getEvidenceDescription(), 1000),
                "差异结案必须提供部分纠正申请、永久请求号、事实指纹和1至1000字证据说明");
    }

    private void validateReview(MesReportCorrectionSettlementDTO.ReviewCommand command) {
        require(command != null && command.getSettlementId() != null && command.getApproved() != null
                        && validRequestId(command.getOperationRequestId())
                        && validFingerprint(command.getExpectedEvidenceFingerprint())
                        && validText(command.getReason(), 500),
                "差异结案复核必须提供申请、永久请求号、冻结指纹、结论和1至500字依据");
    }

    private String normalizeResolutionType(String value) {
        require(StrUtil.isNotBlank(value), "请选择差异结案类型");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        require(RESOLUTION_TYPES.contains(normalized),
                "差异结案类型只支持REPORTED_COMPLETE或COUNTING_ERROR_CONFIRMED");
        return normalized;
    }

    private boolean validRequestId(String value) {
        return StrUtil.isNotBlank(value) && value.trim().length() >= 8 && value.trim().length() <= 64;
    }

    private boolean validFingerprint(String value) {
        return StrUtil.isNotBlank(value) && value.trim().length() == 64;
    }

    private boolean validText(String value, int max) {
        return StrUtil.isNotBlank(value) && value.trim().length() <= max;
    }

    private MomUser actor() {
        MomUser user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && StrUtil.isNotBlank(user.getUsername()),
                "聚合报工差异结案必须使用实名用户");
        return user;
    }

    private String settlementNo(String requestId, LocalDateTime now) {
        return "RCS" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + hash(requestId).substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String name(MomUser user) {
        return StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.toString(value, "").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("生成差异结案业务指纹失败", exception);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record SettlementEvidence(MesReportReverseRequestEntity reverseRequest,
                                      MesTaskReportEntity originalReport,
                                      List<MesTaskReportEntity> reports,
                                      BigDecimal correctionQuantity,
                                      BigDecimal reReportedQuantity,
                                      BigDecimal unresolvedQuantity,
                                      List<String> blockers,
                                      String fingerprint,
                                      String json) {
    }
}
