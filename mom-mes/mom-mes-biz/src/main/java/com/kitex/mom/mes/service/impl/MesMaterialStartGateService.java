package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.util.MesMaterialSubstituteResolver;
import com.kitex.mom.mes.util.MesTaskUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 派工剩余需求的点时齐套核验，不创建物料预留或代替报工实际扣料。
 */
@Service
@RequiredArgsConstructor
public class MesMaterialStartGateService {
    private final MesActualFactoryAccessService access;
    private final MesTaskArrangeMapper arranges;
    private final MesTaskMapper tasks;
    private final MesProduceTaskMapper processes;
    private final MesTaskReportMapper reports;
    private final MesWarehouseFeedMapper feeds;
    private final MesWarehouseLiveStockService stocks;
    private final MesMaterialConsumptionModeResolver consumptionModes;
    private final MesStartMaterialBindingReader materialBindings;
    private final RemoteMaterialService materials;
    private final MesReworkMaterialPlanService rework;
    private final MesMaterialStartCheckMapper records;
    private final MesMaterialStartCheckWriter writer;
    @Value("${mom.material-start-gate.mode:STRICT_REMAINING}")
    private String mode;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> preview(Long id, long page) {
        String factory = access.require("read");
        require(page > 0 && page <= 100000, "页码无效");
        var a = arranges.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getId, id).eq(MesTaskArrangeEntity::getFactoryCode, factory).eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId()));
        require(a != null, "当前工厂派工不存在");
        var check = evaluate(a, "PREVIEW");
        var history = records.selectPage(new Page<>(page, 20), new LambdaQueryWrapper<MesMaterialStartCheckEntity>().eq(MesMaterialStartCheckEntity::getArrangeId, id)
                .eq(MesMaterialStartCheckEntity::getTenantId, a.getTenantId()).eq(MesMaterialStartCheckEntity::getFactoryCode, factory).orderByDesc(MesMaterialStartCheckEntity::getId));
        return Map.of("check", check, "history", history, "notice", "按本派工剩余数量核验当前可用量；这是点时检查，不预留库存，开工及报工仍会重新核验。");
    }

    /**
     * Agent/交接专用纯读观察边界：暂停调用者事务，任何模式或来源异常都不能污染外层快照事务。
     * 只复用计算，不读历史、不保存核验账、不调用开工门；派工仍按真实身份与精确作用域重读。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MesMaterialStartCheckEntity observe(MesTaskArrangeEntity expected) {
        String factory = access.require("read");
        require(expected != null && expected.getId() != null
                && Objects.equals(expected.getTenantId(), TenantContextHolder.getTenantId())
                && factory.equals(expected.getFactoryCode()), "物料观察派工作用域不一致");
        var actual = arranges.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, expected.getId())
                .eq(MesTaskArrangeEntity::getTenantId, expected.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory));
        require(actual != null && Objects.equals(actual.getCode(), expected.getCode())
                && Objects.equals(actual.getTaskCode(), expected.getTaskCode())
                && Objects.equals(actual.getProduceTaskCode(), expected.getProduceTaskCode()), "物料观察派工身份发生变化或不存在");
        return evaluate(actual, "PREVIEW");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assertStart(MesTaskArrangeEntity a) {
        assertStart(a, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assertStart(MesTaskArrangeEntity a, List<Long> selectedFeedIds) {
        require(access.require("write").equals(a.getFactoryCode()), "开工物料核验工厂不一致");
        var check = evaluate(a, "START_CHECK", selectedFeedIds);
        if (!"READY".equals(check.getResult()) && (!"OBSERVE_REMAINING".equals(check.getMode()) || "UNKNOWN".equals(check.getResult()))) {
            check.setStage("START_BLOCKED");
            writer.rejected(check);
            throw new CheckedException("开工物料核验" + ("SHORTAGE".equals(check.getResult()) ? "存在缺料" : "证据未知") + "，已阻断；核验号=" + check.getId() + "，请打开物料齐套核对明细");
        }
        require(records.insert(check) == 1, "开工物料核验保存失败");
        a.setMaterialGateRecordId(check.getId());
    }

    private MesMaterialStartCheckEntity evaluate(MesTaskArrangeEntity a, String stage) {
        return evaluate(a, stage, null);
    }

    private MesMaterialStartCheckEntity evaluate(MesTaskArrangeEntity a, String stage, List<Long> selectedFeedIds) {
        require(Set.of("STRICT_REMAINING", "OBSERVE_REMAINING").contains(mode), "开工物料策略无效，仅允许严格或明确观察模式");
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("version", "MATERIAL_START_V1");
        manifest.put("mode", mode);
        manifest.put("arrangeId", String.valueOf(a.getId()));
        manifest.put("noticeQuantity", decimal(a.getNoticeNum()));
        manifest.put("completedQuantity", decimal(a.getCompleteNum()));
        manifest.put("asOf", java.time.LocalDateTime.now().toString());
        String result;
        try {
            result = assess(a, manifest, selectedFeedIds);
        } catch (CheckedException ex) {
            result = "UNKNOWN";
            manifest.put("error", ex.getMessage());
        } catch (RuntimeException ex) {
            result = "UNKNOWN";
            manifest.put("error", "物料证据读取/解析失败：" + ex.getClass().getSimpleName() + "，不能当作无缺料");
        }
        manifest.put("result", result);
        String json = JSONUtil.toJsonStr(manifest);
        var row = new MesMaterialStartCheckEntity();
        row.setTenantId(a.getTenantId());
        row.setFactoryCode(a.getFactoryCode());
        row.setArrangeId(a.getId());
        row.setArrangeCode(a.getCode());
        row.setTaskCode(a.getTaskCode());
        row.setProduceTaskCode(a.getProduceTaskCode());
        row.setOperatorId(SecurityUtils.getUser().getId());
        row.setOperatorCode(SecurityUtils.getUser().getUsername());
        row.setStage(stage);
        row.setMode(mode);
        row.setResult(result);
        row.setManifestJson(json);
        row.setFingerprint(hash(json));
        return row;
    }

    private String assess(MesTaskArrangeEntity a, Map<String, Object> manifest, List<Long> selectedFeedIds) {
        var t = tasks.selectOne(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getCode, a.getTaskCode()).eq(MesTaskEntity::getTenantId, a.getTenantId()).eq(MesTaskEntity::getFactoryCode, a.getFactoryCode()));
        var p = processes.selectOne(new LambdaQueryWrapper<MesProduceTaskEntity>().eq(MesProduceTaskEntity::getCode, a.getProduceTaskCode()).eq(MesProduceTaskEntity::getTenantId, a.getTenantId()).eq(MesProduceTaskEntity::getFactoryCode, a.getFactoryCode()));
        require(t != null && p != null && Objects.equals(p.getTaskCode(), t.getCode()) && Objects.equals(p.getRouteCode(), a.getRouteCode()) && Objects.equals(p.getProcessRouteSnapshot(), t.getProcessRouteSnapshot()), "工单/工序/派工来源或冻结工艺不一致");
        com.kitex.mom.mes.util.MesFrozenExecutionGraph.assertArrangement(p, a);
        rework.assertReady(t);
        manifest.put("bomFingerprint", hash(Objects.toString(t.getBomSnapshot(), "")));
        manifest.put("routeFingerprint", hash(Objects.toString(t.getProcessRouteSnapshot(), "")));
        require(a.getNoticeNum() != null && a.getNoticeNum().signum() > 0, "派工需求数量未知或非正数");
        BigDecimal completed = a.getCompleteNum();
        if (completed == null) {
            require(reports.selectCount(new LambdaQueryWrapper<MesTaskReportEntity>().eq(MesTaskReportEntity::getArrangeCode, a.getCode()).eq(MesTaskReportEntity::getTenantId, a.getTenantId()).eq(MesTaskReportEntity::getFactoryCode, a.getFactoryCode())) == 0, "已有报工但派工完成量未知，不能假定为0");
            completed = BigDecimal.ZERO;
            manifest.put("zeroCompletedBasis", "NO_ACTIVE_REPORT");
        }
        BigDecimal quantity = a.getNoticeNum().subtract(completed);
        require(completed.signum() >= 0 && quantity.signum() > 0, "派工剩余需求量无效；已足量任务不应再次开工");
        manifest.put("remainingQuantity", quantity.toPlainString());
        var route = MesTaskUtils.getCurrentProcess(p.getProcessRouteSnapshot(),
                p.getRouteCode(), p.getSourceRouteNodeCode());
        var demand = route.getMdmProcessProcedureMatList();
        require(demand != null && demand.size() <= 100, "工序用料未明确声明或超过100项，不能按无需物料放行");
        boolean backflush = consumptionModes.resolve(a) == MesMaterialConsumptionModeResolver.Mode.BACKFLUSH;
        manifest.put("consumptionMode", backflush ? "BACKFLUSH" : "FEED");
        manifest.put("emptyMaterialDeclared", demand.isEmpty());
        var feedQuery = new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                .eq(MesWarehouseFeedEntity::getArrangeCode, a.getCode())
                .eq(MesWarehouseFeedEntity::getTenantId, a.getTenantId())
                .eq(MesWarehouseFeedEntity::getFactoryCode, a.getFactoryCode())
                .eq(MesWarehouseFeedEntity::getIsLock, false)
                .and(w -> w.isNull(MesWarehouseFeedEntity::getMatValidEndDate)
                        .or().ge(MesWarehouseFeedEntity::getMatValidEndDate, LocalDate.now()));
        materialBindings.constrain(a, feedQuery);
        if (selectedFeedIds != null) {
            require(!backflush && !selectedFeedIds.isEmpty() && selectedFeedIds.size() <= 100
                    && selectedFeedIds.stream().allMatch(id -> id != null && id > 0)
                    && new HashSet<>(selectedFeedIds).size() == selectedFeedIds.size(), "开工投料来源集合无效");
            feedQuery.in(MesWarehouseFeedEntity::getId, selectedFeedIds);
        }
        var allFeed = backflush ? List.<MesWarehouseFeedEntity>of() : feeds.selectList(feedQuery
                .orderByAsc(MesWarehouseFeedEntity::getFeedTime)
                .orderByAsc(MesWarehouseFeedEntity::getId).last("LIMIT 5001"));
        require(allFeed.size() <= 5000, "投料证据超过5000行，不能截断后判断齐套");
        List<Map<String, Object>> lines = new ArrayList<>();
        manifest.put("materials", lines);
        Set<String> seen = new HashSet<>();
        Map<String, List<Supply>> supplyCache = new HashMap<>();
        Map<String, BigDecimal> used = new HashMap<>();
        boolean shortage = false;
        for (var d : demand) {
            require(d != null && StrUtil.isNotBlank(d.getMaterialCode()) && seen.add(d.getMaterialCode()) && d.getQuantity() != null && d.getQuantity().signum() > 0 && StrUtil.isNotBlank(d.getUnit()), "工序用料存在重复、空编码/单位或无效单耗");
            var master = material(d.getMaterialCode(), a);
            require(d.getUnit().equals(master.getBasicUnit()), "冻结需求单位与当前物料基本单位不一致");
            var policy = MesMaterialSubstituteResolver.resolvePolicy(t.getBomSnapshot(), d.getMaterialCode());
            BigDecimal needed = quantity.multiply(d.getQuantity()), remaining = needed;
            List<MesMaterialSubstituteResolver.Candidate> candidates = policy.candidates();
            var line = new LinkedHashMap<String, Object>();
            line.put("materialCode", d.getMaterialCode());
            line.put("materialName", Objects.toString(d.getMaterialName(), ""));
            line.put("unit", d.getUnit());
            line.put("perUnit", d.getQuantity().toPlainString());
            line.put("required", needed.toPlainString());
            line.put("allowMixed", policy.allowMixed());
            List<Map<String, Object>> allocations = new ArrayList<>();
            line.put("allocations", allocations);
            lines.add(line);
            for (var candidate : candidates) {
                String key = (backflush ? "STOCK" : "FEED") + ":" + candidate.actualMatCode() + ":" + d.getMaterialCode();
                List<Supply> supplies = supplyCache.computeIfAbsent(key, k -> supply(a, backflush, allFeed, d.getMaterialCode(), candidate));
                BigDecimal available = supplies.stream().map(s -> s.quantity().subtract(used.getOrDefault(s.key(), BigDecimal.ZERO))).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (!policy.allowMixed() && available.compareTo(needed.multiply(candidate.ratio())) < 0) continue;
                for (var s : supplies) {
                    BigDecimal actual = s.quantity().subtract(used.getOrDefault(s.key(), BigDecimal.ZERO)).min(remaining.multiply(candidate.ratio()));
                    if (actual.signum() <= 0) continue;
                    BigDecimal satisfied = actual.compareTo(remaining.multiply(candidate.ratio())) == 0 ? remaining : actual.divide(candidate.ratio(), 12, RoundingMode.DOWN);
                    if (satisfied.signum() <= 0) continue;
                    used.merge(s.key(), actual, BigDecimal::add);
                    remaining = remaining.subtract(satisfied);
                    require(used.size() <= 5000, "本次齐套分配证据超过5000行，须受控收窄范围");
                    allocations.add(Map.of("source", backflush ? "STOCK" : "FEED", "id", s.key(), "materialCode", candidate.actualMatCode(), "batch", s.batch(), "actualQuantity", actual.toPlainString(), "ratio", candidate.ratio().toPlainString(), "satisfied", satisfied.toPlainString()));
                    if (remaining.signum() == 0) break;
                }
                if (remaining.signum() == 0 || !policy.allowMixed()) break;
            }
            line.put("shortage", remaining.toPlainString());
            line.put("ready", remaining.signum() == 0);
            shortage |= remaining.signum() > 0;
        }
        return shortage ? "SHORTAGE" : "READY";
    }

    private List<Supply> supply(MesTaskArrangeEntity a, boolean backflush, List<MesWarehouseFeedEntity> allFeed, String requirement, MesMaterialSubstituteResolver.Candidate c) {
        var m = material(c.actualMatCode(), a);
        require(c.ratio() != null && c.ratio().signum() > 0, "替代换算比例无效");
        if (backflush) {
            var query = new MesWarehouseLiveStockInoutDTO();
            query.setMatCode(c.actualMatCode());
            query.setFactoryCode(a.getFactoryCode());
            query.setBillNo(a.getCode());
            query.setRequirementMatCode(requirement);
            query.setSubstituteRatio(c.ratio());
            query.setSubstituteFlag(c.substitute());
            query.setProduceTaskCode(a.getProduceTaskCode());
            query.setAllowUnassignedProduceTask(true);
            query.setNum(BigDecimal.ONE);
            query.setMatDetailType("RAW");
            query.setBizType(WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode());
            return stocks.previewOutCandidates(query).stream().map(x -> new Supply("STOCK:" + x.getId(), x.getNum(), Objects.toString(x.getMatBatch(), ""))).toList();
        }
        List<Supply> result = new ArrayList<>();
        for (var f : allFeed) {
            if (!Objects.equals(f.getMatCode(), c.actualMatCode()) || !(StrUtil.isNotBlank(f.getRequirementMatCode()) ? requirement.equals(f.getRequirementMatCode()) : !c.substitute() && requirement.equals(f.getMatCode())))
                continue;
            require(Objects.equals(f.getTaskCode(), a.getTaskCode()) && Objects.equals(f.getMatUnit(), m.getBasicUnit()) && f.getFeedNum() != null && f.getUsedNum() != null && f.getUsedNum().signum() >= 0 && f.getFeedNum().compareTo(f.getUsedNum()) >= 0, "投料原数量、已用量、单位或工单来源不可核验");
            require(f.getSubstituteRatio() == null && !c.substitute() || f.getSubstituteRatio() != null && f.getSubstituteRatio().compareTo(c.ratio()) == 0, "原投料替代比例与冻结方案不一致");
            result.add(new Supply("FEED:" + f.getId(), f.getFeedNum().subtract(f.getUsedNum()), Objects.toString(f.getMatBatch(), "")));
        }
        return result;
    }

    private com.kitex.mom.mdm.entity.MdmMaterialEntity material(String code, MesTaskArrangeEntity a) {
        var r = materials.getDetails(code);
        require(r != null && r.isOk() && r.getData() != null && Objects.equals(r.getData().getMaterialCode(), code) && Boolean.TRUE.equals(r.getData().getStatus()) && Objects.equals(r.getData().getTenantId(), a.getTenantId()) && Objects.equals(r.getData().getFactoryCode(), a.getFactoryCode()) && StrUtil.isNotBlank(r.getData().getBasicUnit()), "物料编码、启用状态、单位或当前工厂主数据不可核验：" + code);
        return r.getData();
    }

    private String decimal(BigDecimal n) {
        return n == null ? "UNKNOWN" : n.toPlainString();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    private record Supply(String key, BigDecimal quantity, String batch) {
    }
}
