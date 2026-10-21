package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.mom.mes.dto.ProcessProcedureDTO;
import com.kitex.mom.mes.entity.MesProductionFeedBatchEntity;
import com.kitex.mom.mes.entity.MesProductionFeedConsumptionEntity;
import com.kitex.mom.mes.entity.MesProductionFeedOutputEntity;
import com.kitex.mom.mes.entity.MesProductionFeedShareEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 总耗用永久原事实的固定字段协议。
 * 只比对传入的真实全集，不查当前BOM、不读取权限、不改变原SHA或本人读取规则。
 * 创建与受控恢复共用同一原版字段，新增实体字段不会隐式改变历史协议。
 */
@Component
@RequiredArgsConstructor
public class MesProductionFeedLedgerFacts {
    public static final String BATCH_VERSION = "MES_PRODUCTION_FEED_BATCH_V1";
    public static final int MAX_DOCUMENT = MesProductionFeedEvidenceCodec.MAX_BATCH_DOCUMENT;
    private final MesPhysicalTransformCodec codec;
    private final MesProductionFeedEvidenceCodec evidence;

    /** 核原版本、完整原文SHA及实际产出/总耗用/份额两向全集，不以当前feed余额解释历史。 */
    public void verify(MesProductionFeedBatchEntity batch, List<MesProductionFeedOutputEntity> outputs,
                       List<MesProductionFeedConsumptionEntity> consumptions, List<MesProductionFeedShareEntity> shares) {
        require(batch != null && BATCH_VERSION.equals(batch.getEvidenceVersion())
                && "MATERIAL_RECORDED".equals(batch.getStatus())
                && Objects.equals(batch.getEvidenceFingerprint(), codec.fingerprint(batch.getEvidenceVersion(), batch.getEvidenceSnapshot())),
                "总投料版本或原SHA不一致");
        JsonNode original = evidence.batchDocument(batch.getEvidenceSnapshot());
        var observed = header(batch);
        observed.put("routeSnapshot", original.get("routeSnapshot"));
        observed.put("bomSnapshot", original.get("bomSnapshot"));
        observed.put("requirements", original.get("requirements"));
        observed.put("outputs", outputs.stream().map(this::outputFacts)
                .sorted(Comparator.comparing(row -> (String) row.get("id"))).toList());
        observed.put("consumptions", consumptions.stream().map(this::consumptionFacts)
                .sorted(Comparator.comparing(row -> (String) row.get("id"))).toList());
        observed.put("shares", shares.stream().map(this::shareFacts)
                .sorted(Comparator.comparing(row -> (String) row.get("id"))).toList());
        require(original.equals(evidence.batchDocument(codec.snapshot(observed))),
                "总投料真实产出/耗用根/份额全集回读不一致");
    }

    /** 固定字段逐一冻结，不反射实体，新增字段不会悄悄改变原协议。 */
    public Map<String, Object> header(MesProductionFeedBatchEntity batch) {
        var result = new TreeMap<String, Object>();
        result.put("version", BATCH_VERSION);
        result.put("batchId", text(batch.getId()));
        result.put("requestId", batch.getRequestId());
        result.put("tenantId", text(batch.getTenantId()));
        result.put("factoryCode", batch.getFactoryCode());
        result.put("actorId", text(batch.getActorId()));
        result.put("taskCode", batch.getTaskCode());
        result.put("produceTaskCode", batch.getProduceTaskCode());
        result.put("arrangeCode", batch.getArrangeCode());
        result.put("productionUnit", batch.getProductionUnit());
        result.put("processedQuantity", codec.amount(batch.getProcessedQuantity()));
        return result;
    }

    /** 原工序需求，只冻结原物料、单位与精确单耗，不增补当前BOM候选。 */
    public Map<String, Object> requirementFacts(ProcessProcedureDTO.ProcessProcedureMatDTO row) {
        var result = new TreeMap<String, Object>();
        result.put("materialCode", row.getMaterialCode());
        result.put("unit", row.getUnit());
        result.put("quantity", codec.amount(row.getQuantity()));
        return result;
    }

    /** 真实typed产出原件；GOOD/NG来源主键分别保留，不反射新增实体字段。 */
    public Map<String, Object> outputFacts(MesProductionFeedOutputEntity row) {
        var result = new TreeMap<String, Object>();
        result.put("id", text(row.getId()));
        result.put("batchId", text(row.getBatchId()));
        result.put("outputKey", row.getOutputKey());
        result.put("outputKind", row.getOutputKind());
        result.put("goodReportId", text(row.getGoodReportId()));
        result.put("ngReportId", text(row.getNgReportId()));
        result.put("sourceBirthId", text(row.getSourceBirthId()));
        result.put("quantity", codec.amount(row.getQuantity()));
        result.put("productionUnit", row.getProductionUnit());
        result.put("sourceSnapshot", row.getSourceSnapshot());
        return result;
    }

    /** 每feed原实际耗用根；冻结原需求/替代比例/单位与真实前后量，历史不重算。 */
    public Map<String, Object> consumptionFacts(MesProductionFeedConsumptionEntity row) {
        var result = new TreeMap<String, Object>();
        result.put("id", text(row.getId()));
        result.put("batchId", text(row.getBatchId()));
        result.put("feedId", text(row.getFeedId()));
        result.put("requirementMatCode", row.getRequirementMatCode());
        result.put("requirementUnit", row.getRequirementUnit());
        result.put("matCode", row.getMatCode());
        result.put("matUnit", row.getMatUnit());
        result.put("consumeNum", codec.amount(row.getConsumeNum()));
        result.put("requirementNum", codec.amount(row.getRequirementNum()));
        result.put("substituteRatio", codec.amount(row.getSubstituteRatio()));
        result.put("substituteFlag", row.getSubstituteFlag().toString());
        result.put("feedNum", codec.amount(row.getFeedNum()));
        result.put("usedBeforeNum", codec.amount(row.getUsedBeforeNum()));
        result.put("usedAfterNum", codec.amount(row.getUsedAfterNum()));
        result.put("materialId", text(row.getMaterialId()));
        result.put("materialUpdatedAt", row.getMaterialUpdatedAt());
        return result;
    }

    /** 单份产出对原耗用根的精确份额；总耗用和恢复只能引用原边，不猜首条投料。 */
    public Map<String, Object> shareFacts(MesProductionFeedShareEntity row) {
        var result = new TreeMap<String, Object>();
        result.put("id", text(row.getId()));
        result.put("batchId", text(row.getBatchId()));
        result.put("outputId", text(row.getOutputId()));
        result.put("consumptionId", text(row.getConsumptionId()));
        result.put("consumeNum", codec.amount(row.getConsumeNum()));
        result.put("requirementNum", codec.amount(row.getRequirementNum()));
        return result;
    }

    private String text(Long value) {
        return value == null ? null : value.toString();
    }
}
