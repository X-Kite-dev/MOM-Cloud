package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesIdentifierBindingPreviewDTO;
import com.kitex.mom.mes.dto.MesIdentifierNodePolicyDTO;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import com.kitex.mom.mes.service.MesIdentifierBindingService;
import com.kitex.mom.mes.service.MesIdentifierMappingService;
import com.kitex.mom.mes.service.MesReportCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 动态外码统一报工协调器。
 *
 * <p>锁序固定为原请求、真实工单、排序标识键、实例行。本服务不再获取其他工单锁。
 * 准备阶段只核定和锁定；实例状态和操作账在报工/库存/数量/原回执成功后原子提交。
 * 只读预览不持业务锁、不预占、不发离线许可，执行时必须带回具体实例和版本。</p>
 */
@Service
@RequiredArgsConstructor
public class MesIdentifierBindingServiceImpl
        extends ServiceImpl<MesIdentifierBindingMapper, MesIdentifierBindingEntity>
        implements MesIdentifierBindingService {

    private static final ObjectMapper SNAPSHOT_JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final MesActualFactoryAccessService factoryAccess;
    private final MesExecutionTemplateService templates;
    private final MesIdentifierMappingService mappings;
    private final MesIdentifierMappingMapper mappingMapper;
    private final MesIdentifierBindingGroupMapper groupMapper;
    private final MesIdentifierBindingOperationMapper operationMapper;
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesTaskMapper taskMapper;
    private final MesTaskReportMapper reportMapper;
    private final MesAutoReportEventMapper eventMapper;
    private final MesIdentifierNodeReadiness nodeReadiness;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Prepared prepare(MesTaskReportVO command, MesTaskArrangeEntity arrange, MesTaskEntity task,
                            ReportModeEnum mode, MesReportCommandService.CapturedCommand captured) {
        clearFacts(command);
        String factory = factoryAccess.require("write");
        assertScope(arrange, task, factory);
        var contract = templates.contract(arrange);
        MesIdentifierNodePolicyDTO policy = contract.getIdentifierNodePolicy();
        if ("NONE".equals(policy.getAction())) {
            require(command.getExternalIdentifier() == null && command.getExpectedBindingId() == null
                    && command.getExpectedBindingVersion() == null, "普通节点不能接收换码实例输入");
            String submitted = command.getTransferCode();
            mappings.resolve(arrange, task, mode, command);
            // 原扫描与静态解析后的SN一次排序锁定，不能先锁别名再晚取反向规范码锁。
            if (command.getTransferCode() != null && !command.getTransferCode().isBlank()) {
                String canonical = identifier(command.getTransferCode(), "规范标识");
                lockIdentifiers(factory, List.of(identifier(submitted, "现场标识"), canonical));
                assertNotExternalHistory(factory, submitted.trim());
                assertNotExternalHistory(factory, canonical);
            }
            return null;
        }
        require(captured != null && "MES_REPORT_COMMAND_V2".equals(captured.version())
                && captured.replayReportId() == null
                && Objects.equals(captured.tenantId(), tenant())
                && Objects.equals(captured.factoryCode(), factory)
                && Objects.equals(captured.actorId(), SecurityUtils.getUser().getId()), "动态节点缺少本次完整V2原正文");
        // 仅新命令到达此处；成功原号已由上层在任何当前环境/条码解析前返回。
        var channel = com.kitex.mom.mes.enums.MesReportChannelEnum.valueOf(captured.channel());
        nodeReadiness.assertReady(nodeReadiness.execution(arrange, contract, channel));
        MesAutoReportEventEntity deviceEvent = "DEVICE".equals(captured.channel())
                ? verifyDeviceCapture(command, arrange, policy, captured) : null;
        if ("INTEGRATION".equals(captured.channel())) {
            require(command.getSourceEventId() != null && !command.getSourceEventId().isBlank()
                            && command.getSourceEventId().length() <= 100 && command.getSourceEventTime() != null
                            && command.getSourceEventTime().getNano() == 0,
                    "动态集成报工须提供稳定原事件号及发生时间，与原号原正文一起保留");
        }
        require(command.getCarrierOperationId() == null && command.getCarrierCode() == null
                && command.getCorrectionPermitNo() == null, "动态换码不能经旧载具展开或数量重报许可旁路");
        require(mode == ReportModeEnum.ONE_CODE && command.getPassNum() != null && BigDecimal.ONE.compareTo(command.getPassNum()) == 0,
                "动态换码首版只支持一件一个内部SN，不能将批量或包装数量当作单件");
        require(!"REWORK".equals(task.getResourceType()), "返工工单不能继承原工单的活动外码区段");
        MesIdentifierBindingGroupEntity group = frozenGroup(policy, arrange, task, factory);
        if ("BIND".equals(policy.getAction())) assertRunnableGroup(policy, arrange, task, factory);
        Prepared prepared = resolve(command, arrange, task, mode, policy, group, factory, true);
        if (deviceEvent != null) require(Objects.equals(deviceEvent.getCaptureCanonicalIdentifier(),
                prepared.binding().getCanonicalIdentifier()), "原采集内部SN与最终事务核定不一致，禁止改报新件");
        applyFacts(command, prepared);
        return prepared;
    }

    /**
     * 仅核对正式落库采集事实，不采信HTTP隐藏字段、不晚取事件锁造成反向锁序。
     */
    private MesAutoReportEventEntity verifyDeviceCapture(MesTaskReportVO command, MesTaskArrangeEntity arrange,
                                                         MesIdentifierNodePolicyDTO policy,
                                                         MesReportCommandService.CapturedCommand captured) {
        List<MesAutoReportEventEntity> rows = eventMapper.selectList(new LambdaQueryWrapper<MesAutoReportEventEntity>()
                .eq(MesAutoReportEventEntity::getTenantId, captured.tenantId())
                .eq(MesAutoReportEventEntity::getFactoryCode, captured.factoryCode())
                .eq(MesAutoReportEventEntity::getSourceEventId, command.getSourceEventId()).last("LIMIT 2"));
        require(rows.size() == 1, "设备动态节点须有唯一正式原采集事件，不能直接拼报工请求");
        var event = rows.get(0);
        MesAutoReportCaptureEvidence.verifyStored(event);
        require("RECEIVED".equals(event.getStatus()) && Objects.equals(command.getRequestId(), "AUTO_EVENT:" + event.getId())
                        && Objects.equals(event.getCaptureActorId(), captured.actorId())
                        && Objects.equals(event.getCaptureActorCode(), captured.actorCode())
                        && Objects.equals(event.getRequestedArrangeCode(), arrange.getCode())
                        && Objects.equals(event.getCapturePolicySnapshot(), JSONUtil.toJsonStr(policy))
                        && Objects.equals(event.getSourceEventTime(), command.getSourceEventTime())
                        && Objects.equals(event.getDeviceCode(), command.getDeviceCode())
                        && Objects.equals(event.getTransferCode(), command.getTransferCode())
                        && Objects.equals(event.getExternalIdentifier(), command.getExternalIdentifier())
                        && Objects.equals(event.getExpectedBindingId() == null ? null : event.getExpectedBindingId().toString(), command.getExpectedBindingId())
                        && Objects.equals(event.getExpectedBindingVersion(), command.getExpectedBindingVersion())
                        && Objects.equals(JSONUtil.parseObj(event.getCaptureRuleSnapshot()).getStr("worksiteCode"), command.getWorksiteCode())
                        && Objects.equals(command.getOperatorCode(), "DEVICE:" + event.getDeviceCode())
                        && Objects.equals(command.getOperatorName(), "设备自动报工[" + event.getDeviceCode() + "]"),
                "正式设备报工与原采集实名/节点/原码/实例版本不一致，禁止重新解释");
        return event;
    }

    /**
     * 同一事务内构造报工时冻结动态事实，不让HTTP隐藏字段或后续静态映射覆盖。
     */
    @Override
    public void stampReport(Prepared prepared, MesTaskReportEntity report) {
        if (prepared == null) return;
        report.setIdentifierBindingId(prepared.binding().getId())
                .setIdentifierBindingVersion(prepared.versionBefore() == 0 ? 1 : prepared.versionBefore())
                .setIdentifierNodeAction(prepared.action()).setIdentifierBindingSnapshot(prepared.evidenceSnapshot());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void complete(Prepared prepared, MesTaskReportEntity report, MesReportCommandService.CapturedCommand captured) {
        if (prepared == null) return;
        String factory = factoryAccess.require("write");
        MesIdentifierBindingEntity binding = prepared.binding();
        require(report != null && report.getId() != null && "EFFECTIVE".equals(report.getStatus())
                        && Objects.equals(report.getTenantId(), tenant()) && Objects.equals(report.getFactoryCode(), factory)
                        && Objects.equals(report.getRequestId(), captured.requestId())
                        && Objects.equals(report.getIdentifierBindingId(), binding.getId())
                        && Objects.equals(report.getTransferCode(), binding.getCanonicalIdentifier())
                        && Objects.equals(report.getIdentifierNodeAction(), prepared.action())
                        && Objects.equals(report.getIdentifierBindingSnapshot(), prepared.evidenceSnapshot()),
                "动态节点准备结果与实际报工不一致，整笔回滚");
        LocalDateTime now = LocalDateTime.now();
        if ("BIND".equals(prepared.action())) {
            binding.setBindReportId(report.getId()).setBindTime(now);
            audit(binding, captured.actorCode(), now);
            require(baseMapper.insert(binding) == 1, "绑定实例未保存，报工及库存全部回滚");
        } else if ("RELEASE".equals(prepared.action())) {
            // 只允许核定的原活动版本迁移；失败报工、预览和迟到事件均不能提前释放。
            require(baseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MesIdentifierBindingEntity>()
                    .eq(MesIdentifierBindingEntity::getTenantId, tenant())
                    .eq(MesIdentifierBindingEntity::getFactoryCode, factory)
                    .eq(MesIdentifierBindingEntity::getId, binding.getId())
                    .eq(MesIdentifierBindingEntity::getStatus, "ACTIVE")
                    .eq(MesIdentifierBindingEntity::getVersionNo, prepared.versionBefore())
                    .set(MesIdentifierBindingEntity::getStatus, "RELEASED")
                    .set(MesIdentifierBindingEntity::getVersionNo, prepared.versionAfter())
                    .set(MesIdentifierBindingEntity::getReleaseReportId, report.getId())
                    .set(MesIdentifierBindingEntity::getReleaseTime, now)
                    .set(MesIdentifierBindingEntity::getUpdateBy, captured.actorCode())
                    .set(MesIdentifierBindingEntity::getUpdateTime, now)) == 1, "原占用实例已变化，禁止释放另一次占用");
        }
        MesTaskArrangeEntity arrange = arrangeMapper.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant()).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, report.getArrangeCode()));
        require(arrange != null, "节点操作缺少原具体派工");
        var operation = new MesIdentifierBindingOperationEntity().setFactoryCode(factory)
                .setBindingId(binding.getId()).setBindingGroupId(binding.getBindingGroupId()).setReportId(report.getId())
                .setRequestId(captured.requestId()).setCommandVersion(captured.version())
                .setRequestFingerprint(captured.fingerprint()).setAction(prepared.action())
                .setArrangeCode(report.getArrangeCode()).setRouteCode(arrange.getRouteCode())
                .setVersionBefore(prepared.versionBefore()).setVersionAfter(prepared.versionAfter())
                .setEvidenceSnapshot(prepared.evidenceSnapshot()).setActorId(captured.actorId())
                .setActorCode(captured.actorCode()).setOperationTime(now);
        operation.setTenantId(tenant());
        operation.setCreateBy(captured.actorCode());
        operation.setCreateTime(now);
        operation.setDelFlag("0");
        require(operationMapper.insert(operation) == 1, "节点操作永久账未保存，整笔报工回滚");
    }

    @Override
    @Transactional(readOnly = true)
    public MesIdentifierBindingPreviewDTO preview(String arrangeCode, String identifier, String externalIdentifier) {
        String factory = factoryAccess.require("read");
        var arrange = uniqueArrange(arrangeCode, factory);
        List<MesTaskEntity> tasks = taskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant()).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()).last("LIMIT 2"));
        require(tasks.size() == 1, "原派工对应工单归属不唯一");
        var task = tasks.get(0);
        assertScope(arrange, task, factory);
        var policy = templates.contract(arrange).getIdentifierNodePolicy();
        require(!"NONE".equals(policy.getAction()), "普通节点请使用普通过站预览，不推造动态占用");
        var group = frozenGroup(policy, arrange, task, factory);
        MesTaskReportVO command = new MesTaskReportVO();
        command.setTransferCode(identifier);
        command.setExternalIdentifier(externalIdentifier);
        Prepared prepared = resolve(command, arrange, task, ReportModeEnum.ONE_CODE, policy, group, factory, false);
        return new MesIdentifierBindingPreviewDTO().setArrangeCode(arrange.getCode()).setAction(prepared.action())
                .setPolicySchemaVersion(policy.getPolicySchemaVersion())
                .setProduceTaskCode(MesIdentifierNodePolicyValidator.nativePolicy(policy) ? arrange.getProduceTaskCode() : null)
                .setSourceRouteNodeCode(MesIdentifierNodePolicyValidator.nativePolicy(policy) ? arrange.getSourceRouteNodeCode() : null)
                .setBindingGroupId(group.getId().toString()).setCanonicalIdentifier(prepared.binding().getCanonicalIdentifier())
                .setExternalIdentifier(prepared.binding().getExternalCode())
                .setExpectedBindingId("BIND".equals(prepared.action()) ? null : prepared.binding().getId().toString())
                .setExpectedBindingVersion("BIND".equals(prepared.action()) ? null : prepared.versionBefore())
                .setMessage("只读解析成功；未占码，正式提交仍核实时部署、原实例及全部生产门");
    }

    @Override
    @Transactional(readOnly = true)
    public MesIdentifierBindingEntity evidence(Long bindingId) {
        require(bindingId != null, "请选择精确外码占用实例");
        MesIdentifierBindingEntity binding = baseMapper.selectOne(scope(factoryAccess.require("read"))
                .eq(MesIdentifierBindingEntity::getId, bindingId));
        require(binding != null, "当前租户工厂未找到该占用实例");
        return binding;
    }

    @Override
    @Transactional(readOnly = true)
    public IPage<MesIdentifierBindingEntity> occupations(String identifier, long current, long size) {
        String factory = factoryAccess.require("read");
        String code = identifier(identifier, "外码或内部 SN");
        require(current >= 1 && current <= 1_000_000 && size >= 1 && size <= 100, "外码占用查询分页范围无效");
        // 同一码可能经历多次释放、重绑或冲销。保留每个原实例，由操作员按工单和 SN 选择。
        return baseMapper.selectPage(new Page<>(current, size), scope(factory)
                .and(query -> query.eq(MesIdentifierBindingEntity::getExternalCode, code)
                        .or().eq(MesIdentifierBindingEntity::getCanonicalIdentifier, code))
                .orderByDesc(MesIdentifierBindingEntity::getBindTime, MesIdentifierBindingEntity::getId));
    }

    @Override
    public void assertLegacyCorrectionAllowed(MesTaskReportEntity report) {
        String factory = factoryAccess.require("write");
        require(report != null && report.getIdentifierBindingId() == null
                        && report.getIdentifierBindingVersion() == null && report.getIdentifierNodeAction() == null
                        && report.getIdentifierBindingSnapshot() == null,
                "动态完整冲销须沿原操作与异岗补偿账；禁止数量部分纠正或旧裸入口只改产量留下占用");
        require(Objects.equals(report.getTenantId(), tenant()) && Objects.equals(report.getFactoryCode(), factory),
                "原报工不属于当前实际租户工厂");
        var arrange = uniqueArrange(report.getArrangeCode(), factory);
        require("NONE".equals(templates.contract(arrange).getIdentifierNodePolicy().getAction()),
                "原派工属于动态区段，旧冲销/数量纠正不能绕过生命周期账");
    }

    @Override
    @Transactional(readOnly = true)
    public void assertOrdinaryScanAllowed(String identifier) {
        assertNotExternalHistory(factoryAccess.require("read"), identifier(identifier, "现场标识"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> historyCanonicalIdentifiers(String identifier) {
        List<MesIdentifierBindingEntity> rows = baseMapper.selectList(scope(factoryAccess.require("read"))
                .eq(MesIdentifierBindingEntity::getExternalCode, identifier(identifier, "履历标识"))
                .orderByAsc(MesIdentifierBindingEntity::getId).last("LIMIT 2001"));
        require(rows.size() <= 2000, "外码历史超过完整核对上限，禁止截断后生成履历");
        return rows.stream().map(MesIdentifierBindingEntity::getCanonicalIdentifier).distinct().toList();
    }

    /**
     * 绑定预览和执行共享解析；执行才需要采集ID/版本并锁行，预览不产生任何永久事实。
     */
    private Prepared resolve(MesTaskReportVO command, MesTaskArrangeEntity arrange, MesTaskEntity task,
                             ReportModeEnum mode, MesIdentifierNodePolicyDTO policy,
                             MesIdentifierBindingGroupEntity group, String factory, boolean executing) {
        String submitted = identifier(command.getTransferCode(), "现场扫描标识");
        MesIdentifierBindingEntity binding;
        String action = policy.getAction();
        if ("BIND".equals(action)) {
            require(command.getExpectedBindingId() == null && command.getExpectedBindingVersion() == null,
                    "绑定节点创建新占用，不能指定旧实例");
            String external = external(command.getExternalIdentifier(), policy);
            mappings.resolve(arrange, task, mode, command);
            String canonical = identifier(command.getTransferCode(), "内部规范SN");
            require(!external.equals(canonical), "内部SN与外码必须明确区分，不能扫描同一码冒充双码");
            if (executing) lockIdentifiers(factory, List.of(external, canonical, submitted));
            assertNotExternalHistory(factory, submitted);
            assertNotExternalHistory(factory, canonical);
            require(baseMapper.selectCount(scope(factory).eq(MesIdentifierBindingEntity::getExternalNamespace,
                            group.getExternalNamespace()).eq(MesIdentifierBindingEntity::getExternalCode, external)
                    .eq(MesIdentifierBindingEntity::getStatus, "ACTIVE")) == 0, "该命名空间外码仍在占用，不能绑定另一件产品");
            require(baseMapper.selectCount(scope(factory).eq(MesIdentifierBindingEntity::getBindingGroupId, group.getId())
                    .eq(MesIdentifierBindingEntity::getCanonicalIdentifier, canonical)
                    .eq(MesIdentifierBindingEntity::getStatus, "ACTIVE")) == 0, "该产品在本区段已有活动占用");
            require(baseMapper.selectCount(scope(factory).eq(MesIdentifierBindingEntity::getCanonicalIdentifier, external)) == 0,
                    "此外码曾是内部SN，禁止复用为临时识别码造成双重含义");
            require(reportMapper.countCanonicalIdentifierHistory(tenant(), factory, external) == 0,
                    "此外码在原报工中属于内部SN，不能改用夹具外码，预览和最终执行同样阻断");
            assertStaticAgreement(factory, external, canonical);
            binding = new MesIdentifierBindingEntity().setFactoryCode(factory).setBindingGroupId(group.getId())
                    .setTaskId(task.getId()).setTaskCode(task.getCode()).setExternalNamespace(group.getExternalNamespace())
                    .setExternalCode(external).setCanonicalIdentifier(canonical).setIdentifierRouteVersion(1)
                    .setStatus("ACTIVE").setVersionNo(1);
            binding.setId(executing ? IdWorker.getId() : null);
            binding.setTenantId(tenant());
        } else {
            require(command.getExternalIdentifier() == null, "使用/释放节点只提交一个现场扫描值，不重新绑定第二码");
            if (executing) {
                Long expectedId = id(command.getExpectedBindingId());
                require(command.getExpectedBindingVersion() != null && command.getExpectedBindingVersion() > 0
                                && command.getExpectedBindingVersion() % 2 == 1,
                        "缺少有效采集生命周期版本，请重新只读预览");
                binding = baseMapper.selectOne(scope(factory).eq(MesIdentifierBindingEntity::getId, expectedId));
                require(binding != null, "原采集实例不属于当前租户工厂");
                lockIdentifiers(factory, List.of(binding.getExternalCode(), binding.getCanonicalIdentifier(), submitted));
                binding = baseMapper.selectOne(scope(factory).eq(MesIdentifierBindingEntity::getId, expectedId).last("FOR UPDATE"));
            } else {
                List<MesIdentifierBindingEntity> matches = baseMapper.selectList(scope(factory)
                        .eq(MesIdentifierBindingEntity::getBindingGroupId, group.getId())
                        .eq(MesIdentifierBindingEntity::getStatus, "ACTIVE")
                        .and(q -> q.eq(MesIdentifierBindingEntity::getExternalCode, submitted)
                                .or().eq(MesIdentifierBindingEntity::getCanonicalIdentifier, submitted)).last("LIMIT 2"));
                require(matches.size() == 1, "当前区段未唯一找到活动占用；已释放外码不能回退为普通内部SN");
                binding = matches.get(0);
            }
            require(binding != null && "ACTIVE".equals(binding.getStatus()) && binding.getVersionNo() != null
                    && binding.getVersionNo() > 0 && binding.getVersionNo() % 2 == 1
                    && binding.getVersionNo() < Integer.MAX_VALUE
                    && (!executing || Objects.equals(binding.getVersionNo(), command.getExpectedBindingVersion()))
                    && Objects.equals(binding.getBindingGroupId(), group.getId()) && Objects.equals(binding.getTaskId(), task.getId())
                    && Integer.valueOf(1).equals(binding.getIdentifierRouteVersion()), "原占用已释放、复用或不属于此永久区段，禁止改查新实例");
            require(submitted.equals(binding.getExternalCode()) || ("EITHER".equals(policy.getInputMode())
                    && submitted.equals(binding.getCanonicalIdentifier())), "本节点输入不符合冻结的外码/内部码规则");
            assertStaticAgreement(factory, binding.getExternalCode(), binding.getCanonicalIdentifier());
            // 使用活动关系核定内部SN，禁止再次把外码送入静态解析当作另一个产品。
            command.setIdentifierMappingId(null);
            command.setIdentifierType("DYNAMIC_EXTERNAL");
            command.setIdentifierResolutionSnapshot(null);
            command.setTransferCode(binding.getCanonicalIdentifier());
            command.setSubmittedIdentifier(submitted);
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        boolean nativeNodes = MesIdentifierNodePolicyValidator.nativePolicy(policy);
        evidence.put("version", nativeNodes ? "MES_NODE_BINDING_NODE_V2" : "MES_NODE_BINDING_V1");
        evidence.put("bindingId", binding.getId() == null ? null : binding.getId().toString());
        evidence.put("bindingGroupId", group.getId().toString());
        evidence.put("bindingGroupVersion", group.getVersionNo());
        evidence.put("definitionFingerprint", group.getDefinitionFingerprint());
        evidence.put("graphFingerprint", group.getGraphFingerprint());
        evidence.put("sourceRouteFingerprint", group.getSourceRouteFingerprint());
        evidence.put("arrangeCode", arrange.getCode());
        evidence.put("routeCode", arrange.getRouteCode());
        evidence.put("action", action);
        evidence.put("submittedIdentifier", submitted);
        evidence.put("externalNamespace", binding.getExternalNamespace());
        evidence.put("externalIdentifier", binding.getExternalCode());
        evidence.put("canonicalIdentifier", binding.getCanonicalIdentifier());
        evidence.put("identifierRouteVersion", binding.getIdentifierRouteVersion());
        int before = "BIND".equals(action) ? 0 : binding.getVersionNo();
        int after = "BIND".equals(action) ? 1 : "RELEASE".equals(action) ? before + 1 : before;
        evidence.put("versionBefore", before);
        evidence.put("versionAfter", after);
        if (nativeNodes) {
            evidence.put("policySchemaVersion", policy.getPolicySchemaVersion());
            evidence.put("routeExecutionSchemaVersion", arrange.getRouteExecutionSchemaVersion());
            evidence.put("produceTaskCode", arrange.getProduceTaskCode());
            evidence.put("sourceRouteNodeCode", arrange.getSourceRouteNodeCode());
        }
        return new Prepared(action, binding, JSONUtil.toJsonStr(evidence), before, after);
    }

    /**
     * 指纹还要逐项核定公开规则和具体节点，不把指纹值当可改变公开定义的令牌。
     */
    private MesIdentifierBindingGroupEntity frozenGroup(MesIdentifierNodePolicyDTO policy,
                                                        MesTaskArrangeEntity arrange, MesTaskEntity task, String factory) {
        require(Set.of("BIND", "USE", "RELEASE").contains(policy.getAction()), "冻结节点动作未知");
        boolean nativeNodes = MesIdentifierNodePolicyValidator.nativePolicy(policy);
        var group = groupMapper.selectOne(new LambdaQueryWrapper<MesIdentifierBindingGroupEntity>()
                .eq(MesIdentifierBindingGroupEntity::getTenantId, tenant())
                .eq(MesIdentifierBindingGroupEntity::getFactoryCode, factory)
                .eq(MesIdentifierBindingGroupEntity::getId, id(policy.getBindingGroupId())));
        require(group != null && "FROZEN".equals(group.getStatus()) && "ONE_CODE".equals(group.getReportMode())
                        && Objects.equals(group.getTaskId(), task.getId()) && Objects.equals(group.getTaskCode(), task.getCode())
                        && Objects.equals(group.getPolicySchemaVersion(), policy.getPolicySchemaVersion())
                        && Objects.equals(group.getProcessRouteCode(), policy.getProcessRouteCode())
                        && Objects.equals(group.getBindRouteNodeCode(), policy.getBindRouteNodeCode())
                        && Objects.equals(group.getReleaseRouteNodeCode(), policy.getReleaseRouteNodeCode())
                        && (!nativeNodes || ("NODE_EXECUTION_V1".equals(arrange.getRouteExecutionSchemaVersion())
                        && Objects.equals(task.getProcessRouteCode(), group.getProcessRouteCode())))
                        && Objects.equals(group.getVersionNo(), policy.getBindingGroupVersion())
                        && Objects.equals(group.getDefinitionFingerprint(), policy.getDefinitionFingerprint())
                        && Objects.equals(group.getGraphFingerprint(), policy.getGraphFingerprint())
                        && Objects.equals(group.getSourceRouteFingerprint(), policy.getSourceRouteFingerprint()),
                "具体派工未冻结此实际工单的原永久区段，不能读新模板补造");
        JsonNode original = json(group.getDefinitionSnapshot());
        JsonNode actual = json(JSONUtil.toJsonStr(policy));
        original.fields().forEachRemaining(field -> require(field.getValue().equals(actual.path(field.getKey()))
                        || (field.getValue().isNull() && actual.path(field.getKey()).isMissingNode()),
                "派工换码公开规则与永久组不一致"));
        require(json(group.getRegionSnapshot()).equals(actual.path("frozenNodes")), "派工具体节点集合已改变");
        boolean actualNode = policy.getFrozenNodes().stream().anyMatch(node ->
                Objects.equals(node.getRouteCode(), arrange.getRouteCode())
                        && Objects.equals(node.getProduceTaskCode(), arrange.getProduceTaskCode())
                        && (!nativeNodes || Objects.equals(node.getSourceRouteNodeCode(), arrange.getSourceRouteNodeCode())));
        String role = MesIdentifierNodePolicyValidator.role(policy, arrange);
        require(actualNode && Objects.equals(role, policy.getAction()) && "ONE_CODE".equals(arrange.getReportMode()),
                "具体派工工序/对象模式与永久角色不一致");
        return group;
    }

    /**
     * 绑定前复核当前同批全部后续节点仍可执行，不能拿旧完工/关闭节点冒充释放出口。
     * 班次批号可在旧批结束后复用；只排除终态历史，暂停等非终态仍参与唯一性及出口校验。
     * 同工单业务锁由上层持有；只读后续派工，不反向锁其他派工行。
     */
    private void assertRunnableGroup(MesIdentifierNodePolicyDTO policy, MesTaskArrangeEntity arrange,
                                     MesTaskEntity task, String factory) {
        List<MesTaskArrangeEntity> batch = arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant()).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getTaskCode, task.getCode()).eq(MesTaskArrangeEntity::getShiftCode, arrange.getShiftCode())
                .notIn(MesTaskArrangeEntity::getStatus, "CLOSE", "FINISH")
                .last("LIMIT 5001"));
        require(batch.size() <= 5000, "当前班次非终态派工超过完整核对上限，不能截断后绑定");
        for (var node : policy.getFrozenNodes()) {
            var matches = batch.stream().filter(item -> Objects.equals(item.getRouteCode(), node.getRouteCode())
                    && Objects.equals(item.getProduceTaskCode(), node.getProduceTaskCode())
                    && Objects.equals(item.getBatchNo(), arrange.getBatchNo())).toList();
            require(matches.size() == 1, "原动态区段工序" + node.getRouteCode() + "没有唯一同批派工，禁止先占码");
            var member = matches.get(0);
            var contract = templates.contract(member);
            var memberPolicy = contract.getIdentifierNodePolicy();
            require(Objects.equals(memberPolicy.getBindingGroupId(), policy.getBindingGroupId()), "原同批后续工序未冻结同一换码区段");
            frozenGroup(memberPolicy, member, task, factory);
            require(Set.of("UN_RECEIVE", "RECEIVE", "PRODUCT").contains(Objects.toString(member.getStatus(), ""))
                            && contract.getRemainingQuantity() != null && contract.getRemainingQuantity().compareTo(BigDecimal.ONE) >= 0
                            && (Boolean.TRUE.equals(contract.getPartialReportAllowed())
                            || contract.getRemainingQuantity().compareTo(BigDecimal.ONE) == 0),
                    "原动态区段工序" + node.getRouteCode() + "已暂停/关闭/完工、数量不足或禁止逐件分批，禁止先绑定");
        }
    }

    /**
     * 同厂存在不同内部含义的静态外码时明确拒绝，不以优先级静默选一个产品。
     */
    private void assertStaticAgreement(String factory, String external, String canonical) {
        List<MesIdentifierMappingEntity> mappings = mappingMapper.selectList(new LambdaQueryWrapper<MesIdentifierMappingEntity>()
                .eq(MesIdentifierMappingEntity::getTenantId, tenant()).eq(MesIdentifierMappingEntity::getFactoryCode, factory)
                .eq(MesIdentifierMappingEntity::getAliasCode, external)
                .in(MesIdentifierMappingEntity::getStatus, List.of("PUBLISHED", "RETIRED")).last("LIMIT 2001"));
        require(mappings.size() <= 2000 && mappings.stream().allMatch(row -> canonical.equals(row.getCanonicalCode())),
                "外码静态映射与本次活动产品含义冲突，请核查映射治理后再执行");
    }

    private void assertNotExternalHistory(String factory, String scanned) {
        require(baseMapper.selectCount(scope(factory).eq(MesIdentifierBindingEntity::getExternalCode, scanned)) == 0,
                "此外码存在动态占用历史，只能在原区段按具体活动实例使用；释放后不能回落为普通SN或静态别名");
    }

    private void lockIdentifiers(String factory, List<String> identifiers) {
        identifiers.stream().map(value -> MesIdentifierBindingLock.key(tenant(), factory, value)).distinct().sorted()
                .forEach(key -> require(Integer.valueOf(1).equals(baseMapper.lockIdentity(key)),
                        "未取得现场标识业务锁"));
    }

    private MesTaskArrangeEntity uniqueArrange(String code, String factory) {
        require(code != null && !code.isBlank(), "请选择精确派工任务");
        List<MesTaskArrangeEntity> rows = arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant()).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, code).last("LIMIT 2"));
        require(rows.size() == 1, "当前实际租户工厂未唯一找到该派工");
        return rows.get(0);
    }

    private void assertScope(MesTaskArrangeEntity arrange, MesTaskEntity task, String factory) {
        require(arrange != null && task != null && Objects.equals(arrange.getTenantId(), tenant())
                        && Objects.equals(task.getTenantId(), tenant()) && Objects.equals(arrange.getFactoryCode(), factory)
                        && Objects.equals(task.getFactoryCode(), factory) && Objects.equals(arrange.getTaskCode(), task.getCode()),
                "动态节点与真实工单/派工不属于当前实际租户工厂");
    }

    private void applyFacts(MesTaskReportVO command, Prepared prepared) {
        command.setIdentifierBindingId(prepared.binding().getId());
        command.setIdentifierBindingVersion(prepared.versionBefore() == 0 ? 1 : prepared.versionBefore());
        command.setIdentifierNodeAction(prepared.action());
        command.setIdentifierBindingSnapshot(prepared.evidenceSnapshot());
    }

    private void clearFacts(MesTaskReportVO command) {
        command.setIdentifierBindingId(null);
        command.setIdentifierBindingVersion(null);
        command.setIdentifierNodeAction(null);
        command.setIdentifierBindingSnapshot(null);
    }

    private String external(String input, MesIdentifierNodePolicyDTO policy) {
        String value = identifier(input, "绑定外码");
        int characters = value.codePointCount(0, value.length());
        require(policy.getExternalCodeMinLength() != null && policy.getExternalCodeMaxLength() != null
                        && characters >= policy.getExternalCodeMinLength() && characters <= policy.getExternalCodeMaxLength()
                        && (policy.getExternalCodePrefix() == null || value.startsWith(policy.getExternalCodePrefix())),
                "外码不符合原冻结的字面前缀/长度规则");
        return value;
    }

    private String identifier(String input, String label) {
        require(input != null && !input.isBlank(), label + "不能为空");
        String value = input.trim();
        // 与PostgreSQL varchar/length一致按Unicode码点计数，禁止未配对代理字符被UTF8替换后串锁。
        require(value.codePointCount(0, value.length()) <= 200 && value.chars().noneMatch(Character::isISOControl)
                        && value.codePoints().noneMatch(code -> code >= 0xD800 && code <= 0xDFFF),
                label + "须为不超过200个Unicode字符的完整无控制字符文本");
        return value;
    }

    private Long id(String input) {
        require(input != null && input.matches("[1-9][0-9]{0,18}"), "具体实例/永久组ID必须为完整正整数文本");
        try {
            return Long.valueOf(input);
        } catch (NumberFormatException exception) {
            throw new CheckedException("实例/永久组ID超出Long范围");
        }
    }

    private JsonNode json(String value) {
        try {
            return SNAPSHOT_JSON.readTree(value);
        } catch (IOException | IllegalArgumentException exception) {
            throw new CheckedException("永久换码依据JSON损坏，禁止猜测执行");
        }
    }

    private Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private LambdaQueryWrapper<MesIdentifierBindingEntity> scope(String factory) {
        return new LambdaQueryWrapper<MesIdentifierBindingEntity>().eq(MesIdentifierBindingEntity::getTenantId, tenant())
                .eq(MesIdentifierBindingEntity::getFactoryCode, factory);
    }

    private void audit(MesIdentifierBindingEntity row, String actor, LocalDateTime time) {
        row.setCreateBy(actor);
        row.setCreateTime(time);
        row.setDelFlag("0");
    }

    /**
     * 领域对象不能由通用CRUD、链式操作或独立事务修改占用。
     */
    @Override
    public MesIdentifierBindingMapper getBaseMapper() {
        throw new CheckedException("活动外码禁止通用CRUD，请使用统一报工与只读预览");
    }

    @Override
    public boolean saveBatch(Collection<MesIdentifierBindingEntity> rows, int size) {
        throw new CheckedException("活动外码禁止批量独立占用");
    }

    @Override
    public boolean saveOrUpdateBatch(Collection<MesIdentifierBindingEntity> rows, int size) {
        throw new CheckedException("活动外码禁止批量覆盖");
    }

    @Override
    public boolean updateBatchById(Collection<MesIdentifierBindingEntity> rows, int size) {
        throw new CheckedException("活动外码禁止批量迁移");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
