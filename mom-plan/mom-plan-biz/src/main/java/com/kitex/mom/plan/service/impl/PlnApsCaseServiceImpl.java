package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryPlanningPolicyService;
import com.kitex.mom.oms.entity.OmsOrderBaseEntity;
import com.kitex.mom.plan.constant.PlnApsCaseTypeEnum;
import com.kitex.mom.plan.dto.PlnApsCaseCommand;
import com.kitex.mom.plan.dto.PlnApsCaseDTO;
import com.kitex.mom.plan.dto.PlnFormalPlanContractDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlnApsCaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 智能 APS 案件领域服务实现。
 *
 * <p>当前类只负责案件身份、来源复核、幂等和状态机。资源采集、求解、A2/A3、MES 下达分别由后续领域服务
 * 完成，并以本服务的显式动作推进案件，避免形成可以任意修改状态的通用 CRUD。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnApsCaseServiceImpl extends ServiceImpl<PlnApsCaseMapper, PlnApsCaseEntity>
        implements PlnApsCaseService {

    private static final Set<String> SUPPORTED_SOURCE_TYPES =
            Set.of("OMS_ORDER", "MPS_VERSION", "MRP_LINE", "PRODUCTION_PLAN",
                    "SHORTAGE_REPLAN_CASE", "RESOURCE_REPLAN_CASE", "QUALITY_REPLAN_CASE",
                    "INSERTION_REPLAN_CASE", "EXECUTION_DEVIATION_CASE", "EFFECT_REVIEW");
    private static final int MAX_REQUEST_LENGTH = 100;
    private static final int MAX_REASON_LENGTH = 1000;

    private final PlnApsCaseSourceMapper sourceMapper;
    private final PlnApsCaseOperationMapper operationMapper;
    private final OmsOrderBaseMapper orderMapper;
    private final PlnMpsVersionMapper mpsVersionMapper;
    private final PlanMatMrpMapper mrpMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnShortageReplanCaseMapper shortageReplanCaseMapper;
    private final PlnResourceReplanCaseMapper resourceReplanCaseMapper;
    private final PlnQualityReplanCaseMapper qualityReplanCaseMapper;
    private final PlnInsertionReplanCaseMapper insertionReplanCaseMapper;
    private final PlnSchedulingEffectReviewMapper effectReviewMapper;
    private final RemoteMdmFactoryAccessService factoryAccessService;
    private final RemoteMdmFactoryPlanningPolicyService planningPolicyService;
    private final PlnApsWorkflowGuard workflowGuard;
    private final ObjectMapper objectMapper;

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String caseNo(Long id) {
        String value = id.toString();
        return "APS-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + '-'
                + value.substring(Math.max(0, value.length() - 8));
    }

    private static Long parseId(String id, String label) {
        try {
            Long value = Long.valueOf(id);
            require(value > 0, label + "必须为正整数");
            return value;
        } catch (NumberFormatException ex) {
            throw new CheckedException(label + "格式不正确");
        }
    }

    private static boolean isFingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String text(Long value) {
        return value == null ? null : value.toString();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsCaseDTO launch(PlnApsCaseCommand.Launch command) {
        require(command != null, "APS案件启动参数不能为空");
        String caseType = upper(command.getCaseType());
        String sourceType = upper(command.getSourceType());
        String requestId = trim(command.getRequestId());
        require(PlnApsCaseTypeEnum.isValid(caseType), "APS案件类型不受支持");
        require(SUPPORTED_SOURCE_TYPES.contains(sourceType),
                "当前来源类型尚未接入受控取证：" + sourceType);
        String requiredCaseType = requiredCaseType(sourceType);
        require(requiredCaseType == null || requiredCaseType.equals(caseType),
                "来源类型" + sourceType + "只能建立" + requiredCaseType + "案件");
        require(StrUtil.isNotBlank(requestId) && requestId.length() <= MAX_REQUEST_LENGTH,
                "APS案件启动请求号不能为空且不能超过100个字符");
        require(StrUtil.isNotBlank(command.getSourceVersion()) && command.getSourceVersion().length() <= 64,
                "APS案件启动必须携带已看到的来源业务版本");
        Long sourceId = parseId(command.getSourceId(), "来源主键");
        Scope scope = requireScope("write");
        VerifiedSource source = verifySource(sourceType, sourceId, scope);
        require(Objects.equals(source.version(), command.getSourceVersion().trim()),
                "来源业务版本已变化，请刷新来源页面后重新启动排产案件");

        String launchFingerprint = sha256(String.join("|", "APS_CASE_LAUNCH_V1", scope.tenantId().toString(),
                scope.factoryCode(), caseType, source.type(), source.id().toString(), source.code(),
                source.version(), source.fingerprint()));
        baseMapper.lock("APS_CASE_LAUNCH:" + scope.tenantId() + ':' + scope.factoryCode() + ':' + requestId);
        PlnApsCaseEntity existing = baseMapper.selectOne(caseQuery(scope)
                .eq(PlnApsCaseEntity::getLaunchRequestId, requestId).last("LIMIT 1"));
        if (existing != null) {
            require(Objects.equals(existing.getLaunchFingerprint(), launchFingerprint),
                    "同一APS案件启动请求号已对应不同来源或版本");
            return assemble(existing, true);
        }

        PreviousSourceCase previous = latestSourceCase(source, caseType, scope);
        if (previous != null && Objects.equals(previous.source().getSourceFingerprint(), source.fingerprint())
                && !Set.of("COMPLETED", "CANCELLED", "SUPERSEDED").contains(previous.caseEntity().getCaseStatus())) {
            return assemble(previous.caseEntity(), true);
        }

        MomUser user = SecurityUtils.getUser();
        Long id = IdUtil.getSnowflakeNextId();
        String operator = user.getUsername();
        PlnApsCaseEntity entity = new PlnApsCaseEntity()
                .setCaseNo(caseNo(id))
                .setCaseType(caseType)
                .setTitle(PlnApsCaseTypeEnum.fromCode(caseType).orElseThrow().getDescription() + " · " + source.code())
                .setCaseStatus("DRAFT")
                .setCurrentStage("PREPARATION")
                .setCurrentStep("SCOPE_CONFIRMATION")
                .setOwnerCode(operator)
                .setOwnerName(operator)
                .setLaunchRequestId(requestId)
                .setLaunchFingerprint(launchFingerprint)
                .setSourceVersion(previous == null ? 1 : previous.caseEntity().getSourceVersion() + 1)
                .setPreviousCaseId(previous == null ? null : previous.caseEntity().getId())
                .setWorkflowVersion(1)
                .setFactoryCode(scope.factoryCode());
        bindSourceContext(entity, source);
        entity.setId(id);
        entity.setTenantId(scope.tenantId());
        entity.setCreateBy(operator);
        entity.setContextFingerprint(contextFingerprint(entity, 1));
        require(baseMapper.insert(entity) == 1, "创建APS案件失败");

        PlnApsCaseSourceEntity sourceEntity = new PlnApsCaseSourceEntity()
                .setCaseId(id)
                .setSourceType(source.type())
                .setSourceId(source.id())
                .setSourceCode(source.code())
                .setSourceVersion(source.version())
                .setSourceFingerprint(source.fingerprint())
                .setSourceRole("PRIMARY")
                .setRelationshipStatus("ACTIVE")
                .setFactoryCode(scope.factoryCode());
        sourceEntity.setId(IdUtil.getSnowflakeNextId());
        sourceEntity.setTenantId(scope.tenantId());
        sourceEntity.setCreateBy(operator);
        require(sourceMapper.insert(sourceEntity) == 1, "保存APS案件来源失败");

        appendOperation(entity, entity, "LAUNCH", requestId, launchFingerprint, 0, 1,
                launchFingerprint, entity.getContextFingerprint(), "创建APS排产案件", scope, operator);
        return assemble(entity, true);
    }

    @Override
    public PlnApsCaseDTO detail(Long caseId) {
        Scope scope = requireScope("read");
        return assemble(requireCase(caseId, scope), true);
    }

    @Override
    public Page<PlnApsCaseDTO> page(SearchPage page, PlnApsCaseCommand.Query query) {
        Scope scope = requireScope("read");
        PlnApsCaseCommand.Query q = query == null ? new PlnApsCaseCommand.Query() : query;
        Set<Long> sourceCaseIds = sourceCaseIds(q, scope);
        Page<PlnApsCaseEntity> entityPage;
        if (sourceCaseIds != null && sourceCaseIds.isEmpty()) {
            entityPage = new Page<>(page.getCurrent(), page.getSize(), 0);
        } else {
            LambdaQueryWrapper<PlnApsCaseEntity> wrapper = caseQuery(scope)
                    .in(sourceCaseIds != null, PlnApsCaseEntity::getId, sourceCaseIds)
                    .like(StrUtil.isNotBlank(q.getCaseNo()), PlnApsCaseEntity::getCaseNo, q.getCaseNo())
                    .eq(StrUtil.isNotBlank(q.getCaseType()), PlnApsCaseEntity::getCaseType, upper(q.getCaseType()))
                    .eq(StrUtil.isNotBlank(q.getCaseStatus()), PlnApsCaseEntity::getCaseStatus, upper(q.getCaseStatus()))
                    .eq(StrUtil.isNotBlank(q.getCurrentStage()), PlnApsCaseEntity::getCurrentStage, upper(q.getCurrentStage()));
            applyCaseGroup(wrapper, q.getCaseGroup());
            wrapper.orderByDesc(PlnApsCaseEntity::getUpdateTime)
                    .orderByDesc(PlnApsCaseEntity::getId);
            entityPage = baseMapper.selectPage(page.toMpPage(), wrapper);
        }
        Page<PlnApsCaseDTO> result = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        result.setRecords(entityPage.getRecords().stream().map(entity -> assemble(entity, false)).toList());
        return result;
    }

    /**
     * 将案件中心的业务分组转换为数据库条件。
     *
     * <p>分组在服务端执行，保证分页总数和每页内容一致；已取消、完成或被替代的案件只进入已结束分组，
     * 不会因为保留了原阶段而混入待办。</p>
     */
    private void applyCaseGroup(LambdaQueryWrapper<PlnApsCaseEntity> wrapper, String caseGroup) {
        if (StrUtil.isBlank(caseGroup)) {
            return;
        }
        Set<String> closedStatuses = Set.of("COMPLETED", "CANCELLED", "SUPERSEDED");
        switch (upper(caseGroup)) {
            case "PREPARE" -> wrapper
                    .in(PlnApsCaseEntity::getCurrentStage, "PREPARATION", "STRATEGY", "CALCULATION")
                    .notIn(PlnApsCaseEntity::getCaseStatus, closedStatuses);
            case "DECISION" -> wrapper
                    .eq(PlnApsCaseEntity::getCurrentStage, "DECISION")
                    .notIn(PlnApsCaseEntity::getCaseStatus, closedStatuses);
            case "EXECUTION" -> wrapper
                    .eq(PlnApsCaseEntity::getCurrentStage, "EXECUTION")
                    .notIn(PlnApsCaseEntity::getCaseStatus, closedStatuses);
            case "CLOSED" -> wrapper.in(PlnApsCaseEntity::getCaseStatus, closedStatuses);
            default -> throw new CheckedException("不支持的APS案件业务分组：" + caseGroup);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsCaseDTO advance(Long caseId, PlnApsCaseCommand.Advance command) {
        require(command != null, "APS案件推进参数不能为空");
        String action = upper(command.getAction());
        String requestId = trim(command.getRequestId());
        String reason = trim(command.getReason());
        require(StrUtil.isNotBlank(requestId) && requestId.length() <= MAX_REQUEST_LENGTH,
                "APS案件操作请求号不能为空且不能超过100个字符");
        require(StrUtil.isNotBlank(reason) && reason.length() <= MAX_REASON_LENGTH,
                "APS案件操作原因不能为空且不能超过1000个字符");
        require(command.getExpectedVersion() != null && command.getExpectedVersion() >= 0,
                "APS案件操作必须携带期望版本");
        require(isFingerprint(command.getExpectedContextFingerprint()),
                "APS案件操作必须携带有效的期望上下文指纹");
        Scope scope = requireScope("write");
        String commandFingerprint = sha256(String.join("|", "APS_CASE_ACTION_V1", scope.tenantId().toString(),
                scope.factoryCode(), String.valueOf(caseId), action, requestId,
                command.getExpectedVersion().toString(), command.getExpectedContextFingerprint(), reason));
        baseMapper.lock("APS_CASE_ACTION:" + scope.tenantId() + ':' + scope.factoryCode() + ':' + requestId);

        PlnApsCaseOperationEntity replay = operationMapper.selectOne(new LambdaQueryWrapper<PlnApsCaseOperationEntity>()
                .eq(PlnApsCaseOperationEntity::getTenantId, scope.tenantId())
                .eq(PlnApsCaseOperationEntity::getFactoryCode, scope.factoryCode())
                .eq(PlnApsCaseOperationEntity::getRequestId, requestId).last("LIMIT 1"));
        if (replay != null) {
            require(Objects.equals(replay.getCaseId(), caseId)
                            && Objects.equals(replay.getCommandFingerprint(), commandFingerprint),
                    "同一APS操作请求号已被不同命令使用");
            return assemble(requireCase(caseId, scope), true);
        }

        PlnApsCaseEntity current = requireCase(caseId, scope);
        require(Objects.equals(current.getWorkflowVersion(), command.getExpectedVersion())
                        && Objects.equals(current.getContextFingerprint(), command.getExpectedContextFingerprint()),
                "APS案件已被其他页面或Agent推进，请刷新后重试");
        PlnApsWorkflowGuard.Transition transition = workflowGuard.requireTransition(current, action);
        requirePermission(transition.requiredPermission());
        return applyTransition(current, transition, action, requestId, commandFingerprint, reason, scope);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsCaseDTO cancel(Long caseId, PlnApsCaseCommand.Cancel command) {
        require(command != null, "APS案件取消参数不能为空");
        PlnApsCaseCommand.Advance advance = new PlnApsCaseCommand.Advance();
        advance.setAction("CANCEL");
        advance.setRequestId(command.getRequestId());
        advance.setExpectedVersion(command.getExpectedVersion());
        advance.setExpectedContextFingerprint(command.getExpectedContextFingerprint());
        advance.setReason(command.getReason());
        return advance(caseId, advance);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsCaseDTO bindResourceSnapshot(Long caseId, Long snapshotId, String resourceFingerprint,
                                              String readinessStatus, String requestId,
                                              String expectedCaseFingerprint) {
        require(snapshotId != null && snapshotId > 0, "APS资源快照主键不能为空");
        require(isFingerprint(resourceFingerprint), "APS资源快照指纹不合法");
        require(Set.of("READY", "WARNING", "BLOCKED").contains(readinessStatus), "APS预检结论不合法");
        require(StrUtil.isNotBlank(requestId) && requestId.length() <= MAX_REQUEST_LENGTH,
                "APS资源取证请求号不能为空且不能超过100个字符");
        Scope scope = requireScope("write");
        requirePermission("plan_apsResource_capture");
        baseMapper.lock("APS_CASE_RESOURCE:" + scope.tenantId() + ':' + scope.factoryCode() + ':' + caseId);
        PlnApsCaseEntity current = requireCase(caseId, scope);
        require(Set.of("DRAFT", "PREPARING", "BLOCKED", "READY").contains(current.getCaseStatus()),
                "当前案件状态不允许刷新资源快照");
        require(Objects.equals(current.getContextFingerprint(), expectedCaseFingerprint),
                "APS案件上下文已变化，请重新获取资源证据");

        String commandFingerprint = sha256(String.join("|", "APS_RESOURCE_BIND_V1", scope.tenantId().toString(),
                scope.factoryCode(), caseId.toString(), snapshotId.toString(), resourceFingerprint, readinessStatus));
        PlnApsCaseOperationEntity replay = operationMapper.selectOne(new LambdaQueryWrapper<PlnApsCaseOperationEntity>()
                .eq(PlnApsCaseOperationEntity::getTenantId, scope.tenantId())
                .eq(PlnApsCaseOperationEntity::getFactoryCode, scope.factoryCode())
                .eq(PlnApsCaseOperationEntity::getRequestId, requestId).last("LIMIT 1"));
        if (replay != null) {
            require(Objects.equals(replay.getCaseId(), caseId)
                            && Objects.equals(replay.getCommandFingerprint(), commandFingerprint),
                    "同一APS资源取证请求号已对应不同快照");
            return assemble(requireCase(caseId, scope), true);
        }

        PlnApsCaseEntity before = new PlnApsCaseEntity();
        BeanUtils.copyProperties(current, before);
        int expectedVersion = current.getWorkflowVersion();
        current.setResourceSnapshotId(snapshotId);
        current.setResourceFingerprint(resourceFingerprint);
        current.setCurrentStage("READY".equals(readinessStatus) ? "STRATEGY" : "PREPARATION");
        current.setCurrentStep("READY".equals(readinessStatus) ? "OBJECTIVE_SETUP" : "READINESS_REVIEW");
        current.setCaseStatus("READY".equals(readinessStatus) ? "READY" :
                ("BLOCKED".equals(readinessStatus) ? "BLOCKED" : "PREPARING"));
        current.setContextFingerprint(contextFingerprint(current, expectedVersion + 1));
        current.setUpdateBy(SecurityUtils.getUser().getUsername());
        require(baseMapper.updateById(current) == 1, "APS案件版本已变化，请重新获取资源证据");
        PlnApsCaseEntity after = requireCase(caseId, scope);
        appendOperation(before, after, "CAPTURE_RESOURCE", requestId, commandFingerprint,
                expectedVersion, expectedVersion + 1, expectedCaseFingerprint, after.getContextFingerprint(),
                "绑定资源快照并完成数据预检：" + readinessStatus, scope, SecurityUtils.getUser().getUsername());
        return assemble(after, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsCaseDTO bindScenarioResult(Long caseId, Long scenarioId, String requestId,
                                            String expectedCaseFingerprint, String reason) {
        require(scenarioId != null && scenarioId > 0, "APS情景主键不能为空");
        return bindReferenceTransition(caseId, scenarioId, "SCENARIO", "COMPLETE_CALCULATION",
                requestId, expectedCaseFingerprint, reason,
                (entity, referenceId) -> {
                    require(entity.getScenarioId() == null || Objects.equals(entity.getScenarioId(), referenceId),
                            "APS案件已绑定其他求解情景");
                    entity.setScenarioId(referenceId);
                    entity.setSelectedTaskId(null);
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsCaseDTO bindSelectedCandidate(Long caseId, Long selectedTaskId, String requestId,
                                               String expectedCaseFingerprint, String reason) {
        require(selectedTaskId != null && selectedTaskId > 0, "APS候选任务主键不能为空");
        return bindReferenceTransition(caseId, selectedTaskId, "CANDIDATE", "SELECT_CANDIDATE",
                requestId, expectedCaseFingerprint, reason,
                (entity, referenceId) -> {
                    require(entity.getScenarioId() != null, "APS案件尚未绑定求解情景");
                    entity.setSelectedTaskId(referenceId);
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsCaseDTO bindFormalPlanContract(Long caseId, MdmFactoryPlanningPolicyDTO policy,
                                                PlnFormalPlanContractDTO contract, String requestId,
                                                String expectedCaseFingerprint, String reason) {
        require(policy != null && contract != null, "APS正式合同绑定缺少策略或合同");
        require(StrUtil.isNotBlank(requestId) && requestId.trim().length() <= MAX_REQUEST_LENGTH,
                "APS正式合同绑定请求号不能为空且不能超过100个字符");
        require(isFingerprint(expectedCaseFingerprint), "APS正式合同绑定必须携带有效的案件指纹");
        require(StrUtil.isNotBlank(reason) && reason.trim().length() <= MAX_REASON_LENGTH,
                "APS正式合同绑定原因不能为空且不能超过1000个字符");
        require(StrUtil.isNotBlank(contract.getContractNo()) && contract.getContractVersion() != null
                        && contract.getContractVersion() > 0 && isFingerprint(contract.getSourceContentFingerprint())
                        && contract.getLines() != null && !contract.getLines().isEmpty(),
                "APS正式合同身份、摘要或明细不完整");
        require(Objects.equals(policy.getId(), contract.getPlanningPolicyId())
                        && Objects.equals(policy.getVersionNo(), contract.getPlanningPolicyVersion())
                        && Objects.equals(policy.getContentFingerprint(), contract.getPlanningPolicyFingerprint()),
                "正式计划合同与当前工厂计划权威不一致");
        Scope scope = requireScope("write");
        require(Objects.equals(scope.factoryCode(), policy.getFactoryCode()),
                "正式计划合同策略与当前工厂不一致");
        requirePermission("plan_apsScenario_publish");
        String normalizedRequestId = requestId.trim();
        String contractLineId = contract.getLines().stream()
                .filter(line -> "MAKE".equals(line.getSupplyType()))
                .map(PlnFormalPlanContractDTO.Line::getId).filter(StrUtil::isNotBlank)
                .findFirst().orElseThrow(() -> new CheckedException("正式计划合同缺少可执行的自制行"));
        long formalPlanContractId = parseId(contractLineId, "正式计划合同主键");
        String commandFingerprint = sha256(String.join("|", "APS_FORMAL_CONTRACT_BIND_V1",
                scope.tenantId().toString(), scope.factoryCode(), String.valueOf(caseId),
                policy.getId(), String.valueOf(policy.getVersionNo()), policy.getContentFingerprint(),
                policy.getMrpAuthority(), contract.getContractNo(),
                String.valueOf(contract.getContractVersion()), contract.getSourceContentFingerprint(),
                expectedCaseFingerprint, reason.trim()));
        baseMapper.lock("APS_CASE_CONTRACT:" + scope.tenantId() + ':' + scope.factoryCode() + ':' + caseId);
        PlnApsCaseOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<PlnApsCaseOperationEntity>()
                        .eq(PlnApsCaseOperationEntity::getTenantId, scope.tenantId())
                        .eq(PlnApsCaseOperationEntity::getFactoryCode, scope.factoryCode())
                        .eq(PlnApsCaseOperationEntity::getRequestId, normalizedRequestId).last("LIMIT 1"));
        if (replay != null) {
            require(Objects.equals(replay.getCaseId(), caseId)
                            && Objects.equals(replay.getCommandFingerprint(), commandFingerprint),
                    "同一APS合同绑定请求号已被不同合同使用");
            return assemble(requireCase(caseId, scope), true);
        }
        PlnApsCaseEntity current = requireCase(caseId, scope);
        require("SELECTED".equals(current.getCaseStatus()) && current.getSelectedTaskId() != null,
                "只有完成A2选案的APS案件可以绑定正式计划合同");
        require(Objects.equals(current.getContextFingerprint(), expectedCaseFingerprint),
                "APS案件上下文已变化，请刷新后重新绑定正式合同");
        PlnApsCaseEntity before = new PlnApsCaseEntity();
        BeanUtils.copyProperties(current, before);
        int expectedVersion = current.getWorkflowVersion();
        current.setPlanningPolicyId(parseId(policy.getId(), "计划权威策略主键"));
        current.setPlanningPolicyVersion(policy.getVersionNo());
        current.setPlanningPolicyFingerprint(policy.getContentFingerprint());
        current.setMrpAuthority(policy.getMrpAuthority());
        current.setFormalPlanContractId(formalPlanContractId);
        current.setFormalPlanContractNo(contract.getContractNo());
        current.setFormalPlanContractVersion(contract.getContractVersion());
        current.setFormalPlanContractFingerprint(contract.getSourceContentFingerprint());
        current.setContextFingerprint(contextFingerprint(current, expectedVersion + 1));
        String operator = SecurityUtils.getUser().getUsername();
        current.setUpdateBy(operator);
        require(baseMapper.updateById(current) == 1, "APS案件版本已变化，请重新绑定正式合同");
        PlnApsCaseEntity after = requireCase(caseId, scope);
        appendOperation(before, after, "BIND_FORMAL_CONTRACT", normalizedRequestId, commandFingerprint,
                expectedVersion, expectedVersion + 1, expectedCaseFingerprint, after.getContextFingerprint(),
                reason.trim(), scope, operator);
        return assemble(after, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsCaseDTO completePublish(Long caseId, String requestId,
                                         String expectedCaseFingerprint, String reason) {
        return bindReferenceTransition(caseId, null, "PUBLISH", "PUBLISH", requestId,
                expectedCaseFingerprint, reason,
                (entity, ignored) -> require(entity.getScenarioId() != null && entity.getSelectedTaskId() != null,
                        "APS案件缺少已选情景和候选，不能完成发布"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsCaseDTO recordDispatchStatus(Long caseId, String dispatchStatus,
                                              String requestId, String reason) {
        String normalizedStatus = upper(dispatchStatus);
        require(Set.of("PENDING", "DISPATCHING", "PARTIAL", "SUCCEEDED", "RETRY_WAIT",
                "MANUAL_REVIEW", "FAILED").contains(normalizedStatus), "MES下达汇总状态不合法");
        require(StrUtil.isNotBlank(requestId) && requestId.trim().length() <= MAX_REQUEST_LENGTH,
                "MES下达状态请求号不能为空且不能超过100个字符");
        require(StrUtil.isNotBlank(reason) && reason.trim().length() <= MAX_REASON_LENGTH,
                "MES下达状态原因不能为空且不能超过1000个字符");
        Scope scope = requireInternalScope();
        String normalizedRequestId = requestId.trim();
        String commandFingerprint = sha256(String.join("|", "APS_DISPATCH_STATE_V1",
                scope.tenantId().toString(), scope.factoryCode(), String.valueOf(caseId),
                normalizedStatus, reason.trim()));
        baseMapper.lock("APS_CASE_DISPATCH:" + scope.tenantId() + ':' + scope.factoryCode() + ':' + caseId);
        PlnApsCaseOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<PlnApsCaseOperationEntity>()
                        .eq(PlnApsCaseOperationEntity::getTenantId, scope.tenantId())
                        .eq(PlnApsCaseOperationEntity::getFactoryCode, scope.factoryCode())
                        .eq(PlnApsCaseOperationEntity::getRequestId, normalizedRequestId).last("LIMIT 1"));
        if (replay != null) {
            require(Objects.equals(replay.getCaseId(), caseId)
                            && Objects.equals(replay.getCommandFingerprint(), commandFingerprint),
                    "同一MES下达状态请求号已被不同结果使用");
            return assemble(requireCase(caseId, scope), true);
        }

        PlnApsCaseEntity current = requireCase(caseId, scope);
        String nextCaseStatus = dispatchCaseStatus(current.getCaseStatus(), normalizedStatus);
        PlnApsCaseEntity before = new PlnApsCaseEntity();
        BeanUtils.copyProperties(current, before);
        int expectedVersion = current.getWorkflowVersion();
        current.setDispatchStatus(normalizedStatus);
        current.setCaseStatus(nextCaseStatus);
        current.setCurrentStage("EXECUTION");
        current.setCurrentStep(dispatchStep(normalizedStatus));
        current.setContextFingerprint(contextFingerprint(current, expectedVersion + 1));
        current.setUpdateBy("APS_DISPATCH_WORKER");
        require(baseMapper.updateById(current) == 1, "MES下达状态回写时APS案件版本已变化");
        PlnApsCaseEntity after = requireCase(caseId, scope);
        appendOperation(before, after, "MES_DISPATCH_" + normalizedStatus, normalizedRequestId,
                commandFingerprint, expectedVersion, expectedVersion + 1, before.getContextFingerprint(),
                after.getContextFingerprint(), reason.trim(), scope, "APS_DISPATCH_WORKER");
        return assemble(after, true);
    }

    private PlnApsCaseDTO bindReferenceTransition(Long caseId, Long referenceId, String referenceType,
                                                  String action, String requestId,
                                                  String expectedCaseFingerprint, String reason,
                                                  ReferenceBinder binder) {
        require(StrUtil.isNotBlank(requestId) && requestId.length() <= MAX_REQUEST_LENGTH,
                "APS案件操作请求号不能为空且不能超过100个字符");
        require(isFingerprint(expectedCaseFingerprint), "APS案件操作必须携带有效的期望上下文指纹");
        require(StrUtil.isNotBlank(reason) && reason.trim().length() <= MAX_REASON_LENGTH,
                "APS案件操作原因不能为空且不能超过1000个字符");
        Scope scope = requireScope("write");
        baseMapper.lock("APS_CASE_BIND:" + scope.tenantId() + ':' + scope.factoryCode() + ':' + caseId + ':' + action);
        String commandFingerprint = sha256(String.join("|", "APS_CASE_BIND_V1", scope.tenantId().toString(),
                scope.factoryCode(), String.valueOf(caseId), action, referenceType,
                text(referenceId), expectedCaseFingerprint, reason.trim()));
        PlnApsCaseOperationEntity replay = operationMapper.selectOne(new LambdaQueryWrapper<PlnApsCaseOperationEntity>()
                .eq(PlnApsCaseOperationEntity::getTenantId, scope.tenantId())
                .eq(PlnApsCaseOperationEntity::getFactoryCode, scope.factoryCode())
                .eq(PlnApsCaseOperationEntity::getRequestId, requestId.trim()).last("LIMIT 1"));
        if (replay != null) {
            require(Objects.equals(replay.getCaseId(), caseId)
                            && Objects.equals(replay.getCommandFingerprint(), commandFingerprint),
                    "同一APS案件操作请求号已被不同命令使用");
            return assemble(requireCase(caseId, scope), true);
        }

        PlnApsCaseEntity current = requireCase(caseId, scope);
        require(Objects.equals(current.getContextFingerprint(), expectedCaseFingerprint),
                "APS案件上下文已变化，请刷新后重试");
        PlnApsWorkflowGuard.Transition transition = workflowGuard.requireTransition(current, action);
        requirePermission(transition.requiredPermission());
        PlnApsCaseEntity before = new PlnApsCaseEntity();
        BeanUtils.copyProperties(current, before);
        int expectedVersion = current.getWorkflowVersion();
        binder.bind(current, referenceId);
        current.setCaseStatus(transition.status());
        current.setCurrentStage(transition.stage());
        current.setCurrentStep(transition.step());
        current.setContextFingerprint(contextFingerprint(current, expectedVersion + 1));
        String operator = SecurityUtils.getUser().getUsername();
        current.setUpdateBy(operator);
        require(baseMapper.updateById(current) == 1, "APS案件版本已变化，请刷新后重试");
        PlnApsCaseEntity after = requireCase(caseId, scope);
        appendOperation(before, after, action, requestId.trim(), commandFingerprint,
                expectedVersion, expectedVersion + 1, expectedCaseFingerprint,
                after.getContextFingerprint(), reason.trim(), scope, operator);
        return assemble(after, true);
    }

    private PlnApsCaseDTO applyTransition(PlnApsCaseEntity current, PlnApsWorkflowGuard.Transition transition,
                                          String action, String requestId, String commandFingerprint,
                                          String reason, Scope scope) {
        PlnApsCaseEntity before = new PlnApsCaseEntity();
        BeanUtils.copyProperties(current, before);
        int expectedVersion = current.getWorkflowVersion();
        current.setCaseStatus(transition.status());
        current.setCurrentStage(transition.stage());
        current.setCurrentStep(transition.step());
        if (Set.of("COMPLETED", "CANCELLED", "SUPERSEDED").contains(transition.status())) {
            current.setCloseReason(reason);
            current.setClosedTime(LocalDateTime.now());
        }
        current.setContextFingerprint(contextFingerprint(current, expectedVersion + 1));
        current.setUpdateBy(SecurityUtils.getUser().getUsername());
        require(baseMapper.updateById(current) == 1,
                "APS案件版本已变化，请刷新后重试");
        PlnApsCaseEntity after = requireCase(current.getId(), scope);
        appendOperation(before, after, action, requestId, commandFingerprint,
                expectedVersion, expectedVersion + 1, before.getContextFingerprint(),
                after.getContextFingerprint(), reason, scope, SecurityUtils.getUser().getUsername());
        return assemble(after, true);
    }

    private void appendOperation(PlnApsCaseEntity before, PlnApsCaseEntity after, String action,
                                 String requestId, String commandFingerprint, int expectedVersion,
                                 int resultVersion, String expectedContextFingerprint,
                                 String resultContextFingerprint, String reason, Scope scope, String operator) {
        PlnApsCaseOperationEntity operation = new PlnApsCaseOperationEntity()
                .setCaseId(after.getId())
                .setRequestId(requestId)
                .setCommandFingerprint(commandFingerprint)
                .setAction(action)
                .setBeforeStatus(before.getCaseStatus())
                .setAfterStatus(after.getCaseStatus())
                .setBeforeStage(before.getCurrentStage())
                .setAfterStage(after.getCurrentStage())
                .setExpectedVersion(expectedVersion)
                .setResultVersion(resultVersion)
                .setExpectedContextFingerprint(expectedContextFingerprint)
                .setResultContextFingerprint(resultContextFingerprint)
                .setReason(reason)
                .setBeforeSnapshot(json(caseSnapshot(before, expectedVersion)))
                .setAfterSnapshot(json(caseSnapshot(after, resultVersion)))
                .setOperatorCode(operator)
                .setOperatorName(operator)
                .setOperationTime(LocalDateTime.now())
                .setFactoryCode(scope.factoryCode());
        operation.setId(IdUtil.getSnowflakeNextId());
        operation.setTenantId(scope.tenantId());
        operation.setCreateBy(operator);
        require(operationMapper.insert(operation) == 1, "保存APS案件操作履历失败");
    }

    private VerifiedSource verifySource(String sourceType, Long sourceId, Scope scope) {
        return switch (sourceType) {
            case "OMS_ORDER" -> {
                OmsOrderBaseEntity row = orderMapper.selectById(sourceId);
                require(row != null && Objects.equals(row.getTenantId(), scope.tenantId())
                                && Objects.equals(row.getFactoryCode(), scope.factoryCode()),
                        "OMS订单不存在或不属于当前租户工厂");
                String version = businessVersion(row.getUpdateTime(), row.getCreateTime(), row.getOrderState());
                yield verified(sourceType, sourceId, row.getOrderCode(), version, scope);
            }
            case "MPS_VERSION" -> {
                PlnMpsVersionEntity row = mpsVersionMapper.selectById(sourceId);
                require(row != null && Objects.equals(row.getTenantId(), scope.tenantId())
                                && Objects.equals(row.getFactoryCode(), scope.factoryCode()),
                        "MPS版本不存在或不属于当前租户工厂");
                String version = String.join(":", row.getVersionNo(), String.valueOf(row.getRevision()),
                        businessVersion(row.getUpdateTime(), row.getCreateTime(), row.getStatus()));
                yield verified(sourceType, sourceId, row.getVersionNo(), version, scope);
            }
            case "MRP_LINE" -> {
                PlanMatMrpEntity row = mrpMapper.selectById(sourceId);
                require(row != null && Objects.equals(row.getTenantId(), scope.tenantId())
                                && Objects.equals(row.getFactoryCode(), scope.factoryCode()),
                        "MRP记录不存在或不属于当前租户工厂");
                String code = StrUtil.blankToDefault(row.getPlanDemandNo(), row.getSaleOrderCode())
                        + ':' + StrUtil.blankToDefault(row.getMatCode(), String.valueOf(sourceId));
                String version = businessVersion(row.getUpdateTime(), row.getCreateTime(), row.getBomPathFingerprint());
                yield verified(sourceType, sourceId, code, version, scope);
            }
            case "PRODUCTION_PLAN" -> {
                PlnIssueEntity row = issueMapper.selectById(sourceId);
                require(row != null && Objects.equals(row.getTenantId(), scope.tenantId())
                                && Objects.equals(row.getFactoryCode(), scope.factoryCode()),
                        "生产计划不存在或不属于当前租户工厂");
                String version = businessVersion(row.getUpdateTime(), row.getCreateTime(), row.getStatus());
                yield verified(sourceType, sourceId, row.getIssueNo(), version, scope);
            }
            case "SHORTAGE_REPLAN_CASE" -> {
                PlnShortageReplanCaseEntity row = shortageReplanCaseMapper.selectById(sourceId);
                require(row != null && Objects.equals(row.getTenantId(), scope.tenantId())
                                && Objects.equals(row.getFactoryCode(), scope.factoryCode()),
                        "缺料重排案件不存在或不属于当前租户工厂");
                yield verified(sourceType, sourceId, row.getCaseNo(),
                        businessVersion(row.getUpdateTime(), row.getCreateTime(), row.getStatus()), scope);
            }
            case "RESOURCE_REPLAN_CASE", "EXECUTION_DEVIATION_CASE" -> {
                PlnResourceReplanCaseEntity row = resourceReplanCaseMapper.selectById(sourceId);
                require(row != null && Objects.equals(row.getTenantId(), scope.tenantId())
                                && Objects.equals(row.getFactoryCode(), scope.factoryCode()),
                        "资源重排案件不存在或不属于当前租户工厂");
                yield verified(sourceType, sourceId, row.getCaseNo(),
                        businessVersion(row.getUpdateTime(), row.getCreateTime(), row.getStatus()), scope);
            }
            case "QUALITY_REPLAN_CASE" -> {
                PlnQualityReplanCaseEntity row = qualityReplanCaseMapper.selectById(sourceId);
                require(row != null && Objects.equals(row.getTenantId(), scope.tenantId())
                                && Objects.equals(row.getFactoryCode(), scope.factoryCode()),
                        "质量重排案件不存在或不属于当前租户工厂");
                yield verified(sourceType, sourceId, row.getCaseNo(),
                        businessVersion(row.getUpdateTime(), row.getCreateTime(), row.getStatus()), scope);
            }
            case "INSERTION_REPLAN_CASE" -> {
                PlnInsertionReplanCaseEntity row = insertionReplanCaseMapper.selectById(sourceId);
                require(row != null && Objects.equals(row.getTenantId(), scope.tenantId())
                                && Objects.equals(row.getFactoryCode(), scope.factoryCode()),
                        "插单重排案件不存在或不属于当前租户工厂");
                yield verified(sourceType, sourceId, row.getCaseNo(),
                        businessVersion(row.getUpdateTime(), row.getCreateTime(), row.getStatus()), scope);
            }
            case "EFFECT_REVIEW" -> {
                PlnSchedulingEffectReviewEntity row = effectReviewMapper.selectById(sourceId);
                require(row != null && Objects.equals(row.getTenantId(), scope.tenantId())
                                && Objects.equals(row.getFactoryCode(), scope.factoryCode()),
                        "排程效果回评不存在或不属于当前租户工厂");
                yield verified(sourceType, sourceId, row.getReviewNo(),
                        businessVersion(row.getUpdateTime(), row.getCreateTime(), row.getLatestExecutionFingerprint()), scope);
            }
            default -> throw new CheckedException("当前来源类型尚未接入受控取证：" + sourceType);
        };
    }

    private String requiredCaseType(String sourceType) {
        return switch (sourceType) {
            case "SHORTAGE_REPLAN_CASE" -> "SHORTAGE_REPLAN";
            case "RESOURCE_REPLAN_CASE" -> "RESOURCE_EXCEPTION_REPLAN";
            case "EXECUTION_DEVIATION_CASE" -> "EXECUTION_DEVIATION_REPLAN";
            case "QUALITY_REPLAN_CASE" -> "QUALITY_EXCEPTION_REPLAN";
            case "INSERTION_REPLAN_CASE" -> "INSERTION_REPLAN";
            case "EFFECT_REVIEW" -> "EXECUTION_DEVIATION_REPLAN";
            default -> null;
        };
    }

    private VerifiedSource verified(String type, Long id, String code, String version, Scope scope) {
        require(StrUtil.isNotBlank(code) && StrUtil.isNotBlank(version), "APS来源缺少可核验的业务编码或版本");
        String fingerprint = sha256(String.join("|", "APS_SOURCE_V1", scope.tenantId().toString(),
                scope.factoryCode(), type, id.toString(), code, version));
        return new VerifiedSource(type, id, code, version, fingerprint);
    }

    private PreviousSourceCase latestSourceCase(VerifiedSource source, String caseType, Scope scope) {
        List<PlnApsCaseSourceEntity> references = sourceMapper.selectList(
                new LambdaQueryWrapper<PlnApsCaseSourceEntity>()
                        .eq(PlnApsCaseSourceEntity::getTenantId, scope.tenantId())
                        .eq(PlnApsCaseSourceEntity::getFactoryCode, scope.factoryCode())
                        .eq(PlnApsCaseSourceEntity::getSourceType, source.type())
                        .eq(PlnApsCaseSourceEntity::getSourceId, source.id())
                        .eq(PlnApsCaseSourceEntity::getSourceRole, "PRIMARY")
                        .orderByDesc(PlnApsCaseSourceEntity::getId));
        for (PlnApsCaseSourceEntity reference : references) {
            PlnApsCaseEntity caseEntity = baseMapper.selectOne(caseQuery(scope)
                    .eq(PlnApsCaseEntity::getId, reference.getCaseId())
                    .eq(PlnApsCaseEntity::getCaseType, caseType).last("LIMIT 1"));
            if (caseEntity != null) return new PreviousSourceCase(caseEntity, reference);
        }
        return null;
    }

    private String businessVersion(LocalDateTime updateTime, LocalDateTime createTime, String state) {
        LocalDateTime time = updateTime == null ? createTime : updateTime;
        require(time != null, "APS来源缺少可核验的更新时间");
        return time + ":" + StrUtil.blankToDefault(state, "NA");
    }

    private PlnApsCaseEntity requireCase(Long caseId, Scope scope) {
        require(caseId != null && caseId > 0, "APS案件主键不能为空");
        PlnApsCaseEntity entity = baseMapper.selectOne(caseQuery(scope)
                .eq(PlnApsCaseEntity::getId, caseId).last("LIMIT 1"));
        require(entity != null, "APS案件不存在或不属于当前租户工厂");
        return entity;
    }

    private LambdaQueryWrapper<PlnApsCaseEntity> caseQuery(Scope scope) {
        return new LambdaQueryWrapper<PlnApsCaseEntity>()
                .eq(PlnApsCaseEntity::getTenantId, scope.tenantId())
                .eq(PlnApsCaseEntity::getFactoryCode, scope.factoryCode());
    }

    private Set<Long> sourceCaseIds(PlnApsCaseCommand.Query query, Scope scope) {
        if (StrUtil.isBlank(query.getSourceType()) && StrUtil.isBlank(query.getSourceCode())) {
            return null;
        }
        return new LinkedHashSet<>(sourceMapper.selectList(new LambdaQueryWrapper<PlnApsCaseSourceEntity>()
                        .select(PlnApsCaseSourceEntity::getCaseId)
                        .eq(PlnApsCaseSourceEntity::getTenantId, scope.tenantId())
                        .eq(PlnApsCaseSourceEntity::getFactoryCode, scope.factoryCode())
                        .eq(StrUtil.isNotBlank(query.getSourceType()), PlnApsCaseSourceEntity::getSourceType,
                                upper(query.getSourceType()))
                        .like(StrUtil.isNotBlank(query.getSourceCode()), PlnApsCaseSourceEntity::getSourceCode,
                                query.getSourceCode()))
                .stream().map(PlnApsCaseSourceEntity::getCaseId).toList());
    }

    private Scope requireScope(String factoryPermission) {
        Long tenantId = TenantContextHolder.getTenantId();
        String factoryCode = FactoryContextHolder.getFactoryCode();
        MomUser user = SecurityUtils.getUser();
        require(tenantId != null && tenantId > 0 && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && user != null && user.getId() != null && StrUtil.isNotBlank(user.getUsername())
                        && StrUtil.isNotBlank(factoryCode),
                "智能APS必须在已登录、明确租户和factory-code工厂上下文中操作");
        R<Boolean> result = factoryAccessService.check(new MdmFactoryAccessCheck()
                .setUserId(user.getId()).setFactoryCode(factoryCode).setRequiredPermission(factoryPermission));
        require(result != null && result.isOk() && Boolean.TRUE.equals(result.getData()),
                "当前用户没有所选工厂权限，或MDM工厂核验不可用");
        return new Scope(tenantId, factoryCode);
    }

    /**
     * 模块内部工作器作用域；不伪造登录用户，也不绕过租户和工厂边界。
     */
    private Scope requireInternalScope() {
        Long tenantId = TenantContextHolder.getTenantId();
        String factoryCode = FactoryContextHolder.getFactoryCode();
        require(tenantId != null && tenantId > 0 && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && StrUtil.isNotBlank(factoryCode),
                "APS下达工作器必须在明确租户和工厂上下文中运行");
        return new Scope(tenantId, factoryCode);
    }

    private void requirePermission(String permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        require(authentication != null && authentication.isAuthenticated()
                        && authentication.getAuthorities().stream().anyMatch(authority ->
                        permission.equals(authority.getAuthority()) || "ROLE_ADMIN".equals(authority.getAuthority())),
                "当前岗位未配置APS动作权限：" + permission);
    }

    private PlnApsCaseDTO assemble(PlnApsCaseEntity entity, boolean includeHistory) {
        PlnApsCaseDTO dto = new PlnApsCaseDTO()
                .setId(text(entity.getId())).setCaseNo(entity.getCaseNo()).setCaseType(entity.getCaseType())
                .setTitle(entity.getTitle()).setCaseStatus(entity.getCaseStatus())
                .setCurrentStage(entity.getCurrentStage()).setCurrentStep(entity.getCurrentStep())
                .setOwnerCode(entity.getOwnerCode()).setOwnerName(entity.getOwnerName())
                .setSourceVersion(entity.getSourceVersion()).setPreviousCaseId(text(entity.getPreviousCaseId()))
                .setPlanningPolicyId(text(entity.getPlanningPolicyId()))
                .setPlanningPolicyVersion(entity.getPlanningPolicyVersion())
                .setPlanningPolicyFingerprint(entity.getPlanningPolicyFingerprint())
                .setMrpAuthority(entity.getMrpAuthority())
                .setFormalPlanContractId(text(entity.getFormalPlanContractId()))
                .setFormalPlanContractNo(entity.getFormalPlanContractNo())
                .setFormalPlanContractVersion(entity.getFormalPlanContractVersion())
                .setFormalPlanContractFingerprint(entity.getFormalPlanContractFingerprint())
                .setResourceSnapshotId(text(entity.getResourceSnapshotId()))
                .setResourceFingerprint(entity.getResourceFingerprint())
                .setScenarioId(text(entity.getScenarioId())).setSelectedTaskId(text(entity.getSelectedTaskId()))
                .setEffectReviewId(text(entity.getEffectReviewId())).setDispatchStatus(entity.getDispatchStatus())
                .setWorkflowVersion(entity.getWorkflowVersion()).setContextFingerprint(entity.getContextFingerprint())
                .setCloseReason(entity.getCloseReason()).setClosedTime(entity.getClosedTime())
                .setFactoryCode(entity.getFactoryCode()).setCreateTime(entity.getCreateTime())
                .setUpdateTime(entity.getUpdateTime());
        if (!includeHistory) return dto;
        dto.setSources(sourceMapper.selectList(new LambdaQueryWrapper<PlnApsCaseSourceEntity>()
                        .eq(PlnApsCaseSourceEntity::getCaseId, entity.getId())
                        .orderByAsc(PlnApsCaseSourceEntity::getId)).stream()
                .map(this::sourceDto).toList());
        dto.setRecentOperations(operationMapper.selectList(new LambdaQueryWrapper<PlnApsCaseOperationEntity>()
                        .eq(PlnApsCaseOperationEntity::getCaseId, entity.getId())
                        .orderByDesc(PlnApsCaseOperationEntity::getOperationTime)
                        .orderByDesc(PlnApsCaseOperationEntity::getId).last("LIMIT 20")).stream()
                .map(this::operationDto).toList());
        return dto;
    }

    private PlnApsCaseDTO.Source sourceDto(PlnApsCaseSourceEntity entity) {
        return new PlnApsCaseDTO.Source().setId(text(entity.getId())).setSourceType(entity.getSourceType())
                .setSourceId(text(entity.getSourceId())).setSourceCode(entity.getSourceCode())
                .setSourceVersion(entity.getSourceVersion()).setSourceFingerprint(entity.getSourceFingerprint())
                .setSourceRole(entity.getSourceRole()).setRelationshipStatus(entity.getRelationshipStatus());
    }

    private PlnApsCaseDTO.Operation operationDto(PlnApsCaseOperationEntity entity) {
        return new PlnApsCaseDTO.Operation().setId(text(entity.getId())).setRequestId(entity.getRequestId())
                .setAction(entity.getAction()).setBeforeStatus(entity.getBeforeStatus())
                .setAfterStatus(entity.getAfterStatus()).setBeforeStage(entity.getBeforeStage())
                .setAfterStage(entity.getAfterStage()).setExpectedVersion(entity.getExpectedVersion())
                .setResultVersion(entity.getResultVersion()).setReason(entity.getReason())
                .setOperatorCode(entity.getOperatorCode()).setOperatorName(entity.getOperatorName())
                .setOperationTime(entity.getOperationTime());
    }

    private Map<String, Object> caseSnapshot(PlnApsCaseEntity entity, int version) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", text(entity.getId()));
        snapshot.put("caseNo", entity.getCaseNo());
        snapshot.put("status", entity.getCaseStatus());
        snapshot.put("stage", entity.getCurrentStage());
        snapshot.put("step", entity.getCurrentStep());
        snapshot.put("version", version);
        snapshot.put("contextFingerprint", entity.getContextFingerprint());
        snapshot.put("planningPolicyId", text(entity.getPlanningPolicyId()));
        snapshot.put("planningPolicyVersion", entity.getPlanningPolicyVersion());
        snapshot.put("planningPolicyFingerprint", entity.getPlanningPolicyFingerprint());
        snapshot.put("mrpAuthority", entity.getMrpAuthority());
        snapshot.put("formalPlanContractId", text(entity.getFormalPlanContractId()));
        snapshot.put("formalPlanContractNo", entity.getFormalPlanContractNo());
        snapshot.put("formalPlanContractVersion", entity.getFormalPlanContractVersion());
        snapshot.put("formalPlanContractFingerprint", entity.getFormalPlanContractFingerprint());
        snapshot.put("resourceFingerprint", entity.getResourceFingerprint());
        snapshot.put("scenarioId", text(entity.getScenarioId()));
        snapshot.put("selectedTaskId", text(entity.getSelectedTaskId()));
        snapshot.put("effectReviewId", text(entity.getEffectReviewId()));
        snapshot.put("dispatchStatus", entity.getDispatchStatus());
        return snapshot;
    }

    private String contextFingerprint(PlnApsCaseEntity entity, int version) {
        return sha256(String.join("|", "APS_CASE_CONTEXT_V1", text(entity.getTenantId()),
                entity.getFactoryCode(), text(entity.getId()), entity.getCaseStatus(), entity.getCurrentStage(),
                entity.getCurrentStep(), String.valueOf(version), value(entity.getPlanningPolicyFingerprint()),
                value(entity.getFormalPlanContractFingerprint()), value(entity.getResourceFingerprint()),
                text(entity.getScenarioId()), text(entity.getSelectedTaskId()), text(entity.getEffectReviewId()),
                value(entity.getDispatchStatus())));
    }

    /**
     * 冻结案件启动时真实可读取的工厂计划权威；缺失策略仍允许建立准备案件，后续正式动作故障关闭。
     * 专业重排候选和发布结果继续由原领域服务持有。
     */
    private void bindSourceContext(PlnApsCaseEntity entity, VerifiedSource source) {
        bindPlanningPolicy(entity);
        if (!"EFFECT_REVIEW".equals(source.type())) {
            return;
        }
        PlnSchedulingEffectReviewEntity review = effectReviewMapper.selectById(source.id());
        require(review != null, "排程效果回评已变化，请重新启动APS案件");
        entity.setEffectReviewId(review.getId());
        entity.setScenarioId(review.getScenarioId());
        entity.setSelectedTaskId(review.getSelectedTaskId());
    }

    private void bindPlanningPolicy(PlnApsCaseEntity entity) {
        try {
            R<MdmFactoryPlanningPolicyDTO> response = planningPolicyService.active(entity.getFactoryCode());
            if (response == null || !response.isOk() || response.getData() == null) {
                return;
            }
            MdmFactoryPlanningPolicyDTO policy = response.getData();
            if (!Objects.equals(entity.getFactoryCode(), policy.getFactoryCode())
                    || !isFingerprint(policy.getContentFingerprint())) {
                return;
            }
            entity.setPlanningPolicyId(parseId(policy.getId(), "工厂计划权威策略主键"));
            entity.setPlanningPolicyVersion(policy.getVersionNo());
            entity.setPlanningPolicyFingerprint(policy.getContentFingerprint());
            entity.setMrpAuthority(policy.getMrpAuthority());
        } catch (RuntimeException ignored) {
            // 启动准备案件不依赖MDM可用；资源取证、求解和发布会继续把缺失策略作为BLOCKER。
        }
    }

    private String dispatchCaseStatus(String currentStatus, String dispatchStatus) {
        if ("SUCCEEDED".equals(dispatchStatus)) {
            require(Set.of("PUBLISHED", "DISPATCHING", "EXECUTING").contains(currentStatus),
                    "当前APS案件状态不能确认MES已接收");
            return "EXECUTING";
        }
        if (Set.of("MANUAL_REVIEW", "FAILED").contains(dispatchStatus)) {
            require(Set.of("PUBLISHED", "DISPATCHING", "FAILED").contains(currentStatus),
                    "当前APS案件状态不能登记MES下达失败");
            return "FAILED";
        }
        if ("PENDING".equals(dispatchStatus) && "PUBLISHED".equals(currentStatus)) {
            return "PUBLISHED";
        }
        require(Set.of("PUBLISHED", "DISPATCHING", "FAILED").contains(currentStatus),
                "当前APS案件状态不能进入MES下达过程");
        return "DISPATCHING";
    }

    private String dispatchStep(String dispatchStatus) {
        return switch (dispatchStatus) {
            case "PENDING" -> "WAITING_DISPATCH";
            case "SUCCEEDED" -> "EXECUTION_TRACKING";
            case "MANUAL_REVIEW" -> "DISPATCH_MANUAL_REVIEW";
            case "FAILED" -> "DISPATCH_FAILED";
            case "PARTIAL" -> "MES_DISPATCH_PARTIAL";
            case "RETRY_WAIT" -> "MES_DISPATCH_RETRY";
            default -> "MES_DISPATCH";
        };
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new CheckedException("生成APS案件审计快照失败");
        }
    }

    @FunctionalInterface
    private interface ReferenceBinder {
        void bind(PlnApsCaseEntity entity, Long referenceId);
    }

    private record Scope(Long tenantId, String factoryCode) {
    }

    private record VerifiedSource(String type, Long id, String code, String version, String fingerprint) {
    }

    private record PreviousSourceCase(PlnApsCaseEntity caseEntity, PlnApsCaseSourceEntity source) {
    }
}
