package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.qms.dto.QmsCapaExternalCreateCommand;
import com.kitex.mom.qms.dto.QmsCapaLinkDTO;
import com.kitex.mom.qms.dto.req.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.QmsCapaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * CAPA/8D业务闭环。所有命令都要求幂等号，业务记录不提供通用删除或任意状态编辑。
 */
@Service
@RequiredArgsConstructor
public class QmsCapaServiceImpl extends ServiceImpl<QmsCapaMapper, QmsCapaEntity> implements QmsCapaService {
    private static final String DRAFT = "DRAFT";
    private static final String CONTAINMENT = "CONTAINMENT";
    private static final String ROOT_CAUSE = "ROOT_CAUSE";
    private static final String ACTION_PLAN = "ACTION_PLAN";
    private static final String IMPLEMENTATION = "IMPLEMENTATION";
    private static final String EFFECTIVENESS = "EFFECTIVENESS";
    private static final String CLOSED = "CLOSED";
    private static final String CANCELLED = "CANCELLED";
    private static final Set<String> ACTION_TYPES = Set.of("CONTAINMENT", "CORRECTIVE", "PREVENTIVE");
    private static final Set<String> CAUSE_TYPES = Set.of("OCCURRENCE", "ESCAPE", "SYSTEM");
    private static final Set<String> CAUSE_METHODS = Set.of("5WHY", "FISHBONE", "FAULT_TREE", "IS_IS_NOT", "OTHER");

    private final QmsCapaActionMapper actionMapper;
    private final QmsCapaCauseMapper causeMapper;
    private final QmsCapaOperationMapper operationMapper;
    private final QmsBadInfoMapper badInfoMapper;

    @Override
    public Page<QmsCapaEntity> queryPage(Page<QmsCapaEntity> page, QmsCapaEntity query) {
        requireActualContext();
        QueryWrapper<QmsCapaEntity> wrapper = QueryGenerator.initQueryWrapper(query);
        wrapper.lambda().eq(QmsCapaEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .orderByAsc(QmsCapaEntity::getStatus)
                .orderByDesc(QmsCapaEntity::getCreateTime);
        Page<QmsCapaEntity> result = this.page(page, wrapper);
        result.getRecords().forEach(this::markOverdue);
        return result;
    }

    @Override
    public QmsCapaEntity getDetail(Long id) {
        requireActualContext();
        QmsCapaEntity capa = getOne(new LambdaQueryWrapper<QmsCapaEntity>()
                .eq(QmsCapaEntity::getId, id)
                .eq(QmsCapaEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .last("LIMIT 1"), false);
        require(capa != null, "当前实际工厂未找到CAPA");
        markOverdue(capa);
        return capa;
    }

    @Override
    public List<QmsCapaActionEntity> actions(Long capaId) {
        getDetail(capaId);
        List<QmsCapaActionEntity> list = actionMapper.selectList(new LambdaQueryWrapper<QmsCapaActionEntity>()
                .eq(QmsCapaActionEntity::getCapaId, capaId)
                .orderByAsc(QmsCapaActionEntity::getCycleNo)
                .orderByAsc(QmsCapaActionEntity::getCreateTime));
        list.forEach(this::markActionOverdue);
        return list;
    }

    /** 先按原案件鉴权，再在数据库分页前限制措施范围。定位不到不回退到其他措施。 */
    @Override
    public Page<QmsCapaActionEntity> actionPage(Long capaId, long current, long size, Integer cycleNo, String status, Long actionId) {
        require(capaId != null && capaId > 0 && current > 0 && current <= 100000 && size > 0 && size <= 100,
                "措施分页范围无效，每页最多100条");
        require(cycleNo == null || cycleNo > 0, "措施周期必须为正数");
        require(actionId == null || actionId > 0, "原措施主键无效");
        require(status == null || status.isBlank() || Set.of("PENDING_ACCEPT", "IN_PROGRESS", "COMPLETED", "REJECTED", "CANCELLED").contains(status),
                "措施状态不合法");
        getDetail(capaId);
        var result = actionMapper.selectPage(new Page<QmsCapaActionEntity>(current, size), new LambdaQueryWrapper<QmsCapaActionEntity>()
                .eq(QmsCapaActionEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(QmsCapaActionEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(QmsCapaActionEntity::getCapaId, capaId)
                .eq(cycleNo != null, QmsCapaActionEntity::getCycleNo, cycleNo)
                .eq(status != null && !status.isBlank(), QmsCapaActionEntity::getStatus, status)
                .eq(actionId != null, QmsCapaActionEntity::getId, actionId)
                .orderByDesc(QmsCapaActionEntity::getCycleNo).orderByDesc(QmsCapaActionEntity::getId));
        result.getRecords().forEach(this::markActionOverdue);
        return result;
    }

    /** 展示派生逾期状态，不修改原期限、执行状态或有效性验证。 */
    private void markActionOverdue(QmsCapaActionEntity item) {
        item.setOverdueFlag(item.getDueTime() != null && item.getDueTime().isBefore(LocalDateTime.now())
                && !Set.of("COMPLETED", "REJECTED", "CANCELLED").contains(item.getStatus()));
    }

    @Override
    public List<QmsCapaCauseEntity> causes(Long capaId) {
        getDetail(capaId);
        return causeMapper.selectList(new LambdaQueryWrapper<QmsCapaCauseEntity>()
                .eq(QmsCapaCauseEntity::getCapaId, capaId)
                .orderByAsc(QmsCapaCauseEntity::getCycleNo)
                .orderByAsc(QmsCapaCauseEntity::getCreateTime));
    }

    @Override
    public List<QmsCapaOperationEntity> operations(Long capaId) {
        getDetail(capaId);
        return operationMapper.selectList(new LambdaQueryWrapper<QmsCapaOperationEntity>()
                .eq(QmsCapaOperationEntity::getCapaId, capaId)
                .orderByAsc(QmsCapaOperationEntity::getOperationTime)
                .orderByAsc(QmsCapaOperationEntity::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(QmsCapaCreateReq req) {
        validateCreate(req);
        requireActualContext();
        QmsCapaEntity duplicate = getOne(new LambdaQueryWrapper<QmsCapaEntity>()
                .eq(QmsCapaEntity::getRequestId, req.getRequestId()), false);
        if (duplicate != null) return duplicate.getId();

        QmsBadInfoEntity source = badInfoMapper.selectOne(new LambdaQueryWrapper<QmsBadInfoEntity>()
                .eq(QmsBadInfoEntity::getId, req.getSourceBadId())
                .eq(QmsBadInfoEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("FOR UPDATE"));
        require(source != null, "当前实际工厂未找到来源NCR");
        Long openCount = baseMapper.selectCount(new LambdaQueryWrapper<QmsCapaEntity>()
                .eq(QmsCapaEntity::getSourceType, "NCR")
                .eq(QmsCapaEntity::getSourceId, source.getId())
                .notIn(QmsCapaEntity::getStatus, CLOSED, CANCELLED));
        require(openCount == 0, "该NCR已有未关闭CAPA，不能重复发起");

        QmsCapaEntity capa = new QmsCapaEntity()
                .setCapaNo("CAPA-" + IdUtil.getSnowflakeNextIdStr())
                .setRequestId(req.getRequestId()).setSourceType("NCR").setSourceId(source.getId())
                .setSourceNo(source.getNcrNo()).setTitle(req.getTitle()).setProblemStatement(req.getProblemStatement())
                .setImpactScope(req.getImpactScope()).setTriggerReason(req.getTriggerReason())
                .setSeverity(req.getSeverity()).setOccurrence(req.getOccurrence()).setDetection(req.getDetection())
                .setRiskPriorityNo(req.getSeverity() * req.getOccurrence() * req.getDetection())
                .setTeamMembers(req.getTeamMembers()).setCoordinatorCode(req.getCoordinatorCode())
                .setCoordinatorName(req.getCoordinatorName()).setStatus(DRAFT).setCycleNo(1)
                .setRecurrenceCount(0).setDueTime(req.getDueTime()).setFactoryCode(source.getFactoryCode());
        save(capa);
        source.setCapaId(capa.getId()).setCapaStatus(DRAFT).setCapaRequiredReason(req.getTriggerReason());
        badInfoMapper.updateById(source);
        saveOperation(capa, req.getRequestId(), "CAPA", capa.getId(), "CREATE", null, DRAFT,
                "由NCR " + source.getNcrNo() + " 发起，RPN=" + capa.getRiskPriorityNo());
        return capa.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsCapaLinkDTO createExternal(QmsCapaExternalCreateCommand command) {
        validateExternalCreate(command);
        QmsCapaEntity duplicate = getOne(new LambdaQueryWrapper<QmsCapaEntity>()
                .eq(QmsCapaEntity::getRequestId, command.getRequestId()), false);
        if (duplicate != null) return toLink(duplicate);

        QmsCapaEntity open = getOne(new LambdaQueryWrapper<QmsCapaEntity>()
                .eq(QmsCapaEntity::getSourceType, command.getSourceType())
                .eq(QmsCapaEntity::getSourceId, command.getSourceId())
                .notIn(QmsCapaEntity::getStatus, CLOSED, CANCELLED)
                .orderByDesc(QmsCapaEntity::getId).last("LIMIT 1"), false);
        if (open != null) return toLink(open);

        String factory = FactoryContextHolder.getFactoryCode();
        QmsCapaEntity capa = new QmsCapaEntity()
                .setCapaNo("CAPA-" + IdUtil.getSnowflakeNextIdStr())
                .setRequestId(command.getRequestId()).setSourceType(command.getSourceType())
                .setSourceId(command.getSourceId()).setSourceNo(command.getSourceNo())
                .setTitle(command.getTitle()).setProblemStatement(command.getProblemStatement())
                .setImpactScope(command.getImpactScope()).setTriggerReason(command.getTriggerReason())
                .setSeverity(command.getSeverity()).setOccurrence(command.getOccurrence())
                .setDetection(command.getDetection())
                .setRiskPriorityNo(command.getSeverity() * command.getOccurrence() * command.getDetection())
                .setTeamMembers(command.getTeamMembers()).setCoordinatorCode(command.getCoordinatorCode())
                .setCoordinatorName(command.getCoordinatorName()).setStatus(DRAFT).setCycleNo(1)
                .setRecurrenceCount(0).setDueTime(command.getDueTime()).setFactoryCode(factory);
        save(capa);
        saveOperation(capa, command.getRequestId(), "CAPA", capa.getId(), "CREATE", null, DRAFT,
                "由" + command.getSourceType() + "来源" + command.getSourceNo()
                        + "发起，RPN=" + capa.getRiskPriorityNo() + "；来源证据=" + command.getSourceEvidence());
        return toLink(capa);
    }

    @Override
    public QmsCapaLinkDTO getExternalLink(Long id) {
        require(id != null, "CAPA不能为空");
        requireActualContext();
        QmsCapaEntity capa = getOne(new LambdaQueryWrapper<QmsCapaEntity>()
                .eq(QmsCapaEntity::getId, id)
                .eq(QmsCapaEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("LIMIT 1"), false);
        require(capa != null, "当前实际工厂未找到CAPA");
        return toLink(capa);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(QmsCapaStageReq req) {
        validateStage(req);
        if (hasOperation(req.getRequestId())) return;
        QmsCapaEntity capa = lockCapa(req.getCapaId());
        require(capa != null, "CAPA不存在");
        requireCoordinator(capa);
        require(DRAFT.equals(capa.getStatus()), "仅草稿CAPA允许提交");
        require(StrUtil.isNotBlank(capa.getTeamMembers()), "提交前必须明确跨职能团队成员");
        changeStatus(capa, req.getRequestId(), "SUBMIT", CONTAINMENT,
                StrUtil.blankToDefault(req.getRemark(), "提交CAPA并进入紧急围堵"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addAction(QmsCapaActionAddReq req) {
        validateAction(req);
        QmsCapaActionEntity duplicate = actionMapper.selectOne(new LambdaQueryWrapper<QmsCapaActionEntity>()
                .eq(QmsCapaActionEntity::getRequestId, req.getRequestId()));
        if (duplicate != null) return duplicate.getId();
        QmsCapaEntity capa = lockCapa(req.getCapaId());
        require(capa != null, "CAPA不存在");
        requireCoordinator(capa);
        if ("CONTAINMENT".equals(req.getActionType())) {
            require(CONTAINMENT.equals(capa.getStatus()), "围堵措施只能在围堵阶段建立");
        } else {
            require(ACTION_PLAN.equals(capa.getStatus()), "纠正/预防措施只能在措施策划阶段建立");
        }
        QmsCapaActionEntity action = new QmsCapaActionEntity()
                .setActionNo("CAPAA-" + IdUtil.getSnowflakeNextIdStr()).setRequestId(req.getRequestId())
                .setCapaId(capa.getId()).setCycleNo(capa.getCycleNo()).setActionType(req.getActionType())
                .setTitle(req.getTitle()).setDescription(req.getDescription()).setOwnerCode(req.getOwnerCode())
                .setOwnerName(req.getOwnerName()).setDueTime(req.getDueTime()).setStatus("PENDING_ACCEPT")
                .setFactoryCode(capa.getFactoryCode());
        actionMapper.insert(action);
        saveOperation(capa, req.getRequestId(), "ACTION", action.getId(), "ACTION_ASSIGN", null,
                action.getStatus(), req.getActionType() + "：" + req.getTitle() + "，责任人=" + req.getOwnerName());
        return action.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acceptAction(QmsCapaActionAcceptReq req) {
        require(req != null && req.getActionId() != null, "措施不能为空");
        require(StrUtil.isNotBlank(req.getRequestId()), "请求号不能为空");
        require(req.getAccepted() != null, "接单结论不能为空");
        if (hasOperation(req.getRequestId())) return;
        QmsCapaActionEntity action = lockAction(req.getActionId());
        require(action != null, "措施不存在");
        QmsCapaEntity capa = lockCapa(action.getCapaId());
        require(capa != null && Objects.equals(capa.getCycleNo(), action.getCycleNo()), "措施不属于当前CAPA周期");
        require("PENDING_ACCEPT".equals(action.getStatus()), "当前措施无需重复接单");
        require(Objects.equals(action.getOwnerCode(), currentUserCode()), "仅被指派责任人可以接单或拒绝");
        if (!req.getAccepted()) require(StrUtil.isNotBlank(req.getRemark()), "拒绝接单必须填写原因");
        String before = action.getStatus();
        action.setStatus(req.getAccepted() ? "IN_PROGRESS" : "REJECTED")
                .setAcceptanceRemark(req.getRemark()).setAcceptedTime(LocalDateTime.now());
        actionMapper.updateById(action);
        saveOperation(capa, req.getRequestId(), "ACTION", action.getId(),
                req.getAccepted() ? "ACTION_ACCEPT" : "ACTION_REJECT", before, action.getStatus(),
                StrUtil.blankToDefault(req.getRemark(), "责任人已接单"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelAction(QmsCapaActionCancelReq req) {
        require(req != null && req.getActionId() != null, "措施不能为空");
        require(StrUtil.isNotBlank(req.getRequestId()), "请求号不能为空");
        require(StrUtil.isNotBlank(req.getReason()), "撤销原因不能为空");
        if (hasOperation(req.getRequestId())) return;
        QmsCapaActionEntity action = lockAction(req.getActionId());
        require(action != null, "措施不存在");
        QmsCapaEntity capa = lockCapa(action.getCapaId());
        require(capa != null && Objects.equals(capa.getCycleNo(), action.getCycleNo()), "措施不属于当前CAPA周期");
        requireCoordinator(capa);
        require(Set.of("PENDING_ACCEPT", "REJECTED").contains(action.getStatus()), "仅未接单或已拒绝措施允许撤销");
        if ("CONTAINMENT".equals(action.getActionType())) {
            require(CONTAINMENT.equals(capa.getStatus()), "当前阶段不能撤销围堵措施");
        } else {
            require(ACTION_PLAN.equals(capa.getStatus()), "当前阶段不能撤销纠正/预防措施");
        }
        String before = action.getStatus();
        action.setStatus("CANCELLED").setAcceptanceRemark(req.getReason());
        actionMapper.updateById(action);
        saveOperation(capa, req.getRequestId(), "ACTION", action.getId(), "ACTION_CANCEL", before,
                action.getStatus(), "协调人撤销措施：" + req.getReason());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeAction(QmsCapaActionCompleteReq req) {
        require(req != null && req.getActionId() != null, "措施不能为空");
        require(StrUtil.isNotBlank(req.getRequestId()), "请求号不能为空");
        require(StrUtil.isNotBlank(req.getResult()) && StrUtil.isNotBlank(req.getEvidence()),
                "措施完成结果和客观证据不能为空");
        if (hasOperation(req.getRequestId())) return;
        QmsCapaActionEntity action = lockAction(req.getActionId());
        require(action != null, "措施不存在");
        QmsCapaEntity capa = lockCapa(action.getCapaId());
        require(capa != null && Objects.equals(capa.getCycleNo(), action.getCycleNo()), "措施不属于当前CAPA周期");
        require("IN_PROGRESS".equals(action.getStatus()), "仅已接单执行中的措施允许完工");
        require(Objects.equals(action.getOwnerCode(), currentUserCode()), "仅措施责任人可以提交完工");
        String before = action.getStatus();
        action.setStatus("COMPLETED").setCompletionResult(req.getResult()).setEvidence(req.getEvidence())
                .setCompletedBy(currentUserCode()).setCompletedByName(currentUserName())
                .setCompletedTime(LocalDateTime.now());
        actionMapper.updateById(action);
        saveOperation(capa, req.getRequestId(), "ACTION", action.getId(), "ACTION_COMPLETE",
                before, action.getStatus(), "措施完成：" + req.getResult() + "，证据=" + req.getEvidence());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addCause(QmsCapaCauseAddReq req) {
        require(req != null && req.getCapaId() != null, "CAPA不能为空");
        require(StrUtil.isNotBlank(req.getRequestId()), "请求号不能为空");
        require(CAUSE_TYPES.contains(req.getCauseType()), "根因类型无效");
        require(CAUSE_METHODS.contains(req.getAnalysisMethod()), "根因分析方法无效");
        require(StrUtil.isNotBlank(req.getCauseDescription()) && StrUtil.isNotBlank(req.getEvidence()),
                "根因描述和验证证据不能为空");
        QmsCapaCauseEntity duplicate = causeMapper.selectOne(new LambdaQueryWrapper<QmsCapaCauseEntity>()
                .eq(QmsCapaCauseEntity::getRequestId, req.getRequestId()));
        if (duplicate != null) return duplicate.getId();
        QmsCapaEntity capa = lockCapa(req.getCapaId());
        require(capa != null, "CAPA不存在");
        require(ROOT_CAUSE.equals(capa.getStatus()), "当前不在根因分析阶段");
        QmsCapaCauseEntity cause = new QmsCapaCauseEntity()
                .setCauseNo("CAPAC-" + IdUtil.getSnowflakeNextIdStr()).setRequestId(req.getRequestId())
                .setCapaId(capa.getId()).setCycleNo(capa.getCycleNo()).setCauseType(req.getCauseType())
                .setAnalysisMethod(req.getAnalysisMethod()).setCauseDescription(req.getCauseDescription())
                .setEvidence(req.getEvidence()).setProposedBy(currentUserCode()).setProposedByName(currentUserName())
                .setProposedTime(LocalDateTime.now()).setVerificationStatus("PENDING")
                .setFactoryCode(capa.getFactoryCode());
        causeMapper.insert(cause);
        saveOperation(capa, req.getRequestId(), "CAUSE", cause.getId(), "CAUSE_PROPOSE", null,
                "PENDING", req.getCauseType() + "根因假设：" + req.getCauseDescription());
        return cause.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void verifyCause(QmsCapaCauseVerifyReq req) {
        require(req != null && req.getCauseId() != null, "根因不能为空");
        require(StrUtil.isNotBlank(req.getRequestId()), "请求号不能为空");
        require(Set.of("CONFIRMED", "REJECTED").contains(req.getResult()), "根因验证结论无效");
        require(StrUtil.isNotBlank(req.getRemark()), "根因验证说明不能为空");
        if (hasOperation(req.getRequestId())) return;
        QmsCapaCauseEntity cause = lockCause(req.getCauseId());
        require(cause != null, "根因不存在");
        QmsCapaEntity capa = lockCapa(cause.getCapaId());
        require(capa != null && ROOT_CAUSE.equals(capa.getStatus())
                && Objects.equals(capa.getCycleNo(), cause.getCycleNo()), "根因不属于当前分析周期");
        require("PENDING".equals(cause.getVerificationStatus()), "根因已完成验证");
        require(!Objects.equals(cause.getProposedBy(), currentUserCode()), "根因提出人与验证人必须独立");
        String before = cause.getVerificationStatus();
        cause.setVerificationStatus(req.getResult()).setVerificationRemark(req.getRemark())
                .setVerifierCode(currentUserCode()).setVerifierName(currentUserName())
                .setVerificationTime(LocalDateTime.now());
        causeMapper.updateById(cause);
        saveOperation(capa, req.getRequestId(), "CAUSE", cause.getId(), "CAUSE_VERIFY", before,
                cause.getVerificationStatus(), "根因验证=" + req.getResult() + "，" + req.getRemark());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void advance(QmsCapaStageReq req) {
        validateStage(req);
        if (hasOperation(req.getRequestId())) return;
        QmsCapaEntity capa = lockCapa(req.getCapaId());
        require(capa != null, "CAPA不存在");
        requireCoordinator(capa);
        String next;
        String content;
        if (CONTAINMENT.equals(capa.getStatus())) {
            List<QmsCapaActionEntity> containment = currentActions(capa, "CONTAINMENT");
            require(!containment.isEmpty(), "至少建立一项紧急围堵措施");
            require(containment.stream().allMatch(item -> "COMPLETED".equals(item.getStatus())),
                    "所有有效围堵措施完成并提交证据后才能进入根因分析");
            next = ROOT_CAUSE;
            content = "围堵完成，进入发生根因与逃逸根因分析";
        } else if (ROOT_CAUSE.equals(capa.getStatus())) {
            List<QmsCapaCauseEntity> confirmed = currentCauses(capa).stream()
                    .filter(item -> "CONFIRMED".equals(item.getVerificationStatus())).toList();
            require(confirmed.stream().anyMatch(item -> "OCCURRENCE".equals(item.getCauseType())),
                    "至少确认一项发生根因");
            require(confirmed.stream().anyMatch(item -> "ESCAPE".equals(item.getCauseType())),
                    "至少确认一项逃逸根因");
            next = ACTION_PLAN;
            content = "发生根因与逃逸根因已独立确认，进入纠正/预防措施策划";
        } else if (ACTION_PLAN.equals(capa.getStatus())) {
            List<QmsCapaActionEntity> plans = currentExecutionActions(capa);
            require(plans.stream().anyMatch(item -> "CORRECTIVE".equals(item.getActionType())),
                    "至少建立一项纠正措施");
            require(plans.stream().anyMatch(item -> "PREVENTIVE".equals(item.getActionType())),
                    "至少建立一项预防措施");
            require(plans.stream().allMatch(item -> Set.of("IN_PROGRESS", "COMPLETED").contains(item.getStatus())),
                    "所有有效纠正/预防措施须由责任人接单后才能进入实施阶段");
            next = IMPLEMENTATION;
            content = "措施责任人均已接单，进入实施阶段";
        } else if (IMPLEMENTATION.equals(capa.getStatus())) {
            List<QmsCapaActionEntity> plans = currentExecutionActions(capa);
            require(!plans.isEmpty() && plans.stream().allMatch(item -> "COMPLETED".equals(item.getStatus())),
                    "所有有效纠正/预防措施完成并提交证据后才能观察有效性");
            require(StrUtil.isNotBlank(req.getEffectivenessCriteria()), "有效性判定标准不能为空");
            require(req.getEffectivenessReviewTime() != null
                    && req.getEffectivenessReviewTime().isAfter(LocalDateTime.now()), "有效性观察日期必须晚于当前时间");
            require(StrUtil.isNotBlank(req.getStandardizationScope()), "必须明确防再发的标准化范围");
            capa.setEffectivenessCriteria(req.getEffectivenessCriteria())
                    .setEffectivenessReviewTime(req.getEffectivenessReviewTime())
                    .setStandardizationScope(req.getStandardizationScope());
            next = EFFECTIVENESS;
            content = "措施实施完成，进入有效性观察；标准=" + req.getEffectivenessCriteria()
                    + "，标准化范围=" + req.getStandardizationScope();
        } else {
            throw new CheckedException("当前CAPA状态不允许推进");
        }
        changeStatus(capa, req.getRequestId(), "ADVANCE", next,
                content + (StrUtil.isBlank(req.getRemark()) ? "" : "，说明=" + req.getRemark()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void verifyEffectiveness(QmsCapaEffectivenessReq req) {
        require(req != null && req.getCapaId() != null, "CAPA不能为空");
        require(StrUtil.isNotBlank(req.getRequestId()), "请求号不能为空");
        require(Set.of("PASS", "FAIL").contains(req.getResult()), "有效性结论必须为PASS或FAIL");
        require(StrUtil.isNotBlank(req.getRemark()) && StrUtil.isNotBlank(req.getEvidence()),
                "有效性结论和客观证据不能为空");
        if (hasOperation(req.getRequestId())) return;
        QmsCapaEntity capa = lockCapa(req.getCapaId());
        require(capa != null && EFFECTIVENESS.equals(capa.getStatus()), "当前CAPA不在有效性验证阶段");
        require(capa.getEffectivenessReviewTime() != null
                && !LocalDateTime.now().isBefore(capa.getEffectivenessReviewTime()), "尚未到有效性观察日期");
        String verifier = currentUserCode();
        require(!Objects.equals(capa.getCoordinatorCode(), verifier), "CAPA协调人与有效性验证人必须独立");
        require(currentExecutionActions(capa).stream().noneMatch(item -> Objects.equals(item.getOwnerCode(), verifier)),
                "措施责任人不能验证本周期CAPA有效性");

        String before = capa.getStatus();
        capa.setEffectivenessResult(req.getResult()).setEffectivenessRemark(req.getRemark())
                .setEffectivenessEvidence(req.getEvidence()).setVerifierCode(verifier)
                .setVerifierName(currentUserName()).setVerificationTime(LocalDateTime.now())
                .setLessonsLearned(req.getLessonsLearned());
        if ("PASS".equals(req.getResult())) {
            require(StrUtil.isNotBlank(req.getLessonsLearned()), "关闭CAPA前必须沉淀经验教训");
            capa.setStatus(CLOSED).setClosedTime(LocalDateTime.now());
        } else {
            capa.setStatus(CONTAINMENT).setCycleNo(capa.getCycleNo() + 1)
                    .setRecurrenceCount(value(capa.getRecurrenceCount()) + 1).setClosedTime(null);
        }
        updateById(capa);
        syncSource(capa);
        saveOperation(capa, req.getRequestId(), "CAPA", capa.getId(),
                "PASS".equals(req.getResult()) ? "EFFECTIVENESS_PASS" : "EFFECTIVENESS_FAIL",
                before, capa.getStatus(), "有效性验证=" + req.getResult() + "，" + req.getRemark()
                        + ("FAIL".equals(req.getResult()) ? "；已开启第" + capa.getCycleNo() + "周期" : ""));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(QmsCapaStageReq req) {
        validateStage(req);
        require(StrUtil.isNotBlank(req.getRemark()), "取消原因不能为空");
        if (hasOperation(req.getRequestId())) return;
        QmsCapaEntity capa = lockCapa(req.getCapaId());
        require(capa != null, "CAPA不存在");
        requireCoordinator(capa);
        require(Set.of(DRAFT, CONTAINMENT).contains(capa.getStatus()), "进入根因分析后不得取消CAPA");
        if (CONTAINMENT.equals(capa.getStatus())) {
            require(currentActions(capa, "CONTAINMENT").stream()
                            .noneMatch(item -> Set.of("IN_PROGRESS", "COMPLETED").contains(item.getStatus())),
                    "已有围堵措施被接单或完成，不允许取消CAPA");
        }
        capa.setCancelReason(req.getRemark());
        changeStatus(capa, req.getRequestId(), "CANCEL", CANCELLED, "取消CAPA：" + req.getRemark());
    }

    private void validateCreate(QmsCapaCreateReq req) {
        require(req != null && req.getSourceBadId() != null, "来源NCR不能为空");
        require(StrUtil.isNotBlank(req.getRequestId()), "请求号不能为空");
        require(StrUtil.isNotBlank(req.getTitle()) && StrUtil.isNotBlank(req.getProblemStatement()),
                "CAPA标题和可验证的问题陈述不能为空");
        require(StrUtil.isNotBlank(req.getImpactScope()) && StrUtil.isNotBlank(req.getTriggerReason()),
                "影响范围和触发原因不能为空");
        require(inRiskRange(req.getSeverity()) && inRiskRange(req.getOccurrence()) && inRiskRange(req.getDetection()),
                "严重度、发生度和探测度必须在1到10之间");
        require(StrUtil.isNotBlank(req.getCoordinatorCode()) && StrUtil.isNotBlank(req.getCoordinatorName()),
                "CAPA协调人不能为空");
        require(req.getDueTime() != null && req.getDueTime().isAfter(LocalDateTime.now()),
                "CAPA计划关闭时间必须晚于当前时间");
    }

    private void validateExternalCreate(QmsCapaExternalCreateCommand command) {
        requireActualContext();
        require(command != null && command.getSourceId() != null, "外部CAPA来源不能为空");
        require("MES_CHANGE_POINT_PARETO".equals(command.getSourceType()), "外部CAPA来源类型无效");
        require(StrUtil.isNotBlank(command.getRequestId()) && command.getRequestId().length() <= 150,
                "外部CAPA请求号不能为空且不能超过150字符");
        require(StrUtil.isNotBlank(command.getSourceNo()) && command.getSourceNo().length() <= 100,
                "外部CAPA来源编号不能为空且不能超过100字符");
        require(StrUtil.isNotBlank(command.getSourceEvidence()) && command.getSourceEvidence().length() <= 2000,
                "外部CAPA来源证据不能为空且不能超过2000字符");
        require(Objects.equals(command.getFactoryCode(), FactoryContextHolder.getFactoryCode()),
                "外部CAPA来源工厂与当前实际工厂不一致");
        require(StrUtil.isNotBlank(command.getTitle()) && command.getTitle().length() <= 300
                        && StrUtil.isNotBlank(command.getProblemStatement()) && command.getProblemStatement().length() <= 3000,
                "CAPA标题或可验证问题陈述无效");
        require(StrUtil.isNotBlank(command.getImpactScope()) && command.getImpactScope().length() <= 2000
                        && StrUtil.isNotBlank(command.getTriggerReason()) && command.getTriggerReason().length() <= 1000,
                "CAPA影响范围或触发原因无效");
        require(inRiskRange(command.getSeverity()) && inRiskRange(command.getOccurrence())
                        && inRiskRange(command.getDetection()),
                "严重度、发生度和探测度必须在1到10之间");
        require(StrUtil.isNotBlank(command.getCoordinatorCode()) && command.getCoordinatorCode().length() <= 100
                        && StrUtil.isNotBlank(command.getCoordinatorName()) && command.getCoordinatorName().length() <= 200,
                "CAPA协调人不能为空或长度超限");
        require(command.getTeamMembers() == null || command.getTeamMembers().length() <= 3000,
                "跨职能团队成员不能超过3000字符");
        require(command.getDueTime() != null && command.getDueTime().isAfter(LocalDateTime.now()),
                "CAPA计划关闭时间必须晚于当前时间");
    }

    private void requireActualContext() {
        require(SecurityUtils.getUser() != null && SecurityUtils.getUser().getId() != null
                        && TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && StrUtil.isNotBlank(FactoryContextHolder.getFactoryCode()),
                "CAPA内部业务命令须实名登录并选择实际工厂，禁止跳过隔离");
    }

    private QmsCapaLinkDTO toLink(QmsCapaEntity capa) {
        List<String> excluded = new java.util.ArrayList<>();
        if (StrUtil.isNotBlank(capa.getCoordinatorCode())) excluded.add(capa.getCoordinatorCode());
        actionMapper.selectList(new LambdaQueryWrapper<QmsCapaActionEntity>()
                        .eq(QmsCapaActionEntity::getTenantId, capa.getTenantId())
                        .eq(QmsCapaActionEntity::getFactoryCode, capa.getFactoryCode())
                        .eq(QmsCapaActionEntity::getCapaId, capa.getId())
                        .eq(QmsCapaActionEntity::getCycleNo, capa.getCycleNo()))
                .stream().map(QmsCapaActionEntity::getOwnerCode).filter(StrUtil::isNotBlank).forEach(excluded::add);
        return new QmsCapaLinkDTO().setCapaId(capa.getId()).setCapaNo(capa.getCapaNo())
                .setStatus(capa.getStatus()).setSourceType(capa.getSourceType())
                .setSourceId(capa.getSourceId()).setSourceNo(capa.getSourceNo())
                .setFactoryCode(capa.getFactoryCode()).setTenantId(capa.getTenantId())
                .setCycleNo(capa.getCycleNo()).setClosedTime(capa.getClosedTime())
                .setClosedTimeExact(capa.getClosedTime() == null ? null : capa.getClosedTime().toString())
                .setEffectivenessCriteria(capa.getEffectivenessCriteria())
                .setExcludedReviewerCodes(excluded.stream().distinct().sorted().toList())
                .setLifecycleFingerprint(QmsCapaLifecycleEvidence.fingerprint(capa, excluded));
    }

    private void validateAction(QmsCapaActionAddReq req) {
        require(req != null && req.getCapaId() != null, "CAPA不能为空");
        require(StrUtil.isNotBlank(req.getRequestId()), "请求号不能为空");
        require(ACTION_TYPES.contains(req.getActionType()), "措施类型无效");
        require(StrUtil.isNotBlank(req.getTitle()) && StrUtil.isNotBlank(req.getDescription()),
                "措施标题和可执行内容不能为空");
        require(StrUtil.isNotBlank(req.getOwnerCode()) && StrUtil.isNotBlank(req.getOwnerName()), "措施责任人不能为空");
        require(req.getDueTime() != null && req.getDueTime().isAfter(LocalDateTime.now()),
                "措施完成期限必须晚于当前时间");
    }

    private void validateStage(QmsCapaStageReq req) {
        require(req != null && req.getCapaId() != null, "CAPA不能为空");
        require(StrUtil.isNotBlank(req.getRequestId()), "请求号不能为空");
    }

    private void changeStatus(QmsCapaEntity capa, String requestId, String operationType,
                              String nextStatus, String content) {
        String before = capa.getStatus();
        capa.setStatus(nextStatus);
        updateById(capa);
        syncSource(capa);
        saveOperation(capa, requestId, "CAPA", capa.getId(), operationType, before, nextStatus, content);
    }

    private void syncSource(QmsCapaEntity capa) {
        if (!"NCR".equals(capa.getSourceType()) || capa.getSourceId() == null) return;
        QmsBadInfoEntity badInfo = badInfoMapper.selectById(capa.getSourceId());
        if (badInfo != null && Objects.equals(badInfo.getCapaId(), capa.getId())) {
            badInfo.setCapaStatus(capa.getStatus());
            badInfoMapper.updateById(badInfo);
        }
    }

    private List<QmsCapaActionEntity> currentActions(QmsCapaEntity capa, String type) {
        return actionMapper.selectList(new LambdaQueryWrapper<QmsCapaActionEntity>()
                .eq(QmsCapaActionEntity::getCapaId, capa.getId())
                .eq(QmsCapaActionEntity::getCycleNo, capa.getCycleNo())
                .eq(QmsCapaActionEntity::getActionType, type)
                .notIn(QmsCapaActionEntity::getStatus, "REJECTED", "CANCELLED"));
    }

    private List<QmsCapaActionEntity> currentExecutionActions(QmsCapaEntity capa) {
        return actionMapper.selectList(new LambdaQueryWrapper<QmsCapaActionEntity>()
                .eq(QmsCapaActionEntity::getCapaId, capa.getId())
                .eq(QmsCapaActionEntity::getCycleNo, capa.getCycleNo())
                .in(QmsCapaActionEntity::getActionType, "CORRECTIVE", "PREVENTIVE")
                .notIn(QmsCapaActionEntity::getStatus, "REJECTED", "CANCELLED"));
    }

    private List<QmsCapaCauseEntity> currentCauses(QmsCapaEntity capa) {
        return causeMapper.selectList(new LambdaQueryWrapper<QmsCapaCauseEntity>()
                .eq(QmsCapaCauseEntity::getCapaId, capa.getId())
                .eq(QmsCapaCauseEntity::getCycleNo, capa.getCycleNo()));
    }

    private QmsCapaEntity lockCapa(Long id) {
        requireActualContext();
        return getOne(new LambdaQueryWrapper<QmsCapaEntity>()
                .eq(QmsCapaEntity::getId, id)
                .eq(QmsCapaEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .last("FOR UPDATE"), false);
    }

    private QmsCapaActionEntity lockAction(Long id) {
        return actionMapper.selectOne(new LambdaQueryWrapper<QmsCapaActionEntity>()
                .eq(QmsCapaActionEntity::getId, id).last("FOR UPDATE"));
    }

    private QmsCapaCauseEntity lockCause(Long id) {
        return causeMapper.selectOne(new LambdaQueryWrapper<QmsCapaCauseEntity>()
                .eq(QmsCapaCauseEntity::getId, id).last("FOR UPDATE"));
    }

    private boolean hasOperation(String requestId) {
        return operationMapper.selectCount(new LambdaQueryWrapper<QmsCapaOperationEntity>()
                .eq(QmsCapaOperationEntity::getRequestId, requestId)) > 0;
    }

    private void saveOperation(QmsCapaEntity capa, String requestId, String objectType, Long objectId,
                               String operationType, String before, String after, String content) {
        operationMapper.insert(new QmsCapaOperationEntity().setRequestId(requestId).setCapaId(capa.getId())
                .setCapaNo(capa.getCapaNo()).setCycleNo(capa.getCycleNo()).setObjectType(objectType)
                .setObjectId(objectId).setOperationType(operationType).setBeforeStatus(before).setAfterStatus(after)
                .setContent(content).setOperatorCode(currentUserCode()).setOperatorName(currentUserName())
                .setOperationTime(LocalDateTime.now()).setFactoryCode(capa.getFactoryCode()));
    }

    private void requireCoordinator(QmsCapaEntity capa) {
        require(Objects.equals(capa.getCoordinatorCode(), currentUserCode()), "仅CAPA协调人可以执行该阶段操作");
    }

    private void markOverdue(QmsCapaEntity capa) {
        capa.setOverdueFlag(capa.getDueTime() != null && capa.getDueTime().isBefore(LocalDateTime.now())
                && !Set.of(CLOSED, CANCELLED).contains(capa.getStatus()));
    }

    private boolean inRiskRange(Integer value) {
        return value != null && value >= 1 && value <= 10;
    }

    private MomUser currentUser() {
        try {
            return SecurityUtils.getUser();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String currentUserCode() {
        MomUser user = currentUser();
        return user == null ? "SYSTEM" : user.getUsername();
    }

    private String currentUserName() {
        MomUser user = currentUser();
        return user == null ? "系统" : user.getName();
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
