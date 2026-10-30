package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesReworkMaterialCommand;
import com.kitex.mom.mes.dto.ProcessProcedureDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.MesMaterialListStatusEnum;
import com.kitex.mom.mes.enums.MesMaterialPickStatusEnum;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesReworkIdentifierUsageService;
import com.kitex.mom.mes.support.MesReworkTimingPolicy;
import com.kitex.mom.mes.util.MesMaterialSubstituteResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * 返工执行方案：只补修复所需物料，并由双人复核冻结BOM、节点执行依据和跨工单时间口径。
 */
@Service
@RequiredArgsConstructor
public class MesReworkMaterialPlanService {
    private final MesActualFactoryAccessService access;
    private final MesReworkMaterialPlanMapper plans;
    private final MesReworkMaterialOperationMapper operations;
    private final MesTaskMapper tasks;
    private final MesProduceTaskMapper produceTasks;
    private final MesMaterialListMapper materialLists;
    private final MesWarehousePickingBillMapper pickingBills;
    private final RemoteMaterialService materials;
    private final MesMaterialListDetailMapper materialDetails;
    private final MesReworkIdentifierUsageService reworkIdentifiers;
    private final MesReworkEngineeringOverrideService reworkEngineering;
    private final org.mybatis.spring.SqlSessionTemplate sqlSession;

    public Map<String, Object> context(Long taskId, long page) {
        String factory = access.require("read");
        require(page > 0 && page <= 100000, "页码无效");
        var t = task(taskId, factory, false);
        require(t != null && "REWORK".equals(t.getResourceType()), "当前工厂返工工单不存在");
        var original = original(t);
        var result = new LinkedHashMap<String, Object>();
        result.put("task", t);
        result.put("candidates", MesMaterialSubstituteResolver.flattenBom(original[0]));
        result.put("originalRoute", original[1]);
        result.put("plans", plans.selectPage(new Page<>(page, 30), scope(factory)
                .select(MesReworkMaterialPlanEntity.class, f -> !Set.of("preparation_snapshot", "source_snapshot", "original_bom_snapshot", "original_route_snapshot").contains(f.getColumn()))
                .eq(MesReworkMaterialPlanEntity::getTaskId, taskId).orderByDesc(MesReworkMaterialPlanEntity::getId)));
        result.put("approvedMaterials", MesMaterialSubstituteResolver.flattenBom(t.getBomSnapshot()));
        result.put("approvedTimingPolicy", t.getReworkTimingPolicySnapshot());
        var identifierContext = reworkIdentifiers.context(t);
        result.put("sourceIdentifierRequired", identifierContext.required());
        result.put("identifierCandidates", identifierContext.candidates());
        result.put("approvedIdentifierScope", identifierContext.approvedScopeSnapshot());
        result.put("approvedIdentifierScopeFingerprint", identifierContext.approvedScopeFingerprint());
        result.put("engineeringOverrideCandidates", t.getReworkMaterialPlanId() == null
                ? reworkEngineering.candidates(t) : List.of());
        result.put("approvedEngineeringScope", t.getReworkEngineeringScopeSnapshot());
        result.put("approvedEngineeringScopeFingerprint", t.getReworkEngineeringScopeFingerprint());
        result.put("supplementBoundary", "仅已批准、尚无任何报工且未消耗投料的返工；追加不减量，不改历史领退/投料。已有报工须后续消耗版本切换。");
        return result;
    }

    public Map<String, Object> evidence(Long planId) {
        String factory = access.require("read");
        var p = plans.selectOne(scope(factory).eq(MesReworkMaterialPlanEntity::getId, planId));
        require(p != null, "当前工厂返工方案不存在");
        return Map.of("plan", p, "operations", operations.selectList(opScope(factory).eq(MesReworkMaterialOperationEntity::getPlanId, p.getId()).orderByAsc(MesReworkMaterialOperationEntity::getId)));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> execute(MesReworkMaterialCommand c) {
        String factory = access.require("write");
        var actor = SecurityUtils.getUser();
        require(c != null && c.getTaskId() != null && c.getRequestId() != null && c.getRequestId().matches("[A-Za-z0-9_-]{16,64}"), "须提供返工工单和16至64位操作请求号");
        require(Set.of("PROPOSE", "SUPPLEMENT", "APPROVE", "REJECT", "WITHDRAW").contains(Objects.toString(c.getOperation(), "")), "不支持的返工执行方案操作");
        boolean proposing = Set.of("PROPOSE", "SUPPLEMENT").contains(c.getOperation());
        boolean supplement = "SUPPLEMENT".equals(c.getOperation());
        require(StrUtil.isNotBlank(c.getReason()) && c.getReason().length() <= 500, "须填写500字以内实际理由");
        c.setReason(c.getReason().trim());
        if (proposing) {
            require(c.getPlanId() == null && c.getItems() != null && c.getItems().size() <= 100, "提案须明确物料行（最多100项），不能覆盖已有方案");
            Set<String> codes = new HashSet<>();
            for (var item : c.getItems()) {
                require(item != null && StrUtil.isNotBlank(item.getMaterialCode()) && codes.add(item.getMaterialCode()), "补料编码不能为空或重复");
                quantity(item.getQuantityPerUnit());
                item.setQuantityPerUnit(item.getQuantityPerUnit().stripTrailingZeros());
            }
            c.getItems().sort(Comparator.comparing(MesReworkMaterialCommand.Item::getMaterialCode));
            require(!c.getItems().isEmpty() || Boolean.TRUE.equals(c.getNoMaterialConfirmed()), "不补料须明确确认，不能因空列表误判无需物料");
            require(c.getItems().isEmpty() || !Boolean.TRUE.equals(c.getNoMaterialConfirmed()), "有补料行不能同时声明无需补料");
            if (supplement) {
                require(!c.getItems().isEmpty() && !Boolean.TRUE.equals(c.getNoMaterialConfirmed()), "追加必须明确增加正数单耗，不能用空清单取消原批准用料");
                require(c.getTiming() == null, "追加用料不能借机改变已批准时间口径；须走新的返工执行变更流程");
                require(c.getIdentifierScope() == null, "追加用料不能借机改变已批准原动态实例范围");
                require(c.getEngineeringOverride() == null,
                        "追加用料不能借机改变已批准SOP/参数工程口径");
            } else {
                require(c.getTiming() != null, "首轮返工执行方案必须明确时间口径，包括明确批准无限制");
                require(c.getIdentifierScope() != null, "首轮返工执行方案必须明确原动态实例范围");
                require(c.getEngineeringOverride() != null,
                        "首轮返工执行方案必须明确继承原节点或选择已批准SOP/参数覆盖版");
            }
        } else
            require(c.getPlanId() != null && c.getItems() == null && c.getNoMaterialConfirmed() == null
                            && c.getTiming() == null && c.getIdentifierScope() == null
                            && c.getEngineeringOverride() == null,
                    "复核/撤回须引用原方案，不能更改其物料、时间、原实例或工程口径");
        String fingerprint = hash(JSONUtil.toJsonStr(Arrays.asList(c, actor.getId())));
        plans.lockRequest("MES-REWORK-MATERIAL:" + TenantContextHolder.getTenantId() + ":" + factory + ":" + c.getRequestId());
        sqlSession.clearCache();
        var old = operations.selectOne(opScope(factory).eq(MesReworkMaterialOperationEntity::getRequestId, c.getRequestId()));
        if (old != null) {
            require(fingerprint.equals(old.getFingerprint()), "请求号已用于不同内容或人员");
            return Map.of("plan", plans.selectById(old.getPlanId()), "operation", old, "replayed", true);
        }
        var t = task(c.getTaskId(), factory, true);
        require(t != null && "REWORK".equals(t.getResourceType()), "当前工厂返工工单不存在");
        MesReworkMaterialPlanEntity plan;
        String before;
        String executionBefore = null, executionEvidence = null;
        if (proposing) {
            if (supplement) supplementable(t);
            else editable(t);
            MesReworkIdentifierUsageService.FrozenScope identifierScope = supplement
                    ? new MesReworkIdentifierUsageService.FrozenScope(t.getReworkIdentifierScopeSnapshot(),
                    t.getReworkIdentifierScopeFingerprint())
                    : reworkIdentifiers.freeze(t, c.getIdentifierScope());
            MesReworkEngineeringOverrideService.FrozenScope engineeringScope = supplement
                    ? null : reworkEngineering.freeze(t, c.getEngineeringOverride());
            require(plans.selectCount(scope(factory).eq(MesReworkMaterialPlanEntity::getTaskId, t.getId()).eq(MesReworkMaterialPlanEntity::getStatus, "PENDING")) == 0, "已有待审方案，请先复核或撤回");
            String[] original = original(t);
            var bom = MesMaterialSubstituteResolver.flattenBom(original[0]);
            require(bom.size() <= 2000, "原BOM超过2000行，须先治理或受控扩容");
            var route = JSONUtil.parseArray(original[1]);
            require(route.size() == 1, "当前返工执行方案须绑定唯一返工工序，多工序应先明确分段执行与用料");
            var proc = route.getJSONObject(0);
            String process = StrUtil.blankToDefault(proc.getStr("processCode"), proc.getStr("routeCode"));
            require(StrUtil.isNotBlank(process), "返工工序编码缺失");
            List<Object> selected = new ArrayList<>(), processMats = new ArrayList<>(), evidence = new ArrayList<>();
            SortedMap<String, BigDecimal> quantities = new TreeMap<>();
            if (supplement) for (var b : MesMaterialSubstituteResolver.flattenBom(t.getBomSnapshot())) {
                quantity(b.getQuantity());
                require(quantities.put(b.getMaterialCode(), b.getQuantity()) == null, "当前批准组件重复，不能猜测追加对象");
            }
            for (var item : c.getItems())
                quantities.merge(item.getMaterialCode(), item.getQuantityPerUnit(), BigDecimal::add);
            require(quantities.size() <= 100, "追加后组件总数不能超过100项");
            for (var item : quantities.entrySet()) {
                String code = item.getKey();
                BigDecimal perUnit = item.getValue();
                quantity(perUnit);
                var matches = bom.stream().filter(b -> code.equals(b.getMaterialCode())).toList();
                require(matches.size() == 1 && !code.equals(t.getMatCode()), "补料须唯一对应原冻结BOM组件，不能把原返工实物作为重复生产用料：" + code);
                var b = matches.get(0);
                var material = materials.getDetails(b.getMaterialCode());
                require(material != null && material.isOk() && material.getData() != null && Boolean.TRUE.equals(material.getData().getStatus())
                        && Objects.equals(material.getData().getTenantId(), t.getTenantId()) && factory.equals(material.getData().getFactoryCode())
                        && StrUtil.isNotBlank(b.getUnit()) && b.getUnit().equals(material.getData().getBasicUnit()), "补料未启用、归属不一致或冻结单位与当前基本单位不同，不能隐式换算");
                var total = perUnit.multiply(t.getNoticeNum());
                quantity(total);
                var row = JSONUtil.parseObj(b);
                row.remove("children");
                row.set("quantity", perUnit);
                row.set("fixedLossRate", BigDecimal.ZERO);
                row.set("variableLossRate", BigDecimal.ZERO);
                row.set("isLossIssued", false);
                selected.add(row);
                var p = new ProcessProcedureDTO.ProcessProcedureMatDTO();
                p.setMaterialCode(b.getMaterialCode());
                p.setMaterialName(b.getMaterialName());
                p.setUnit(b.getUnit());
                p.setQuantity(perUnit);
                p.setProcessCode(process);
                p.setRouteCode(proc.getStr("routeCode"));
                processMats.add(p);
                evidence.add(Map.of("materialCode", b.getMaterialCode(), "materialName", StrUtil.nullToEmpty(b.getMaterialName()), "unit", b.getUnit(), "perUnit", perUnit.toPlainString(), "total", total.toPlainString(), "master", material.getData()));
            }
            proc.set("mdmProcessProcedureMatList", processMats);
            if (engineeringScope != null) {
                var engineeringRoute = JSONUtil.parseArray(engineeringScope.resultRouteSnapshot());
                require(engineeringRoute.size() == 1
                                && Objects.equals(engineeringRoute.getJSONObject(0).getStr("routeNodeCode"),
                                proc.getStr("routeNodeCode")),
                        "返工工程覆盖的稳定节点与执行方案不一致");
                proc.set("formalSops", engineeringRoute.getJSONObject(0).get("formalSops"));
            }
            plan = new MesReworkMaterialPlanEntity();
            plan.setTenantId(t.getTenantId());
            plan.setFactoryCode(factory);
            plan.setTaskId(t.getId());
            plan.setTaskCode(t.getCode());
            plan.setSourceSnapshot(JSONUtil.toJsonStr(t));
            plan.setSourceFingerprint(basis(t));
            plan.setOriginalBomSnapshot(original[0]);
            plan.setOriginalRouteSnapshot(original[1]);
            plan.setResultBomSnapshot(JSONUtil.toJsonStr(selected));
            plan.setResultRouteSnapshot(route.toString());
            plan.setMaterialSnapshot(JSONUtil.toJsonStr(evidence));
            plan.setNoMaterialConfirmed(Boolean.TRUE.equals(c.getNoMaterialConfirmed()));
            plan.setPlanKind(supplement ? "PREPARED_ADD" : "INITIAL");
            plan.setReworkTimingPolicySnapshot(supplement
                    ? approvedTimingPolicy(t) : timingPolicy(c.getTiming()));
            plan.setIdentifierScopeSnapshot(identifierScope.snapshot());
            plan.setIdentifierScopeFingerprint(identifierScope.fingerprint());
            plan.setReworkEngineeringMode(supplement
                    ? t.getReworkEngineeringMode() : engineeringScope.mode());
            plan.setReworkEngineeringOverrideId(supplement
                    ? t.getReworkEngineeringOverrideId() : engineeringScope.releaseId());
            plan.setReworkEngineeringOverrideHash(supplement
                    ? t.getReworkEngineeringOverrideHash() : engineeringScope.contentHash());
            plan.setReworkEngineeringScopeSnapshot(supplement
                    ? t.getReworkEngineeringScopeSnapshot() : engineeringScope.snapshot());
            plan.setReworkEngineeringScopeFingerprint(supplement
                    ? t.getReworkEngineeringScopeFingerprint() : engineeringScope.fingerprint());
            plan.setResultParameterStandardNo(supplement
                    ? t.getProcessParameterStandardNo() : engineeringScope.parameterStandardNo());
            plan.setResultParameterStandardVersion(supplement
                    ? t.getProcessParameterStandardVersion() : engineeringScope.parameterStandardVersion());
            plan.setResultParameterStandardSnapshot(supplement
                    ? t.getProcessParameterStandardSnapshot() : engineeringScope.parameterSnapshot());
            plan.setResultParameterSnapshotFingerprint(supplement
                    ? t.getProcessParameterSnapshotFingerprint() : engineeringScope.parameterFingerprint());
            if (supplement) {
                plan.setSourcePlanId(t.getReworkMaterialPlanId());
                plan.setSupplementSnapshot(JSONUtil.toJsonStr(c.getItems()));
                plan.setPreparationSnapshot(preparation(t));
            }
            plan.setReason(c.getReason());
            plan.setApplicantId(actor.getId());
            plan.setApplicantCode(actor.getUsername());
            plan.setStatus("PENDING");
            require(plans.insert(plan) == 1, "返工执行方案提案保存失败");
            before = "NONE";
        } else {
            plan = plans.selectOne(scope(factory).eq(MesReworkMaterialPlanEntity::getId, c.getPlanId()).eq(MesReworkMaterialPlanEntity::getTaskId, t.getId()).last("FOR UPDATE"));
            require(plan != null && "PENDING".equals(plan.getStatus()), "方案不存在或已处理；请刷新核对回执");
            before = plan.getStatus();
            if ("WITHDRAW".equals(c.getOperation()))
                require(Objects.equals(plan.getApplicantId(), actor.getId()), "仅原提案人可撤回");
            else require(!Objects.equals(plan.getApplicantId(), actor.getId()), "返工执行方案须由另一名授权人员复核");
            if ("APPROVE".equals(c.getOperation())) {
                if ("PREPARED_ADD".equals(plan.getPlanKind())) {
                    supplementable(t);
                    require(Objects.equals(plan.getSourcePlanId(), t.getReworkMaterialPlanId()), "追加的基准批准版本已变化，请重提");
                    executionBefore = preparation(t);
                } else editable(t);
                require(plan.getSourceFingerprint().equals(basis(t)), "返工工单/BOM/工艺/数量已变化，旧方案失效，请驳回或撤回后重提");
                reworkIdentifiers.validateFrozen(t, plan.getIdentifierScopeSnapshot(),
                        plan.getIdentifierScopeFingerprint());
                if (!"PREPARED_ADD".equals(plan.getPlanKind())) {
                    var engineeringScope = reworkEngineering.revalidateProposal(t,
                            plan.getReworkEngineeringScopeSnapshot(),
                            plan.getReworkEngineeringScopeFingerprint());
                    require(Objects.equals(engineeringScope.mode(), plan.getReworkEngineeringMode())
                                    && Objects.equals(engineeringScope.releaseId(), plan.getReworkEngineeringOverrideId())
                                    && Objects.equals(engineeringScope.contentHash(), plan.getReworkEngineeringOverrideHash())
                                    && Objects.equals(engineeringScope.parameterStandardNo(), plan.getResultParameterStandardNo())
                                    && Objects.equals(engineeringScope.parameterStandardVersion(), plan.getResultParameterStandardVersion())
                                    && Objects.equals(engineeringScope.parameterSnapshot(), plan.getResultParameterStandardSnapshot())
                                    && Objects.equals(engineeringScope.parameterFingerprint(), plan.getResultParameterSnapshotFingerprint())
                                    && Objects.equals(formalSopSnapshot(engineeringScope.resultRouteSnapshot()),
                                    formalSopSnapshot(plan.getResultRouteSnapshot())),
                            "返工工程覆盖重采结果与待审方案不一致，请重新提交");
                }
                var evidence = JSONUtil.parseArray(plan.getMaterialSnapshot());
                for (int i = 0; i < evidence.size(); i++) {
                    var row = evidence.getJSONObject(i);
                    var current = materials.getDetails(row.getStr("materialCode"));
                    require(current != null && current.isOk() && current.getData() != null && JSONUtil.parseObj(JSONUtil.toJsonStr(current.getData())).equals(row.getJSONObject("master")), "补料主数据已变化或不可核验，请重提方案");
                }
                plan.setStatus("APPROVED");
            } else plan.setStatus("REJECT".equals(c.getOperation()) ? "REJECTED" : "WITHDRAWN");
            plan.setReviewerId(actor.getId());
            plan.setReviewReason(c.getReason());
            require(plans.updateById(plan) == 1, "方案复核回执保存失败");
            if ("APPROVE".equals(c.getOperation())) {
                t.setReworkMaterialPlanId(plan.getId());
                t.setBomSnapshot(plan.getResultBomSnapshot());
                t.setProcessRouteSnapshot(plan.getResultRouteSnapshot());
                t.setReworkTimingPolicySnapshot(plan.getReworkTimingPolicySnapshot());
                t.setReworkIdentifierScopeSnapshot(plan.getIdentifierScopeSnapshot());
                t.setReworkIdentifierScopeFingerprint(plan.getIdentifierScopeFingerprint());
                t.setReworkEngineeringMode(plan.getReworkEngineeringMode());
                t.setReworkEngineeringOverrideId(plan.getReworkEngineeringOverrideId());
                t.setReworkEngineeringOverrideHash(plan.getReworkEngineeringOverrideHash());
                t.setReworkEngineeringScopeSnapshot(plan.getReworkEngineeringScopeSnapshot());
                t.setReworkEngineeringScopeFingerprint(plan.getReworkEngineeringScopeFingerprint());
                t.setProcessParameterStandardNo(plan.getResultParameterStandardNo());
                t.setProcessParameterStandardVersion(plan.getResultParameterStandardVersion());
                t.setProcessParameterStandardSnapshot(plan.getResultParameterStandardSnapshot());
                t.setProcessParameterSnapshotFingerprint(plan.getResultParameterSnapshotFingerprint());
                require(tasks.updateById(t) == 1, "返工执行快照发布失败");
                if ("PREPARED_ADD".equals(plan.getPlanKind())) {
                    synchronizePreparation(t);
                    executionEvidence = JSONUtil.createObj().set("before", JSONUtil.parseObj(executionBefore))
                            .set("after", JSONUtil.parseObj(preparation(t))).toString();
                }
            }
        }
        var op = new MesReworkMaterialOperationEntity();
        op.setTenantId(t.getTenantId());
        op.setFactoryCode(factory);
        op.setPlanId(plan.getId());
        op.setRequestId(c.getRequestId());
        op.setFingerprint(fingerprint);
        op.setOperationType(c.getOperation());
        op.setBeforeStatus(before);
        op.setAfterStatus(plan.getStatus());
        op.setOperatorId(actor.getId());
        op.setOperatorCode(actor.getUsername());
        op.setReason(c.getReason());
        op.setExecutionSnapshot(executionEvidence);
        require(operations.insert(op) == 1, "返工执行方案永久回执保存失败");
        return Map.of("plan", plan, "operation", op, "replayed", false);
    }

    public void assertReady(MesTaskEntity t) {
        if (!"REWORK".equals(t.getResourceType())) return;
        if ("CREATE".equals(t.getStatus()) && StrUtil.isNotBlank(t.getReworkRouteNodeCode())) {
            MesReworkTimingPolicy.parse(t.getReworkTimingPolicySnapshot());
        }
        if (t.getReworkMaterialPlanId() == null) {
            require(!"CREATE".equals(t.getStatus()), "返工下达前须提交并独立批准执行方案，明确补料与跨工单时间口径");
            return;
        }
        approvedBasis(t);
        reworkIdentifiers.validateApproved(t);
        reworkEngineering.validateApproved(t);
        require(plans.selectCount(scope(t.getFactoryCode()).eq(MesReworkMaterialPlanEntity::getTaskId, t.getId()).eq(MesReworkMaterialPlanEntity::getStatus, "PENDING")) == 0, "返工执行方案变更待审，暂不能生成用料或下达执行");
    }

    private void approvedBasis(MesTaskEntity t) {
        var p = plans.selectById(t.getReworkMaterialPlanId());
        require(p != null && "APPROVED".equals(p.getStatus()) && Objects.equals(p.getTaskId(), t.getId())
                && Objects.equals(p.getTenantId(), t.getTenantId()) && Objects.equals(p.getFactoryCode(), t.getFactoryCode())
                && Objects.equals(p.getResultBomSnapshot(), t.getBomSnapshot()) && Objects.equals(p.getResultRouteSnapshot(), t.getProcessRouteSnapshot())
                && Objects.equals(p.getReworkTimingPolicySnapshot(), t.getReworkTimingPolicySnapshot())
                && Objects.equals(p.getIdentifierScopeSnapshot(), t.getReworkIdentifierScopeSnapshot())
                && Objects.equals(p.getIdentifierScopeFingerprint(), t.getReworkIdentifierScopeFingerprint())
                && Objects.equals(p.getReworkEngineeringMode(), t.getReworkEngineeringMode())
                && Objects.equals(p.getReworkEngineeringOverrideId(), t.getReworkEngineeringOverrideId())
                && Objects.equals(p.getReworkEngineeringOverrideHash(), t.getReworkEngineeringOverrideHash())
                && Objects.equals(p.getReworkEngineeringScopeSnapshot(), t.getReworkEngineeringScopeSnapshot())
                && Objects.equals(p.getReworkEngineeringScopeFingerprint(), t.getReworkEngineeringScopeFingerprint())
                && Objects.equals(p.getResultParameterStandardNo(), t.getProcessParameterStandardNo())
                && Objects.equals(p.getResultParameterStandardVersion(), t.getProcessParameterStandardVersion())
                && Objects.equals(p.getResultParameterStandardSnapshot(), t.getProcessParameterStandardSnapshot())
                && Objects.equals(p.getResultParameterSnapshotFingerprint(), t.getProcessParameterSnapshotFingerprint())
                && identity(t).equals(identity(JSONUtil.toBean(p.getSourceSnapshot(), MesTaskEntity.class))), "返工执行方案批准依据与当前执行快照不一致");
    }

    public String originalBom(MesTaskEntity t) {
        return original(t)[0];
    }

    private void supplementable(MesTaskEntity t) {
        require(t.getReworkMaterialPlanId() != null && Set.of("CREATE", "ISSUE", "DISPATCH", "PRODUCT").contains(t.getStatus())
                && t.getNoticeNum() != null && t.getNoticeNum().signum() > 0, "追加须引用尚未完工/关闭的本工单已批准方案");
        approvedBasis(t);
        reworkIdentifiers.validateApproved(t);
        reworkEngineering.validateApproved(t);
        require(!plans.hasExecutionHistory(t.getTenantId(), t.getFactoryCode(), t.getCode()),
                "已有报工（含已冲销历史）、实际物料消耗或投料消耗量不明，须按消耗版本切换，不能直接套用全工单追加单耗");
        preparationRows(t);
    }

    private Map<String, Object> preparationRows(MesTaskEntity t) {
        var produced = produceTasks.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>().eq(MesProduceTaskEntity::getTaskCode, t.getCode())
                .eq(MesProduceTaskEntity::getTenantId, t.getTenantId()).eq(MesProduceTaskEntity::getFactoryCode, t.getFactoryCode()).last("LIMIT 2"));
        require(produced.size() <= 1 && produced.stream().allMatch(p -> Objects.equals(p.getProcessRouteSnapshot(), t.getProcessRouteSnapshot())),
                "执行准备的工序数量或工艺快照与当前批准版本不一致，请先核实");
        var lists = materialLists.selectList(new LambdaQueryWrapper<MesMaterialListEntity>().eq(MesMaterialListEntity::getTaskId, t.getId())
                .eq(MesMaterialListEntity::getTenantId, t.getTenantId()).eq(MesMaterialListEntity::getFactoryCode, t.getFactoryCode()).last("LIMIT 2"));
        require(lists.size() <= 1, "返工存在多个用料清单，不能猜测追加对象");
        List<MesMaterialListDetailEntity> details = List.of();
        if (!lists.isEmpty()) {
            var h = lists.get(0);
            require(Objects.equals(h.getBomSnapshot(), t.getBomSnapshot()) && h.getPlanQuantity() != null
                    && h.getPlanQuantity().compareTo(t.getNoticeNum()) == 0, "现有用料清单与批准版本/数量不一致");
            details = materialDetails.selectList(new LambdaQueryWrapper<MesMaterialListDetailEntity>()
                    .eq(MesMaterialListDetailEntity::getMaterialListId, h.getId()).orderByAsc(MesMaterialListDetailEntity::getId).last("LIMIT 101"));
            var bom = MesMaterialSubstituteResolver.flattenBom(t.getBomSnapshot());
            require(details.size() <= 100 && details.size() == bom.size(), "现有用料明细不完整或超出容量，禁止通过追加重置历史");
            for (var b : bom) {
                var matches = details.stream().filter(d -> Objects.equals(d.getMaterialCode(), b.getMaterialCode())).toList();
                require(matches.size() == 1, "现有用料组件缺失或重复");
                var d = matches.get(0);
                require(Objects.equals(d.getTaskId(), t.getId()) && Objects.equals(d.getFactoryCode(), t.getFactoryCode())
                                && Objects.equals(d.getTenantId(), t.getTenantId()) && Objects.equals(d.getUnit(), b.getUnit())
                                && d.getBomQuantity() != null && d.getBomQuantity().compareTo(b.getQuantity()) == 0
                                && d.getRequiredQuantity() != null && d.getRequiredQuantity().compareTo(b.getQuantity().multiply(t.getNoticeNum())) == 0
                                && d.getPickedQuantity() != null && d.getReturnedQuantity() != null && d.getActualQuantity() != null
                                && d.getPickedQuantity().signum() >= 0 && d.getReturnedQuantity().signum() >= 0
                                && d.getReturnedQuantity().compareTo(d.getPickedQuantity()) <= 0
                                && d.getPickedQuantity().compareTo(d.getRequiredQuantity()) <= 0
                                && d.getActualQuantity().compareTo(d.getPickedQuantity().subtract(d.getReturnedQuantity())) == 0,
                        "原用料单位/批准量/领退登记不一致，不能借追加掩盖差异");
            }
        }
        return Map.of("produceTasks", produced, "materialLists", lists, "materialDetails", details,
                "evidenceVersion", "PREPARED_ADD_V1", "sourcePlanId", t.getReworkMaterialPlanId().toString());
    }

    private String preparation(MesTaskEntity t) {
        String json = JSONUtil.toJsonStr(preparationRows(t));
        require(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4 * 1024 * 1024, "执行准备证据超过4MiB，须受控扩容");
        return json;
    }

    private void synchronizePreparation(MesTaskEntity t) {
        // Task row is already locked. Existing quantities are read now and never reset.
        for (var p : produceTasks.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>().eq(MesProduceTaskEntity::getTaskCode, t.getCode())
                .eq(MesProduceTaskEntity::getTenantId, t.getTenantId()).eq(MesProduceTaskEntity::getFactoryCode, t.getFactoryCode()))) {
            p.setProcessRouteSnapshot(t.getProcessRouteSnapshot());
            require(produceTasks.updateById(p) == 1, "返工工序用料快照同步失败");
        }
        var heads = materialLists.selectList(new LambdaQueryWrapper<MesMaterialListEntity>().eq(MesMaterialListEntity::getTaskId, t.getId())
                .eq(MesMaterialListEntity::getTenantId, t.getTenantId()).eq(MesMaterialListEntity::getFactoryCode, t.getFactoryCode()));
        if (heads.isEmpty()) return;
        require(heads.size() == 1, "返工用料清单必须唯一");
        var head = heads.get(0);
        var old = materialDetails.selectList(new LambdaQueryWrapper<MesMaterialListDetailEntity>().eq(MesMaterialListDetailEntity::getMaterialListId, head.getId()));
        boolean anyPicked = false, complete = true;
        for (var b : MesMaterialSubstituteResolver.flattenBom(t.getBomSnapshot())) {
            var matches = old.stream().filter(d -> Objects.equals(d.getMaterialCode(), b.getMaterialCode())).toList();
            require(matches.size() <= 1, "追加组件对应多个原明细");
            var d = matches.isEmpty() ? new MesMaterialListDetailEntity() : matches.get(0);
            if (matches.isEmpty()) {
                d.setTenantId(t.getTenantId());
                d.setFactoryCode(t.getFactoryCode());
                d.setTaskId(t.getId());
                d.setMaterialListId(head.getId());
                d.setMaterialCode(b.getMaterialCode());
                d.setMaterialName(b.getMaterialName());
                d.setUnit(b.getUnit());
                d.setMaterialAttribute(b.getMaterialAttribute());
                d.setDefaultWarehouse(b.getDefaultIssuanceWarehouse());
                d.setUsageType(b.getUsageType());
                d.setFixedLossRate(BigDecimal.ZERO);
                d.setVariableLossRate(BigDecimal.ZERO);
                d.setIsLossIssued(false);
                d.setPickedQuantity(BigDecimal.ZERO);
                d.setReturnedQuantity(BigDecimal.ZERO);
                d.setActualQuantity(BigDecimal.ZERO);
            }
            BigDecimal needed = b.getQuantity().multiply(t.getNoticeNum());
            quantity(needed);
            require(d.getRequiredQuantity() == null || needed.compareTo(d.getRequiredQuantity()) >= 0, "追加不得减少原批准总量");
            d.setBomQuantity(b.getQuantity());
            d.setRequiredQuantity(needed);
            boolean done = d.getPickedQuantity().compareTo(needed) >= 0;
            d.setPickStatus(done ? MesMaterialPickStatusEnum.COMPLETED.getCode() : d.getPickedQuantity().signum() > 0 ? MesMaterialPickStatusEnum.PICKING.getCode() : MesMaterialPickStatusEnum.PENDING.getCode());
            require((matches.isEmpty() ? materialDetails.insert(d) : materialDetails.updateById(d)) == 1, "追加用料明细保存失败");
            sqlSession.clearCache();
            var stored = materialDetails.selectById(d.getId());
            require(stored != null && stored.getBomQuantity().compareTo(d.getBomQuantity()) == 0 && stored.getRequiredQuantity().compareTo(needed) == 0
                    && stored.getPickedQuantity().compareTo(d.getPickedQuantity()) == 0 && stored.getReturnedQuantity().compareTo(d.getReturnedQuantity()) == 0
                    && stored.getActualQuantity().compareTo(d.getActualQuantity()) == 0, "追加保存发生舍入或覆盖领退记录，已回滚");
            complete &= done;
            anyPicked |= d.getPickedQuantity().signum() > 0;
        }
        head.setBomSnapshot(t.getBomSnapshot());
        head.setStatus(complete ? MesMaterialListStatusEnum.COMPLETED.getCode() : anyPicked ? MesMaterialListStatusEnum.PICKING.getCode() : MesMaterialListStatusEnum.NEW.getCode());
        require(materialLists.updateById(head) == 1, "追加用料清单版本同步失败");
    }

    public boolean approvedWithoutMaterials(MesTaskEntity t) {
        if (t == null || !"REWORK".equals(t.getResourceType()) || t.getReworkMaterialPlanId() == null) return false;
        assertReady(t);
        return MesMaterialSubstituteResolver.flattenBom(t.getBomSnapshot()).isEmpty();
    }

    private void editable(MesTaskEntity t) {
        require("CREATE".equals(t.getStatus()) && t.getNoticeNum() != null && t.getNoticeNum().signum() > 0, "仅未下达返工工单可规划补料");
        require(produceTasks.selectCount(new LambdaQueryWrapper<MesProduceTaskEntity>().eq(MesProduceTaskEntity::getTaskCode, t.getCode()).eq(MesProduceTaskEntity::getTenantId, t.getTenantId()).eq(MesProduceTaskEntity::getFactoryCode, t.getFactoryCode())) == 0
                && materialLists.selectCount(new LambdaQueryWrapper<MesMaterialListEntity>().eq(MesMaterialListEntity::getTaskId, t.getId())) == 0
                && pickingBills.selectCount(new LambdaQueryWrapper<MesWarehousePickingBillEntity>().eq(MesWarehousePickingBillEntity::getTaskCode, t.getCode()).eq(MesWarehousePickingBillEntity::getTenantId, t.getTenantId()).eq(MesWarehousePickingBillEntity::getFactoryCode, t.getFactoryCode())) == 0, "已有工序/用料清单/领料单，不能覆盖执行依据；须受控变更而非改快照");
    }

    private String[] original(MesTaskEntity t) {
        if (t.getReworkMaterialPlanId() == null) return new String[]{t.getBomSnapshot(), t.getProcessRouteSnapshot()};
        var p = plans.selectById(t.getReworkMaterialPlanId());
        require(p != null && Objects.equals(p.getTaskId(), t.getId()), "当前补料方案来源丢失");
        return new String[]{p.getOriginalBomSnapshot(), p.getOriginalRouteSnapshot()};
    }

    private String identity(MesTaskEntity t) {
        return JSONUtil.toJsonStr(Arrays.asList(t.getId(), t.getCode(), t.getTenantId(), t.getFactoryCode(),
                t.getResourceType(), t.getMatCode(), t.getBomId(), t.getBomVersion(), t.getProcessRouteId(),
                t.getSourceTaskCode(), t.getSourceProduceTaskCode(), t.getReworkProcessCode(),
                t.getReworkRouteNodeCode(), t.getSourceInspectionType(), t.getSourceInspectionTaskId(),
                t.getSourceHandleRecordId(),
                t.getNoticeNum() == null ? null : t.getNoticeNum().stripTrailingZeros()));
    }

    /**
     * 只比较单节点正式SOP片段；方案路线还包含本次批准的用料，不能整段与MDM覆盖结果比较。
     */
    private String formalSopSnapshot(String routeSnapshot) {
        var route = JSONUtil.parseArray(routeSnapshot);
        require(route.size() == 1, "返工工程覆盖必须绑定唯一工序节点");
        return JSONUtil.toJsonStr(route.getJSONObject(0).get("formalSops"));
    }

    private String basis(MesTaskEntity t) {
        return hash(JSONUtil.toJsonStr(Arrays.asList(identity(t), t.getBomSnapshot(), t.getProcessRouteSnapshot(), t.getReworkMaterialPlanId())));
    }

    private String timingPolicy(MesReworkMaterialCommand.Timing timing) {
        return MesReworkTimingPolicy.encode(new MesReworkTimingPolicy(MesReworkTimingPolicy.VERSION,
                text(timing.getMode()), text(timing.getScope()), text(timing.getSourceEvent()),
                text(timing.getMinSeconds()), text(timing.getMaxSeconds()),
                text(timing.getLateExceptionSeconds())));
    }

    private String approvedTimingPolicy(MesTaskEntity task) {
        MesReworkTimingPolicy.parse(task.getReworkTimingPolicySnapshot());
        return task.getReworkTimingPolicySnapshot();
    }

    private String text(String value) {
        String normalized = StrUtil.trim(value);
        return StrUtil.isBlank(normalized) ? null : normalized;
    }

    private MesTaskEntity task(Long id, String factory, boolean lock) {
        var q = new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getId, id).eq(MesTaskEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesTaskEntity::getFactoryCode, factory);
        if (lock) q.last("FOR UPDATE");
        return tasks.selectOne(q);
    }

    private LambdaQueryWrapper<MesReworkMaterialPlanEntity> scope(String factory) {
        return new LambdaQueryWrapper<MesReworkMaterialPlanEntity>().eq(MesReworkMaterialPlanEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesReworkMaterialPlanEntity::getFactoryCode, factory);
    }

    private LambdaQueryWrapper<MesReworkMaterialOperationEntity> opScope(String factory) {
        return new LambdaQueryWrapper<MesReworkMaterialOperationEntity>().eq(MesReworkMaterialOperationEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesReworkMaterialOperationEntity::getFactoryCode, factory);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void quantity(BigDecimal q) {
        // 数据库工单数量可能自带8位零尾数；校验有效精度，不把无损的零尾数误报为需要舍入。
        var normalized = q == null ? null : q.stripTrailingZeros();
        require(normalized != null && normalized.signum() > 0 && normalized.precision() - Math.min(0, normalized.scale()) <= 24 && Math.max(0, normalized.scale()) <= 8,
                "补料数量必须为正数，最多24位、8位有效小数；乘返工数量后也不得舍入");
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
