package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskReportUnqualifiedEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 真实生产不良来源的独立固定字段协议。
 * 新报告沿报工基本单位约定采集一次；原证据永不从当前MDM或模板补造。
 * 不修改旧SOURCE/COMMAND/SURVEY语义，也不把不良报告ID伪装成合格来源ID。
 */
@Component
@RequiredArgsConstructor
public class MesProductionNgSourceCodec {

    public static final String VERSION = "MES_PRODUCTION_NG_SOURCE_V1";
    public static final int MAX_SOURCE = 8 * 1024 * 1024;
    private static final Set<String> MATERIAL_FIELDS = Set.of("materialId", "materialUnit", "materialUpdatedAt");
    private final MesPhysicalTransformCodec codec;
    private final MesNgIdentificationCodec identification;

    /**
     * 只在真实新不良报告保存前调用；参数均来自服务器已核定的原生产事务。
     */
    public void freeze(MesTaskReportUnqualifiedEntity row, MesTaskArrangeEntity arrange,
                       MdmMaterialMeasureDTO material, ReportModeEnum mode) {
        require(material != null && material.getMaterialId() != null
                && Objects.equals(material.getTenantId(), row.getTenantId().toString())
                && Objects.equals(material.getFactoryCode(), row.getFactoryCode())
                && Objects.equals(material.getMaterialCode(), row.getMatCode())
                && Boolean.TRUE.equals(material.getEnabled()), "原不良物料须唯一归属当前租户工厂且实际启用");
        codec.id(material.getMaterialId());
        codec.code(material.getBasicUnit(), "不良原基本单位", 100);
        require(mode != null && row.getUnqualifiedNum() != null && row.getUnqualifiedNum().signum() > 0,
                "不良原模式或数量未明确");
        codec.quantity(row.getUnqualifiedNum().stripTrailingZeros().toPlainString(), false, "不良原数量");
        require(mode != ReportModeEnum.ONE_CODE && mode != ReportModeEnum.PACKAGE_ONE_CODE
                || row.getUnqualifiedNum().compareTo(BigDecimal.ONE) == 0, "逐件不良报告必须完整1件");
        require(Objects.equals(row.getArrangeCode(), arrange.getCode())
                && Objects.equals(row.getTaskCode(), arrange.getTaskCode())
                && Objects.equals(row.getProduceTaskCode(), arrange.getProduceTaskCode())
                && Objects.equals(row.getMatCode(), arrange.getMatCode())
                && Objects.equals(row.getTenantId(), arrange.getTenantId())
                && Objects.equals(row.getFactoryCode(), arrange.getFactoryCode()), "不良原报告与真实派工归属不一致");
        row.setReportUnit(material.getBasicUnit());
        row.setReportMode(mode.name());
        row.setSourceVersion(VERSION);
        var facts = reportFacts(row);
        facts.put("arrangeId", arrange.getId().toString());
        facts.put("executionPolicySnapshot", arrange.getExecutionPolicySnapshot());
        facts.put("materialId", material.getMaterialId());
        facts.put("materialUnit", material.getBasicUnit());
        facts.put("materialUpdatedAt", material.getUpdatedAt());
        String raw = codec.snapshot(facts);
        codec.objectDocument(raw, MAX_SOURCE);
        row.setSourceSnapshot(raw);
        row.setSourceFingerprint(codec.fingerprint(VERSION, raw));
        identification.freeze(row);
        verify(row);
    }

    /**
     * 原账独立校验不访问当前派工/MDM；历史所有新字段NULL才属于明确的未证明来源。
     */
    public boolean verify(MesTaskReportUnqualifiedEntity row) {
        identification.verify(row);
        if (row.getSourceVersion() == null) {
            require(row.getSourceSnapshot() == null && row.getSourceFingerprint() == null
                            && row.getReportUnit() == null && row.getReportMode() == null && row.getCapturedActorId() == null,
                    "不良来源存在不完整冻结字段，不能回落为旧历史");
            return false;
        }
        require(VERSION.equals(row.getSourceVersion()) && row.getSourceSnapshot() != null
                        && Objects.equals(row.getSourceFingerprint(), codec.fingerprint(VERSION, row.getSourceSnapshot())),
                "不良原来源版本或完整字符串SHA不一致");
        JsonNode raw = codec.objectDocument(row.getSourceSnapshot(), MAX_SOURCE);
        var expected = reportFacts(row);
        var keys = new java.util.HashSet<>(expected.keySet());
        keys.addAll(MATERIAL_FIELDS);
        keys.addAll(Set.of("arrangeId", "executionPolicySnapshot"));
        require(raw.size() == keys.size() && keys.stream().allMatch(raw::has), "不良原来源存在未知或遗漏字段");
        expected.forEach((key, value) -> require(value == null ? raw.get(key).isNull()
                : raw.get(key).isTextual() && value.equals(raw.get(key).textValue()), "不良原来源字段不一致：" + key));
        codec.id(text(raw, "arrangeId"));
        codec.id(text(raw, "materialId"));
        require(Objects.equals(text(raw, "materialUnit"), row.getReportUnit()), "不良原单位与冻结物料单位不一致");
        require(raw.get("materialUpdatedAt").isNull() || raw.get("materialUpdatedAt").isTextual(), "原物料观察时间类型不明");
        require(raw.get("executionPolicySnapshot").isNull() || raw.get("executionPolicySnapshot").isTextual(), "原派工契约必须保留原字符串或原NULL");
        return true;
    }

    /**
     * 显式列举报告字段，后续实体扩展不会静默改变本版本原文/SHA。
     */
    private TreeMap<String, String> reportFacts(MesTaskReportUnqualifiedEntity row) {
        require(row.getId() != null && row.getTenantId() != null && row.getCapturedActorId() != null
                        && row.getId() > 0 && row.getTenantId() > 0 && row.getCapturedActorId() > 0,
                "不良原来源缺真实主键/租户/认证用户");
        codec.code(row.getReportUnit(), "不良原单位", 100);
        codec.code(row.getFactoryCode(), "不良原工厂", 100);
        codec.code(row.getWorkerCode(), "不良原采集人", 100);
        codec.code(row.getType(), "不良原项目", 100);
        codec.code(row.getArrangeCode(), "不良原派工", 100);
        codec.code(row.getTaskCode(), "不良原工单", 100);
        codec.code(row.getProduceTaskCode(), "不良原工序任务", 100);
        codec.code(row.getMatCode(), "不良原物料", 100);
        require(row.getRequestId() != null && row.getRequestId().length() >= 8 && row.getRequestId().length() <= 64,
                "不良原请求号长度须为8至64");
        require(row.getReportTime() != null && row.getUnqualifiedNum() != null && row.getUnqualifiedNum().signum() > 0,
                "不良原采集时间或数量不完整");
        require(row.getReportMode() != null && ReportModeEnum.fromCode(row.getReportMode()).name().equals(row.getReportMode()),
                "不良原报工模式必须规范且明确");
        var facts = new TreeMap<String, String>();
        facts.put("version", VERSION);
        facts.put("originState", "UNSTOCKED");
        facts.put("reportId", row.getId().toString());
        facts.put("requestId", row.getRequestId());
        facts.put("tenantId", row.getTenantId().toString());
        facts.put("factoryCode", row.getFactoryCode());
        facts.put("actorUserId", row.getCapturedActorId().toString());
        facts.put("actorCode", row.getWorkerCode());
        facts.put("actorName", row.getWorkerName());
        facts.put("arrangeCode", row.getArrangeCode());
        facts.put("taskCode", row.getTaskCode());
        facts.put("produceTaskCode", row.getProduceTaskCode());
        facts.put("materialCode", row.getMatCode());
        facts.put("materialName", row.getMatName());
        facts.put("materialSpec", row.getMatSpec());
        facts.put("reportUnit", row.getReportUnit());
        facts.put("reportMode", row.getReportMode());
        facts.put("quantity", row.getUnqualifiedNum().stripTrailingZeros().toPlainString());
        facts.put("defectCode", row.getType());
        facts.put("identifier", row.getTransferCode());
        facts.put("reportedAt", time(row.getReportTime()));
        return facts;
    }

    private String text(JsonNode raw, String key) {
        require(raw.path(key).isTextual(), "不良原证据字段须为文本：" + key);
        return raw.get(key).textValue();
    }

    private String time(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
