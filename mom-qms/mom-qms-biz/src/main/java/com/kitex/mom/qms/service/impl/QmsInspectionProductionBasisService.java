package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEvidenceChecks.require;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesInspectionProductionBasisService;
import com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO;
import com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO.Evidence;
import com.kitex.mom.qms.dto.inspection.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.*;

/** New inspection birth consumes MES-owned immutable engineering evidence, never today's MDM. */
@Service
@RequiredArgsConstructor
public class QmsInspectionProductionBasisService {
    private final RemoteMesInspectionProductionBasisService remote;

    public Evidence freeze(InspectionProcedureCreateParam p) {
        if (p.getSourceRequestId() == null
                || !p.getSourceRequestId()
                        .startsWith(InspectionProcedureSourceContract.REQUEST_PREFIX)) return null;
        require(
                TenantContextHolder.getTenantId() != null
                        && TenantContextHolder.getTenantId() > 0
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && FactoryContextHolder.getFactoryCode() != null
                        && !FactoryContextHolder.getFactoryCode().isBlank(),
                "检验生产依据缺少实际组织");
        require(
                Objects.equals(
                        p.getSourceRequestFingerprint(),
                        InspectionProcedureSourceContract.fingerprint(p)),
                "原V2命令指纹不一致");
        var response =
                remote.query(
                        new MesInspectionProductionBasisDTO.Query(
                                p.getSourceRequestId(),
                                p.getSourceTaskCode(),
                                InspectionProcedureSourceContract.snapshot(p),
                                p.getSourceRequestFingerprint()));
        require(
                response != null && response.isOk() && response.getData() != null,
                "MES检验生产依据未确认，请沿原号重试核对");
        var e = response.getData();
        validate(e);
        require(
                Objects.equals(e.tenantId(), String.valueOf(TenantContextHolder.getTenantId()))
                        && Objects.equals(e.factoryCode(), FactoryContextHolder.getFactoryCode())
                        && Objects.equals(e.requestId(), p.getSourceRequestId())
                        && Objects.equals(e.commandFingerprint(), p.getSourceRequestFingerprint())
                        && Objects.equals(e.operationCode(), p.getSourceTaskCode())
                        && Objects.equals(e.orderCode(), p.getOrderNo())
                        && (p.getOrderId() == null || Objects.equals(e.orderId(), p.getOrderId()))
                        && Objects.equals(e.productCode(), p.getMatCode())
                        && Objects.equals(e.processCode(), p.getProcessCode())
                        && Objects.equals(e.inspectionQuantity(), String.valueOf(p.getNum())),
                "MES生产依据与原检验组织、来源或数量不一致");
        return e;
    }

    static void validate(Evidence e) {
        require(
                e != null
                        && MesInspectionProductionBasisDTO.SCHEMA.equals(e.schemaVersion())
                        && primary(e.tenantId())
                        && text(e.factoryCode(), 100)
                        && text(e.requestId(), 200)
                        && e.requestId()
                                .startsWith(InspectionProcedureSourceContract.REQUEST_PREFIX)
                        && hash(e.commandFingerprint())
                        && text(e.operationCode(), 100)
                        && text(e.orderCode(), 100)
                        && primary(e.orderId())
                        && text(e.productCode(), 100)
                        && text(e.processCode(), 100)
                        && primary(e.inspectionQuantity())
                        && Long.parseLong(e.inspectionQuantity()) <= Integer.MAX_VALUE
                        && e.dataGaps() != null,
                "检验生产依据结构、身份或数量无效");
        boolean known = "FROZEN".equals(e.availability()) || "PARTIAL".equals(e.availability());
        if (known) {
            require(
                    primary(e.engineeringReleaseId())
                            && primary(e.engineeringVersion())
                            && hash(e.engineeringHash())
                            && e.routeNodeCode() != null
                            && e.routeNodeCode().matches("RN-[1-9][0-9]{0,18}")
                            && primary(e.bomId())
                            && text(e.bomVersion(), 100),
                    "冻结工程版本、节点或BOM身份缺失");
            if ("FROZEN".equals(e.availability()))
                require(text(e.quantityUnit(), 40) && e.dataGaps().isEmpty(), "完整生产依据缺少数量单位或仍有缺口");
            else
                require(
                        e.quantityUnit() == null
                                && e.dataGaps().equals(List.of("QUANTITY_UNIT_MISSING")),
                        "部分生产依据不能补猜单位");
        } else {
            require(
                    ("LEGACY_UNAVAILABLE".equals(e.availability())
                                    && e.dataGaps().equals(List.of("ORIGINAL_ENGINEERING_MISSING")))
                            || ("REWORK_UNAVAILABLE".equals(e.availability())
                                    && e.dataGaps()
                                            .equals(List.of("REWORK_APPROVED_BASIS_REQUIRED"))),
                    "检验生产依据状态不可识别");
            require(
                    e.engineeringReleaseId() == null
                            && e.engineeringVersion() == null
                            && e.engineeringHash() == null
                            && e.routeNodeCode() == null
                            && e.bomId() == null
                            && e.bomVersion() == null
                            && e.quantityUnit() == null,
                    "不可用生产依据不能夹带推算工程或单位");
        }
    }

    static void matches(Evidence e, QmsInspectionBasisService.Task t) {
        validate(e);
        require(
                "PROCEDURE".equals(t.type())
                        && Objects.equals(e.tenantId(), String.valueOf(t.tenantId()))
                        && Objects.equals(e.factoryCode(), t.factoryCode())
                        && Objects.equals(e.requestId(), t.sourceRequestId())
                        && Objects.equals(e.operationCode(), t.sourceTaskCode())
                        && Objects.equals(e.orderCode(), t.orderNo())
                        && (t.orderId() == null || Objects.equals(e.orderId(), t.orderId()))
                        && Objects.equals(e.productCode(), t.matCode())
                        && Objects.equals(e.processCode(), t.processCode())
                        && t.num() != null
                        && Long.parseLong(e.inspectionQuantity()) <= t.num(),
                "检验生产依据与实际任务来源冲突");
    }

    private static boolean primary(String s) {
        try {
            return s != null && s.matches("[1-9][0-9]{0,18}") && Long.parseLong(s) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean text(String s, int max) {
        return s != null && !s.isBlank() && s.length() <= max;
    }

    private static boolean hash(String s) {
        return s != null && s.matches("[0-9a-f]{64}");
    }
}
