package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesMaterialBindingSupplementDTO.Command;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 严格完整原文解析与稳定指纹；不接受数值ID、未知字段或来源重复。
 */
@Component
@RequiredArgsConstructor
public class MesMaterialBindingSupplementCodec {
    public static final String VERSION = "MES_MATERIAL_BINDING_SUPPLEMENT_V1";
    public static final String COMMAND = "MES_MATERIAL_BINDING_SUPPLEMENT_COMMAND_V1";
    public static final String PREVIEW = "MES_MATERIAL_BINDING_SUPPLEMENT_PREVIEW_V1";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> KEYS = Set.of("contractVersion", "requestId", "arrangeId", "expectedTenantId",
            "expectedFactoryCode", "expectedActorId", "expectedPoolFingerprint", "addedFeedIds", "expectedPreviewFingerprint");
    private final MesPhysicalTransformCodec physical;

    public Command command(String raw) {
        try {
            require(raw != null && raw.length() <= 32768, "追加原命令正文缺失或超限");
            var tree = JSON.readTree(raw);
            var keys = new HashSet<String>();
            require(tree != null && tree.isObject(), "追加原命令须为完整对象");
            tree.fieldNames().forEachRemaining(keys::add);
            require(keys.equals(KEYS), "追加原命令字段不完整或包含未知字段");
            for (String key : KEYS) {
                if (!"addedFeedIds".equals(key)) {
                    require(tree.get(key).isTextual(), "追加原命令ID、上下文和摘要须为精确文本");
                }
            }
            require(tree.get("addedFeedIds").isArray(), "来源ID须为完整数组");
            for (var value : tree.get("addedFeedIds")) {
                require(value.isTextual(), "来源ID须为精确文本");
            }
            var command = JSON.treeToValue(tree, Command.class);
            require(COMMAND.equals(command.getContractVersion()), "不支持的追加绑定命令版本");
            requestId(command.getRequestId());
            physical.id(command.getArrangeId());
            physical.id(command.getExpectedTenantId());
            physical.id(command.getExpectedActorId());
            physical.code(command.getExpectedFactoryCode(), "预期工厂", 100);
            hash(command.getExpectedPreviewFingerprint());
            hash(command.getExpectedPoolFingerprint());
            command.setAddedFeedIds(ids(command.getAddedFeedIds()).stream().map(Object::toString).toList());
            return command;
        } catch (CheckedException error) {
            throw error;
        } catch (Exception error) {
            throw new CheckedException("追加原命令无法严格解析，须保留原文原号");
        }
    }

    public List<Long> ids(List<String> values) {
        require(values != null && !values.isEmpty() && values.size() <= 100, "须选择1至100个不同的精确投料来源");
        var ids = new TreeSet<Long>();
        for (String value : values) {
            require(ids.add(physical.id(value)), "投料来源ID重复");
        }
        return List.copyOf(ids);
    }

    public String requestHash(Command command) {
        return physical.fingerprint(COMMAND, physical.snapshot(command));
    }

    public void requestId(String request) {
        require(request != null && request.matches("[A-Za-z0-9_.:-]{8,100}"), "须提供8至100位稳定原请求号");
    }

    public void hash(String value) {
        require(value != null && value.matches("[0-9a-f]{64}"), "须提供完整SHA256摘要");
    }

    public Map<String, Object> document(String json, String fingerprint) {
        hash(fingerprint);
        require(Objects.equals(fingerprint, physical.fingerprint(VERSION, json)), "追加来源绑定全文摘要损坏");
        var value = physical.objectDocument(json, 8 * 1024 * 1024);
        require(VERSION.equals(value.path("contractVersion").asText()), "追加来源绑定版本损坏");
        return JSON.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
        });
    }
}
