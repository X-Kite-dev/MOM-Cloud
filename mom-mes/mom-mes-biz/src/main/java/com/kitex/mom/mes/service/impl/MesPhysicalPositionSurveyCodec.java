package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.mom.mes.dto.MesPhysicalPositionSurveyDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 独立位置采集原文严格解析：不改变旧SOURCE/COMMAND/Planner版本或SHA。
 * 拒绝隐式number转文本、重复/未知/遗漏字段、尾随JSON及全集超界。
 */
@Component
@RequiredArgsConstructor
public class MesPhysicalPositionSurveyCodec {
    public static final String VERSION = "MES_PHYSICAL_POSITION_SURVEY_V1";
    public static final String EVIDENCE_VERSION = "MES_PHYSICAL_POSITION_SURVEY_EVIDENCE_V1";
    public static final String DECISION_VERSION = "MES_PHYSICAL_POSITION_DECISION_V1";
    public static final int MAX_DOCUMENT = 16 * 1024 * 1024;
    public static final int MAX_EVIDENCE = 64 * 1024 * 1024;
    private final MesPhysicalTransformCodec codec;

    /** 原采集正文恰好八字段；母件码、工程、质量均由服务器沿原来源核定。 */
    public MesPhysicalPositionSurveyDTO.Body body(String raw) {
        var root = codec.objectDocument(raw, MAX_DOCUMENT);
        fields(root, Set.of("version", "expectedTenantId", "expectedFactoryCode", "expectedActorUserId",
                "expectedSourceVersion", "sources", "positions", "reason"));
        require(VERSION.equals(text(root, "version")), "位置采集原版本未知，不能回落旧转换命令");
        require(MesPhysicalTransformSourceFreezeService.SOURCE_VERSION.equals(text(root, "expectedSourceVersion")),
                "位置采集须明确完整V2来源版本");
        var result = new MesPhysicalPositionSurveyDTO.Body();
        result.setVersion(VERSION);
        result.setExpectedSourceVersion(text(root, "expectedSourceVersion"));
        result.setExpectedTenantId(text(root, "expectedTenantId"));
        result.setExpectedFactoryCode(text(root, "expectedFactoryCode"));
        result.setExpectedActorUserId(text(root, "expectedActorUserId"));
        result.setReason(text(root, "reason"));
        codec.id(result.getExpectedTenantId());
        codec.id(result.getExpectedActorUserId());
        codec.code(result.getExpectedFactoryCode(), "观察工厂", 100);
        codec.reason(result.getReason());

        array(root, "sources", 1, MesPhysicalTransformCodec.MAX_SOURCES);
        var sources = new ArrayList<MesPhysicalPositionSurveyDTO.Source>();
        var sourceIds = new HashSet<String>();
        for (var node : root.get("sources")) {
            fields(node, Set.of("stockId", "expectedFingerprint"));
            var source = new MesPhysicalPositionSurveyDTO.Source();
            source.setStockId(text(node, "stockId"));
            source.setExpectedFingerprint(text(node, "expectedFingerprint"));
            codec.id(source.getStockId());
            require(sourceIds.add(source.getStockId()), "位置采集精确库存来源重复");
            require(source.getExpectedFingerprint().matches("[0-9a-f]{64}"), "原来源SHA须为64位小写文本");
            sources.add(source);
        }
        result.setSources(sources);

        array(root, "positions", 1, MesPhysicalTransformCodec.MAX_OUTPUTS);
        var slots = new ArrayList<MesPhysicalPositionSurveyDTO.Slot>();
        var positionCodes = new HashSet<String>();
        int edgeCount = 0;
        for (var node : root.get("positions")) {
            fields(node, Set.of("positionCode", "presence", "allocations"));
            var slot = new MesPhysicalPositionSurveyDTO.Slot();
            slot.setPositionCode(text(node, "positionCode"));
            slot.setPresence(text(node, "presence"));
            codec.code(slot.getPositionCode(), "实物位置", 100);
            require(positionCodes.add(slot.getPositionCode()), "实际位置不能重复");
            require(Set.of("STOCKED", "UNSTOCKED", "MISSING", "UNKNOWN").contains(slot.getPresence()), "位置存在状态未明确");
            array(node, "allocations", 0, MesPhysicalTransformCodec.MAX_SOURCES);
            var edges = new ArrayList<MesPhysicalPositionSurveyDTO.Allocation>();
            var allocatedStocks = new HashSet<String>();
            for (var edgeNode : node.get("allocations")) {
                fields(edgeNode, Set.of("stockId", "quantity"));
                var edge = new MesPhysicalPositionSurveyDTO.Allocation();
                edge.setStockId(text(edgeNode, "stockId"));
                edge.setQuantity(text(edgeNode, "quantity"));
                codec.id(edge.getStockId());
                require(sourceIds.contains(edge.getStockId()) && allocatedStocks.add(edge.getStockId()), "位置分配引用范围外或重复库存");
                codec.quantity(edge.getQuantity(), false, "位置来源当量");
                edges.add(edge);
            }
            edgeCount += edges.size();
            require(edgeCount <= MesPhysicalTransformCodec.MAX_ALLOCATIONS, "位置来源分配全集超界，不能截断");
            require("STOCKED".equals(slot.getPresence()) ? !edges.isEmpty() : edges.isEmpty(),
                    "有库存位须有真实分配；未库存化/缺位/未知位不得借库存生成实物");
            slot.setAllocations(edges);
            slots.add(slot);
        }
        result.setPositions(slots);
        return result;
    }

    /** 九字段原决策；身份声明仅核对服务器本人，不从原采集人继承权限。 */
    public MesPhysicalPositionSurveyDTO.Decision decision(String raw) {
        var root = codec.objectDocument(raw, MAX_DOCUMENT);
        fields(root, Set.of("version", "expectedTenantId", "expectedFactoryCode", "expectedActorUserId",
                "surveyFingerprint", "evidenceFingerprint", "expectedStatus", "action", "reason"));
        require(DECISION_VERSION.equals(text(root, "version")), "位置决策协议版本未知");
        var result = new MesPhysicalPositionSurveyDTO.Decision();
        result.setVersion(DECISION_VERSION);
        result.setExpectedTenantId(text(root, "expectedTenantId"));
        result.setExpectedFactoryCode(text(root, "expectedFactoryCode"));
        result.setExpectedActorUserId(text(root, "expectedActorUserId"));
        result.setSurveyFingerprint(text(root, "surveyFingerprint"));
        result.setEvidenceFingerprint(text(root, "evidenceFingerprint"));
        result.setExpectedStatus(text(root, "expectedStatus"));
        result.setAction(text(root, "action"));
        result.setReason(text(root, "reason"));
        codec.id(result.getExpectedTenantId());
        codec.id(result.getExpectedActorUserId());
        codec.code(result.getExpectedFactoryCode(), "操作工厂", 100);
        codec.reason(result.getReason());
        require(result.getSurveyFingerprint().matches("[0-9a-f]{64}") && result.getEvidenceFingerprint().matches("[0-9a-f]{64}"),
                "原采集和服务器证据SHA均须64位小写文本");
        require("OBSERVED".equals(result.getExpectedStatus()) && Set.of("CONFIRM", "REJECT", "CANCEL").contains(result.getAction()),
                "只能对明确待确认观察作确认、驳回或取消，不能重开终态");
        return result;
    }

    /** 严格文本，不调用asText把number/boolean转换为另一种原事实。 */
    private String text(JsonNode root, String key) {
        require(root.path(key).isTextual(), "位置采集字段" + key + "必须为文本");
        return root.get(key).textValue();
    }

    private void fields(JsonNode root, Set<String> expected) {
        require(root.isObject(), "位置采集行必须是JSON对象");
        var actual = new HashSet<String>();
        root.fieldNames().forEachRemaining(actual::add);
        require(expected.equals(actual), "位置采集存在遗漏或未知字段");
    }

    private void array(JsonNode root, String key, int min, int max) {
        var node = root.path(key);
        require(node.isArray() && node.size() >= min && node.size() <= max, "位置采集" + key + "全集为空/超界/非数组");
    }
}
