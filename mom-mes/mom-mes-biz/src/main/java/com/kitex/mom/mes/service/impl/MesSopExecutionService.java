package com.kitex.mom.mes.service.impl;

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
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.dto.MesFormalSopNodeDTO;
import com.kitex.mom.mes.dto.MesSopAcknowledgeCommand;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesSopExecutionRecord;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesProduceTaskMapper;
import com.kitex.mom.mes.mapper.MesSopExecutionRecordMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 原冻结SOP逐项实名签收；正式字节版本与旧附件引用保持不同证据语义。
 */
@Service
@RequiredArgsConstructor
public class MesSopExecutionService {
    private final MesTaskArrangeMapper arranges;
    private final MesProduceTaskMapper processes;
    private final MesSopExecutionRecordMapper records;
    private final RemoteMdmFactoryAccessService factories;
    private final MesExecutionTaskLock taskLock;
    private final MesTerminalExecutionGuard terminalExecutionGuard;
    private final MesSopFreezeService formalSops;

    private static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show') || @pms.hasPermission('mesProduceTask:start')")
    public Map<String, Object> view(Long id, long current) {
        var task = arrange(id, false);
        access(task, "read");
        var manifest = manifest(task, false);
        require(current >= 1 && current <= 100000, "页码不合法");
        return Map.of("arrangeId", id.toString(), "arrangeCode", task.getCode(), "status", task.getStatus(), "manifest", manifest,
                "formalSourceState", sourceState(manifest),
                "acknowledged", acknowledged(task, manifest), "history", records.selectPage(new Page<>(current, 20), new LambdaQueryWrapper<MesSopExecutionRecord>()
                        .eq(MesSopExecutionRecord::getTenantId, task.getTenantId())
                        .eq(MesSopExecutionRecord::getArrangeId, id).eq(MesSopExecutionRecord::getFactoryCode, task.getFactoryCode()).orderByDesc(MesSopExecutionRecord::getId)));
    }

    /**
     * 开工准备Agent专用纯读证据。核真实派工执行人/工厂，不加执行锁、不插入START_CHECK。
     * 正式源安全撤回或当前不可核验均在formalSourceState显式阻断。
     */
    public Map<String, Object> previewStartPreparation(MesTaskArrangeEntity task) {
        require(task != null && task.getId() != null, "开工准备SOP取证须明确真实派工");
        var actual = arrange(task.getId(), false);
        require(Objects.equals(actual.getCode(), task.getCode()) && Objects.equals(actual.getTaskCode(), task.getTaskCode())
                && Objects.equals(actual.getProduceTaskCode(), task.getProduceTaskCode()), "开工准备派工身份已变化");
        access(actual, "read");
        var manifest = manifest(actual, false);
        return Map.of("arrangeId", actual.getId().toString(), "manifest", manifest,
                "acknowledged", acknowledged(actual, manifest), "formalSourceState", sourceState(manifest));
    }

    /**
     * 只代理本执行人可读的原冻结文件key，不接受客户端指定其他版本或自由URL。
     */
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show') || @pms.hasPermission('mesProduceTask:start')")
    public ResponseEntity<byte[]> original(Long id, String documentKey) {
        var task = arrange(id, false);
        access(task, "read");
        var manifest = manifest(task, false);
        @SuppressWarnings("unchecked") var documents = (List<Map<String, String>>) manifest.get("documents");
        var matching = documents.stream().filter(d -> Objects.equals(documentKey, d.get("key"))
                && "FORMAL_SOP_VERSION".equals(d.get("sourceType"))).toList();
        require(matching.size() == 1, "文件不在本派工精确冻结正式SOP清单中");
        var document = matching.get(0);
        var source = formalSops.original(task.getFactoryCode(), document.get("sourceId"), document.get("contentHash"), document.get("originalHash"));
        // 只转发受控附件类型/名称，不转发MDM内部认证、Cookie或任意响应头。
        var builder = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff");
        if (source.getHeaders().getContentType() != null) builder.contentType(source.getHeaders().getContentType());
        String disposition = source.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        if (disposition != null) builder.header(HttpHeaders.CONTENT_DISPOSITION, disposition);
        return builder.body(source.getBody());
    }

    /**
     * 网络结果未知时先核原签收账，只读取当前本人、本派工及当前工厂记录。
     */
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show') || @pms.hasPermission('mesProduceTask:start')")
    public MesSopExecutionRecord receipt(Long id, String requestId) {
        var task = arrange(id, false);
        access(task, "read");
        require(text(requestId, 64), "签收原请求号不能为空");
        return records.selectOne(new LambdaQueryWrapper<MesSopExecutionRecord>()
                .eq(MesSopExecutionRecord::getTenantId, task.getTenantId())
                .eq(MesSopExecutionRecord::getFactoryCode, task.getFactoryCode())
                .eq(MesSopExecutionRecord::getArrangeId, task.getId())
                .eq(MesSopExecutionRecord::getOperatorId, SecurityUtils.getUser().getId())
                .eq(MesSopExecutionRecord::getAction, "ACK")
                .eq(MesSopExecutionRecord::getRequestId, requestId));
    }

    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("@pms.hasPermission('mesProduceTask:start')")
    public MesSopExecutionRecord acknowledge(Long id, MesSopAcknowledgeCommand command) {
        var ownership = taskLock.arrange(id);
        var task = arrange(id, true);
        taskLock.assertArrange(ownership, task);
        access(task, "write");
        require(command != null && text(command.getRequestId(), 64) && text(command.getReason(), 1000), "须提供永久请求号及1至1000字确认说明");
        String hash = sha(JSONUtil.toJsonStr(Arrays.asList(id, SecurityUtils.getUser().getId(), command)));
        var old = records.selectOne(new LambdaQueryWrapper<MesSopExecutionRecord>().eq(MesSopExecutionRecord::getRequestId, command.getRequestId()));
        if (old != null) {
            require(Objects.equals(hash, old.getCommandFingerprint()) && Objects.equals(old.getArrangeId(), id), "请求号已用于不同签收命令");
            return old;
        }
        terminalExecutionGuard.assertUnoccupied(task);
        require(Set.of("UN_RECEIVE", "RECEIVE", "PAUSE").contains(task.getStatus()), "仅接收前、已接收或暂停时可签收SOP");
        var manifest = manifest(task, true);
        require(Boolean.TRUE.equals(sourceState(manifest).get("allowed")), "正式SOP已安全撤回或原件不可核验，不能签收；请联系工艺人员");
        require("REQUIRED".equals(manifest.get("status")), "本任务没有可签收的明确SOP清单，不生成假已读记录");
        require(Objects.equals(command.getExpectedFingerprint(), manifest.get("fingerprint")), "工艺/SOP引用已变化，请重新读取并确认");
        @SuppressWarnings("unchecked") var documents = (List<Map<String, String>>) manifest.get("documents");
        var expected = documents.stream().map(d -> d.get("key")).sorted().toList();
        require(command.getDocumentKeys() != null && command.getDocumentKeys().stream().allMatch(Objects::nonNull)
                && Objects.equals(command.getDocumentKeys().stream().sorted().toList(), expected), "须逐项确认全部文件，不能漏项或重复代替");
        return record(task, manifest, "ACK", command.getRequestId(), hash, command.getReason());
    }

    /**
     * 调用者已经锁定派工；检查与本次开工同事务，不能用前端状态绕过。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void assertStartAllowed(MesTaskArrangeEntity task) {
        access(task, "write");
        var manifest = manifest(task, true);
        var currentSource = sourceState(manifest);
        require(Boolean.TRUE.equals(currentSource.get("allowed")), "正式SOP已安全撤回或当前原件不可核验，禁止开工/复工；请联系工艺人员处理原版本");
        require(!"REQUIRED".equals(manifest.get("status")) || acknowledged(task, manifest), "SOP尚未由当前开工执行人完整签收，或冻结引用已变化，请先查阅并逐项确认");
        String key = "SOP-START:" + UUID.randomUUID();
        // 当前来源观察留在开工核验记录，不混入稳定签收指纹。
        var checkedManifest = new LinkedHashMap<String, Object>(manifest);
        checkedManifest.put("formalSourceState", currentSource);
        record(task, checkedManifest, "START_CHECK", key, sha(key), "开工/复工SOP核验；" + manifest.get("status"));
    }

    private Map<String, Object> manifest(MesTaskArrangeEntity task, boolean lock) {
        var query = new LambdaQueryWrapper<MesProduceTaskEntity>().eq(MesProduceTaskEntity::getTenantId, task.getTenantId())
                .eq(MesProduceTaskEntity::getCode, task.getProduceTaskCode())
                .eq(MesProduceTaskEntity::getFactoryCode, task.getFactoryCode()).last(lock ? "LIMIT 2 FOR UPDATE" : "LIMIT 2");
        var rows = processes.selectList(query);
        require(rows.size() == 1, "工序任务缺失或编码不唯一");
        var process = rows.get(0);
        require(Objects.equals(process.getTaskCode(), task.getTaskCode()) && Objects.equals(process.getTenantId(), task.getTenantId()), "派工和工序的工单/租户归属不一致");
        String snapshot = process.getProcessRouteSnapshot();
        List<Map<String, String>> documents = new ArrayList<>();
        String status = "LEGACY_UNDECLARED";
        MesFormalSopNodeDTO formal = null;
        if (StrUtil.isNotBlank(snapshot)) {
            require(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 2 * 1024 * 1024, "工艺快照超过2MiB，不能截断SOP证据");
            List<MdmProcessRouteWorkingDTO> route;
            try {
                route = JSONUtil.toList(snapshot, MdmProcessRouteWorkingDTO.class);
            } catch (Exception ex) {
                throw new CheckedException("工艺快照损坏，无法核验SOP");
            }
            require(route != null && route.size() <= 1000, "工艺快照格式或范围不合法");
            // 新任务按不可变节点；历史无节点必须在整个原快照中唯一匹配主工序。
            var selected = route.stream().filter(Objects::nonNull).filter(r -> StrUtil.isNotBlank(process.getSourceRouteNodeCode())
                    ? Objects.equals(r.getRouteNodeCode(), process.getSourceRouteNodeCode())
                    : Objects.equals(StrUtil.blankToDefault(r.getProcessCode(), r.getRouteCode()), process.getRouteCode())).toList();
            require(selected.size() == 1, "工艺快照不能唯一匹配当前工序，禁止猜SOP清单");
            require(Objects.equals(StrUtil.blankToDefault(selected.get(0).getProcessCode(), selected.get(0).getRouteCode()), process.getRouteCode()),
                    "冻结路线节点主工序与执行任务不一致");
            formal = selected.get(0).getFormalSops();
            if (formal != null) {
                require(StrUtil.isNotBlank(process.getSourceRouteNodeCode()), "正式SOP冻结缺少原工序稳定节点，不能按主工序猜测");
                formalSops.validateFrozen(formal, task.getTenantId().toString(), task.getFactoryCode(),
                        process.getProcessRouteCode(), process.getSourceRouteNodeCode(), process.getRouteCode());
            }
            var sops = selected.get(0).getMdmProcessProcedureSopList();
            if (sops != null) {
                require(sops.size() <= 50, "SOP引用超过50项，不能截断签收清单");
                status = sops.isEmpty() ? "NO_REFERENCE" : "REQUIRED";
                Set<String> keys = new HashSet<>();
                for (var sop : sops) {
                    require(sop != null && text(sop.getName(), 500), "SOP缺少文件名称");
                    String url = StrUtil.blankToDefault(sop.getUrl(), sop.getFileName());
                    require(text(url, 3000) && safeUrl(url), "SOP文件地址为空或不支持安全打开，请核对原工艺文件引用");
                    var d = new LinkedHashMap<String, String>();
                    d.put("name", sop.getName());
                    d.put("url", url);
                    d.put("fileName", Objects.toString(sop.getFileName(), ""));
                    d.put("sourceId", Objects.toString(sop.getId(), ""));
                    String key = sha(JSONUtil.toJsonStr(d));
                    require(keys.add(key), "SOP清单包含重复文件引用");
                    d.put("key", key);
                    documents.add(d);
                }
            }
            if (formal != null && !formal.getSources().isEmpty()) {
                for (var source : formal.getSources()) {
                    var document = new LinkedHashMap<String, String>();
                    document.put("sourceType", "FORMAL_SOP_VERSION");
                    document.put("sourceId", source.getVersionId());
                    document.put("name", source.getName());
                    document.put("sopNo", source.getSopNo());
                    document.put("versionNo", source.getVersionNo().toString());
                    document.put("contentHash", source.getContentHash());
                    document.put("originalId", source.getOriginalId());
                    document.put("originalHash", source.getOriginalHash());
                    document.put("originalSize", source.getOriginalSize());
                    document.put("mediaType", source.getMediaType());
                    document.put("approvedBy", source.getApprovedBy());
                    document.put("approvedAt", source.getApprovedAt().toString());
                    document.put("routeNodeCode", formal.getRouteNodeCode());
                    String key = sha(JSONUtil.toJsonStr(document));
                    document.put("key", key);
                    document.put("url", "/mes/mesTask/arrange/sop/" + task.getId() + "/formal-original?documentKey=" + key);
                    documents.add(document);
                }
                status = "REQUIRED";
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("produceTaskId", process.getId());
        result.put("processCode", process.getRouteCode());
        result.put("assignedWorkers", task.getWorkerCode());
        result.put("routeSnapshotHash", sha(Objects.toString(snapshot, "")));
        result.put("status", status);
        result.put("documents", documents);
        if (formal != null) result.put("formalSops", formal);
        result.put("fingerprint", sha(JSONUtil.toJsonStr(Arrays.asList(task.getTenantId(), task.getFactoryCode(), task.getId(), task.getWorkerCode(), result))));
        return result;
    }

    private Map<String, Object> sourceState(Map<String, Object> manifest) {
        return formalSops.currentState((MesFormalSopNodeDTO) manifest.get("formalSops"));
    }

    private boolean acknowledged(MesTaskArrangeEntity task, Map<String, Object> manifest) {
        return records.selectCount(new LambdaQueryWrapper<MesSopExecutionRecord>()
                .eq(MesSopExecutionRecord::getTenantId, task.getTenantId()).eq(MesSopExecutionRecord::getFactoryCode, task.getFactoryCode())
                .eq(MesSopExecutionRecord::getArrangeId, task.getId())
                .eq(MesSopExecutionRecord::getAction, "ACK").eq(MesSopExecutionRecord::getOperatorId, SecurityUtils.getUser().getId())
                .eq(MesSopExecutionRecord::getWorkerCode, SecurityUtils.getUser().getUsername()).eq(MesSopExecutionRecord::getManifestFingerprint, manifest.get("fingerprint"))) > 0;
    }

    private MesSopExecutionRecord record(MesTaskArrangeEntity task, Map<String, Object> manifest, String action, String key, String hash, String reason) {
        var row = new MesSopExecutionRecord();
        row.setTenantId(task.getTenantId());
        row.setFactoryCode(task.getFactoryCode());
        row.setArrangeId(task.getId());
        row.setProduceTaskId((Long) manifest.get("produceTaskId"));
        row.setAction(action);
        row.setRequestId(key);
        row.setCommandFingerprint(hash);
        row.setManifestFingerprint((String) manifest.get("fingerprint"));
        row.setManifestStatus((String) manifest.get("status"));
        row.setManifestJson(JSONUtil.toJsonStr(manifest));
        row.setOperatorId(SecurityUtils.getUser().getId());
        row.setWorkerCode(SecurityUtils.getUser().getUsername());
        row.setReason(reason);
        row.setOperationTime(LocalDateTime.now());
        require(records.insert(row) == 1, "SOP执行证据保存失败");
        return row;
    }

    private MesTaskArrangeEntity arrange(Long id, boolean lock) {
        require(id != null, "请选择派工任务");
        require(SecurityUtils.getUser() != null && TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && StrUtil.isNotBlank(FactoryContextHolder.getFactoryCode()), "请登录并选择实际工厂，禁止跳过隔离");
        var row = arranges.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getId, id)
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last(lock ? "FOR UPDATE" : ""));
        require(row != null, "派工任务不存在");
        return row;
    }

    private void access(MesTaskArrangeEntity row, String permission) {
        var user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && StrUtil.isNotBlank(user.getUsername()) && TenantContextHolder.getTenantId() != null
                && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()), "SOP签收须登录且不能跳过隔离");
        require(Objects.equals(row.getTenantId(), TenantContextHolder.getTenantId()) && StrUtil.isNotBlank(row.getFactoryCode()) && Objects.equals(row.getFactoryCode(), FactoryContextHolder.getFactoryCode()), "请选择派工所属实际工厂");
        require(Arrays.stream(Objects.toString(row.getWorkerCode(), "").split(",")).map(String::trim).anyMatch(user.getUsername()::equals), "仅本派工指定执行人可读取或签收，不能代签");
        var r = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId()).setFactoryCode(row.getFactoryCode()).setRequiredPermission(permission));
        require(r != null && r.isOk() && Boolean.TRUE.equals(r.getData()), "实际工厂权限不足或MDM不可核验");
    }

    private boolean safeUrl(String value) {
        try {
            if (value.indexOf((char) 92) >= 0 || value.chars().anyMatch(c -> c < 32 || c == 127)) return false;
            var u = java.net.URI.create(value);
            return u.getUserInfo() == null && ((u.isAbsolute() && Set.of("http", "https").contains(u.getScheme().toLowerCase(Locale.ROOT)) && u.getHost() != null) || (!u.isAbsolute() && value.startsWith("/") && !value.startsWith("//")));
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean text(String value, int max) {
        return StrUtil.isNotBlank(value) && value.length() <= max;
    }

    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
