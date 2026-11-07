package com.kitex.mom.mes.agent.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.agent.dto.MesStartPreparationDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesChangePointHandleService;
import com.kitex.mom.mes.service.MesDeviceGateService;
import com.kitex.mom.mes.service.impl.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

/**
 * 开工准备聚合观察：完整列出当前start的十三类门禁，逐项保留阻断和未知。
 *
 * <p>不调用start、不写设备核验或SOP签收、不改变任何生产事实。快照是当前操作者的点时观察，
 * 正式开工仍由原业务事务重新核验；不能因为模型给出建议就把未知或阻断改成就绪。</p>
 */
@Service
@RequiredArgsConstructor
public class MesStartPreparationQueryService {
    private static final String CONTRACT = "MES_START_PREPARATION_QUERY@1";
    private final MesTaskArrangeMapper arranges;
    private final MesProduceTaskMapper processes;
    private final MesToolingExecutionMapper toolings;
    private final MesDispatchReturnRequestMapper returns;
    private final MesTerminalExecutionCommandMapper terminalCommands;
    private final MesStartPreparationCheckMapper snapshots;
    private final RemoteMdmFactoryAccessService factories;
    private final MesSopExecutionService sops;
    private final MesAndonProductionReleaseService andon;
    private final MesRepairProductionReleaseService repair;
    private final MesChangePointHandleService changes;
    private final MesDeviceGateService devices;
    private final MesMaterialStartGateService materials;
    private final MesStartContractGuard startContractGuard;

    /**
     * 专用派工选择器：真实派工主键与工单关系来自本租户/工厂，不将报工记录ID冒充派工ID。
     */
    public Map<String, Object> candidates(long current, long size, String keyword) {
        String factory = FactoryContextHolder.getFactoryCode();
        authorize(factory);
        require(current >= 1 && current <= 100000 && size >= 1 && size <= 100, "派工分页参数无效");
        String search = Objects.toString(keyword, "").trim();
        require(search.length() <= 100, "派工搜索条件最长100字符");
        var query = new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant()).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .and(!search.isEmpty(), group -> group.like(MesTaskArrangeEntity::getCode, search)
                        .or().like(MesTaskArrangeEntity::getTaskCode, search))
                .orderByDesc(MesTaskArrangeEntity::getId);
        var page = arranges.selectPage(Page.of(current, size), query);
        List<Map<String, Object>> records = page.getRecords().stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId().toString());
            item.put("code", row.getCode());
            item.put("taskCode", row.getTaskCode());
            item.put("status", row.getStatus());
            item.put("routeName", row.getRouteName());
            item.put("workerName", row.getWorkerName());
            item.put("matName", row.getMatName());
            return item;
        }).toList();
        return Map.of("records", records, "total", page.getTotal(), "current", current, "size", size,
                "tenantId", tenant().toString(), "factoryCode", factory);
    }

    /**
     * 同一请求号/操作者/载荷仅形成一次观察；读来源失败按UNKNOWN记录而不是吞成通过。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> query(MesStartPreparationDTO.Query query) {
        authorize(query.getFactoryCode());
        Long arrangeId = id(query.getArrangeId());
        String requestHash = hash(List.of(query.getFactoryCode(), query.getArrangeId(), query.getTaskCode(), actor()));
        snapshots.lockRequest("MES_START_PREPARATION:" + tenant() + ":" + query.getFactoryCode() + ":" + query.getRequestId());
        MesStartPreparationCheck existing = snapshots.selectOne(scope().eq(MesStartPreparationCheck::getRequestId, query.getRequestId()));
        if (existing != null) {
            require(Objects.equals(existing.getOperatorId(), actor()) && requestHash.equals(existing.getRequestHash()),
                    "开工准备请求号已经用于其他操作者或内容，请查询原回执");
            return decode(existing);
        }
        MesTaskArrangeEntity task = arrange(arrangeId);
        require(Objects.equals(task.getTaskCode(), query.getTaskCode()), "派工与指定工单关系不一致");
        LocalDateTime observed = LocalDateTime.now();
        List<Map<String, Object>> checks = collectChecks(task, observed);
        long blocked = count(checks, "BLOCKED");
        long unknown = count(checks, "UNKNOWN");
        String status = blocked > 0 ? "BLOCKED" : unknown > 0 ? "UNKNOWN" : "READY";
        Map<String, Object> evidence = new LinkedHashMap<>();
        Long snapshotId = IdUtil.getSnowflakeNextId();
        evidence.put("toolCode", CONTRACT);
        evidence.put("snapshotId", snapshotId.toString());
        evidence.put("tenantId", tenant().toString());
        evidence.put("factoryCode", query.getFactoryCode());
        evidence.put("operatorId", actor().toString());
        evidence.put("arrangeId", task.getId().toString());
        evidence.put("arrangeCode", task.getCode());
        evidence.put("taskCode", task.getTaskCode());
        evidence.put("produceTaskCode", task.getProduceTaskCode());
        evidence.put("summary", Map.of("overallStatus", status, "readyCount", count(checks, "READY"),
                "notApplicableCount", count(checks, "NOT_APPLICABLE"), "blockedCount", blocked, "unknownCount", unknown,
                "allSupportedGatesChecked", checks.size() == 13 && unknown == 0));
        evidence.put("checks", checks);
        evidence.put("dataGaps", checks.stream().filter(item -> "UNKNOWN".equals(item.get("status")))
                .map(item -> item.get("title") + "：" + item.get("reason")).toList());
        evidence.put("scopeBoundary", "仅当前实名操作者的点时开工准备观察，不预留资源，不代签或批准；正式开工必须再次通过全部原门禁。未含尚未配置的厂级附加安全制度。");
        evidence.put("startPermission", false);
        String fingerprint = hash(Map.of("scope", List.of(tenant().toString(), query.getFactoryCode(),
                actor().toString(), task.getId().toString(), task.getTaskCode()), "checks", checks));
        evidence.put("evidenceFingerprint", fingerprint);
        evidence.put("observedAt", observed.toString());
        evidence.put("requestId", query.getRequestId());
        evidence.put("evidenceRefs", List.of("MES_START_PREPARATION:" + snapshotId + ":" + fingerprint));
        String json = JSONUtil.toJsonStr(canonical(JSONUtil.parseObj(JSONUtil.toJsonStr(evidence))));
        require(json.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024, "开工准备证据超过2MiB，不能截断保存");
        MesStartPreparationCheck saved = new MesStartPreparationCheck();
        saved.setId(snapshotId);
        saved.setTenantId(tenant());
        saved.setFactoryCode(query.getFactoryCode());
        saved.setRequestId(query.getRequestId());
        saved.setRequestHash(requestHash);
        saved.setArrangeId(task.getId());
        saved.setArrangeCode(task.getCode());
        saved.setTaskCode(task.getTaskCode());
        saved.setOperatorId(actor());
        saved.setContractVersion(CONTRACT);
        saved.setStatus(status);
        saved.setSnapshotJson(json);
        saved.setEvidenceFingerprint(fingerprint);
        saved.setObservedAt(observed);
        require(snapshots.insert(saved) == 1, "开工准备快照保存失败");
        return JSONUtil.parseObj(json);
    }

    /**
     * 原请求回执：当前用户只可读取自己观察的操作资格，防止把他人签收替换为本人的。
     */
    public Map<String, Object> receipt(String requestId) {
        authorize(FactoryContextHolder.getFactoryCode());
        require(requestId != null && requestId.matches("[A-Za-z0-9._:-]{8,100}"), "请求号格式无效");
        var row = snapshots.selectOne(scope().eq(MesStartPreparationCheck::getRequestId, requestId));
        require(row != null && Objects.equals(row.getOperatorId(), actor()), "当前操作者原请求回执不存在");
        arrange(row.getArrangeId());
        return decode(row);
    }

    /**
     * 已保存证据由模型读取时再次核验实际工厂、操作者和源派工存在性。
     */
    public Map<String, Object> snapshot(MesStartPreparationDTO.SnapshotQuery query) {
        authorize(query.getFactoryCode());
        var row = snapshots.selectOne(scope().eq(MesStartPreparationCheck::getId, id(query.getSnapshotId())));
        require(row != null && Objects.equals(row.getOperatorId(), actor()), "当前操作者开工准备快照不存在");
        arrange(row.getArrangeId());
        return decode(row);
    }

    /**
     * 供正式交接复用的纯读现场观察：不新增准备快照，不修改设备/SOP或开工事实。
     */
    public Map<String, Object> observe(Long arrangeId) {
        authorize(FactoryContextHolder.getFactoryCode());
        MesTaskArrangeEntity task = arrange(arrangeId);
        LocalDateTime observed = LocalDateTime.now();
        List<Map<String, Object>> checks = collectChecks(task, observed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contract", "MES_START_PREPARATION_OBSERVATION_V1");
        result.put("arrangeId", task.getId().toString());
        result.put("arrangeCode", task.getCode());
        result.put("taskCode", task.getTaskCode());
        result.put("operatorId", actor().toString());
        result.put("observedAt", observed.toString());
        result.put("checks", checks);
        result.put("allSupportedGatesChecked", checks.size() == 13 && count(checks, "UNKNOWN") == 0);
        result.put("dataGaps", checks.stream().filter(item -> "UNKNOWN".equals(item.get("status")))
                .map(item -> item.get("title") + "：" + item.get("reason")).toList());
        result.put("fingerprint", hash(Map.of("arrangeId", task.getId().toString(), "operatorId", actor().toString(), "checks", checks)));
        result.put("boundary", "当前实名操作者的点时观察，不是已结束班次事实或开工许可");
        return JSONUtil.parseObj(JSONUtil.toJsonStr(canonical(JSONUtil.parseObj(JSONUtil.toJsonStr(result)))));
    }

    private List<Map<String, Object>> collectChecks(MesTaskArrangeEntity task, LocalDateTime observed) {
        List<Map<String, Object>> checks = new ArrayList<>();
        check(checks, "TERMINAL_COMMAND", "跨端未决执行", "生产执行人", task,
                () -> terminal(task));
        check(checks, "DISPATCH_STATE", "派工状态", "生产调度员", task,
                () -> decision(Set.of("RECEIVE", "PAUSE").contains(task.getStatus()),
                        "当前派工状态=" + task.getStatus() + "；只有已接收或已暂停允许开工", Map.of("status", text(task.getStatus()))));
        check(checks, "ASSIGNED_WORKER", "实名作业者", "生产班组长", task,
                () -> decision(csv(task.getWorkerCode()).contains(SecurityUtils.getUser().getUsername()),
                        "当前账号必须为本派工指定执行人", Map.of("assignedWorkers", text(task.getWorkerCode()), "operatorId", actor().toString())));
        check(checks, "DISPATCH_RETURN", "退派申请", "生产调度员", task, () -> dispatchReturn(task));
        check(checks, "TOOLING", "工装扫码领用", "工装管理员", task, () -> tooling(task));
        check(checks, "SOP", "SOP原版签收", "生产执行人 / 工艺工程师", task, () -> sop(task));
        check(checks, "ANDON_RELEASE", "安灯复产批准", "异常责任人 / 独立批准人", task, () -> {
            var facts = andon.previewStartEvidence(task);
            boolean approved = facts.stream().allMatch(item -> Boolean.TRUE.equals(item.get("approved")));
            return decision(approved, approved ? "关联安灯原复产批准满足当前派工事实" : "存在未获当前事实有效复产批准的关联安灯", facts);
        });
        check(checks, "REPAIR_RELEASE", "维修复产批准", "设备工程师 / 独立批准人", task, () -> {
            var facts = repair.previewStartEvidence(task);
            boolean approved = Boolean.TRUE.equals(facts.get("approved"));
            return decision(approved, approved ? "当前真实维修停工的恢复回执及异岗复产门未发现阻断" : "真实维修停工未获当前恢复回执对应的有效异岗复产批准", facts);
        });
        check(checks, "PREDECESSOR", "前工序转移放行", "前工序班组长", task, () -> predecessor(task, observed));
        check(checks, "CHANGE_POINT", "变化点闭环", "变化点责任人", task, () -> {
            changes.assertStartAllowed(task); // 此方法经检查仅查询状态和抛出规则异常，不触发处理命令。
            return ready("当前范围未发现尚未解除的开工类变化点控制", Map.of("arrangeCode", task.getCode()));
        });
        check(checks, "DEVICE", "设备资格与运行状态", "设备工程师", task, () -> device(task));
        check(checks, "WORKSTATION", "工位占用", "生产调度员", task, () -> workstation(task));
        check(checks, "MATERIAL", "剩余需求齐套", "物料员 / 仓库", task, () -> material(task));

        return checks;
    }

    private Map<String, Object> terminal(MesTaskArrangeEntity task) {
        var pending = terminalCommands.pending(tenant(), task.getFactoryCode(), task.getId());
        return decision(pending.isEmpty(), pending.isEmpty() ? "无移动执行未决原号" : "存在移动执行未决原号，先查原回执，不能换号开工",
                Map.of("pendingIds", pending.stream().map(item -> item.getId().toString()).toList()));
    }

    private Map<String, Object> dispatchReturn(MesTaskArrangeEntity task) {
        var rows = returns.selectList(new LambdaQueryWrapper<MesDispatchReturnRequestEntity>()
                .eq(MesDispatchReturnRequestEntity::getTenantId, tenant()).eq(MesDispatchReturnRequestEntity::getFactoryCode, task.getFactoryCode())
                .eq(MesDispatchReturnRequestEntity::getArrangeId, task.getId()).eq(MesDispatchReturnRequestEntity::getStatus, "SUBMITTED")
                .orderByAsc(MesDispatchReturnRequestEntity::getId).last("LIMIT 201"));
        require(rows.size() <= 200, "待审核退派超过200项，不能截断核验");
        return decision(rows.isEmpty(), rows.isEmpty() ? "无待审核退派申请" : "存在待审核退派申请，禁止开工",
                Map.of("requestNos", rows.stream().map(MesDispatchReturnRequestEntity::getRequestNo).toList()));
    }

    private Map<String, Object> tooling(MesTaskArrangeEntity task) {
        startContractGuard.assertToolingBound(task);
        Set<String> planned = new TreeSet<>(csv(task.getPlannedToolingCodes()));
        if (planned.isEmpty()) return notApplicable("本派工未声明计划工装");
        var rows = toolings.selectList(new LambdaQueryWrapper<MesToolingExecutionEntity>()
                .eq(MesToolingExecutionEntity::getTenantId, tenant()).eq(MesToolingExecutionEntity::getFactoryCode, task.getFactoryCode())
                .eq(MesToolingExecutionEntity::getArrangeCode, task.getCode()).in(MesToolingExecutionEntity::getStatus, "CHECKED_IN", "IN_USE")
                .orderByAsc(MesToolingExecutionEntity::getId).last("LIMIT 1001"));
        require(rows.size() <= 1000, "工装执行证据超过1000项，不能截断");
        Set<String> actual = new TreeSet<>();
        rows.forEach(item -> actual.add(item.getToolingCode()));
        return decision(planned.equals(actual), "计划工装须与已领用/使用中的实物清单完全一致", Map.of("planned", planned, "checked", actual));
    }

    private Map<String, Object> sop(MesTaskArrangeEntity task) {
        Map<String, Object> evidence = sops.previewStartPreparation(task);
        Map<?, ?> manifest = (Map<?, ?>) evidence.get("manifest");
        Map<?, ?> formal = (Map<?, ?>) evidence.get("formalSourceState");
        if (formal == null) return unknown("正式SOP来源状态未返回，不能视为已批准有效", evidence);
        if (!Boolean.TRUE.equals(formal.get("allowed"))) {
            return "UNKNOWN".equals(formal.get("status"))
                    ? unknown(Objects.toString(formal.get("message"), "正式SOP来源未知"), evidence)
                    : decision(false, Objects.toString(formal.get("message"), "正式SOP已撤回或不可执行"), evidence);
        }
        String status = Objects.toString(manifest.get("status"), "UNKNOWN");
        if ("LEGACY_UNDECLARED".equals(status)) return unknown("历史工艺未明确SOP清单，不能视作已签收", manifest);
        if ("NO_REFERENCE".equals(status)) return notApplicable("冻结工艺明确无SOP引用");
        return decision(Boolean.TRUE.equals(evidence.get("acknowledged")),
                "必须由当前派工指定执行人签收精确冻结SOP，且正式原件未被安全撤回", evidence);
    }

    private Map<String, Object> predecessor(MesTaskArrangeEntity task, LocalDateTime now) {
        var current = processes.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, tenant()).eq(MesProduceTaskEntity::getFactoryCode, task.getFactoryCode())
                .eq(MesProduceTaskEntity::getCode, task.getProduceTaskCode()).eq(MesProduceTaskEntity::getTaskCode, task.getTaskCode()).last("LIMIT 2"));
        require(current.size() == 1, "工序任务缺失或关联不唯一");
        List<String> codes = csv(current.get(0).getPredecessorCodes());
        if (codes.isEmpty()) return notApplicable("当前工序无直接前工序");
        require(codes.size() <= 1000, "直接前工序超过1000项，不能截断");
        var rows = processes.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, tenant()).eq(MesProduceTaskEntity::getFactoryCode, task.getFactoryCode())
                .eq(MesProduceTaskEntity::getTaskCode, task.getTaskCode()).in(MesProduceTaskEntity::getRouteCode, codes)
                .orderByAsc(MesProduceTaskEntity::getId).last("LIMIT 1001"));
        require(rows.size() <= 1000, "前工序来源超过1000项，不能截断");
        List<Map<String, Object>> facts = new ArrayList<>();
        boolean allowed = true;
        for (String code : codes) {
            var matches = rows.stream().filter(row -> code.equals(row.getRouteCode())).toList();
            require(matches.size() == 1, "前工序" + code + "缺失或无法唯一匹配");
            var row = matches.get(0);
            boolean released = row.getTransferReleasedTime() != null && !row.getTransferReleasedTime().isAfter(now);
            allowed &= released;
            facts.add(Map.of("produceTaskId", row.getId().toString(), "routeCode", code, "status", text(row.getStatus()),
                    "transferReleasedTime", Objects.toString(row.getTransferReleasedTime(), ""), "released", released));
        }
        return decision(allowed, allowed ? "全部直接前工序已经取得有效转移放行事实" : "存在未取得转移放行的直接前工序", facts);
    }

    private Map<String, Object> device(MesTaskArrangeEntity task) {
        startContractGuard.assertDeviceBound(task);
        var result = devices.previewStart(task);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("deviceCode", result.getDeviceCode());
        facts.put("deviceState", result.getDeviceState());
        facts.put("worksiteCode", result.getWorksiteCode());
        facts.put("unavailableWindows", result.getUnavailableWindows());
        if (uncertain(result.getMessage())) return unknown(result.getMessage(), facts);
        if (StrUtil.isBlank(task.getPlannedDeviceCode()) && Boolean.TRUE.equals(result.getAllowed()))
            return notApplicable(result.getMessage());
        return decision(Boolean.TRUE.equals(result.getAllowed()), result.getMessage(), facts);
    }

    private Map<String, Object> workstation(MesTaskArrangeEntity task) {
        String worksite = StrUtil.blankToDefault(task.getActualWorksiteCode(), task.getWorksiteCodes());
        require(StrUtil.isNotBlank(worksite), "未确定实际工位，无法核验占用");
        var rows = arranges.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant()).eq(MesTaskArrangeEntity::getFactoryCode, task.getFactoryCode())
                .apply("(',' || worker_code || ',') LIKE {0}", "%," + SecurityUtils.getUser().getUsername() + ",%")
                .ne(MesTaskArrangeEntity::getId, task.getId()).eq(MesTaskArrangeEntity::getShiftCode, task.getShiftCode())
                .and(item -> item.eq(MesTaskArrangeEntity::getActualWorksiteCode, worksite)
                        .or(nested -> nested.isNull(MesTaskArrangeEntity::getActualWorksiteCode)
                                .apply("(',' || worksite_codes || ',') LIKE {0}", "%," + worksite + ",%")))
                .in(MesTaskArrangeEntity::getStatus, "RECEIVE", "PRODUCT")
                .orderByAsc(MesTaskArrangeEntity::getId).last("LIMIT 1001"));
        require(rows.size() <= 1000, "工位占用超过1000项，不能截断");
        return decision(rows.isEmpty(), rows.isEmpty() ? "原开工规则的当前作业者/班次工位占用门未发现冲突" : "原开工规则发现同一工位占用任务",
                Map.of("worksite", worksite, "shiftCode", text(task.getShiftCode()), "conflictArranges", rows.stream().map(MesTaskArrangeEntity::getCode).toList()));
    }

    private Map<String, Object> material(MesTaskArrangeEntity task) {
        // 独立纯读边界的异常由外层check转UNKNOWN，不能把当前准备快照/正式交接事务标为rollbackOnly。
        MesMaterialStartCheckEntity value;
        try {
            value = materials.observe(task);
        } catch (CheckedException failure) {
            // 配置无效/派工边界变化也属于取证未知，不能仅因文案未命中通用关键词而误报确定阻断。
            return unknown("物料证据无法核验：" + failure.getMessage(), Map.of());
        }
        Map<String, Object> manifest = JSONUtil.parseObj(value.getManifestJson());
        manifest.remove("asOf"); // 时间单独保留在观察外壳，不能使同一事实每次生成不同摘要。
        if ("UNKNOWN".equals(value.getResult())) return unknown("剩余需求物料证据未知，禁止当作齐套", manifest);
        return decision("READY".equals(value.getResult()), "READY".equals(value.getResult())
                ? "当前剩余数量需求齐套；观察不预留库存" : "存在剩余需求缺料；即使原策略为观察模式也不显示准备就绪", manifest);
    }

    private void check(List<Map<String, Object>> checks, String code, String title, String role,
                       MesTaskArrangeEntity task, Supplier<Map<String, Object>> reader) {
        Map<String, Object> result;
        try {
            result = reader.get();
        } catch (CheckedException exception) {
            String reason = exception.getMessage();
            result = uncertain(reason) ? unknown(reason, Map.of()) : decision(false, reason, Map.of());
        } catch (RuntimeException exception) {
            result = unknown("来源读取异常：" + exception.getClass().getSimpleName() + "；不能当作通过", Map.of());
        }
        result.put("code", code);
        result.put("title", title);
        result.put("required", !"NOT_APPLICABLE".equals(result.get("status")));
        result.put("responsibleRole", role);
        result.put("evidenceRefs", List.of("MES_DISPATCH:" + task.getId() + ":" + code));
        result.put("businessTarget", Map.of("permission", "mesTaskArrange:show", "businessType", "MES_DISPATCH",
                "businessId", task.getId().toString(), "businessNo", task.getCode(), "taskCode", task.getTaskCode(), "gateCode", code));
        result.put("sourceFingerprint", hash(result));
        checks.add(result);
    }

    private Map<String, Object> decision(boolean allowed, String reason, Object facts) {
        return result(allowed ? "READY" : "BLOCKED", reason, facts);
    }

    private Map<String, Object> ready(String reason, Object facts) {
        return result("READY", reason, facts);
    }

    private Map<String, Object> unknown(String reason, Object facts) {
        return result("UNKNOWN", reason, facts);
    }

    private Map<String, Object> notApplicable(String reason) {
        return result("NOT_APPLICABLE", reason, Map.of());
    }

    private Map<String, Object> result(String status, String reason, Object facts) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("reason", reason);
        result.put("facts", facts);
        return result;
    }

    private long count(List<Map<String, Object>> checks, String status) {
        return checks.stream().filter(row -> status.equals(row.get("status"))).count();
    }

    private boolean uncertain(String message) {
        return message == null || List.of("未知", "缺失", "损坏", "失败", "无响应", "无有效响应", "不可核验", "无法", "超过", "不唯一", "仅本派工指定").stream().anyMatch(message::contains);
    }

    private List<String> csv(String value) {
        return StrUtil.isBlank(value) ? List.of() : Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank).distinct().sorted().toList();
    }

    private void authorize(String factory) {
        var user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && tenant() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory)
                && Objects.equals(factory, FactoryContextHolder.getFactoryCode()), "开工准备须登录并选择实际授权工厂");
        var response = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId()).setFactoryCode(factory).setRequiredPermission("read"));
        require(response != null && response.isOk() && Boolean.TRUE.equals(response.getData()), "实际工厂权限不足或MDM不可核验");
    }

    private MesTaskArrangeEntity arrange(Long id) {
        var row = arranges.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getId, id)
                .eq(MesTaskArrangeEntity::getTenantId, tenant()).eq(MesTaskArrangeEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()));
        require(row != null, "当前工厂派工不存在");
        return row;
    }

    private LambdaQueryWrapper<MesStartPreparationCheck> scope() {
        return new LambdaQueryWrapper<MesStartPreparationCheck>().eq(MesStartPreparationCheck::getTenantId, tenant())
                .eq(MesStartPreparationCheck::getFactoryCode, FactoryContextHolder.getFactoryCode());
    }

    /** 包内历史阅读复用原指纹核验；调用者须先按实名租户工厂及派工限定表行。 */
    Map<String, Object> decode(MesStartPreparationCheck row) {
        require(CONTRACT.equals(row.getContractVersion()), "快照合同不支持");
        var evidence = JSONUtil.parseObj(row.getSnapshotJson());
        require(Objects.equals(evidence.getStr("evidenceFingerprint"), row.getEvidenceFingerprint()), "快照摘要不一致");
        require(Objects.equals(row.getEvidenceFingerprint(), hash(Map.of("scope", List.of(row.getTenantId().toString(), row.getFactoryCode(),
                row.getOperatorId().toString(), row.getArrangeId().toString(), row.getTaskCode()), "checks", evidence.get("checks")))), "快照内容校验失败");
        return evidence;
    }

    private Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private Long actor() {
        return SecurityUtils.getUser().getId();
    }

    private Long id(String value) {
        try {
            return Long.valueOf(value);
        } catch (RuntimeException ex) {
            throw new CheckedException("主键不是合法长整数");
        }
    }

    private String text(String value) {
        return Objects.toString(value, "");
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSONUtil.toJsonStr(canonical(JSONUtil.parse(JSONUtil.toJsonStr(value)))).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("准备事实摘要失败", ex);
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof Collection<?> items) return items.stream().map(this::canonical).toList();
        if (value instanceof Long || value instanceof java.math.BigInteger) return value.toString();
        return value;
    }

    private void require(boolean allowed, String message) {
        if (!allowed) throw new CheckedException(message);
    }
}
