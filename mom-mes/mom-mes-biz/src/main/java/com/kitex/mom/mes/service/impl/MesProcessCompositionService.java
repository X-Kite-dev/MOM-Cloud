package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesProcessCompositionDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesProcessCompositionMapper;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesTaskArrangeService;
import com.kitex.mom.mes.service.MesTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 组合配置的事务边界；生产事实仍由现有工单、工序和派工服务持有。
 */
@Service
@RequiredArgsConstructor
public class MesProcessCompositionService {
    private static final String COMMAND_KIND = "PROCESS_COMPOSITION";

    private final MesActualFactoryAccessService factoryAccess;
    private final MesProcessCompositionMapper mapper;
    private final MesTaskService tasks;
    private final MesProduceTaskService operations;
    private final MesTaskArrangeService arrangements;
    private final MesIdentifierBindingGroupService taskLocks;
    private final MesExecutionCommandCancellationService cancellations;

    public Map<String, Object> capabilities() {
        String factory = factoryAccess.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        require(tenant != null && tenant > 0, "过程能力缺少实际租户");
        return Map.of("contractVersion", "PROCESS_COMPOSITION_CAPABILITIES_V1",
                "tenantId", tenant.toString(), "factoryCode", factory, "schema", MesProcessCompositionSchema.schema());
    }

    @Transactional(rollbackFor = Exception.class)
    public MesProcessCompositionDTO.Template saveDraft(MesProcessCompositionDTO.Draft draft) {
        String factory = factoryAccess.require("write");
        Long tenant = TenantContextHolder.getTenantId();
        require(draft != null && draft.getCode() != null && draft.getCode().matches("[A-Za-z0-9_-]{1,60}")
                        && draft.getName() != null && !draft.getName().isBlank() && draft.getName().length() <= 100,
                "请填写有效过程编码与1至100字名称");
        MesProcessCompositionModel.validate(draft.getDefinition());
        mapper.lock(tenant, factory, "TEMPLATE:" + draft.getCode());
        var actor = SecurityUtils.getUser();
        MesProcessCompositionTemplateEntity row;
        if (draft.getId() == null) {
            row = new MesProcessCompositionTemplateEntity();
            row.setId(IdWorker.getId());
            row.setTenantId(tenant);
            row.setFactoryCode(factory);
            row.setCode(draft.getCode());
            row.setVersionNo(mapper.maxVersion(tenant, factory, draft.getCode()) + 1);
            row.setRevisionNo(0L);
            row.setStatus("DRAFT");
            row.setCreateBy(actor.getUsername());
            row.setCreateTime(LocalDateTime.now());
        } else {
            row = mapper.template(tenant, factory, id(draft.getId()));
            require(row != null && row.getCode().equals(draft.getCode()) && "DRAFT".equals(row.getStatus())
                    && Objects.equals(row.getRevisionNo(), draft.getRevisionNo()), "过程草稿已变化或版本已发布，请重新加载");
        }
        row.setName(draft.getName().trim());
        row.setDefinitionJson(JSONUtil.toJsonStr(draft.getDefinition()));
        row.setUpdateBy(actor.getUsername());
        row.setUpdateTime(LocalDateTime.now());
        require((draft.getId() == null ? mapper.insertTemplate(row) : mapper.updateDraft(row)) == 1,
                "过程草稿保存失败或并发修订，请重新加载");
        return templateView(mapper.template(tenant, factory, row.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public MesProcessCompositionDTO.Template publish(String templateId, Long expectedRevisionNo) {
        String factory = factoryAccess.require("write");
        Long tenant = TenantContextHolder.getTenantId();
        var row = mapper.template(tenant, factory, id(templateId));
        require(row != null, "当前组织不存在该过程版本");
        mapper.lock(tenant, factory, "TEMPLATE:" + row.getCode());
        row = mapper.template(tenant, factory, row.getId());
        require("DRAFT".equals(row.getStatus()) && Objects.equals(row.getRevisionNo(), expectedRevisionNo),
                "过程草稿已变化或已发布，请重新加载");
        MesProcessCompositionModel.validate(definition(row.getDefinitionJson()));
        row.setStatus("PUBLISHED");
        row.setUpdateBy(SecurityUtils.getUser().getUsername());
        row.setUpdateTime(LocalDateTime.now());
        require(mapper.updateDraft(row) == 1, "过程版本发布失败或发生并发修订");
        return templateView(mapper.template(tenant, factory, row.getId()));
    }

    public List<MesProcessCompositionDTO.Template> templates() {
        String factory = factoryAccess.require("read");
        return mapper.templates(TenantContextHolder.getTenantId(), factory).stream().map(this::templateView).toList();
    }

    private MesProcessCompositionDTO.Template templateView(MesProcessCompositionTemplateEntity row) {
        var result = new MesProcessCompositionDTO.Template();
        result.setId(row.getId().toString());
        result.setCode(row.getCode());
        result.setName(row.getName());
        result.setVersionNo(row.getVersionNo());
        result.setRevisionNo(row.getRevisionNo());
        result.setStatus(row.getStatus());
        result.setDefinition(definition(row.getDefinitionJson()));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public MesProcessCompositionDTO.View run(MesProcessCompositionDTO.Run command) {
        String factory = factoryAccess.require("write");
        Long tenant = TenantContextHolder.getTenantId();
        var actor = SecurityUtils.getUser();
        require(command != null && command.getRequestId() != null
                && command.getRequestId().matches("[A-Za-z0-9_.:-]{8,100}"), "请提供8至100位稳定过程运行原号");
        require(command.getDispatches() != null && !command.getDispatches().isEmpty()
                && command.getDispatches().size() <= 20, "请选择1至20个已下达正式工单");
        String fingerprint = commandFingerprint(command, tenant, factory, actor.getId());
        // 原号先锁后查，避免并发重放在MyBatis一级缓存中继续看到不存在。
        mapper.lock(tenant, factory, "REQUEST:" + command.getRequestId());
        var replay = mapper.request(tenant, factory, command.getRequestId());
        if (replay != null) {
            require(Objects.equals(replay.getActorId(), actor.getId())
                    && fingerprint.equals(replay.getRequestFingerprint()), "该过程原号已用于另一正文或操作人");
            return view(replay);
        }
        cancellations.assertActive(COMMAND_KIND, command.getRequestId(), fingerprint);
        var template = mapper.template(tenant, factory, id(command.getTemplateId()));
        require(template != null && "PUBLISHED".equals(template.getStatus())
                        && Objects.equals(template.getRevisionNo(), command.getExpectedRevisionNo()),
                "请选择当前组织精确已发布过程版本并刷新修订");
        var definition = definition(template.getDefinitionJson());
        MesProcessCompositionModel.validate(definition);
        var requestedCodes = new HashSet<String>();
        for (var dispatch : command.getDispatches()) {
            require(dispatch != null && dispatch.getCode() != null && !dispatch.getCode().isBlank()
                    && dispatch.getShiftCode() != null && !dispatch.getShiftCode().isBlank()
                    && requestedCodes.add(dispatch.getCode()), "工单或班次缺失，或同批重复工单");
        }
        var codes = requestedCodes.stream().sorted().toList();
        // 与既有派工、报工使用相同工单锁，多个工单始终按编码排序锁定。
        for (String code : codes) {
            taskLocks.lockDispatchTask(code);
        }
        var sourceTasks = sourceTasks(codes, tenant, factory);
        require(sourceTasks.size() == codes.size(), "部分工单不存在或不属于当前实际组织");
        var sourceOperations = sourceOperations(codes, tenant, factory);
        for (var task : sourceTasks) {
            require("ISSUE".equals(task.getStatus()) && task.getFormalPlanContractId() != null
                    && task.getEngineeringReleaseId() != null && task.getNoticeNum() != null
                    && task.getNoticeNum().signum() > 0, "过程运行仅接收已有正式计划及工程依据的已下达未派工工单");
            var members = sourceOperations.stream().filter(row -> task.getCode().equals(row.getTaskCode())).toList();
            com.kitex.mom.mes.util.MesFrozenExecutionGraph.of(members);
            var dispatch = command.getDispatches().stream().filter(row -> task.getCode().equals(row.getCode())).findFirst().orElseThrow();
            require(dispatch.getTaskArrangeList() != null && dispatch.getTaskArrangeList().size() == members.size(),
                    "首次过程运行须完整派出原冻结工序，不自动补造任务");
            var selected = dispatch.getTaskArrangeList().stream().map(row -> {
                require(row != null && row.getWorkerCode() != null && !row.getWorkerCode().isBlank()
                        && row.getWorksiteCodes() != null && !row.getWorksiteCodes().isBlank(), "请为全部工序选择实际人员及工位");
                return row.getProduceTaskCode();
            }).collect(Collectors.toSet());
            require(selected.equals(members.stream().map(MesProduceTaskEntity::getCode).collect(Collectors.toSet())),
                    "派工工序必须精确覆盖本工单冻结图，不能跨工单或重复");
            for (var member : members) {
                require(member.getNoticeNum() != null && member.getNoticeNum().compareTo(task.getNoticeNum()) == 0,
                        "原工单与工序数量不一致，请先核定，不能由组合层改写");
            }
        }
        if (definition.getLevels().get(0).getBindings().contains("ORDER")) {
            String order = sourceTasks.get(0).getProductionOrder();
            require(order != null && !order.isBlank()
                            && sourceTasks.stream().allMatch(task -> order.equals(task.getProductionOrder())),
                    "四级过程须来自同一真实生产订单，缺少或混合来源不能运行");
        }
        require(sourceArrangements(codes, tenant, factory).isEmpty(), "工单已有派工，请查看既有过程，不得再次启动");
        for (String code : codes) {
            var dispatch = command.getDispatches().stream().filter(row -> code.equals(row.getCode())).findFirst().orElseThrow();
            arrangements.arrange(dispatch);
        }
        var actualArrangements = sourceArrangements(codes, tenant, factory);
        require(actualArrangements.stream().allMatch(row -> row.getExecutionPolicySnapshot() != null
                && !row.getExecutionPolicySnapshot().isBlank()), "实际派工缺少执行策略冻结，组合事务不能提交");
        var nodes = MesProcessCompositionModel.bind(definition, sourceTasks, sourceOperations, actualArrangements);
        var row = new MesProcessCompositionRunEntity();
        row.setId(IdWorker.getId());
        row.setTenantId(tenant);
        row.setFactoryCode(factory);
        row.setRequestId(command.getRequestId());
        row.setRequestFingerprint(fingerprint);
        row.setActorId(actor.getId());
        row.setTemplateId(template.getId());
        row.setTemplateVersion(template.getVersionNo());
        row.setTemplateName(template.getName());
        row.setDefinitionSnapshot(template.getDefinitionJson());
        row.setNodesJson(JSONUtil.toJsonStr(nodes));
        row.setTaskCodesJson(JSONUtil.toJsonStr(codes));
        row.setCreateBy(actor.getUsername());
        row.setCreateTime(LocalDateTime.now());
        require(mapper.insertRun(row) == 1, "过程绑定保存失败，派工一起回滚");
        return view(row);
    }

    public MesProcessCompositionDTO.View runView(String runId) {
        String factory = factoryAccess.require("read");
        var row = mapper.run(TenantContextHolder.getTenantId(), factory, id(runId));
        require(row != null, "当前组织不存在该过程运行");
        return view(row);
    }

    public List<MesProcessCompositionDTO.View> runs() {
        String factory = factoryAccess.require("read");
        return mapper.runs(TenantContextHolder.getTenantId(), factory).stream().map(this::view).toList();
    }

    public MesProcessCompositionDTO.Receipt receipt(String requestId) {
        String factory = factoryAccess.require("read");
        require(requestId != null && requestId.matches("[A-Za-z0-9_.:-]{8,100}"), "过程原号无效");
        var row = mapper.request(TenantContextHolder.getTenantId(), factory, requestId);
        var result = new MesProcessCompositionDTO.Receipt();
        result.setStatus(row == null
                ? cancellations.cancelled(COMMAND_KIND, requestId) ? "CANCELLED" : "ABSENT"
                : "APPLIED");
        if (row != null) {
            require(Objects.equals(row.getActorId(), SecurityUtils.getUser().getId()), "请回原操作人账号核对过程回执");
            result.setRun(view(row));
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public MesProcessCompositionDTO.Receipt cancelOriginal(MesProcessCompositionDTO.Run command) {
        String factory = factoryAccess.require("write");
        Long tenant = TenantContextHolder.getTenantId();
        var actor = SecurityUtils.getUser();
        String fingerprint = commandFingerprint(command, tenant, factory, actor.getId());
        mapper.lock(tenant, factory, "REQUEST:" + command.getRequestId());
        var original = mapper.request(tenant, factory, command.getRequestId());
        var receipt = new MesProcessCompositionDTO.Receipt();
        if (original != null) {
            require(Objects.equals(original.getActorId(), actor.getId())
                    && fingerprint.equals(original.getRequestFingerprint()), "该过程原号已用于另一正文或操作人");
            receipt.setStatus("APPLIED");
            receipt.setRun(view(original));
        } else {
            // 与执行共用原号锁；墓碑提交后，迟到的原执行不能再进入真实派工。
            cancellations.cancel(COMMAND_KIND, command.getRequestId(), fingerprint);
            receipt.setStatus("CANCELLED");
        }
        return receipt;
    }

    private String commandFingerprint(MesProcessCompositionDTO.Run command, Long tenant, String factory, Long actorId) {
        require(command != null && command.getRequestId() != null
                && command.getRequestId().matches("[A-Za-z0-9_.:-]{8,100}"), "请提供8至100位稳定过程运行原号");
        return sha(JSONUtil.toJsonStr(List.of("MES_PROCESS_COMPOSITION_V1", tenant, factory, actorId.toString(), command)));
    }

    private MesProcessCompositionDTO.View view(MesProcessCompositionRunEntity row) {
        var result = new MesProcessCompositionDTO.View();
        result.setId(row.getId().toString());
        result.setRequestId(row.getRequestId());
        result.setTemplateId(row.getTemplateId().toString());
        result.setTemplateVersion(row.getTemplateVersion());
        result.setTemplateName(row.getTemplateName());
        result.setDefinition(definition(row.getDefinitionSnapshot()));
        result.setNodes(JSONUtil.toList(row.getNodesJson(), MesProcessCompositionDTO.Node.class));
        var codes = JSONUtil.toList(row.getTaskCodesJson(), String.class);
        result.setProgress(MesProcessCompositionModel.progress(sourceTasks(codes, row.getTenantId(), row.getFactoryCode()),
                sourceOperations(codes, row.getTenantId(), row.getFactoryCode())));
        result.setQuantityBasis("按原工单唯一末工序完成量统计；各工序及层级不重复相加，异物料不混加；未提供完成量表示依据缺失");
        return result;
    }

    private List<MesTaskEntity> sourceTasks(List<String> codes, Long tenant, String factory) {
        var rows = tasks.list(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getTenantId, tenant)
                .eq(MesTaskEntity::getFactoryCode, factory).in(MesTaskEntity::getCode, codes));
        require(rows.stream().allMatch(row -> Objects.equals(tenant, row.getTenantId())
                && factory.equals(row.getFactoryCode()) && codes.contains(row.getCode()))
                && rows.size() == codes.size()
                && rows.stream().map(MesTaskEntity::getCode).distinct().count() == rows.size(), "工单来源范围或唯一性异常");
        return rows;
    }

    private List<MesProduceTaskEntity> sourceOperations(List<String> codes, Long tenant, String factory) {
        var rows = operations.list(new LambdaQueryWrapper<MesProduceTaskEntity>().eq(MesProduceTaskEntity::getTenantId, tenant)
                .eq(MesProduceTaskEntity::getFactoryCode, factory).in(MesProduceTaskEntity::getTaskCode, codes));
        require(rows.stream().allMatch(row -> Objects.equals(tenant, row.getTenantId())
                && factory.equals(row.getFactoryCode()) && codes.contains(row.getTaskCode())), "工序来源范围异常");
        return rows;
    }

    private List<MesTaskArrangeEntity> sourceArrangements(List<String> codes, Long tenant, String factory) {
        var rows = arrangements.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .in(MesTaskArrangeEntity::getTaskCode, codes));
        require(rows.stream().allMatch(row -> Objects.equals(tenant, row.getTenantId())
                && factory.equals(row.getFactoryCode()) && codes.contains(row.getTaskCode())), "派工来源范围异常");
        return rows;
    }

    private MesProcessCompositionDTO.Definition definition(String json) {
        try {
            return JSONUtil.toBean(json, MesProcessCompositionDTO.Definition.class);
        } catch (RuntimeException failure) {
            throw new CheckedException("过程定义快照损坏，请核定原版本");
        }
    }

    private Long id(String text) {
        require(text != null && text.matches("[1-9][0-9]{0,18}"), "请选择精确过程记录");
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException failure) {
            throw new CheckedException("过程记录ID超出范围");
        }
    }

    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
