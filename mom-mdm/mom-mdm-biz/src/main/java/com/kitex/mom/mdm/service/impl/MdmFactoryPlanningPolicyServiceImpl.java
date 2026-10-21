package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyCommand;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningImpactDTO;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.entity.MdmFactoryPlanningPolicyEntity;
import com.kitex.mom.mdm.enums.FactoryModelTypeEnum;
import com.kitex.mom.mdm.enums.MrpAuthorityEnum;
import com.kitex.mom.mdm.enums.ProductionOrderSyncModeEnum;
import com.kitex.mom.mdm.enums.SubstituteGovernanceModeEnum;
import com.kitex.mom.mdm.mapper.MdmFactoryModelMapper;
import com.kitex.mom.mdm.mapper.MdmFactoryPlanningPolicyMapper;
import com.kitex.mom.mdm.service.MdmFactoryPlanningPolicyService;
import com.kitex.mom.mdm.service.FactoryPlanningPolicyImpactInspector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 工厂计划协同策略领域实现。
 *
 * <p>这里只创建草稿和读取已发布版本；发布与模式切换必须经过后续跨模块影响评估，
 * 因而没有暴露通用 save/update 作为业务入口。</p>
 */
@Service
@RequiredArgsConstructor
public class MdmFactoryPlanningPolicyServiceImpl
        extends ServiceImpl<MdmFactoryPlanningPolicyMapper, MdmFactoryPlanningPolicyEntity>
        implements MdmFactoryPlanningPolicyService {

    private static final int MAX_REQUEST_LENGTH = 100;
    private final MdmFactoryModelMapper factoryMapper;
    private final FactoryPlanningPolicyImpactInspector impactInspector;

    @Override
    public MdmFactoryPlanningPolicyDTO requireActive(String factoryCode) {
        Scope scope = requireScope(factoryCode, false);
        List<MdmFactoryPlanningPolicyEntity> rows = list(policyQuery(scope)
                .eq(MdmFactoryPlanningPolicyEntity::getStatus, "ACTIVE"));
        require(rows.size() == 1, rows.isEmpty()
                ? "当前工厂尚未发布计划协同策略"
                : "当前工厂存在多个生效计划协同策略，请先治理数据");
        MdmFactoryPlanningPolicyEntity row = rows.get(0);
        require(Objects.equals(row.getContentFingerprint(), fingerprint(row)),
                "当前工厂计划协同策略摘要损坏，请停止正式计划写动作");
        return toDto(row);
    }

    @Override
    public List<MdmFactoryPlanningPolicyDTO> history(String factoryModelId) {
        Scope scope = requireScope(null, false);
        Long modelId = parseId(factoryModelId, "工厂节点主键");
        MdmFactoryModelEntity factory = requireFactory(modelId, scope);
        return list(policyQuery(new Scope(scope.tenantId(), factory.getCode(), scope.operator()))
                .eq(MdmFactoryPlanningPolicyEntity::getFactoryModelId, modelId)
                .orderByDesc(MdmFactoryPlanningPolicyEntity::getVersionNo))
                .stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MdmFactoryPlanningPolicyDTO createDraft(MdmFactoryPlanningPolicyCommand.CreateDraft command) {
        require(command != null, "计划协同策略草稿参数不能为空");
        Scope scope = requireScope(null, true);
        String requestId = trim(command.getRequestId());
        require(StrUtil.isNotBlank(requestId) && requestId.length() <= MAX_REQUEST_LENGTH,
                "草稿永久请求号不能为空且不能超过100个字符");
        Long modelId = parseId(command.getFactoryModelId(), "工厂节点主键");
        MdmFactoryModelEntity factory = requireFactory(modelId, scope);
        require(Objects.equals(factory.getCode(), scope.factoryCode()),
                "只能维护当前已选择工厂的计划协同策略");

        String authority = upper(command.getMrpAuthority());
        String syncMode = upper(command.getProductionOrderSyncMode());
        String substituteMode = upper(command.getSubstituteGovernanceMode());
        String erpSystemCode = trim(command.getErpSystemCode());
        String externalFactoryCode = trim(command.getExternalFactoryCode());
        String reason = trim(command.getChangeReason());
        require(MrpAuthorityEnum.isValid(authority), "MRP权威只允许ERP_MRP或MOM_MRP");
        require(ProductionOrderSyncModeEnum.isValid(syncMode), "生产订单同步方式不受支持");
        require(SubstituteGovernanceModeEnum.isValid(substituteMode), "替代料治理方式不受支持");
        require(StrUtil.isNotBlank(reason) && reason.length() <= 1000, "变更原因不能为空且不能超过1000个字符");
        require(command.getEffectiveFrom() != null, "计划协同策略必须指定生效时间");
        validateMode(authority, erpSystemCode, externalFactoryCode, syncMode, substituteMode);

        baseMapper.lock("MDM_FACTORY_PLANNING_POLICY:" + scope.tenantId() + ':' + factory.getCode());
        String commandFingerprint = sha256(String.join("|", "PLANNING_POLICY_DRAFT_V1",
                scope.tenantId().toString(), factory.getId().toString(), factory.getCode(), authority,
                value(erpSystemCode), value(externalFactoryCode), syncMode, substituteMode,
                command.getEffectiveFrom().toString(), reason));
        MdmFactoryPlanningPolicyEntity replay = getOne(policyQuery(scope)
                .eq(MdmFactoryPlanningPolicyEntity::getDraftRequestId, requestId).last("LIMIT 1"));
        if (replay != null) {
            require(Objects.equals(replay.getDraftRequestFingerprint(), commandFingerprint),
                    "同一草稿请求号已对应不同配置");
            return toDto(replay);
        }
        Integer maxVersion = list(policyQuery(scope)).stream()
                .map(MdmFactoryPlanningPolicyEntity::getVersionNo).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(0);
        MdmFactoryPlanningPolicyEntity entity = new MdmFactoryPlanningPolicyEntity();
        entity.setId(IdUtil.getSnowflakeNextId());
        entity.setFactoryModelId(factory.getId());
        entity.setFactoryCode(factory.getCode());
        entity.setVersionNo(maxVersion + 1);
        entity.setMrpAuthority(authority);
        entity.setErpSystemCode(erpSystemCode);
        entity.setExternalFactoryCode(externalFactoryCode);
        entity.setProductionOrderSyncMode(syncMode);
        entity.setSubstituteGovernanceMode(substituteMode);
        entity.setEffectiveFrom(command.getEffectiveFrom());
        entity.setStatus("DRAFT");
        entity.setChangeReason(reason);
        entity.setDraftRequestId(requestId);
        entity.setDraftRequestFingerprint(commandFingerprint);
        entity.setTenantId(scope.tenantId());
        entity.setCreateBy(scope.operator());
        entity.setContentFingerprint(fingerprint(entity));
        require(baseMapper.insert(entity) == 1, "创建工厂计划协同策略草稿失败");
        return toDto(entity);
    }

    @Override
    public MdmFactoryPlanningImpactDTO inspect(String draftId) {
        Scope scope = requireScope(null, false);
        MdmFactoryPlanningPolicyEntity draft = requireDraft(parseId(draftId, "策略草稿主键"), scope, false);
        return impactInspector.inspect(draft);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MdmFactoryPlanningPolicyDTO publish(MdmFactoryPlanningPolicyCommand.Publish command) {
        require(command != null, "策略发布参数不能为空");
        Scope scope = requireScope(null, true);
        String requestId = trim(command.getRequestId());
        String expectedFingerprint = trim(command.getExpectedFingerprint());
        String impactConfirmationNo = trim(command.getImpactConfirmationNo());
        require(StrUtil.isNotBlank(requestId) && requestId.length() <= MAX_REQUEST_LENGTH,
                "发布永久请求号不能为空且不能超过100个字符");
        require(isSha256(expectedFingerprint), "发布必须携带64位草稿内容摘要");
        require(isSha256(impactConfirmationNo), "发布必须携带当前影响检查确认号");
        Long draftId = parseId(command.getDraftId(), "策略草稿主键");
        baseMapper.lock("MDM_FACTORY_PLANNING_POLICY:" + scope.tenantId() + ':' + scope.factoryCode());

        String commandFingerprint = sha256(String.join("|", "PLANNING_POLICY_PUBLISH_V1",
                scope.tenantId().toString(), scope.factoryCode(), draftId.toString(), expectedFingerprint,
                impactConfirmationNo, requestId));
        MdmFactoryPlanningPolicyEntity replay = getOne(policyQuery(scope)
                .eq(MdmFactoryPlanningPolicyEntity::getPublishRequestId, requestId).last("LIMIT 1"), false);
        if (replay != null) {
            require(Objects.equals(replay.getPublishCommandFingerprint(), commandFingerprint),
                    "同一发布请求号已对应不同命令");
            return toDto(replay);
        }

        MdmFactoryPlanningPolicyEntity draft = requireDraft(draftId, scope, true);
        require("DRAFT".equals(draft.getStatus()), "只有DRAFT策略可以提交发布");
        require(Objects.equals(expectedFingerprint, draft.getContentFingerprint())
                        && Objects.equals(expectedFingerprint, fingerprint(draft)),
                "策略草稿内容或摘要已变化，请重新检查影响");
        String lastCompiler = StrUtil.blankToDefault(draft.getUpdateBy(), draft.getCreateBy());
        require(!Objects.equals(lastCompiler, scope.operator()),
                "策略发布必须由不同于最后编制人的实名人员完成");

        MdmFactoryPlanningImpactDTO impact = impactInspector.inspect(draft);
        require(Objects.equals(impactConfirmationNo, impact.getConfirmationNo()),
                "PLAN/MES影响事实已变化，请重新查看影响并确认");
        boolean future = draft.getEffectiveFrom().isAfter(LocalDateTime.now());
        require(future || Boolean.TRUE.equals(impact.getImmediateSwitchAllowed()),
                "当前存在未结计划或生产执行事实，不能立即切换；请预约未来生效并先完成迁移清理："
                        + String.join("、", impact.getBlockers()));

        LocalDateTime now = LocalDateTime.now();
        draft.setSubmittedBy(lastCompiler);
        draft.setSubmittedTime(now);
        draft.setPublishedBy(scope.operator());
        draft.setPublishedTime(now);
        draft.setPublishRequestId(requestId);
        draft.setPublishCommandFingerprint(commandFingerprint);
        draft.setImpactConfirmationNo(impactConfirmationNo);
        draft.setActivationFailureReason(null);
        if (future) {
            draft.setStatus("PENDING_EFFECTIVE");
        } else {
            retireCurrentActive(scope, draft.getId());
            draft.setStatus("ACTIVE");
        }
        draft.setUpdateBy(scope.operator());
        draft.setUpdateTime(now);
        require(baseMapper.updateById(draft) == 1, "发布工厂计划协同策略失败");
        return toDto(draft);
    }

    private MdmFactoryPlanningPolicyEntity requireDraft(Long draftId, Scope scope, boolean lock) {
        String suffix = lock ? "FOR UPDATE" : "LIMIT 1";
        MdmFactoryPlanningPolicyEntity draft = getOne(policyQuery(scope)
                .eq(MdmFactoryPlanningPolicyEntity::getId, draftId).last(suffix), false);
        require(draft != null, "计划协同策略草稿不存在或不属于当前工厂");
        require(Set.of("DRAFT", "PENDING_EFFECTIVE").contains(draft.getStatus()),
                "当前策略版本不是可检查的草稿或预约版本");
        return draft;
    }

    private void retireCurrentActive(Scope scope, Long nextPolicyId) {
        MdmFactoryPlanningPolicyEntity active = getOne(policyQuery(scope)
                .eq(MdmFactoryPlanningPolicyEntity::getStatus, "ACTIVE").last("FOR UPDATE"), false);
        if (active == null || Objects.equals(active.getId(), nextPolicyId)) {
            return;
        }
        boolean retired = update(new LambdaUpdateWrapper<MdmFactoryPlanningPolicyEntity>()
                .eq(MdmFactoryPlanningPolicyEntity::getId, active.getId())
                .eq(MdmFactoryPlanningPolicyEntity::getTenantId, scope.tenantId())
                .eq(MdmFactoryPlanningPolicyEntity::getFactoryCode, scope.factoryCode())
                .eq(MdmFactoryPlanningPolicyEntity::getStatus, "ACTIVE")
                .set(MdmFactoryPlanningPolicyEntity::getStatus, "RETIRED")
                .set(MdmFactoryPlanningPolicyEntity::getUpdateBy, scope.operator())
                .set(MdmFactoryPlanningPolicyEntity::getUpdateTime, LocalDateTime.now()));
        require(retired, "当前生效计划协同策略已变化，请重新发布");
    }

    private void validateMode(String authority, String erpSystemCode, String externalFactoryCode,
                              String syncMode, String substituteMode) {
        if ("ERP_MRP".equals(authority)) {
            require(StrUtil.isNotBlank(erpSystemCode) && StrUtil.isNotBlank(externalFactoryCode),
                    "ERP_MRP必须配置ERP系统编码和ERP工厂编码");
            require("ERP_ORDER_REQUIRED".equals(syncMode), "ERP_MRP必须使用ERP_ORDER_REQUIRED工单边界");
            require(!"MOM_APPROVAL".equals(substituteMode), "ERP_MRP不能由MOM形成正式替代料批准");
        } else {
            require("MOM_APPROVAL".equals(substituteMode), "MOM_MRP必须由MOM_APPROVAL治理正式替代料决策");
            require(StrUtil.isNotBlank(erpSystemCode) && StrUtil.isNotBlank(externalFactoryCode),
                    "MOM_MRP的ERP订单申请或执行凭证回传必须配置ERP系统和外部工厂编码");
        }
    }

    private MdmFactoryModelEntity requireFactory(Long modelId, Scope scope) {
        MdmFactoryModelEntity factory = factoryMapper.selectOne(new LambdaQueryWrapper<MdmFactoryModelEntity>()
                .eq(MdmFactoryModelEntity::getId, modelId)
                .eq(MdmFactoryModelEntity::getTenantId, scope.tenantId())
                .eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.FACTORY.getCode())
                .eq(MdmFactoryModelEntity::getStatus, true).last("LIMIT 1"));
        require(factory != null, "目标节点不存在、未启用或不是FACTORY节点");
        return factory;
    }

    private LambdaQueryWrapper<MdmFactoryPlanningPolicyEntity> policyQuery(Scope scope) {
        LambdaQueryWrapper<MdmFactoryPlanningPolicyEntity> query = new LambdaQueryWrapper<MdmFactoryPlanningPolicyEntity>()
                .eq(MdmFactoryPlanningPolicyEntity::getTenantId, scope.tenantId())
                .eq(MdmFactoryPlanningPolicyEntity::getDelFlag, "0");
        if (StrUtil.isNotBlank(scope.factoryCode())) {
            query.eq(MdmFactoryPlanningPolicyEntity::getFactoryCode, scope.factoryCode());
        }
        return query;
    }

    private String fingerprint(MdmFactoryPlanningPolicyEntity row) {
        return sha256(String.join("|", "PLANNING_POLICY_V1", value(row.getTenantId()),
                value(row.getFactoryCode()), value(row.getVersionNo()), value(row.getMrpAuthority()),
                value(row.getErpSystemCode()), value(row.getExternalFactoryCode()),
                value(row.getProductionOrderSyncMode()), value(row.getSubstituteGovernanceMode()),
                value(row.getEffectiveFrom())));
    }

    private MdmFactoryPlanningPolicyDTO toDto(MdmFactoryPlanningPolicyEntity row) {
        MdmFactoryPlanningPolicyDTO dto = new MdmFactoryPlanningPolicyDTO();
        dto.setId(value(row.getId()));
        dto.setFactoryModelId(value(row.getFactoryModelId()));
        dto.setFactoryCode(row.getFactoryCode());
        dto.setVersionNo(row.getVersionNo());
        dto.setMrpAuthority(row.getMrpAuthority());
        dto.setErpSystemCode(row.getErpSystemCode());
        dto.setExternalFactoryCode(row.getExternalFactoryCode());
        dto.setProductionOrderSyncMode(row.getProductionOrderSyncMode());
        dto.setSubstituteGovernanceMode(row.getSubstituteGovernanceMode());
        dto.setEffectiveFrom(row.getEffectiveFrom());
        dto.setStatus(row.getStatus());
        dto.setContentFingerprint(row.getContentFingerprint());
        dto.setChangeReason(row.getChangeReason());
        dto.setCreateBy(row.getCreateBy());
        dto.setUpdateBy(row.getUpdateBy());
        dto.setCreateTime(row.getCreateTime());
        dto.setUpdateTime(row.getUpdateTime());
        dto.setSubmittedBy(row.getSubmittedBy());
        dto.setSubmittedTime(row.getSubmittedTime());
        dto.setPublishedBy(row.getPublishedBy());
        dto.setPublishedTime(row.getPublishedTime());
        dto.setActivationFailureReason(row.getActivationFailureReason());
        return dto;
    }

    private Scope requireScope(String requestedFactory, boolean write) {
        Long tenantId = TenantContextHolder.getTenantId();
        String factoryCode = StrUtil.trim(FactoryContextHolder.getFactoryCode());
        require(tenantId != null && tenantId > 0 && !TenantContextHolder.getTenantSkip(),
                "计划协同策略必须在实际租户上下文访问");
        require(!Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factoryCode),
                "计划协同策略必须选择实际工厂");
        require(StrUtil.isBlank(requestedFactory) || Objects.equals(factoryCode, requestedFactory.trim()),
                "请求工厂与当前工厂上下文不一致");
        MomUser user = SecurityUtils.getUser();
        require(!write || user != null && user.getId() != null, "计划协同策略写入必须实名登录");
        return new Scope(tenantId, factoryCode, user == null ? "internal" : user.getUsername());
    }

    private static Long parseId(String value, String label) {
        try {
            Long id = Long.valueOf(value);
            require(id > 0, label + "必须为正整数");
            return id;
        } catch (Exception ex) {
            throw new CheckedException(label + "格式不正确");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    private record Scope(Long tenantId, String factoryCode, String operator) {
    }
}
