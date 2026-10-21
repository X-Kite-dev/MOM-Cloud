package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.mom.mes.dto.MesPhysicalTransformCommandDTO;
import com.kitex.mom.mes.dto.MesPhysicalTransformDTO;
import com.kitex.mom.mes.dto.MesPhysicalTransformSourceBundleDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/** 占用入口严格解析与服务器冻结正文；不把客户端观察包直接变成永久占用。 */
@Component
@RequiredArgsConstructor
public class MesPhysicalTransformSourceHoldCodec {
    public static final String VERSION = "MES_PHYSICAL_SOURCE_HOLD_V1";
    /** 完整外层原字符串的UTF-16容量；超界拒绝，不能截断来源图。 */
    public static final int MAX_SNAPSHOT = 64 * 1024 * 1024;
    /** 身份全集硬边界；不以分页/首项代替实际完整来源。 */
    public static final int MAX_IDENTITIES = 100000;
    private static final Set<String> TYPES = Set.of("STOCK_ID", "REPORT_ID", "IDENTIFIER", "TASK_CODE",
            "PRODUCE_TASK_CODE", "ARRANGE_ID", "ARRANGE_CODE");
    private final MesPhysicalTransformCodec codec;
    private final MesPhysicalTransformPlanner planner;

    /** DEPANEL占用阶段唯一允许保留的待决项；正式执行仍必须绑定异人确认的位置原账。 */
    private static final String POSITION_PENDING = "当前尚无永久母件实际位置及合格/报废/缺位余额账，批准布局不能代替实物位置";

    /** 恰好一个文本SHA，拒绝reason/来源包/资格标记或number隐式强转。 */
    public String acquireFingerprint(String raw) {
        var root = exact(raw, Set.of("expectedFingerprint"));
        return fingerprint(text(root, "expectedFingerprint"));
    }

    /** 取消必须原hold SHA及明确原因，不复用原command seal或客户端状态。 */
    public Cancel cancel(String raw) {
        var root = exact(raw, Set.of("expectedHoldFingerprint", "reason"));
        String reason = text(root, "reason");
        codec.reason(reason);
        return new Cancel(fingerprint(text(root, "expectedHoldFingerprint")), reason);
    }

    /** 本方法只消费服务器在真实原号/来源锁内取得的包，并沿原真实政策核全部当量。 */
    public Frozen freeze(String commandId, String requestId, String commandFingerprint,
                         MesPhysicalTransformCommandDTO.BodyV2 body, MesPhysicalTransformSourceBundleDTO bundle) {
        require(body.getExpectedTenantId().equals(bundle.getTenantId())
                && body.getExpectedFactoryCode().equals(bundle.getFactoryCode())
                && body.getExpectedActorUserId().equals(bundle.getActorUserId()), "永久原文与服务器当前实际owner不一致");
        require(bundle.getSources().size() == body.getInputs().size(), "服务器完整来源与原投入集合不一致");
        String configuration = null;
        for (var input : body.getInputs()) {
            var entries = bundle.getSources().stream().filter(row -> input.getStockId().equals(row.getStockId())).toList();
            require(entries.size() == 1, "原投入必须恰好对应一条服务器精确来源");
            var source = entries.get(0);
            require(MesPhysicalTransformSourceFreezeService.SOURCE_VERSION.equals(source.getSourceVersion())
                    && input.getExpectedFingerprint().equals(source.getSourceFingerprint()), "原V2来源指纹已改变，请保留原号核对，不重写旧观察");
            require(source.getPolicyConfiguration() != null && !MesPhysicalTransformPolicyValidator.NONE.equals(source.getPolicyConfiguration()),
                    "原派工缺真实转换政策，禁止补当前模板");
            var policy = codec.policy(source.getPolicyConfiguration());
            boolean positionPendingOnly = "DEPANEL".equals(policy.getOperationType())
                    && source.getBlockingReasons().size() == 1 && POSITION_PENDING.equals(source.getBlockingReasons().get(0));
            require(Boolean.TRUE.equals(source.getSourceChecksPassed()) && source.getBlockingReasons().isEmpty() || positionPendingOnly,
                    "原来源/工程已知检查未通过：" + String.join("；", source.getBlockingReasons()));
            if (configuration == null) {
                configuration = source.getPolicyConfiguration();
            } else {
                require(configuration.equals(source.getPolicyConfiguration()), "多来源原工程政策不同，不能猜首个比例汇合");
            }
        }
        var plan = new MesPhysicalTransformDTO.Plan();
        plan.setExpectedTenantId(body.getExpectedTenantId());
        plan.setExpectedFactoryCode(body.getExpectedFactoryCode());
        plan.setExpectedActorUserId(body.getExpectedActorUserId());
        plan.setPolicyDraft(configuration);
        plan.setInputs(body.getInputs());
        plan.setOutputs(body.getOutputs());
        plan.setAllocations(body.getAllocations());
        plan.setReason(body.getReason());
        // 这里只把真实冻结政策适配给原纯数学计算，不把它称为policyDraft发布权限。
        var calculation = planner.calculate(plan, codec.policy(configuration), bundle.getSources().stream().map(row -> row.getSource()).toList());
        String calculationSnapshot = codec.snapshot(calculation);
        codec.objectDocument(calculationSnapshot, 16 * 1024 * 1024);
        var identities = identities(bundle);
        var evidence = new TreeMap<String, Object>();
        evidence.put("version", VERSION);
        evidence.put("tenantId", bundle.getTenantId());
        evidence.put("factoryCode", bundle.getFactoryCode());
        evidence.put("actorUserId", bundle.getActorUserId());
        evidence.put("actorCode", bundle.getActorCode());
        evidence.put("commandId", commandId);
        evidence.put("requestId", requestId);
        evidence.put("commandFingerprint", commandFingerprint);
        evidence.put("sourceBundleSnapshot", bundle.getBundleSnapshot());
        evidence.put("sourceBundleFingerprint", bundle.getBundleFingerprint());
        evidence.put("calculationSnapshot", calculationSnapshot);
        evidence.put("identities", identities);
        String snapshot = codec.snapshot(evidence);
        codec.objectDocument(snapshot, MAX_SNAPSHOT);
        return new Frozen(snapshot, codec.fingerprint(VERSION, snapshot), identities);
    }

    /** 原冻结完整身份集合，恢复只验原SHA/结构，不查询新模板或重新计算原来源。 */
    public List<List<String>> storedIdentities(String snapshot, String sha) {
        require(snapshot != null && sha != null && sha.equals(codec.fingerprint(VERSION, snapshot)), "永久来源冻结全文SHA损坏，禁止猜恢复结果");
        var root = codec.objectDocument(snapshot, MAX_SNAPSHOT);
        var fields = new TreeSet<String>();
        root.fieldNames().forEachRemaining(fields::add);
        require(fields.equals(Set.of("version", "tenantId", "factoryCode", "actorUserId", "actorCode", "commandId",
                "requestId", "commandFingerprint", "sourceBundleSnapshot", "sourceBundleFingerprint", "calculationSnapshot", "identities")),
                "永久冻结正文存在缺失或未知字段");
        require(VERSION.equals(text(root, "version")) && root.path("identities").isArray(), "永久占用版本/身份集合损坏");
        var bundle = codec.objectDocument(text(root, "sourceBundleSnapshot"), MAX_SNAPSHOT);
        require(text(root, "sourceBundleFingerprint").equals(codec.fingerprint(MesPhysicalTransformSourceFreezeService.BUNDLE_VERSION,
                text(root, "sourceBundleSnapshot"))) && MesPhysicalTransformSourceFreezeService.BUNDLE_VERSION.equals(text(bundle, "version")),
                "永久来源包原算法/全文SHA不一致");
        for (String field : List.of("tenantId", "factoryCode", "actorUserId", "actorCode")) {
            require(text(root, field).equals(text(bundle, field)), "永久占用与原来源包owner不一致");
        }
        codec.objectDocument(text(root, "calculationSnapshot"), 16 * 1024 * 1024);
        require(root.get("identities").size() > 0 && root.get("identities").size() <= MAX_IDENTITIES, "永久占用身份集合超界或为空");
        var result = new ArrayList<List<String>>();
        var unique = new TreeSet<String>();
        for (var pair : root.get("identities")) {
            require(pair.isArray() && pair.size() == 2 && pair.get(0).isTextual() && pair.get(1).isTextual(), "永久typed identity须完整二文本字段");
            String type = pair.get(0).textValue();
            String key = pair.get(1).textValue();
            identity(type, key);
            require(unique.add(codec.snapshot(List.of(type, key))), "永久来源身份集合重复");
            result.add(List.of(type, key));
        }
        return List.copyOf(result);
    }

    private List<List<String>> identities(MesPhysicalTransformSourceBundleDTO bundle) {
        var keys = new TreeSet<String>();
        var values = new TreeMap<String, List<String>>();
        for (var entry : bundle.getSources()) {
            var source = codec.objectDocument(entry.getSourceSnapshot(), 16 * 1024 * 1024);
            var origin = codec.objectDocument(text(source, "originSnapshot"), 16 * 1024 * 1024);
            add(values, keys, "STOCK_ID", entry.getStockId());
            for (String list : List.of("peerStocks", "originStocks")) {
                for (var stock : origin.path(list)) {
                    add(values, keys, "STOCK_ID", stock.path("id").textValue());
                }
            }
            add(values, keys, "REPORT_ID", source.path("report").path("id").textValue());
            add(values, keys, "ARRANGE_ID", source.path("arrange").path("id").textValue());
            add(values, keys, "ARRANGE_CODE", source.path("arrange").path("code").textValue());
            add(values, keys, "TASK_CODE", source.path("task").path("code").textValue());
            for (var node : source.path("completeTaskGraph")) {
                add(values, keys, "PRODUCE_TASK_CODE", node.path("code").textValue());
            }
            add(values, keys, "IDENTIFIER", entry.getSource().getIdentifier());
            add(values, keys, "IDENTIFIER", source.path("report").path("transferCode").textValue());
        }
        require(!values.isEmpty() && values.size() <= MAX_IDENTITIES, "实际来源身份全集超界，拒绝截断");
        return List.copyOf(values.values());
    }

    private void add(TreeMap<String, List<String>> values, Set<String> keys, String type, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        identity(type, key);
        String literal = codec.snapshot(List.of(type, key));
        if (keys.add(literal)) {
            values.put(literal, List.of(type, key));
        }
    }

    private void identity(String type, String key) {
        require(TYPES.contains(type), "未知来源身份类型");
        if (type.endsWith("_ID")) {
            codec.id(key);
        } else {
            codec.code(key, "原来源身份", 100);
        }
    }

    private JsonNode exact(String raw, Set<String> fields) {
        var root = codec.objectDocument(raw, 16 * 1024);
        var found = new TreeSet<String>();
        root.fieldNames().forEachRemaining(found::add);
        require(found.equals(fields), "来源占用/取消正文存在缺失或未知字段");
        return root;
    }

    private String text(JsonNode node, String field) {
        require(node.path(field).isTextual(), field + "必须是文本，不能隐式强转");
        return node.get(field).textValue();
    }

    private String fingerprint(String value) {
        require(value.matches("[0-9a-f]{64}"), "原指纹必须是SHA-256文本");
        return value;
    }

    /** 严格取消原SHA和原原因，不设置客户端owner。 */
    public record Cancel(String fingerprint, String reason) {
    }

    /** 服务器生成的完整冻结原文及身份，不是客户端提交DTO。 */
    public record Frozen(String snapshot, String fingerprint, List<List<String>> identities) {
    }
}
