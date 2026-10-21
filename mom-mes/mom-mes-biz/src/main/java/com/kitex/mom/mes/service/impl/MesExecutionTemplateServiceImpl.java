package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesExecutionContractDTO;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.dto.MesIdentifierNodeReadinessDTO;
import com.kitex.mom.mes.dto.MesIdentifierNodePolicyDTO;
import com.kitex.mom.mes.dto.ReportModeOptionDTO;
import com.kitex.mom.mes.entity.MesExecutionTemplateEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.MesReportChannelEnum;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.MesExecutionTemplateMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 生产执行模板产品化实现。
 */
@Service
@RequiredArgsConstructor
public class MesExecutionTemplateServiceImpl
        extends ServiceImpl<MesExecutionTemplateMapper, MesExecutionTemplateEntity>
        implements MesExecutionTemplateService {

    private static final String DRAFT = "DRAFT";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String RETIRED = "RETIRED";

    private final MesActualFactoryAccessService factoryAccess;
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesIdentifierNodePolicyValidator identifierNodePolicyValidator;
    private final MesIdentifierBindingGroupService identifierBindingGroupService;
    private final MesIdentifierNodeReadiness nodeReadiness;
    private final MesPhysicalTransformPolicyValidator physicalTransformPolicyValidator;
    private final MesPhysicalTransformEngineeringFreezeService physicalTransformEngineering;

    @Override
    public IPage<MesExecutionTemplateEntity> queryPage(Page<MesExecutionTemplateEntity> page,
                                                       MesExecutionTemplateEntity query) {
        String factoryCode = factoryAccess.require("read");
        LambdaQueryWrapper<MesExecutionTemplateEntity> wrapper = new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                .eq(MesExecutionTemplateEntity::getFactoryCode, factoryCode);
        if (query != null) {
            wrapper.like(StrUtil.isNotBlank(query.getTemplateCode()),
                            MesExecutionTemplateEntity::getTemplateCode, query.getTemplateCode())
                    .like(StrUtil.isNotBlank(query.getTemplateName()),
                            MesExecutionTemplateEntity::getTemplateName, query.getTemplateName())
                    .eq(StrUtil.isNotBlank(query.getStatus()),
                            MesExecutionTemplateEntity::getStatus, query.getStatus())
                    .eq(StrUtil.isNotBlank(query.getReportMode()),
                            MesExecutionTemplateEntity::getReportMode, query.getReportMode());
        }
        wrapper.orderByAsc(MesExecutionTemplateEntity::getTemplateCode)
                .orderByDesc(MesExecutionTemplateEntity::getVersionNo);
        return page(page, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesExecutionTemplateEntity saveDraft(MesExecutionTemplateEntity input) {
        String factoryCode = factoryAccess.require("write");
        normalizeAndValidate(input);
        baseMapper.lockTemplate(factoryCode, input.getTemplateCode());
        MesExecutionTemplateEntity existing = input.getId() == null ? null : getOne(
                new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                        .eq(MesExecutionTemplateEntity::getId, input.getId())
                        .eq(MesExecutionTemplateEntity::getFactoryCode, factoryCode)
                        .last("FOR UPDATE"));
        if (input.getId() != null && existing == null) {
            throw new CheckedException("生产执行模板不存在");
        }
        if (existing != null && !DRAFT.equals(existing.getStatus())) {
            throw new CheckedException("已发布或已退役版本不可原地修改，请复制新版本");
        }
        if (existing != null && !existing.getTemplateCode().equals(input.getTemplateCode())) {
            throw new CheckedException("模板编码不可修改，请另建模板");
        }
        if (existing == null) {
            Integer maxVersion = list(new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                    .eq(MesExecutionTemplateEntity::getFactoryCode, factoryCode)
                    .eq(MesExecutionTemplateEntity::getTemplateCode, input.getTemplateCode()))
                    .stream().map(MesExecutionTemplateEntity::getVersionNo)
                    .filter(Objects::nonNull).max(Integer::compareTo).orElse(0);
            input.setVersionNo(maxVersion + 1);
            input.setStatus(DRAFT);
        } else {
            input.setVersionNo(existing.getVersionNo());
            input.setStatus(DRAFT);
            input.setCreateBy(existing.getCreateBy());
            input.setCreateTime(existing.getCreateTime());
            input.setDelFlag(existing.getDelFlag());
        }
        input.setPublishedBy(null).setPublishedTime(null).setFactoryCode(factoryCode)
                .setTenantId(TenantContextHolder.getTenantId());
        saveOrUpdate(input);
        return getById(input.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesExecutionTemplateEntity copyVersion(Long sourceId) {
        String factoryCode = factoryAccess.require("write");
        MesExecutionTemplateEntity source = getOne(new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                .eq(MesExecutionTemplateEntity::getId, sourceId)
                .eq(MesExecutionTemplateEntity::getFactoryCode, factoryCode));
        if (source == null) {
            throw new CheckedException("生产执行模板不存在");
        }
        MesExecutionTemplateEntity copy = new MesExecutionTemplateEntity();
        BeanUtil.copyProperties(source, copy);
        copy.setId(null);
        copy.setVersionNo(null).setStatus(DRAFT).setPublishedBy(null).setPublishedTime(null);
        copy.setCreateBy(null);
        copy.setCreateTime(null);
        copy.setUpdateBy(null);
        copy.setUpdateTime(null);
        return saveDraft(copy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesExecutionTemplateEntity publish(Long id) {
        String factoryCode = factoryAccess.require("write");
        MesExecutionTemplateEntity template = locked(id, factoryCode);
        if (template == null) {
            throw new CheckedException("生产执行模板不存在");
        }
        if (!DRAFT.equals(template.getStatus())) {
            throw new CheckedException("只有草稿版本可以发布");
        }
        normalizeAndValidate(template);
        if (template.getRouteNodeCode() != null) {
            identifierNodePolicyValidator.validateTemplateNodes(template, baseMapper.templateNodes(
                    TenantContextHolder.getTenantId(), factoryCode, template.getProcessRouteCode()));
        }
        physicalTransformPolicyValidator.assertPublicationAvailable(template.getPhysicalTransformPolicy());
        MesIdentifierNodePolicyDTO nodePolicy = identifierNodePolicyValidator.normalize(template.getIdentifierNodePolicy(), template);
        if (!"NONE".equals(nodePolicy.getAction())) {
            nodeReadiness.assertReady(nodeReadiness.publication(templateContract(template, new MesTaskArrangeEntity())));
        }
        assertNoAmbiguousPublishedTemplate(template, factoryCode);
        MomUser user = SecurityUtils.getUser();
        template.setStatus(PUBLISHED)
                .setPublishedBy(user == null ? "SYSTEM" : user.getUsername())
                .setPublishedTime(LocalDateTime.now());
        updateById(template);
        return getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesExecutionTemplateEntity retire(Long id) {
        String factoryCode = factoryAccess.require("write");
        MesExecutionTemplateEntity template = locked(id, factoryCode);
        if (template == null) {
            throw new CheckedException("生产执行模板不存在");
        }
        if (!PUBLISHED.equals(template.getStatus())) {
            throw new CheckedException("只有已发布版本可以退役");
        }
        template.setStatus(RETIRED);
        updateById(template);
        return getById(id);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void freezeForDispatch(MesTaskEntity task, MesTaskArrangeEntity arrange) {
        String factory = factoryAccess.require("write");
        if (task == null || arrange == null) {
            throw new CheckedException("冻结生产执行模板缺少工单或派工信息");
        }
        if (!Objects.equals(task.getTenantId(), TenantContextHolder.getTenantId())
                || !Objects.equals(arrange.getTenantId(), TenantContextHolder.getTenantId())
                || !Objects.equals(task.getFactoryCode(), factory)
                || !Objects.equals(arrange.getFactoryCode(), factory)) {
            throw new CheckedException("冻结模板前必须确认工单和派工属于当前实际租户工厂");
        }
        LocalDateTime now = LocalDateTime.now();
        MesExecutionTemplateEntity matched = list(new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                .eq(MesExecutionTemplateEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesExecutionTemplateEntity::getFactoryCode, factory)
                .eq(MesExecutionTemplateEntity::getStatus, PUBLISHED)
                .and(item -> item.isNull(MesExecutionTemplateEntity::getEffectiveFrom)
                        .or().le(MesExecutionTemplateEntity::getEffectiveFrom, now))
                .and(item -> item.isNull(MesExecutionTemplateEntity::getEffectiveTo)
                        .or().gt(MesExecutionTemplateEntity::getEffectiveTo, now)))
                .stream().filter(item -> matches(item, task, arrange))
                .max(Comparator.comparingInt((MesExecutionTemplateEntity item) ->
                                Objects.requireNonNullElse(item.getPriority(), 0))
                        .thenComparingInt(this::specificity)
                        .thenComparingInt(item -> Objects.requireNonNullElse(item.getVersionNo(), 0))
                        .thenComparing(item -> Objects.requireNonNullElse(item.getId(), 0L)))
                .orElse(null);

        MesExecutionContractDTO contract = matched == null
                ? legacyContract(arrange) : templateContract(matched, arrange);
        physicalTransformPolicyValidator.assertPublicationAvailable(contract.getPhysicalTransformPolicy().getConfigurationJson());
        contract.setPhysicalTransformStandard(physicalTransformEngineering.freeze(task, arrange,
                matched == null ? null : matched.getPhysicalTransformStandardId(), contract.getPhysicalTransformPolicy().getConfigurationJson()));
        if ("REWORK".equals(task.getResourceType())) {
            /*
             * 返工不出生第二套BIND/USE/RELEASE生命周期。原实例范围已由双岗返工方案冻结，
             * 派工只保留普通节点动作，现场识别由MesReworkIdentifierUsageService独立核定并记账。
             */
            contract.setIdentifierNodePolicy(new MesIdentifierNodePolicyDTO());
        }
        if (!"NONE".equals(contract.getIdentifierNodePolicy().getAction())) nodeReadiness.requireDispatch(contract);
        contract.setIdentifierNodePolicy(identifierBindingGroupService.freezeForDispatch(
                task, arrange, contract.getIdentifierNodePolicy()));
        arrange.setReportMode(contract.getReportMode())
                .setExecutionTemplateId(contract.getTemplateId())
                .setExecutionTemplateCode(contract.getTemplateCode())
                .setExecutionTemplateVersion(contract.getTemplateVersion())
                .setExecutionPolicySnapshot(physicalTransformEngineering.writableSnapshot(contract));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void validateDispatchBundle(List<MesTaskArrangeEntity> arrangements) {
        for (MesTaskArrangeEntity arrange : arrangements) {
            var policy = contract(arrange).getIdentifierNodePolicy();
            if ("NONE".equals(policy.getAction())) continue;
            // 使用已经冻结的实际成员和具体派工，而不是再读取此刻的模板猜后续角色。
            for (var node : policy.getFrozenNodes()) {
                var members = arrangements.stream().filter(candidate ->
                        Objects.equals(candidate.getRouteCode(), node.getRouteCode())
                                && Objects.equals(candidate.getProduceTaskCode(), node.getProduceTaskCode())).toList();
                if (members.size() != 1) {
                    throw new CheckedException("动态区段" + policy.getBindingGroupCode() + "须同批完整派工，缺少工序" + node.getRouteCode());
                }
                var member = members.get(0);
                var actual = contract(member).getIdentifierNodePolicy();
                String role = MesIdentifierNodePolicyValidator.role(policy, member);
                if (!Objects.equals(arrange.getTenantId(), member.getTenantId())
                        || !Objects.equals(arrange.getFactoryCode(), member.getFactoryCode())
                        || !Objects.equals(arrange.getTaskCode(), member.getTaskCode())
                        || !Objects.equals(role, actual.getAction())
                        || !Objects.equals(policy.getBindingGroupId(), actual.getBindingGroupId())
                        || !Objects.equals(policy.getBindingGroupVersion(), actual.getBindingGroupVersion())
                        || !Objects.equals(policy.getDefinitionFingerprint(), actual.getDefinitionFingerprint())
                        || !Objects.equals(policy.getGraphFingerprint(), actual.getGraphFingerprint())
                        || !Objects.equals(policy.getSourceRouteFingerprint(), actual.getSourceRouteFingerprint())) {
                    throw new CheckedException("工序" + node.getRouteCode() + "未冻结同一动态区段/正确角色，请发布相应作用域的模板后重新整批派工");
                }
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public MesIdentifierNodeReadinessDTO nodeReadiness(Long templateId, String arrangeCode, MesReportChannelEnum channel) {
        String factory = factoryAccess.require("read");
        if (templateId != null && StrUtil.isNotBlank(arrangeCode)) throw new CheckedException("请只选择一个精确模板或派工");
        if (templateId != null) {
            var template = getOne(new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                    .eq(MesExecutionTemplateEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesExecutionTemplateEntity::getFactoryCode, factory).eq(MesExecutionTemplateEntity::getId, templateId));
            if (template == null) throw new CheckedException("当前组织未找到该执行模板");
            normalizeAndValidate(template);
            return nodeReadiness.publication(templateContract(template, new MesTaskArrangeEntity()));
        }
        if (StrUtil.isNotBlank(arrangeCode)) {
            if (channel == null) throw new CheckedException("精确派工诊断须指定受支持渠道");
            var rows = arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                    .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesTaskArrangeEntity::getFactoryCode, factory).eq(MesTaskArrangeEntity::getCode, arrangeCode).last("LIMIT 2"));
            if (rows.size() != 1) throw new CheckedException("当前组织未找到唯一精确派工");
            return nodeReadiness.execution(rows.get(0), contract(rows.get(0)), channel);
        }
        if (channel != null) throw new CheckedException("渠道诊断须同时指定精确派工");
        return nodeReadiness.deployment();
    }

    @Override
    public MesExecutionContractDTO contract(String arrangeCode) {
        String factoryCode = factoryAccess.require("read");
        MesTaskArrangeEntity arrange = arrangeMapper.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getCode, arrangeCode)
                .eq(MesTaskArrangeEntity::getFactoryCode, factoryCode));
        if (arrange == null) {
            throw new CheckedException("派工任务不存在或不属于当前工厂");
        }
        return contract(arrange);
    }

    @Override
    public MesExecutionContractDTO contract(MesTaskArrangeEntity arrange) {
        MesExecutionContractDTO contract;
        if (StrUtil.isNotBlank(arrange.getExecutionPolicySnapshot())) {
            try {
                contract = JSONUtil.toBean(arrange.getExecutionPolicySnapshot(), MesExecutionContractDTO.class);
            } catch (Exception exception) {
                throw new CheckedException("派工任务的生产执行策略快照已损坏，禁止继续执行");
            }
        } else {
            contract = legacyContract(arrange);
        }
        // 旧快照缺字段或显式null都表示未开启动态动作，绝不读取新模板补造旧事实。
        if (contract.getIdentifierNodePolicy() == null) {
            contract.setIdentifierNodePolicy(new com.kitex.mom.mes.dto.MesIdentifierNodePolicyDTO());
        }
        exposeNativeNode(contract, arrange);
        contract.setPhysicalTransformPolicy(physicalTransformPolicyValidator.readFrozen(arrange));
        contract.setPhysicalTransformStandard(physicalTransformEngineering.readFrozen(arrange,
                contract.getPhysicalTransformPolicy().getConfigurationJson()));
        BigDecimal notice = Objects.requireNonNullElse(arrange.getNoticeNum(), BigDecimal.ZERO);
        BigDecimal completed = Objects.requireNonNullElse(arrange.getCompleteNum(), BigDecimal.ZERO);
        BigDecimal upper = Boolean.TRUE.equals(contract.getOverReportAllowed())
                ? notice.multiply(BigDecimal.ONE.add(Objects.requireNonNullElse(
                contract.getOverReportRate(), BigDecimal.ZERO))) : notice;
        contract.setArrangeCode(arrange.getCode())
                .setNoticeQuantity(notice)
                .setCompletedQuantity(completed)
                .setRemainingQuantity(upper.subtract(completed).max(BigDecimal.ZERO));
        return contract;
    }

    @Override
    public void validateReport(MesTaskArrangeEntity arrange, MesTaskReportVO command,
                               MesReportChannelEnum channel) {
        MesExecutionContractDTO contract = contract(arrange);
        if (!"NONE".equals(contract.getIdentifierNodePolicy().getAction())) {
            nodeReadiness.assertReady(nodeReadiness.execution(arrange, contract, channel));
            if (command.getIdentifierBindingId() == null || command.getIdentifierBindingSnapshot() == null
                    || !Objects.equals(command.getIdentifierNodeAction(), contract.getIdentifierNodePolicy().getAction())) {
                throw new CheckedException("动态节点缺少共享协调器核定的原实例事实，禁止普通报工旁路");
            }
        }
        String trigger = channel.getTriggerCode();
        if (!contract.getAllowedTriggers().contains(trigger)) {
            throw new CheckedException("当前生产执行模板不允许" + triggerName(trigger) + "报工");
        }
        if (channel == MesReportChannelEnum.MOBILE && !Boolean.TRUE.equals(contract.getMobileAllowed())) {
            throw new CheckedException("当前生产执行模板未开放移动端报工");
        }
        if (channel == MesReportChannelEnum.STATION && !Boolean.TRUE.equals(contract.getStationAllowed())) {
            throw new CheckedException("当前生产执行模板未开放PAD/工位端报工");
        }
        BigDecimal quantity = command.getPassNum();
        if (quantity == null || quantity.signum() <= 0) {
            throw new CheckedException("合格报工数量必须大于0");
        }
        if (contract.getMaxSingleQuantity() != null
                && quantity.compareTo(contract.getMaxSingleQuantity()) > 0) {
            throw new CheckedException("本次报工数量超过执行模板规定的单次上限"
                    + contract.getMaxSingleQuantity());
        }
        boolean routeReturn = MesIdentifierRouteReturnCoordinator.ROUTE_RETURN.equals(command.getExecutionKind());
        if (!routeReturn && !Boolean.TRUE.equals(contract.getPartialReportAllowed())
                && quantity.compareTo(contract.getRemainingQuantity()) != 0) {
            throw new CheckedException("当前执行模板要求一次报完剩余数量"
                    + contract.getRemainingQuantity());
        }
        if (!routeReturn && quantity.compareTo(contract.getRemainingQuantity()) > 0) {
            throw new CheckedException("本次报工数量超过执行模板允许的剩余数量"
                    + contract.getRemainingQuantity());
        }
        if (Boolean.TRUE.equals(contract.getDeviceRequired()) && StrUtil.isBlank(command.getDeviceCode())) {
            throw new CheckedException("当前执行模板要求报工时绑定设备");
        }
        if (Boolean.TRUE.equals(contract.getWorksiteRequired())
                && StrUtil.isBlank(command.getWorksiteCode()) && StrUtil.isBlank(arrange.getActualWorksiteCode())) {
            throw new CheckedException("当前执行模板要求报工时确认实际工位");
        }
        if (Boolean.TRUE.equals(contract.getToolingRequired())
                && StrUtil.isBlank(arrange.getPlannedToolingCodes())) {
            throw new CheckedException("当前执行模板要求工装校验，但派工未冻结计划工装");
        }
    }

    private String triggerName(String trigger) {
        return switch (trigger) {
            case "AUTO_EVENT" -> "设备事件";
            case "API" -> "集成接口";
            default -> "人工";
        };
    }

    private MesExecutionTemplateEntity locked(Long id, String factoryCode) {
        return getOne(new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                .eq(MesExecutionTemplateEntity::getId, id)
                .eq(MesExecutionTemplateEntity::getFactoryCode, factoryCode)
                .last("FOR UPDATE"));
    }

    private void normalizeAndValidate(MesExecutionTemplateEntity template) {
        if (template == null || StrUtil.isBlank(template.getTemplateCode())
                || StrUtil.isBlank(template.getTemplateName())) {
            throw new CheckedException("模板编码和名称不能为空");
        }
        template.setTemplateCode(template.getTemplateCode().trim())
                .setTemplateName(template.getTemplateName().trim())
                .setMaterialCode(trimToNull(template.getMaterialCode()))
                .setRouteCode(trimToNull(template.getRouteCode()))
                .setProcessRouteCode(trimToNull(template.getProcessRouteCode()))
                .setRouteNodeCode(trimToNull(template.getRouteNodeCode()))
                .setWorkshopCode(trimToNull(template.getWorkshopCode()))
                .setWorksiteCode(trimToNull(template.getWorksiteCode()))
                .setPriority(Objects.requireNonNullElse(template.getPriority(), 0))
                .setReportMode(ReportModeEnum.fromCode(template.getReportMode()).getCode())
                .setAllowedTriggers(normalizeTriggers(template.getAllowedTriggers()))
                .setIdentifierResolveMode(normalizeIdentifierResolveMode(template.getIdentifierResolveMode()))
                .setAllowedIdentifierTypes(normalizeIdentifierTypes(template.getAllowedIdentifierTypes()))
                .setPartialReportAllowed(!Boolean.FALSE.equals(template.getPartialReportAllowed()))
                .setOverReportAllowed(Boolean.TRUE.equals(template.getOverReportAllowed()))
                .setOverReportRate(Objects.requireNonNullElse(template.getOverReportRate(), BigDecimal.ZERO))
                // 四项是标准产品安全门，模板只能冻结并展示，当前版本不允许配置旁路。
                .setPredecessorRequired(true)
                .setMaterialReadyRequired(true)
                .setQualityGateRequired(true)
                .setWorksiteRequired(true)
                .setDeviceRequired(Boolean.TRUE.equals(template.getDeviceRequired()))
                .setToolingRequired(Boolean.TRUE.equals(template.getToolingRequired()))
                .setMobileAllowed(!Boolean.FALSE.equals(template.getMobileAllowed()))
                .setStationAllowed(!Boolean.FALSE.equals(template.getStationAllowed()))
                .setRemark(trimToNull(template.getRemark()));
        if (template.getTemplateCode().length() > 100 || template.getTemplateName().length() > 200) {
            throw new CheckedException("模板编码或名称超过允许长度");
        }
        if ((template.getProcessRouteCode() == null) != (template.getRouteNodeCode() == null)
                || (template.getRouteNodeCode() != null && (template.getRouteCode() == null
                || template.getProcessRouteCode().length() > 100 || template.getRouteNodeCode().length() > 64
                || !template.getRouteNodeCode().matches("RN-[1-9][0-9]*")))) {
            throw new CheckedException("精确节点模板须同时指定真实工艺路线、稳定节点和主工序");
        }
        if (template.getOverReportRate().signum() < 0
                || template.getOverReportRate().compareTo(new BigDecimal("1")) > 0) {
            throw new CheckedException("允许超报比例必须在0至1之间");
        }
        if (!Boolean.TRUE.equals(template.getOverReportAllowed())) {
            template.setOverReportRate(BigDecimal.ZERO);
        }
        if (template.getMaxSingleQuantity() != null && template.getMaxSingleQuantity().signum() <= 0) {
            throw new CheckedException("单次报工上限必须大于0");
        }
        if (template.getEffectiveFrom() != null && template.getEffectiveTo() != null
                && !template.getEffectiveTo().isAfter(template.getEffectiveFrom())) {
            throw new CheckedException("模板失效时间必须晚于生效时间");
        }
        template.setIdentifierNodePolicy(identifierNodePolicyValidator.configurationJson(
                identifierNodePolicyValidator.normalize(template.getIdentifierNodePolicy(), template)));
        template.setPhysicalTransformPolicy(physicalTransformPolicyValidator.normalize(template.getPhysicalTransformPolicy(), template));
        if (MesPhysicalTransformPolicyValidator.NONE.equals(template.getPhysicalTransformPolicy())
                && template.getPhysicalTransformStandardId() != null) {
            throw new CheckedException("NONE转换模板不能引用工程标准，请先明确实际转换政策");
        }
        if (template.getPhysicalTransformStandardId() != null && template.getPhysicalTransformStandardId() <= 0) {
            throw new CheckedException("转换工程标准须为精确正Long ID");
        }
    }

    private String normalizeTriggers(String value) {
        List<String> triggers = Arrays.stream(StrUtil.blankToDefault(value, "MANUAL").split(","))
                .map(String::trim).map(String::toUpperCase).filter(StrUtil::isNotBlank).distinct().toList();
        if (triggers.isEmpty() || triggers.stream().anyMatch(item ->
                !List.of("MANUAL", "AUTO_EVENT", "API").contains(item))) {
            throw new CheckedException("触发来源只允许MANUAL、AUTO_EVENT、API");
        }
        return String.join(",", triggers);
    }

    private String normalizeIdentifierResolveMode(String value) {
        String normalized = StrUtil.blankToDefault(value, "ALLOW_MAPPING").trim().toUpperCase();
        if (!List.of("CANONICAL_ONLY", "ALLOW_MAPPING", "MAPPING_REQUIRED").contains(normalized)) {
            throw new CheckedException("标识解析方式只允许CANONICAL_ONLY、ALLOW_MAPPING、MAPPING_REQUIRED");
        }
        return normalized;
    }

    private String normalizeIdentifierTypes(String value) {
        List<String> types = Arrays.stream(StrUtil.blankToDefault(value,
                        "TEMPORARY,ALTERNATE,EXTERNAL,CUSTOMER,SUPPLIER,LEGACY").split(","))
                .map(String::trim).map(String::toUpperCase).filter(StrUtil::isNotBlank).distinct().toList();
        if (types.isEmpty() || types.stream().anyMatch(type -> !List.of(
                "TEMPORARY", "ALTERNATE", "EXTERNAL", "CUSTOMER", "SUPPLIER", "LEGACY").contains(type))) {
            throw new CheckedException("执行模板包含不支持的标识映射类型");
        }
        return String.join(",", types);
    }

    private void assertNoAmbiguousPublishedTemplate(MesExecutionTemplateEntity template, String factoryCode) {
        boolean conflict = list(new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                .eq(MesExecutionTemplateEntity::getFactoryCode, factoryCode)
                .eq(MesExecutionTemplateEntity::getStatus, PUBLISHED)
                .ne(MesExecutionTemplateEntity::getId, template.getId()))
                .stream().anyMatch(item -> Objects.equals(item.getPriority(), template.getPriority())
                        && Objects.equals(item.getMaterialCode(), template.getMaterialCode())
                        && Objects.equals(item.getRouteCode(), template.getRouteCode())
                        && Objects.equals(item.getProcessRouteCode(), template.getProcessRouteCode())
                        && Objects.equals(item.getRouteNodeCode(), template.getRouteNodeCode())
                        && Objects.equals(item.getWorkshopCode(), template.getWorkshopCode())
                        && Objects.equals(item.getWorksiteCode(), template.getWorksiteCode())
                        && timeOverlaps(item, template));
        if (conflict) {
            throw new CheckedException("存在相同适用范围、优先级且有效期重叠的已发布模板，请调整后再发布");
        }
    }

    private boolean timeOverlaps(MesExecutionTemplateEntity first, MesExecutionTemplateEntity second) {
        LocalDateTime firstStart = Objects.requireNonNullElse(first.getEffectiveFrom(), LocalDateTime.MIN);
        LocalDateTime firstEnd = Objects.requireNonNullElse(first.getEffectiveTo(), LocalDateTime.MAX);
        LocalDateTime secondStart = Objects.requireNonNullElse(second.getEffectiveFrom(), LocalDateTime.MIN);
        LocalDateTime secondEnd = Objects.requireNonNullElse(second.getEffectiveTo(), LocalDateTime.MAX);
        return firstStart.isBefore(secondEnd) && secondStart.isBefore(firstEnd);
    }

    private boolean matches(MesExecutionTemplateEntity template, MesTaskEntity task,
                            MesTaskArrangeEntity arrange) {
        return matchesValue(template.getMaterialCode(), arrange.getMatCode())
                && matchesValue(template.getRouteCode(), arrange.getRouteCode())
                && matchesValue(template.getProcessRouteCode(), task.getProcessRouteCode())
                && matchesValue(template.getRouteNodeCode(), arrange.getSourceRouteNodeCode())
                && (template.getRouteNodeCode() == null || "NODE_EXECUTION_V1".equals(arrange.getRouteExecutionSchemaVersion()))
                && matchesValue(template.getWorkshopCode(), task.getWorkshopCode())
                && (StrUtil.isBlank(template.getWorksiteCode())
                || split(arrange.getWorksiteCodes()).contains(template.getWorksiteCode()));
    }

    private boolean matchesValue(String expected, String actual) {
        return StrUtil.isBlank(expected) || Objects.equals(expected, actual);
    }

    private int specificity(MesExecutionTemplateEntity template) {
        return (StrUtil.isNotBlank(template.getMaterialCode()) ? 1 : 0)
                + (StrUtil.isNotBlank(template.getRouteCode()) ? 1 : 0)
                + (StrUtil.isNotBlank(template.getProcessRouteCode()) ? 1 : 0)
                + (StrUtil.isNotBlank(template.getRouteNodeCode()) ? 1 : 0)
                + (StrUtil.isNotBlank(template.getWorkshopCode()) ? 1 : 0)
                + (StrUtil.isNotBlank(template.getWorksiteCode()) ? 1 : 0);
    }

    private List<String> split(String value) {
        return Arrays.stream(StrUtil.blankToDefault(value, "").split(","))
                .map(String::trim).filter(StrUtil::isNotBlank).toList();
    }

    private MesExecutionContractDTO templateContract(MesExecutionTemplateEntity template,
                                                     MesTaskArrangeEntity arrange) {
        ReportModeEnum mode = ReportModeEnum.fromCode(template.getReportMode());
        ReportModeOptionDTO option = ReportModeOptionDTO.from(mode);
        MesExecutionContractDTO contract = baseContract(option, arrange);
        contract.setTemplateId(template.getId()).setTemplateCode(template.getTemplateCode())
                .setTemplateName(template.getTemplateName()).setTemplateVersion(template.getVersionNo())
                .setTemplateFrozen(true).setAllowedTriggers(split(template.getAllowedTriggers()))
                .setIdentifierResolveMode(template.getIdentifierResolveMode())
                .setAllowedIdentifierTypes(split(template.getAllowedIdentifierTypes()))
                .setIdentifierNodePolicy(identifierNodePolicyValidator.normalize(template.getIdentifierNodePolicy(), template))
                .setPhysicalTransformPolicy(physicalTransformPolicyValidator.freeze(template.getPhysicalTransformPolicy()))
                .setPartialReportAllowed(template.getPartialReportAllowed())
                .setOverReportAllowed(template.getOverReportAllowed())
                .setOverReportRate(template.getOverReportRate())
                .setMaxSingleQuantity(template.getMaxSingleQuantity())
                .setPredecessorRequired(template.getPredecessorRequired())
                .setMaterialReadyRequired(template.getMaterialReadyRequired())
                .setQualityGateRequired(template.getQualityGateRequired())
                .setWorksiteRequired(template.getWorksiteRequired())
                .setDeviceRequired(template.getDeviceRequired())
                .setToolingRequired(template.getToolingRequired())
                .setMobileAllowed(template.getMobileAllowed())
                .setStationAllowed(template.getStationAllowed());
        exposeNativeNode(contract, arrange);
        addMessages(contract);
        return contract;
    }

    private MesExecutionContractDTO legacyContract(MesTaskArrangeEntity arrange) {
        ReportModeEnum mode = ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType());
        MesExecutionContractDTO contract = baseContract(ReportModeOptionDTO.from(mode), arrange)
                .setTemplateCode("LEGACY_ROUTE").setTemplateName("历史工艺兼容策略")
                .setTemplateVersion(0).setTemplateFrozen(false)
                .setPhysicalTransformPolicy(physicalTransformPolicyValidator.freeze(MesPhysicalTransformPolicyValidator.NONE))
                .setAllowedTriggers(List.of("MANUAL", "AUTO_EVENT", "API"))
                .setIdentifierResolveMode("ALLOW_MAPPING")
                .setAllowedIdentifierTypes(List.of("TEMPORARY", "ALTERNATE", "EXTERNAL", "CUSTOMER", "SUPPLIER", "LEGACY"))
                .setPartialReportAllowed(true).setOverReportAllowed(false)
                .setOverReportRate(BigDecimal.ZERO).setPredecessorRequired(true)
                .setMaterialReadyRequired(true).setQualityGateRequired(true)
                .setWorksiteRequired(true).setDeviceRequired(false).setToolingRequired(false)
                .setMobileAllowed(true).setStationAllowed(true);
        addMessages(contract);
        return contract;
    }

    /** 仅原生动态契约交付当前精确节点；旧派工不补字段，以免改变设备旧采集原文。 */
    private void exposeNativeNode(MesExecutionContractDTO contract, MesTaskArrangeEntity arrange) {
        if (MesIdentifierNodePolicyValidator.nativePolicy(contract.getIdentifierNodePolicy())) {
            contract.setProcessRouteCode(contract.getIdentifierNodePolicy().getProcessRouteCode())
                    .setProduceTaskCode(arrange.getProduceTaskCode()).setSourceRouteNodeCode(arrange.getSourceRouteNodeCode());
        }
    }

    private MesExecutionContractDTO baseContract(ReportModeOptionDTO option, MesTaskArrangeEntity arrange) {
        return new MesExecutionContractDTO().setArrangeCode(arrange.getCode())
                .setReportMode(option.getCode()).setReportModeName(option.getName())
                .setObjectGranularity(option.getObjectGranularity())
                .setQuantityPolicy(option.getQuantityPolicy())
                .setIdentifierPolicy(option.getIdentifierPolicy());
    }

    private void addMessages(MesExecutionContractDTO contract) {
        contract.getControlMessages().clear();
        contract.getControlMessages().add("报工模式：" + contract.getReportModeName());
        if ("MAPPING_REQUIRED".equals(contract.getIdentifierResolveMode())) {
            contract.getControlMessages().add("扫描标识必须通过已发布映射转换为规范码");
        } else if ("CANONICAL_ONLY".equals(contract.getIdentifierResolveMode())) {
            contract.getControlMessages().add("只允许直接扫描MES规范码");
        }
        if (!Boolean.TRUE.equals(contract.getPartialReportAllowed())) {
            contract.getControlMessages().add("本派工必须一次报完剩余数量");
        }
        if (Boolean.TRUE.equals(contract.getDeviceRequired())) {
            contract.getControlMessages().add("报工前必须绑定并校验设备");
        }
        if (Boolean.TRUE.equals(contract.getToolingRequired())) {
            contract.getControlMessages().add("报工前必须完成工装校验");
        }
        if (contract.getMaxSingleQuantity() != null) {
            contract.getControlMessages().add("单次报工上限：" + contract.getMaxSingleQuantity());
        }
    }

    private String trimToNull(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }
}
