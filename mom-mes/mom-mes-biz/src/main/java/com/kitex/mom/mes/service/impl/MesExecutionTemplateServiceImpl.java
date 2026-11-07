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
import com.kitex.mom.mes.dto.*;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
                .eq(MesExecutionTemplateEntity::getTenantId, TenantContextHolder.getTenantId())
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
    @Transactional(readOnly = true)
    public MesExecutionTemplatePreviewDTO preview(MesExecutionTemplatePreviewQueryDTO input) {
        String factory = factoryAccess.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        if (input == null || tenant == null) {
            throw new CheckedException("匹配试算缺少参数或实际租户");
        }
        var query = new MesExecutionTemplatePreviewQueryDTO();
        BeanUtil.copyProperties(input, query);
        query.setMaterialCode(previewCode(input.getMaterialCode(), "物料", 100));
        query.setRouteCode(previewCode(input.getRouteCode(), "主工序", 100));
        query.setProcessRouteCode(previewCode(input.getProcessRouteCode(), "工艺路线", 100));
        query.setRouteNodeCode(previewCode(input.getRouteNodeCode(), "稳定节点", 64));
        query.setWorkshopCode(previewCode(input.getWorkshopCode(), "车间", 100));
        query.setWorksiteCodes(String.join(",", split(previewCode(input.getWorksiteCodes(), "所选工位", 2000))));
        query.setRouteExecutionSchemaVersion(trimToNull(input.getRouteExecutionSchemaVersion()));
        if (query.getRouteExecutionSchemaVersion() != null
                && !"NODE_EXECUTION_V1".equals(query.getRouteExecutionSchemaVersion())) {
            throw new CheckedException("不支持该节点执行类型");
        }
        boolean nativeNode = "NODE_EXECUTION_V1".equals(query.getRouteExecutionSchemaVersion());
        if ((query.getRouteNodeCode() != null && !nativeNode) || (nativeNode &&
                (query.getRouteCode() == null || query.getProcessRouteCode() == null || query.getRouteNodeCode() == null
                        || !query.getRouteNodeCode().matches("RN-[1-9][0-9]*")))) {
            throw new CheckedException("原生节点试算须同时填写工艺路线、主工序和稳定节点，不能用显示序号代替节点");
        }
        int current = Objects.requireNonNullElse(query.getCurrent(), 1),
                size = Objects.requireNonNullElse(query.getSize(), 20);
        if (current < 1 || size < 1 || size > 100) {
            throw new CheckedException("试算分页参数无效，每页允许1至100条");
        }
        query.setCurrent(current);
        query.setSize(size);
        query.setMatchedOnly(Boolean.TRUE.equals(query.getMatchedOnly()));
        var task = new MesTaskEntity().setWorkshopCode(query.getWorkshopCode())
                .setProcessRouteCode(query.getProcessRouteCode());
        var arrange = new MesTaskArrangeEntity().setMatCode(query.getMaterialCode()).setRouteCode(query.getRouteCode())
                .setSourceRouteNodeCode(query.getRouteNodeCode())
                .setRouteExecutionSchemaVersion(query.getRouteExecutionSchemaVersion())
                .setWorksiteCodes(query.getWorksiteCodes());
        LocalDateTime at = LocalDateTime.now();
        List<MesExecutionTemplateEntity> templates = list(new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                .eq(MesExecutionTemplateEntity::getTenantId, tenant)
                .eq(MesExecutionTemplateEntity::getFactoryCode, factory));
        List<MesExecutionTemplatePreviewDTO.Candidate> candidates = new ArrayList<>();
        // 与真实派工同一排序；状态/时间/范围不符的行仍返回具体原因。
        for (var template : templates.stream().sorted(MesExecutionTemplateMatcher.order().reversed()).toList()) {
            if (!Objects.equals(template.getTenantId(), tenant)
                    || !Objects.equals(template.getFactoryCode(), factory)) {
                throw new CheckedException("匹配试算返回了其他租户或工厂的模板");
            }
            var candidate = new MesExecutionTemplatePreviewDTO.Candidate();
            BeanUtil.copyProperties(template, candidate);
            candidate.setTemplateId(template.getId() == null ? null : template.getId().toString());
            candidate.setPriority(Objects.requireNonNullElse(template.getPriority(), 0));
            candidate.setSpecificity(MesExecutionTemplateMatcher.specificity(template));
            candidate.setReasons(MesExecutionTemplateMatcher.reasons(template, task, arrange, at));
            candidate.setMatched(candidate.getReasons().isEmpty());
            candidates.add(candidate);
        }
        var eligible = candidates.stream().filter(MesExecutionTemplatePreviewDTO.Candidate::isMatched).toList();
        var result = new MesExecutionTemplatePreviewDTO();
        result.setTenantId(tenant.toString());
        result.setFactoryCode(factory);
        result.setEvaluatedAt(at);
        result.setScenario(query);
        result.setMatchedCount(eligible.size());
        if (!eligible.isEmpty()) {
            eligible.get(0).setSelected(true);
            result.setSelected(eligible.get(0));
        }
        result.setRankingTie(eligible.size() > 1 && eligible.get(0).getPriority() == eligible.get(1).getPriority()
                && eligible.get(0).getSpecificity() == eligible.get(1).getSpecificity());
        result.setMessage(eligible.isEmpty() ? "未命中有效已发布模板，新派工将沿用历史工艺兼容策略；试算不代表可以派工。"
                : result.isRankingTie() ? "存在同优先级、同精确度的重叠候选，当前按版本号及记录号选择；建议调整范围或优先级。"
                : "已找到范围匹配模板；实际派工仍须通过工程、节点和冻结条件核验。");
        var displayed = candidates.stream()
                .filter(row -> !Boolean.TRUE.equals(query.getMatchedOnly()) || row.isMatched()).toList();
        result.setTotal(displayed.size());
        long offset = (long) (current - 1) * size;
        result.setRecords(offset >= displayed.size() ? List.of()
                : List.copyOf(displayed.subList((int) offset, (int) Math.min(offset + size, displayed.size()))));
        return result;
    }

    private String previewCode(String value, String label, int maximum) {
        String code = trimToNull(value);
        if (code != null && code.length() > maximum) {
            throw new CheckedException(label + "试算值超过允许长度");
        }
        return code;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesExecutionTemplateEntity saveDraft(MesExecutionTemplateEntity input) {
        String factoryCode = factoryAccess.require("write");
        normalizeAndValidate(input);
        baseMapper.lockTemplate(factoryCode, input.getTemplateCode());
        MesExecutionTemplateEntity existing = input.getId() == null ? null
                : getOne(
                new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                        .eq(MesExecutionTemplateEntity::getId, input.getId())
                        .eq(MesExecutionTemplateEntity::getTenantId, TenantContextHolder.getTenantId())
                        .eq(MesExecutionTemplateEntity::getFactoryCode, factoryCode)
                        .last("FOR UPDATE"));
        if (input.getId() != null && existing == null) {
            throw new CheckedException("生产执行模板不存在");
        }
        if (existing != null) {
            assertScope(existing, factoryCode);
        }
        if (existing != null && !DRAFT.equals(existing.getStatus())) {
            throw new CheckedException("已发布或已退役版本不可原地修改，请复制新版本");
        }
        if (existing != null && !existing.getTemplateCode().equals(input.getTemplateCode())) {
            throw new CheckedException("模板编码不可修改，请另建模板");
        }
        if (existing == null) {
            Integer maxVersion = list(new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                    .eq(MesExecutionTemplateEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesExecutionTemplateEntity::getFactoryCode, factoryCode)
                    .eq(MesExecutionTemplateEntity::getTemplateCode, input.getTemplateCode()))
                    .stream().map(MesExecutionTemplateEntity::getVersionNo)
                    .filter(Objects::nonNull).max(Integer::compareTo).orElse(0);
            input.setVersionNo(maxVersion + 1);
            input.setRevisionNo(1L);
            input.setStatus(DRAFT);
        } else {
            requireRevision(existing, input.getRevisionNo());
            input.setRevisionNo(nextRevision(existing));
            input.setVersionNo(existing.getVersionNo());
            input.setStatus(DRAFT);
            input.setCreateBy(existing.getCreateBy());
            input.setCreateTime(existing.getCreateTime());
            input.setDelFlag(existing.getDelFlag());
        }
        input.setPublishedBy(null).setPublishedTime(null).setFactoryCode(factoryCode)
                .setTenantId(TenantContextHolder.getTenantId());
        if (!saveOrUpdate(input)) {
            throw new CheckedException("生产执行模板草稿保存失败，请重新读取核对");
        }
        return getById(input.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesExecutionTemplateEntity copyVersion(Long sourceId) {
        String factoryCode = factoryAccess.require("write");
        MesExecutionTemplateEntity source = getOne(new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                .eq(MesExecutionTemplateEntity::getId, sourceId)
                .eq(MesExecutionTemplateEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesExecutionTemplateEntity::getFactoryCode, factoryCode));
        if (source == null) {
            throw new CheckedException("生产执行模板不存在");
        }
        assertScope(source, factoryCode);
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
    @Deprecated
    public MesExecutionTemplateEntity publish(Long id) {
        throw new CheckedException("发布必须提交所见修订，请刷新模板并更新调用端");
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public MesExecutionTemplateEntity publish(Long id, Long expectedRevisionNo) {
        String factoryCode = factoryAccess.require("write");
        baseMapper.lockPublication(TenantContextHolder.getTenantId(), factoryCode);
        MesExecutionTemplateEntity template = locked(id, factoryCode);
        if (template == null) {
            throw new CheckedException("生产执行模板不存在");
        }
        requireRevision(template, expectedRevisionNo);
        if (!DRAFT.equals(template.getStatus())) {
            throw new CheckedException("只有草稿版本可以发布");
        }
        validatePublication(template);
        assertNoAmbiguousPublishedTemplate(template, factoryCode);
        markPublished(template);
        return getById(id);
    }

    private void validatePublication(MesExecutionTemplateEntity template) {
        normalizeAndValidate(template);
        if (template.getRouteNodeCode() != null) {
            identifierNodePolicyValidator.validateTemplateNodes(template, baseMapper.templateNodes(
                    TenantContextHolder.getTenantId(), template.getFactoryCode(), template.getProcessRouteCode()));
        }
        physicalTransformPolicyValidator.assertPublicationAvailable(template.getPhysicalTransformPolicy());
        MesIdentifierNodePolicyDTO nodePolicy = identifierNodePolicyValidator
                .normalize(template.getIdentifierNodePolicy(), template);
        if (!"NONE".equals(nodePolicy.getAction())) {
            nodeReadiness
                    .assertReady(nodeReadiness.publication(templateContract(template, new MesTaskArrangeEntity())));
        }
    }

    private void markPublished(MesExecutionTemplateEntity template) {
        MomUser user = SecurityUtils.getUser();
        template.setRevisionNo(nextRevision(template));
        template.setStatus(PUBLISHED)
                .setPublishedBy(user == null ? "SYSTEM" : user.getUsername())
                .setPublishedTime(LocalDateTime.now());
        if (!updateById(template)) {
            throw new CheckedException("生产执行模板状态保存失败，请重新读取核对");
        }
    }

    @Override
    @Deprecated
    public MesExecutionTemplateEntity retire(Long id) {
        throw new CheckedException("退役必须提交所见修订，请刷新模板并更新调用端");
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public MesExecutionTemplateEntity retire(Long id, Long expectedRevisionNo) {
        String factoryCode = factoryAccess.require("write");
        baseMapper.lockPublication(TenantContextHolder.getTenantId(), factoryCode);
        MesExecutionTemplateEntity template = locked(id, factoryCode);
        if (template == null) {
            throw new CheckedException("生产执行模板不存在");
        }
        requireRevision(template, expectedRevisionNo);
        if (!PUBLISHED.equals(template.getStatus())) {
            throw new CheckedException("只有已发布版本可以退役");
        }
        markRetired(template);
        return getById(id);
    }

    private void markRetired(MesExecutionTemplateEntity template) {
        template.setRevisionNo(nextRevision(template));
        template.setStatus(RETIRED);
        if (!updateById(template)) {
            throw new CheckedException("生产执行模板状态保存失败，请重新读取核对");
        }
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public MesExecutionTemplateReplacementDTO replacementPreview(Long draftId, Long expectedRevisionNo) {
        String factory = factoryAccess.require("read");
        if (draftId == null || draftId <= 0) {
            throw new CheckedException("请指定待切换草稿");
        }
        var draft = getOne(new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                .eq(MesExecutionTemplateEntity::getId, draftId)
                .eq(MesExecutionTemplateEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesExecutionTemplateEntity::getFactoryCode, factory));
        if (draft == null) {
            throw new CheckedException("生产执行模板不存在");
        }
        assertScope(draft, factory);
        requireRevision(draft, expectedRevisionNo);
        LocalDateTime now = LocalDateTime.now();
        if (!DRAFT.equals(draft.getStatus()) || !effectiveAt(draft, now)) {
            throw new CheckedException("立即切换须使用当前有效期内的草稿，未来或已过期配置请单独安排发布");
        }
        validatePublication(draft);
        var sources = list(new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                .eq(MesExecutionTemplateEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesExecutionTemplateEntity::getFactoryCode, factory)
                .eq(MesExecutionTemplateEntity::getStatus, PUBLISHED)
                .eq(MesExecutionTemplateEntity::getTemplateCode, draft.getTemplateCode()))
                .stream().filter(row -> Objects.equals(row.getTemplateCode(), draft.getTemplateCode())
                        && sameScopeAndPriority(row, draft) && effectiveAt(row, now))
                .toList();
        if (sources.size() != 1) {
            throw new CheckedException("须存在唯一同编码、同范围及同优先级的当前有效已发布版本，请核对模板");
        }
        var source = sources.get(0);
        assertScope(source, factory);
        requireRevision(source, source.getRevisionNo());
        validateReplacement(source, draft, now);
        var result = new MesExecutionTemplateReplacementDTO();
        result.setTenantId(TenantContextHolder.getTenantId().toString());
        result.setFactoryCode(factory);
        result.setEvaluatedAt(now);
        result.setSource(source);
        result.setDraft(draft);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public MesExecutionTemplateEntity replacePublished(Long sourceId, Long sourceRevisionNo, Long draftId,
                                                       Long draftRevisionNo) {
        String factory = factoryAccess.require("write");
        if (sourceId == null || draftId == null || sourceId <= 0 || draftId <= 0 || sourceId.equals(draftId)) {
            throw new CheckedException("版本切换须指定不同的已发布版本和草稿");
        }
        baseMapper.lockPublication(TenantContextHolder.getTenantId(), factory);
        // 工厂锁后按ID固定顺序取行锁；不能分别调用两个独立管理事务。
        var first = locked(Math.min(sourceId, draftId), factory);
        var second = locked(Math.max(sourceId, draftId), factory);
        if (first == null || second == null) {
            throw new CheckedException("版本切换模板不存在");
        }
        var source = sourceId.equals(first.getId()) ? first : second;
        var draft = draftId.equals(first.getId()) ? first : second;
        requireRevision(source, sourceRevisionNo);
        requireRevision(draft, draftRevisionNo);
        validatePublication(draft);
        validateReplacement(source, draft, LocalDateTime.now());
        // 所有发布门在状态变化前检查；后续冲突或必要写失败由当前事务恢复旧版。
        markRetired(source);
        assertNoAmbiguousPublishedTemplate(draft, factory);
        markPublished(draft);
        return getById(draftId);
    }

    private void validateReplacement(MesExecutionTemplateEntity source, MesExecutionTemplateEntity draft,
                                     LocalDateTime now) {
        if (!PUBLISHED.equals(source.getStatus()) || !DRAFT.equals(draft.getStatus())
                || !Objects.equals(source.getTemplateCode(), draft.getTemplateCode())
                || !sameScopeAndPriority(source, draft)
                || source.getVersionNo() == null || draft.getVersionNo() == null || source.getVersionNo() <= 0
                || draft.getVersionNo() <= source.getVersionNo()) {
            throw new CheckedException("只能用同编码、同范围、同优先级的更高草稿版本替换已发布版本");
        }
        if (!effectiveAt(source, now) || !effectiveAt(draft, now)) {
            throw new CheckedException("立即切换要求新旧版本均在当前有效期内，请核对有效时间");
        }
    }

    private boolean effectiveAt(MesExecutionTemplateEntity row, LocalDateTime now) {
        return (row.getEffectiveFrom() == null || !row.getEffectiveFrom().isAfter(now))
                && (row.getEffectiveTo() == null || row.getEffectiveTo().isAfter(now));
    }

	/**
	 * 为新派工选择并冻结执行策略，按本行真实配额核对数量可执行性。
	 * 已发布模板和无匹配模板的历史兼容策略均须校验；失败由原派工事务整体回滚。
	 * 此门不接入旧契约读取或历史报工，不因今日配置重写已冻结依据。
	 *
	 * @param task 当前实际组织的原生产工单
	 * @param arrange 本次待出生派工，noticeNum为本行实际配额
	 * @throws CheckedException 实际组织不符、数量规则不可执行或原工程及动态冻结门不通过时拒绝
	 */
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
				.stream().filter(item -> MesExecutionTemplateMatcher.reasons(item, task, arrange, now).isEmpty())
				.max(MesExecutionTemplateMatcher.order())
				.orElse(null);

		MesExecutionContractDTO contract = matched == null
				? legacyContract(arrange)
				: templateContract(matched, arrange);
		// 在任何新冻结事实落入事务前核本行配额；旧发布版本也不能继续出生无法报工的新派工。
		MesExecutionQuantityCompatibility.validateDispatch(ReportModeEnum.fromCode(contract.getReportMode()),
				contract.getPartialReportAllowed(), contract.getOverReportAllowed(), contract.getOverReportRate(),
				contract.getMaxSingleQuantity(), arrange.getNoticeNum());
		physicalTransformPolicyValidator
				.assertPublicationAvailable(contract.getPhysicalTransformPolicy().getConfigurationJson());
		contract.setPhysicalTransformStandard(physicalTransformEngineering.freeze(task, arrange,
				matched == null ? null : matched.getPhysicalTransformStandardId(),
				contract.getPhysicalTransformPolicy().getConfigurationJson()));
		if ("REWORK".equals(task.getResourceType())) {
			/*
			 * 返工不出生第二套BIND/USE/RELEASE生命周期。原实例范围已由双岗返工方案冻结， 派工只保留普通节点动作，现场识别由MesReworkIdentifierUsageService独立核定并记账。
			 */
			contract.setIdentifierNodePolicy(new MesIdentifierNodePolicyDTO());
		}
		if (!"NONE".equals(contract.getIdentifierNodePolicy().getAction())) {
			nodeReadiness.requireDispatch(contract);
		}
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
            if ("NONE".equals(policy.getAction())) {
                continue;
            }
            // 使用已经冻结的实际成员和具体派工，而不是再读取此刻的模板猜后续角色。
            for (var node : policy.getFrozenNodes()) {
                var members = arrangements.stream()
                        .filter(candidate -> Objects.equals(candidate.getRouteCode(), node.getRouteCode())
                                && Objects.equals(candidate.getProduceTaskCode(), node.getProduceTaskCode()))
                        .toList();
                if (members.size() != 1) {
                    throw new CheckedException(
                            "动态区段" + policy.getBindingGroupCode() + "须同批完整派工，缺少工序" + node.getRouteCode());
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
    public MesIdentifierNodeReadinessDTO nodeReadiness(Long templateId, String arrangeCode,
                                                       MesReportChannelEnum channel) {
        String factory = factoryAccess.require("read");
        if (templateId != null && StrUtil.isNotBlank(arrangeCode)) {
            throw new CheckedException("请只选择一个精确模板或派工");
        }
        if (templateId != null) {
            var template = getOne(new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                    .eq(MesExecutionTemplateEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesExecutionTemplateEntity::getFactoryCode, factory)
                    .eq(MesExecutionTemplateEntity::getId, templateId));
            if (template == null) {
                throw new CheckedException("当前组织未找到该执行模板");
            }
            normalizeAndValidate(template);
            return nodeReadiness.publication(templateContract(template, new MesTaskArrangeEntity()));
        }
        if (StrUtil.isNotBlank(arrangeCode)) {
            if (channel == null) {
                throw new CheckedException("精确派工诊断须指定受支持渠道");
            }
            var rows = arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                    .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesTaskArrangeEntity::getFactoryCode, factory).eq(MesTaskArrangeEntity::getCode, arrangeCode)
                    .last("LIMIT 2"));
            if (rows.size() != 1) {
                throw new CheckedException("当前组织未找到唯一精确派工");
            }
            return nodeReadiness.execution(rows.get(0), contract(rows.get(0)), channel);
        }
        if (channel != null) {
            throw new CheckedException("渠道诊断须同时指定精确派工");
        }
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
                contract.getOverReportRate(), BigDecimal.ZERO)))
                : notice;
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
                    || !Objects.equals(command.getIdentifierNodeAction(),
                    contract.getIdentifierNodePolicy().getAction())) {
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
        var template = getOne(new LambdaQueryWrapper<MesExecutionTemplateEntity>()
                .eq(MesExecutionTemplateEntity::getId, id)
                .eq(MesExecutionTemplateEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesExecutionTemplateEntity::getFactoryCode, factoryCode)
                .last("FOR UPDATE"));
        if (template != null) {
            assertScope(template, factoryCode);
        }
        return template;
    }

    private void assertScope(MesExecutionTemplateEntity template, String factoryCode) {
        if (!Objects.equals(template.getTenantId(), TenantContextHolder.getTenantId())
                || !Objects.equals(template.getFactoryCode(), factoryCode)) {
            throw new CheckedException("模板不属于当前租户工厂");
        }
    }

    private void requireRevision(MesExecutionTemplateEntity template, Long expected) {
        if (template.getRevisionNo() == null || template.getRevisionNo() < 0) {
            throw new CheckedException("模板修订信息无效，请确认数据库升级及当前记录");
        }
        if (expected == null || expected < 0 || !Objects.equals(expected, template.getRevisionNo())) {
            throw new CheckedException("模板已变化或缺少所见修订，请刷新并重新核对，原操作未执行");
        }
    }

    private long nextRevision(MesExecutionTemplateEntity template) {
        if (template.getRevisionNo() == null || template.getRevisionNo() < 0
                || template.getRevisionNo() == Long.MAX_VALUE) {
            throw new CheckedException("模板修订计数无效或已达上限，请核对当前记录");
        }
        return template.getRevisionNo() + 1;
    }

	/**
	 * 规范化原草稿字段，并在保存、发布及替换发布前拒绝无法精确落库或无法单件报工的配置。
	 * 数量校验仅使用当前拟保存模板，不回写任何历史派工快照。
	 *
	 * @param template 本次拟保存或发布的模板版本
	 * @throws CheckedException 数量存储精度、单件上限或既有模板规则不通过时拒绝原操作
	 */
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
		MesExecutionQuantityCompatibility.validateTemplate(ReportModeEnum.fromCode(template.getReportMode()),
				template.getOverReportRate(), template.getMaxSingleQuantity());
		if (!Boolean.TRUE.equals(template.getOverReportAllowed())) {
			template.setOverReportRate(BigDecimal.ZERO);
		}
		if (template.getEffectiveFrom() != null && template.getEffectiveTo() != null
				&& !template.getEffectiveTo().isAfter(template.getEffectiveFrom())) {
			throw new CheckedException("模板失效时间必须晚于生效时间");
		}
		template.setIdentifierNodePolicy(identifierNodePolicyValidator.configurationJson(
				identifierNodePolicyValidator.normalize(template.getIdentifierNodePolicy(), template)));
		template.setPhysicalTransformPolicy(
				physicalTransformPolicyValidator.normalize(template.getPhysicalTransformPolicy(), template));
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
        if (triggers.isEmpty()
                || triggers.stream().anyMatch(item -> !List.of("MANUAL", "AUTO_EVENT", "API").contains(item))) {
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
                .eq(MesExecutionTemplateEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesExecutionTemplateEntity::getFactoryCode, factoryCode)
                .eq(MesExecutionTemplateEntity::getStatus, PUBLISHED)
                .ne(MesExecutionTemplateEntity::getId, template.getId()))
                .stream().anyMatch(item -> sameScopeAndPriority(item, template) && timeOverlaps(item, template));
        if (conflict) {
            throw new CheckedException("存在相同适用范围、优先级且有效期重叠的已发布模板，请调整后再发布");
        }
    }

    private boolean sameScopeAndPriority(MesExecutionTemplateEntity first, MesExecutionTemplateEntity second) {
        return Objects.equals(first.getPriority(), second.getPriority())
                && Objects.equals(first.getMaterialCode(), second.getMaterialCode())
                && Objects.equals(first.getRouteCode(), second.getRouteCode())
                && Objects.equals(first.getProcessRouteCode(), second.getProcessRouteCode())
                && Objects.equals(first.getRouteNodeCode(), second.getRouteNodeCode())
                && Objects.equals(first.getWorkshopCode(), second.getWorkshopCode())
                && Objects.equals(first.getWorksiteCode(), second.getWorksiteCode());
    }

    private boolean timeOverlaps(MesExecutionTemplateEntity first, MesExecutionTemplateEntity second) {
        LocalDateTime firstStart = Objects.requireNonNullElse(first.getEffectiveFrom(), LocalDateTime.MIN);
        LocalDateTime firstEnd = Objects.requireNonNullElse(first.getEffectiveTo(), LocalDateTime.MAX);
        LocalDateTime secondStart = Objects.requireNonNullElse(second.getEffectiveFrom(), LocalDateTime.MIN);
        LocalDateTime secondEnd = Objects.requireNonNullElse(second.getEffectiveTo(), LocalDateTime.MAX);
        return firstStart.isBefore(secondEnd) && secondStart.isBefore(firstEnd);
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
                .setIdentifierNodePolicy(
                        identifierNodePolicyValidator.normalize(template.getIdentifierNodePolicy(), template))
                .setPhysicalTransformPolicy(
                        physicalTransformPolicyValidator.freeze(template.getPhysicalTransformPolicy()))
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
                .setPhysicalTransformPolicy(
                        physicalTransformPolicyValidator.freeze(MesPhysicalTransformPolicyValidator.NONE))
                .setAllowedTriggers(List.of("MANUAL", "AUTO_EVENT", "API"))
                .setIdentifierResolveMode("ALLOW_MAPPING")
                .setAllowedIdentifierTypes(
                        List.of("TEMPORARY", "ALTERNATE", "EXTERNAL", "CUSTOMER", "SUPPLIER", "LEGACY"))
                .setPartialReportAllowed(true).setOverReportAllowed(false)
                .setOverReportRate(BigDecimal.ZERO).setPredecessorRequired(true)
                .setMaterialReadyRequired(true).setQualityGateRequired(true)
                .setWorksiteRequired(true).setDeviceRequired(false).setToolingRequired(false)
                .setMobileAllowed(true).setStationAllowed(true);
        addMessages(contract);
        return contract;
    }

    /**
     * 仅原生动态契约交付当前精确节点；旧派工不补字段，以免改变设备旧采集原文。
     */
    private void exposeNativeNode(MesExecutionContractDTO contract, MesTaskArrangeEntity arrange) {
        if (MesIdentifierNodePolicyValidator.nativePolicy(contract.getIdentifierNodePolicy())) {
            contract.setProcessRouteCode(contract.getIdentifierNodePolicy().getProcessRouteCode())
                    .setProduceTaskCode(arrange.getProduceTaskCode())
                    .setSourceRouteNodeCode(arrange.getSourceRouteNodeCode());
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
