package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.dto.MesMaterialBindingSupplementDTO.Pool;
import com.kitex.mom.mes.entity.MesMaterialBindingSupplementEntity;
import com.kitex.mom.mes.entity.MesStartMaterialBindingEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesMaterialBindingSupplementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 无生命周期服务依赖的完整哈希链读取；缺保护、重复、分叉或损坏均拒绝降级。
 */
@Component
@RequiredArgsConstructor
public class MesMaterialBindingPoolReader {
    private static final Set<String> SOURCE_KEYS = Set.of("feedId", "birthId", "birthFingerprint",
            "requirementMaterialCode", "actualMaterialCode", "batchNo", "barcode", "unit", "ratio", "feedQuantity", "usedQuantity");
    private static final Set<String> DOCUMENT_KEYS = Set.of("tenantId", "factoryCode", "actorId", "arrangeId", "arrangeCode",
            "taskCode", "produceTaskCode", "routeNodeCode", "productBatchNo", "routeSnapshot", "bomSnapshot", "remainingQuantity",
            "productionUnit", "selectedSources", "selectedSourceStates", "existingSources", "initialBindingId", "sequenceNo", "previousFingerprint",
            "expectedPoolFingerprint", "contractVersion", "command", "commandJson", "previewFingerprint", "confirmedAt");
    private final MesMaterialBindingSupplementMapper mapper;
    private final MesMaterialBindingSupplementCodec codec;
    private final MesStartMaterialBindingCodec initialCodec;
    private final MesPhysicalTransformCodec physical;

    public Pool pool(MesStartMaterialBindingEntity initial, MesTaskArrangeEntity arrange) {
        require(initial != null && arrange != null && Objects.equals(initial.getArrangeId(), arrange.getId())
                && Objects.equals(initial.getTenantId(), arrange.getTenantId()) && Objects.equals(initial.getFactoryCode(), arrange.getFactoryCode())
                && Objects.equals(initial.getArrangeCode(), arrange.getCode()) && Objects.equals(initial.getTaskCode(), arrange.getTaskCode())
                && Objects.equals(initial.getProduceTaskCode(), arrange.getProduceTaskCode())
                && Objects.equals(initial.getRouteNodeCode(), arrange.getSourceRouteNodeCode())
                && Objects.equals(initial.getProductBatchNo(), arrange.getBatchNo())
                && Objects.equals(initial.getStartedAt(), arrange.getStartTime()), "初始绑定与实际批次、派工或开工时刻不同");
        require(Boolean.TRUE.equals(mapper.ready()), "追加来源池目录保护未就绪，禁止退回初始池");
        var root = initialCodec.document(initial.getBindingJson(), initial.getBindingFingerprint());
        var initialCommand = initialCodec.command((String) root.get("commandJson"));
        var sources = sources(root.get("selectedSources"), initialCommand.getFeedIds(), false);
        var rows = mapper.byArrange(initial.getTenantId(), initial.getFactoryCode(), initial.getArrangeId());
        require(rows != null && rows.size() <= 50, "追加历史缺失或超过50次，禁止截断");
        String previous = initial.getBindingFingerprint();
        var history = new ArrayList<Map<String, Object>>();
        var requests = new HashSet<String>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            require(row != null && Objects.equals(row.getInitialBindingId(), initial.getId())
                    && Objects.equals(row.getSequenceNo(), index + 1) && Objects.equals(row.getPreviousFingerprint(), previous), "追加链初始根、序号或前驱摘要损坏");
            var document = verified(row);
            require(Objects.equals(row.getTenantId(), initial.getTenantId()) && Objects.equals(row.getFactoryCode(), initial.getFactoryCode())
                    && Objects.equals(row.getArrangeId(), initial.getArrangeId()) && Objects.equals(row.getArrangeCode(), initial.getArrangeCode())
                    && Objects.equals(row.getTaskCode(), initial.getTaskCode()) && Objects.equals(row.getProduceTaskCode(), initial.getProduceTaskCode())
                    && Objects.equals(row.getRouteNodeCode(), initial.getRouteNodeCode()) && Objects.equals(row.getProductBatchNo(), initial.getProductBatchNo())
                    && Objects.equals(document.get("routeSnapshot"), root.get("routeSnapshot"))
                    && Objects.equals(document.get("bomSnapshot"), root.get("bomSnapshot")) && requests.add(row.getRequestId()), "追加历史跨组织、批次、工程或重复原请求");
            var existing = sources(document.get("existingSources"), sources.keySet().stream().toList(), true);
            for (var entry : sources.entrySet()) {
                assertIdentity(entry.getValue(), existing.get(entry.getKey()));
            }
            var command = codec.command((String) document.get("commandJson"));
            var additions = sources(document.get("selectedSources"), command.getAddedFeedIds(), false);
            for (var entry : additions.entrySet()) {
                require(sources.putIfAbsent(entry.getKey(), entry.getValue()) == null, "追加来源已属于累计池，禁止重复加入");
            }
            require(sources.size() <= 100, "累计来源池超过100，禁止截断");
            previous = row.getSupplementFingerprint();
            history.add(Collections.unmodifiableMap(document));
        }
        var ids = sources.keySet().stream().map(Long::valueOf).sorted().map(Object::toString).toList();
        return new Pool(initial.getId().toString(), initial.getBindingFingerprint(), Integer.toString(rows.size()), previous, ids, List.copyOf(history));
    }

    public Map<String, Object> verified(MesMaterialBindingSupplementEntity row) {
        require(row != null && row.getId() != null && row.getId() > 0 && row.getTenantId() != null
                && row.getInitialBindingId() != null && row.getOperatorId() != null && row.getConfirmedAt() != null
                && row.getSequenceNo() != null && row.getSequenceNo() > 0 && row.getSequenceNo() <= 50
                && "0".equals(row.getDelFlag()), "追加原账归属或永久状态损坏");
        var document = codec.document(row.getSupplementJson(), row.getSupplementFingerprint());
        require(document.keySet().equals(DOCUMENT_KEYS), "追加正文缺少精确字段或存在未知字段");
        var command = codec.command((String) document.get("commandJson"));
        require(Objects.equals(row.getRequestHash(), codec.requestHash(command))
                && Objects.equals(row.getRequestId(), command.getRequestId())
                && Objects.equals(row.getArrangeId().toString(), command.getArrangeId())
                && Objects.equals(row.getTenantId().toString(), command.getExpectedTenantId())
                && Objects.equals(row.getFactoryCode(), command.getExpectedFactoryCode())
                && Objects.equals(row.getOperatorId().toString(), command.getExpectedActorId())
                && Objects.equals(row.getPreviousFingerprint(), command.getExpectedPoolFingerprint())
                && Objects.equals(row.getPreviewFingerprint(), command.getExpectedPreviewFingerprint())
                && Objects.equals(physical.snapshot(command), physical.snapshot(document.get("command")))
                && Objects.equals(physical.snapshot(command), document.get("commandJson")), "追加原命令与永久账身份或内容不同");
        var expected = new LinkedHashMap<String, Object>();
        expected.put("tenantId", row.getTenantId().toString());
        expected.put("factoryCode", row.getFactoryCode());
        expected.put("actorId", row.getOperatorId().toString());
        expected.put("arrangeId", row.getArrangeId().toString());
        expected.put("arrangeCode", row.getArrangeCode());
        expected.put("taskCode", row.getTaskCode());
        expected.put("produceTaskCode", row.getProduceTaskCode());
        expected.put("routeNodeCode", row.getRouteNodeCode());
        expected.put("productBatchNo", row.getProductBatchNo());
        expected.put("initialBindingId", row.getInitialBindingId().toString());
        expected.put("sequenceNo", row.getSequenceNo().toString());
        expected.put("previousFingerprint", row.getPreviousFingerprint());
        expected.put("expectedPoolFingerprint", row.getPreviousFingerprint());
        expected.put("previewFingerprint", row.getPreviewFingerprint());
        expected.put("confirmedAt", row.getConfirmedAt().toString());
        require(expected.entrySet().stream().allMatch(e -> Objects.equals(e.getValue(), document.get(e.getKey()))), "追加正文与永久账原身份不同");
        for (String key : List.of("routeSnapshot", "remainingQuantity", "productionUnit")) {
            require(document.get(key) instanceof String, "追加工程、原数量或单位必须完整文本");
        }
        require(document.get("bomSnapshot") == null || document.get("bomSnapshot") instanceof String, "BOM原快照必须为原文本或明确空值");
        physical.quantity((String) document.get("remainingQuantity"), false, "追加时原剩余量");
        var proof = new LinkedHashMap<>(document);
        for (String key : List.of("contractVersion", "command", "commandJson", "previewFingerprint", "confirmedAt")) {
            proof.remove(key);
        }
        require(Objects.equals(row.getPreviewFingerprint(),
                physical.fingerprint(MesMaterialBindingSupplementCodec.PREVIEW, physical.snapshot(proof))), "追加完整预览证据摘要损坏");
        sources(document.get("selectedSources"), command.getAddedFeedIds(), false);
        states(document.get("selectedSourceStates"), command.getAddedFeedIds(), row.getConfirmedAt());
        return document;
    }

    private void states(Object value, List<String> ids, LocalDateTime confirmedAt) {
        require(value instanceof List<?> && ((List<?>) value).size() == ids.size(), "新增来源资格快照缺失");
        var values = (List<?>) value;
        for (int index = 0; index < values.size(); index++) {
            require(values.get(index) instanceof Map<?, ?>, "新增来源资格不是完整对象");
            var state = (Map<?, ?>) values.get(index);
            require(state.keySet().equals(Set.of("feedId", "isLocked", "validEndDate", "eligible"))
                    && Objects.equals(ids.get(index), state.get("feedId")) && Boolean.FALSE.equals(state.get("isLocked"))
                    && Boolean.TRUE.equals(state.get("eligible")), "新增来源资格不完整或不可用");
            if (state.get("validEndDate") != null) {
                require(state.get("validEndDate") instanceof String, "原效期不是精确文本");
                require(!java.time.LocalDate.parse((String) state.get("validEndDate")).isBefore(confirmedAt.toLocalDate()), "新增来源在原确认时已过期");
            }
        }
    }

    /**
     * 比较永久成员身份，允许已用量、质量锁和效期状态随实际执行变化。
     */
    public void assertIdentity(Map<String, Object> original, Map<String, Object> current) {
        require(original != null && current != null && SOURCE_KEYS.stream().filter(key -> !"usedQuantity".equals(key))
                .allMatch(key -> Objects.equals(original.get(key), current.get(key))), "累计来源原身份、出生、单位或总量被改写");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> sources(Object value, List<String> expected, boolean existing) {
        require(value instanceof List<?> && ((List<?>) value).size() == expected.size() && expected.size() <= 100, "来源全集缺失、超限或不完整");
        var result = new TreeMap<String, Map<String, Object>>(Comparator.comparingLong(Long::parseLong));
        var keys = new HashSet<>(SOURCE_KEYS);
        if (existing) {
            keys.addAll(Set.of("isLocked", "validEndDate", "eligible"));
        }
        for (Object item : (List<?>) value) {
            require(item instanceof Map<?, ?>, "来源原身份不是完整对象");
            var source = (Map<String, Object>) item;
            require(source.keySet().equals(keys), "来源字段缺失或包含未知字段");
            for (String key : SOURCE_KEYS) {
                require(source.get(key) instanceof String || (("batchNo".equals(key) || "barcode".equals(key)) && source.get(key) == null), "来源身份或原数量不是精确文本");
            }
            String id = (String) source.get("feedId");
            physical.id(id);
            physical.id((String) source.get("birthId"));
            codec.hash((String) source.get("birthFingerprint"));
            var total = physical.quantity((String) source.get("feedQuantity"), false, "原投料量");
            var used = physical.quantity((String) source.get("usedQuantity"), true, "实际已用量");
            physical.quantity((String) source.get("ratio"), false, "替代比例");
            require(total.compareTo(used) >= 0 && (existing || total.compareTo(used) > 0), "来源余额无效或新增来源已耗尽");
            if (existing) {
                require(source.get("isLocked") instanceof Boolean && source.get("eligible") instanceof Boolean
                        && (source.get("validEndDate") == null || source.get("validEndDate") instanceof String), "旧来源资格快照不完整");
            }
            require(result.put(id, source) == null, "来源全集存在重复成员");
        }
        require(result.keySet().stream().toList().equals(expected), "来源全集与原命令或前池不一致");
        return result;
    }
}
