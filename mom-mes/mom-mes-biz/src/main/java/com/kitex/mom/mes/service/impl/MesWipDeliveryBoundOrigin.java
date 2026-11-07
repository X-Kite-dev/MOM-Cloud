package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesReceivingTargetRules.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.mdm.support.MdmReceivingReferenceFingerprint;
import com.kitex.mom.mes.dto.MesWipDeliverySimulationDTO.RouteTarget;
import com.kitex.mom.mes.dto.MesWipDispatchTargetDTO.Validated;
import com.kitex.mom.mes.dto.MesWipTransferCommand;
import com.kitex.mom.mes.entity.MesWipTransferEntity;
import com.kitex.mom.mes.entity.MesWipTransferOperationEntity;

import java.time.Instant;
import java.util.*;

/** 配送模拟保存原发出目标；不是当前工位可承接或生产放行证明。 */
final class MesWipDeliveryBoundOrigin {
    static final String SCHEMA = "MES_WIP_DELIVERY_BOUND_ORIGIN@1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private MesWipDeliveryBoundOrigin() {}

    static RouteTarget freeze(
            MesWipTransferEntity transfer, List<MesWipTransferOperationEntity> operations) {
        var origin = MesWipTransferOriginVerifier.verify(transfer, operations);
        var operation = operations.get(0);
        try {
            var evidence = JSON.readTree(operation.getEvidence());
            var raw = evidence.get("dispatchTarget");
            if (origin.boundTarget() == null) {
                need(raw == null || raw.isNull(), "无绑定原命令不能夹带工序目标原件");
                return null;
            }
            need(raw != null && raw.isObject(), "原发出未留存完整工序目标依据");
            strictReference(raw.get("reference"));
            var strictSelection =
                    JSON.treeToValue(
                            evidence.path("command").get("dispatchTarget"),
                            com.kitex.mom.mes.dto.MesWipDispatchTargetDTO.Selection.class);
            need(Objects.equals(strictSelection, origin.boundTarget()), "原命令工序目标格式或内容损坏");
            var validated = JSON.treeToValue(raw, Validated.class);
            need(Objects.equals(validated.selection(), origin.boundTarget()), "原发出目标与留存复核原件不一致");
            need(
                    operation.getOperatorId().toString().equals(origin.boundTarget().getActorId()),
                    "原目标不属于原发出人");
            var target =
                    new RouteTarget(
                            SCHEMA,
                            operation.getId().toString(),
                            operation.getFingerprint(),
                            validated.selection(),
                            validated.reference(),
                            validated.observedAt());
            verify(
                    target,
                    transfer.getTenantId().toString(),
                    transfer.getFactoryCode(),
                    transfer.getSourceStockId().toString(),
                    transfer.getSourceReportId().toString(),
                    transfer.getToLocationCode());
            var to = JSON.readTree(transfer.getLocationSnapshot()).get(1);
            need(
                    target.reference().point().id().equals(to.path("id").asText())
                            && target.reference()
                                    .point()
                                    .warehouseCode()
                                    .equals(to.path("warehouseCode").asText()),
                    "冻结工位位置与原发出位置不一致");
            return target;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("原发出工序目标依据损坏", e);
        }
    }

    /** 读取本任务原件时核验原生JSON文本，避免Jackson把数字位置身份转为字符串。 */
    static void verifySnapshotJson(String raw) {
        try {
            var snapshot = JSON.readTree(raw);
            var target = snapshot.get("routeTarget");
            if (target != null && !target.isNull()) {
                for (String key :
                        List.of(
                                "transferId",
                                "transferRevision",
                                "sourceStockId",
                                "sourceReportId"))
                    need(snapshot.path(key).isTextual(), "绑定任务来源身份须为完整文本");
                need(target.isObject(), "模拟工序目标原件损坏");
                for (String key :
                        List.of(
                                "schemaVersion",
                                "dispatchOperationId",
                                "dispatchFingerprint",
                                "observedAt")) need(target.path(key).isTextual(), "模拟工序目标身份须为完整文本");
                strictReference(target.get("reference"));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("模拟工序目标JSON损坏", e);
        }
    }

    private static void strictReference(JsonNode ref) {
        need(ref != null && ref.isObject() && ref.path("hash").isTextual(), "冻结位置引用缺失");
        for (String part : List.of("station", "point")) {
            var node = ref.path(part);
            need(node.isObject(), "冻结位置引用不完整");
            node.fields()
                    .forEachRemaining(
                            field -> {
                                if ("valid".equals(field.getKey()))
                                    need(field.getValue().isBoolean(), "冻结位置有效标志损坏");
                                else
                                    need(
                                            field.getValue().isTextual()
                                                    || field.getValue().isNull(),
                                            "冻结位置身份须为完整文本");
                            });
        }
    }

    static void verify(
            RouteTarget target,
            String tenant,
            String factory,
            String stock,
            String report,
            String location) {
        need(target != null && SCHEMA.equals(target.schemaVersion()), "模拟原目标协议损坏");
        id(target.dispatchOperationId());
        need(
                target.dispatchFingerprint() != null
                        && target.dispatchFingerprint().matches("[0-9a-f]{64}"),
                "原发出指纹缺失");
        Instant.parse(target.observedAt());
        var selection = target.selection();
        need(
                selection != null
                        && target.reference() != null
                        && target.reference().station() != null
                        && target.reference().point() != null,
                "模拟原目标引用不完整");
        var command = new MesWipTransferCommand();
        command.setOperationType("DISPATCH");
        command.setSourceStockId(id(stock));
        command.setDispatchTarget(selection);
        MesWipTransferCommandCodec.target(command);
        var st = target.reference().station();
        var pt = target.reference().point();
        for (String value :
                Arrays.asList(
                        st.id(),
                        st.lineId(),
                        st.workshopId(),
                        st.factoryId(),
                        st.industryId(),
                        pt.id(),
                        pt.warehouseId(),
                        pt.areaId(),
                        pt.shelfId())) id(value);
        for (String value :
                Arrays.asList(
                        st.code(), st.lineCode(), st.workshopCode(), pt.code(), pt.warehouseCode()))
            need(code(value), "冻结位置路径编码不完整");
        need(
                tenant.equals(selection.getTenantId())
                        && factory.equals(selection.getFactoryCode())
                        && report.equals(selection.getSourceReportId())
                        && st.valid()
                        && pt.valid()
                        && st.code().equals(selection.getWorksiteCode())
                        && pt.id().equals(selection.getLocationId())
                        && location.equals(pt.code())
                        && target.reference().hash().equals(selection.getReferenceHash())
                        && target.reference()
                                .hash()
                                .equals(
                                        MdmReceivingReferenceFingerprint.of(
                                                tenant, factory, st, pt)),
                "模拟目标与原来源、工位或位置结构不一致");
    }
}
