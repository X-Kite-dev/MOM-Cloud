package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.barcode.api.feign.RemoteBarcodeTemplateService;
import com.kitex.mom.common.barcode.api.feign.RemotePrintService;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.entity.PrintTemplateEntity;
import com.kitex.mom.common.barcode.utils.PrintTemplateFingerprint;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.dto.MesDispatchHistoryDTO.Detail;
import com.kitex.mom.mes.dto.MesDispatchHistoryDTO.NoteCommand;
import com.kitex.mom.mes.dto.MesDispatchHistoryDTO.PrintCommand;
import com.kitex.mom.mes.dto.MesDispatchHistoryDTO.Query;
import com.kitex.mom.mes.entity.MesDispatchHistoryNoteEntity;
import com.kitex.mom.mes.mapper.MesDispatchHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 精确历史读与原冻结投影。所有写入仅追加说明，不改派工或生产实绩。
 */
@Service
@RequiredArgsConstructor
public class MesDispatchHistoryService {
    private static final String NOTE_KIND = "DISPATCH_HISTORY_NOTE";
    private static final ObjectMapper JSON = jsonMapper();
    private final MesActualFactoryAccessService access;
    private final MesDispatchHistoryMapper mapper;
    private final MesExecutionTaskLock taskLock;
    private final MesExecutionCommandCancellationService cancellations;
    private final MesSopFreezeService formalSops;
    private final RemoteBarcodeTemplateService templates;
    private final RemotePrintService prints;

    private static ObjectMapper jsonMapper() {
        var module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        module.addSerializer(BigDecimal.class, ToStringSerializer.instance);
        return new ObjectMapper().findAndRegisterModules().registerModule(module)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new CheckedException("原派工证据无法完整序列化");
        }
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String processCode(MdmProcessRouteWorkingDTO node) {
        return blank(node.getProcessCode()) ? node.getRouteCode() : node.getProcessCode();
    }

    private static Long id(String value) {
        require(value != null && value.matches("[1-9][0-9]{0,18}"), "须提供精确正整数ID");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new CheckedException("ID超过有效整数范围");
        }
    }

    private static String code(String value) {
        String result = value == null ? null : value.trim();
        if (blank(result)) {
            return null;
        }
        require(result.length() <= 200 && result.chars().noneMatch(Character::isISOControl), "精确编码格式或长度无效");
        return result;
    }

    private static String member(String value) {
        String result = code(value);
        require(result == null || (!result.contains(",") && !result.contains(" ")), "人员或工位筛选须为单个精确编码");
        return result;
    }

    private static void request(String request) {
        require(request != null && request.matches("[A-Za-z0-9_.:-]{8,100}"), "须提供8至100位永久原请求号");
    }

    private static void hash(String value) {
        require(value != null && value.matches("[0-9a-f]{64}"), "缺少精确SHA256观察依据");
    }

    private static void pageBounds(int current, int size) {
        require(current > 0 && current <= 100000 && size > 0 && size <= 100, "每页1至100条且页码有效");
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> page(Query input) {
        var scope = context("read");
        Query q = normalize(input);
        var result = envelope(scope, false);
        result.put("scope", q);
        result.put("current", q.getCurrent());
        result.put("size", q.getSize());
        result.put("total", mapper.total(scope.tenant(), scope.factory(), q));
        result.put("records", mapper.page(scope.tenant(), scope.factory(), q, q.getSize(),
                (long) (q.getCurrent() - 1) * q.getSize()));
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(String arrangeId) {
        return read(context("read"), id(arrangeId));
    }

    private Detail read(Context scope, Long id) {
        var arrange = mapper.arrange(scope.tenant(), scope.factory(), id);
        require(arrange != null && Objects.equals(arrange.getId(), id)
                && Objects.equals(arrange.getTenantId(), scope.tenant())
                && Objects.equals(arrange.getFactoryCode(), scope.factory()), "本租户工厂未找到精确原派工");
        var tasks = mapper.tasks(scope.tenant(), scope.factory(), arrange.getTaskCode());
        var processes = mapper.processes(scope.tenant(), scope.factory(), arrange.getProduceTaskCode(), arrange.getTaskCode());
        var gaps = new ArrayList<String>();
        var task = tasks.size() == 1 ? tasks.get(0) : null;
        var process = processes.size() == 1 ? processes.get(0) : null;
        if (task == null) {
            gaps.add("原工单缺失或不唯一，未补用当前主数据");
        }
        if (process == null) {
            gaps.add("原工序任务缺失或不唯一，未猜测工艺引用");
        }
        Map<String, Object> engineering = new LinkedHashMap<>();
        if (task != null) {
            engineering.put("releaseId", task.getEngineeringReleaseId());
            engineering.put("versionNo", task.getEngineeringVersionNo());
            engineering.put("contentHash", task.getEngineeringContentHash());
            engineering.put("routeCode", task.getProcessRouteCode());
            engineering.put("bundleSnapshot", task.getEngineeringBundleSnapshot());
            if (task.getEngineeringReleaseId() == null || blank(task.getEngineeringContentHash())) {
                gaps.add("历史工单未保存正式工程版本证明，沿用原自维护引用");
            }
        }
        var documents = new ArrayList<Map<String, String>>();
        if (process != null) {
            String snapshot = process.getProcessRouteSnapshot();
            if (blank(snapshot)) {
                gaps.add("历史工序未保存工艺/SOP快照");
            } else {
                try {
                    require(snapshot.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024, "快照超出2MiB");
                    var nodes = JSONUtil.toList(snapshot, MdmProcessRouteWorkingDTO.class);
                    require(nodes != null && nodes.size() <= 1000, "快照节点范围无效");
                    var matched = nodes.stream().filter(Objects::nonNull).filter(n -> !blank(process.getSourceRouteNodeCode())
                            ? Objects.equals(n.getRouteNodeCode(), process.getSourceRouteNodeCode())
                            : Objects.equals(processCode(n), process.getRouteCode())).toList();
                    require(matched.size() == 1 && Objects.equals(processCode(matched.get(0)), process.getRouteCode()), "原节点不能唯一匹配");
                    require(blank(arrange.getSourceRouteNodeCode()) || Objects.equals(arrange.getSourceRouteNodeCode(), process.getSourceRouteNodeCode()), "派工稳定节点与原工序不同");
                    var node = matched.get(0);
                    var formal = node.getFormalSops();
                    if (formal != null) {
                        require(!blank(process.getSourceRouteNodeCode()), "正式SOP缺原稳定节点");
                        formalSops.validateFrozen(formal, scope.tenant().toString(), scope.factory(), process.getProcessRouteCode(), process.getSourceRouteNodeCode(), process.getRouteCode());
                        for (var source : formal.getSources()) {
                            var d = new LinkedHashMap<String, String>();
                            d.put("code", source.getSopNo());
                            d.put("name", source.getName());
                            d.put("version", Objects.toString(source.getVersionNo(), ""));
                            d.put("documentHash", source.getOriginalHash());
                            d.put("contentHash", source.getContentHash());
                            d.put("fileRef", source.getOriginalId());
                            d.put("sourceType", "FORMAL_SOP_VERSION");
                            d.put("sourceId", source.getVersionId());
                            documents.add(d);
                        }
                        gaps.addAll(formal.getGaps());
                    }
                    if (node.getMdmProcessProcedureSopList() != null) {
                        require(node.getMdmProcessProcedureSopList().size() <= 50, "旧附件超过50项");
                        for (var sop : node.getMdmProcessProcedureSopList()) {
                            require(sop != null && !blank(sop.getName()), "旧附件名称缺失");
                            var d = new LinkedHashMap<String, String>();
                            d.put("code", Objects.toString(sop.getId(), ""));
                            d.put("name", sop.getName());
                            d.put("fileName", Objects.toString(sop.getFileName(), ""));
                            d.put("fileRef", blank(sop.getUrl()) ? Objects.toString(sop.getFileName(), "") : sop.getUrl());
                            d.put("sourceType", "LEGACY_REFERENCE");
                            documents.add(d);
                        }
                    }
                } catch (RuntimeException failure) {
                    documents.clear();
                    gaps.add("原工艺/SOP快照损坏或节点归属不可核验，未猜测当前版本");
                }
            }
        }
        var result = new Detail().setTenantId(scope.tenant().toString()).setFactoryCode(scope.factory())
                .setObservedAt(now()).setArrange(arrange).setTask(task).setProduceTask(process)
                .setEngineering(engineering).setSopDocuments(documents).setDataGaps(gaps);
        // 不包含观察时间或说明列表，补充说明不会使原生产依据自动失效。
        result.setSourceFingerprint(sha(json(Arrays.asList("MES_DISPATCH_SOURCE_V1", scope.tenant(), scope.factory(), arrange, task, process, gaps))));
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> notes(String arrangeId, int current, int size) {
        var scope = context("read");
        Long id = id(arrangeId);
        read(scope, id);
        pageBounds(current, size);
        require(Boolean.TRUE.equals(mapper.ready()), "历史说明数据库保护尚未安装");
        var result = envelope(scope, false);
        result.put("current", current);
        result.put("size", size);
        result.put("total", mapper.noteTotal(scope.tenant(), scope.factory(), id));
        result.put("records", mapper.notes(scope.tenant(), scope.factory(), id, size, (long) (current - 1) * size));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> note(NoteCommand command) {
        var scope = context("write");
        validateNote(command);
        lock(scope, command.getRequestId());
        String hash = commandHash(scope, command);
        var old = mapper.noteRequest(scope.tenant(), scope.factory(), command.getRequestId());
        if (old != null) {
            checkReplay(old, scope, hash);
            return resolution(scope, command.getRequestId(), "APPLIED", old);
        }
        cancellations.assertActive(NOTE_KIND, command.getRequestId(), hash);
        var ownership = taskLock.arrange(id(command.getArrangeId()));
        var detail = read(scope, id(command.getArrangeId()));
        taskLock.assertArrange(ownership, detail.getArrange());
        require(Objects.equals(command.getExpectedSourceFingerprint(), detail.getSourceFingerprint()), "原派工依据已变化，请重读后以新请求号补充说明");
        var row = new MesDispatchHistoryNoteEntity();
        row.setId(IdWorker.getId());
        row.setTenantId(scope.tenant());
        row.setFactoryCode(scope.factory());
        row.setArrangeId(detail.getArrange().getId());
        row.setArrangeCode(detail.getArrange().getCode());
        row.setTaskCode(detail.getArrange().getTaskCode());
        row.setProduceTaskCode(detail.getArrange().getProduceTaskCode());
        row.setRequestId(command.getRequestId());
        row.setRequestHash(hash);
        row.setActorId(scope.actor());
        row.setActorCode(scope.actorCode());
        row.setNote(command.getNote());
        row.setSourceFingerprint(detail.getSourceFingerprint());
        row.setSourceSnapshot(json(detail));
        row.setCreateTime(now());
        row.setDelFlag("0");
        require(mapper.insertNote(row) == 1, "补充说明未完整保存");
        var persisted = mapper.noteRequest(scope.tenant(), scope.factory(), command.getRequestId());
        require(row.equals(persisted), "说明原文或实际归属回读不一致，已回滚");
        return resolution(scope, command.getRequestId(), "APPLIED", persisted);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> noteReceipt(String requestId) {
        var scope = context("read");
        request(requestId);
        require(Boolean.TRUE.equals(mapper.ready()), "历史说明数据库保护尚未安装");
        var old = mapper.noteRequest(scope.tenant(), scope.factory(), requestId);
        if (old != null && Objects.equals(old.getActorId(), scope.actor())) {
            return resolution(scope, requestId, "APPLIED", old);
        }
        return resolution(scope, requestId, cancellations.cancelled(NOTE_KIND, requestId) ? "CANCELLED" : "ABSENT", null);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelNote(NoteCommand command) {
        var scope = context("write");
        validateNote(command);
        lock(scope, command.getRequestId());
        String hash = commandHash(scope, command);
        var old = mapper.noteRequest(scope.tenant(), scope.factory(), command.getRequestId());
        if (old != null) {
            checkReplay(old, scope, hash);
            return resolution(scope, command.getRequestId(), "APPLIED", old);
        }
        cancellations.cancel(NOTE_KIND, command.getRequestId(), hash);
        return resolution(scope, command.getRequestId(), "CANCELLED", null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> printTemplate(String templateId) {
        var scope = context("read");
        return layoutEnvelope(scope, layout(scope, id(templateId)));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> printPreview(PrintCommand command) {
        var scope = context("read");
        require(command != null, "缺少打印请求");
        hash(command.getExpectedSourceFingerprint());
        hash(command.getExpectedTemplateFingerprint());
        var detail = read(scope, id(command.getArrangeId()));
        require(Objects.equals(command.getExpectedSourceFingerprint(), detail.getSourceFingerprint()), "原派工依据已变化，请重读并重新预览");
        var layout = layout(scope, id(command.getTemplateId()));
        String fingerprint = PrintTemplateFingerprint.of(layout);
        require(Objects.equals(command.getExpectedTemplateFingerprint(), fingerprint), "打印布局已变化，请重新选择并预览");
        var request = new PreviewObj();
        request.setTemplateCode(layout.getCode());
        request.setExpectedTemplateId(layout.getId().toString());
        request.setExpectedTemplateFingerprint(fingerprint);
        request.setExpectedBusinessTypeCode("MES_DISPATCH");
        request.setJsonData(json(detail));
        var rendered = prints.preview(request);
        require(rendered != null && rendered.isOk() && !blank(rendered.getData()), "打印服务未返回有效预览，请核对模板或恢复服务后重试");
        var result = layoutEnvelope(scope, layout);
        result.put("arrangeId", detail.getArrange().getId().toString());
        result.put("sourceFingerprint", detail.getSourceFingerprint());
        result.put("generatedAt", now());
        result.put("html", rendered.getData());
        return result;
    }

    private PrintTemplateEntity layout(Context scope, Long id) {
        var response = templates.getTemplateById(id);
        require(response != null && response.isOk() && response.getData() != null, "未找到打印模板，请维护启用的MES_DISPATCH派工模板");
        PrintTemplateEntity template;
        try {
            template = JSON.convertValue(response.getData(), PrintTemplateEntity.class);
        } catch (IllegalArgumentException failure) {
            throw new CheckedException("打印模板返回格式不可核验");
        }
        require(Objects.equals(template.getId(), id) && Objects.equals(template.getTenantId(), scope.tenant())
                && Objects.equals(template.getFactoryCode(), scope.factory()) && Boolean.TRUE.equals(template.getStatus())
                && "0".equals(template.getDelFlag()) && "MES_DISPATCH".equals(template.getBusinessTypeCode())
                && !blank(template.getCode()) && !blank(template.getContent()), "打印模板须属于实际租户工厂、启用且业务类型为MES_DISPATCH");
        return template;
    }

    private Map<String, Object> layoutEnvelope(Context scope, PrintTemplateEntity layout) {
        var result = envelope(scope, true);
        result.put("templateId", layout.getId().toString());
        result.put("templateCode", layout.getCode());
        result.put("templateName", layout.getName());
        result.put("templateFingerprint", PrintTemplateFingerprint.of(layout));
        return result;
    }

    private Query normalize(Query source) {
        require(source != null, "缺少查询条件");
        var q = new Query().setTaskCode(code(source.getTaskCode())).setProduceTaskCode(code(source.getProduceTaskCode()))
                .setArrangeCode(code(source.getArrangeCode())).setArrangeId(code(source.getArrangeId()))
                .setWorkshopCode(code(source.getWorkshopCode())).setWorksiteCode(member(source.getWorksiteCode()))
                .setWorkerCode(member(source.getWorkerCode())).setShiftCode(code(source.getShiftCode())).setStatus(code(source.getStatus()))
                .setExecutionTemplateCode(code(source.getExecutionTemplateCode())).setExecutionTemplateVersion(source.getExecutionTemplateVersion())
                .setDateBasis(Objects.requireNonNullElse(source.getDateBasis(), "DISPATCH"))
                .setStartTime(source.getStartTime()).setEndTime(source.getEndTime())
                .setCurrent(Objects.requireNonNullElse(source.getCurrent(), 1)).setSize(Objects.requireNonNullElse(source.getSize(), 20));
        pageBounds(q.getCurrent(), q.getSize());
        require(Set.of("DISPATCH", "START", "FINISH").contains(q.getDateBasis()), "不支持的日期依据");
        if (q.getArrangeId() != null) {
            id(q.getArrangeId());
        }
        require(q.getExecutionTemplateVersion() == null || q.getExecutionTemplateVersion() > 0, "执行模板版本须为正整数");
        boolean exact = q.getTaskCode() != null || q.getProduceTaskCode() != null || q.getArrangeCode() != null || q.getArrangeId() != null;
        require((q.getStartTime() == null) == (q.getEndTime() == null), "起止时间须成对提供");
        require(exact || q.getStartTime() != null, "全工厂历史查询须提供最多31天起止范围，或指定精确工单/工序/派工");
        if (q.getStartTime() != null) {
            require(q.getEndTime().isAfter(q.getStartTime()) && Duration.between(q.getStartTime(), q.getEndTime())
                    .compareTo(Duration.ofDays(exact ? 366 : 31)) <= 0, "历史查询日期范围无效或过大");
        }
        return q;
    }

    private void lock(Context scope, String request) {
        require(Boolean.TRUE.equals(mapper.ready()), "历史说明数据库保护尚未安装");
        mapper.lockRequest(scope.tenant() + ":" + scope.factory() + ":" + request);
    }

    private void validateNote(NoteCommand command) {
        require(command != null, "缺少原补充说明请求");
        request(command.getRequestId());
        id(command.getArrangeId());
        hash(command.getExpectedSourceFingerprint());
        require(command.getNote() != null && !command.getNote().isBlank() && command.getNote().length() <= 1000
                && command.getNote().chars().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'), "补充说明须为1至1000字有效原文");
    }

    private void checkReplay(MesDispatchHistoryNoteEntity row, Context scope, String hash) {
        require(Objects.equals(row.getTenantId(), scope.tenant()) && Objects.equals(row.getFactoryCode(), scope.factory())
                && Objects.equals(row.getActorId(), scope.actor()) && Objects.equals(row.getRequestHash(), hash), "原请求号已属于其他操作者或不同说明，禁止覆盖");
    }

    private String commandHash(Context scope, NoteCommand command) {
        return sha(json(Arrays.asList(scope.tenant(), scope.factory(), scope.actor(), command)));
    }

    private Map<String, Object> resolution(Context scope, String request, String state, MesDispatchHistoryNoteEntity row) {
        var result = envelope(scope, false);
        result.put("requestId", request);
        result.put("state", state);
        if (row != null) {
            result.put("result", row);
        }
        return result;
    }

    private Map<String, Object> envelope(Context scope, boolean print) {
        var result = new LinkedHashMap<String, Object>();
        result.put("contractVersion", print ? "MES_DISPATCH_PRINT_V1" : "MES_DISPATCH_HISTORY_V1");
        result.put("tenantId", scope.tenant().toString());
        result.put("factoryCode", scope.factory());
        result.put("observedAt", now());
        return result;
    }

    private Context context(String mode) {
        String factory = access.require(mode);
        Long tenant = TenantContextHolder.getTenantId();
        var user = SecurityUtils.getUser();
        require(tenant != null && tenant > 0 && user != null && user.getId() != null && user.getId() > 0
                && !blank(factory) && !blank(user.getUsername()), "历史查询缺少实际组织或实名操作者");
        return new Context(tenant, factory, user.getId(), user.getUsername());
    }

    private record Context(Long tenant, String factory, Long actor, String actorCode) {
    }
}
