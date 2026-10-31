package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesQmsInspectionCommandMapper;
import com.kitex.mom.mes.mapper.MesQualityGateSourceMapper;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureSourceContract;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.enums.ProcedureLabelTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 复用V2永久创建原件核源；不是新的检验修订或首检指标授权。
 */
@Service
@RequiredArgsConstructor
public class MesQualityGateSourceEvidence {
    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "taskCode",
                    "sourceTaskCode",
                    "sourceRequestId",
                    "labelType",
                    "name",
                    "matCode",
                    "matName",
                    "matSpec",
                    "processCode",
                    "processName",
                    "standardId",
                    "batchNo",
                    "num",
                    "inspectionNum",
                    "isCreate",
                    "deptCode",
                    "orderNo",
                    "orderId",
                    "matBarcode");
    private final MesQmsInspectionCommandMapper commands;
    private final MesQualityGateSourceMapper sources;

    static void scope() {
        if (TenantContextHolder.getTenantId() == null
                || TenantContextHolder.getTenantId() <= 0
                || TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || FactoryContextHolder.getFactoryCode() == null
                || FactoryContextHolder.getFactoryCode().isBlank())
            throw new CheckedException("质量门须指定实际租户和工厂，不能跳过组织校验");
    }

    private static Long longValue(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new Rejected("冻结质检整数不可解析");
        }
    }

    private static Integer intValue(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new Rejected("冻结质检数量不可解析");
        }
    }

    private static Boolean boolValue(String value) {
        if (value == null) return null;
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new Rejected("冻结质检布尔值不可解析");
    }

    private static void quantities(QmsInspectionProcedureTaskEntity inspection) {
        int usable = Objects.requireNonNullElse(inspection.getUsableNum(), 0),
                handled = Objects.requireNonNullElse(inspection.getHandleNum(), 0),
                total = inspection.getNum();
        require(
                usable >= 0 && handled >= 0 && usable <= handled && handled <= total,
                "检验累计处置数量关系不正确");
        String result = Objects.toString(inspection.getResult(), "");
        String status = Objects.toString(inspection.getStatus(), "");
        require(Set.of("WAIT", "RUNNING", "UNHANDLED", "FINISHED").contains(status), "QMS检验状态未知");
        switch (result) {
            case "QUALIFIED" -> require(
                    "FINISHED".equals(status)
                            && ((usable == 0 && handled == 0)
                            || (usable == total && handled == total)),
                    "合格结论与明确处置量不一致");
            case "RELEASE", "RECEIVE", "EXEMPTION" -> require(
                    "FINISHED".equals(status) && usable == total && handled == total,
                    "放行结论须具备全批明确处置数量");
            case "UN_QUALIFIED" -> require(
                    inspection.getUsableNum() != null
                            && inspection.getHandleNum() != null
                            && Set.of("UNHANDLED", "FINISHED").contains(status),
                    "不合格处置缺少明确数量或合法状态");
            case "CANCELED" -> {
            }
            case "" -> require(
                    usable == 0
                            && handled == 0
                            && Set.of("WAIT", "RUNNING").contains(status),
                    "未判检验不能携带处置量");
            default -> throw new Rejected("QMS检验结论未知，不能作为数量依据");
        }
    }

    static Map<String, String> parse(String text) {
        String header = InspectionProcedureSourceContract.VERSION + "\n";
        require(text.startsWith(header), "质检创建原文版本不一致");
        Map<String, String> values = new HashMap<>();
        int at = header.length();
        try {
            while (at < text.length()) {
                int colon = text.indexOf(':', at);
                require(colon > at, "质检创建原文字段不完整");
                String name = text.substring(at, colon);
                require(FIELDS.contains(name) && !values.containsKey(name), "质检创建原文字段未知或重复");
                int boundary = text.indexOf(':', colon + 1);
                require(boundary > colon + 1, "质检创建原文长度缺失");
                int length = Integer.parseInt(text.substring(colon + 1, boundary));
                require(length >= -1 && length <= 65536, "质检创建原文长度无效");
                at = boundary + 1;
                int end = Math.addExact(at, Math.max(0, length));
                require(end < text.length() && text.charAt(end) == '\n', "质检创建原文长度或边界不一致");
                values.put(name, length == -1 ? null : text.substring(at, end));
                at = end + 1;
            }
        } catch (NumberFormatException | ArithmeticException e) {
            throw new Rejected("质检创建原文长度不可解析");
        }
        require(values.keySet().equals(FIELDS), "质检创建原文缺少完整字段");
        return values;
    }

    private static int positive(String value) {
        try {
            int n = Integer.parseInt(value);
            require(n > 0, "原创建检验批数量不是正整数");
            return n;
        } catch (NumberFormatException e) {
            throw new Rejected("原创建检验批数量不可核验");
        }
    }

    private static String phase(String text) {
        if (text == null) return "";
        for (var p : ProcedureLabelTypeEnum.values())
            if (p.name().equalsIgnoreCase(text.trim()) || p.getName().equals(text.trim()))
                return p.name();
        return "";
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new Rejected(message);
    }

    MesProduceTaskEntity parent(String code) {
        scope();
        if (code == null || code.isBlank()) throw new CheckedException("质量门缺少工序任务");
        var rows =
                sources.parents(
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode(),
                        code);
        if (rows.size() != 1) throw new CheckedException("当前组织不存在唯一的实际工序任务");
        return rows.get(0);
    }

    MesTaskEntity root(MesProduceTaskEntity process) {
        scope();
        var rows =
                sources.roots(
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode(),
                        process.getTaskCode());
        if (rows.size() != 1) throw new CheckedException("当前组织不存在唯一的生产工单归属");
        return rows.get(0);
    }

    Proof verify(MesInspectionGenRecordEntity record, QmsInspectionProcedureTaskEntity inspection) {
        Proof proof = verifyOriginal(record, inspection);
        quantities(inspection);
        return proof;
    }

    /**
     * 核验不可变创建身份；收件不以当前处置量是否可放行为前提。
     */
    Proof verifyOriginal(
            MesInspectionGenRecordEntity record, QmsInspectionProcedureTaskEntity inspection) {
        scope();
        var process = parent(record.getProduceTaskCode());
        var owner = root(process);
        require(
                Objects.equals(record.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(
                        record.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                        && Objects.equals(record.getTaskCode(), process.getTaskCode()),
                "质检生成记录与实际工序归属不一致");
        require(
                inspection != null
                        && Objects.equals(inspection.getId(), record.getInspectionId())
                        && Objects.equals(inspection.getTenantId(), record.getTenantId())
                        && Objects.equals(inspection.getFactoryCode(), record.getFactoryCode())
                        && Objects.equals(
                        inspection.getSourceTaskCode(), record.getProduceTaskCode())
                        && Objects.equals(inspection.getOrderNo(), record.getTaskCode())
                        && Objects.equals(
                        inspection.getSourceRequestId(), record.getSourceRequestId())
                        && Objects.equals(
                        phase(inspection.getLabelType()), phase(record.getInspectionType()))
                        && !phase(record.getInspectionType()).isEmpty(),
                "QMS检验任务与实际组织、工序或阶段不一致");
        require(
                record.getSourceRequestId() != null
                        && record.getSourceRequestId()
                        .startsWith(InspectionProcedureSourceContract.REQUEST_PREFIX),
                "缺少永久V2创建原件，不能补猜历史放行依据");
        require(Boolean.TRUE.equals(commands.installed()), "质检永久创建账未就绪，无法核源");
        var command = commands.original(record.getTenantId(), record.getSourceRequestId());
        require(
                command != null
                        && "0".equals(command.getDelFlag())
                        && Objects.equals(command.getTenantId(), record.getTenantId())
                        && Objects.equals(command.getFactoryCode(), record.getFactoryCode())
                        && "QMS_CONFIRMED".equals(command.getStatus())
                        && Objects.equals(command.getQmsTaskId(), record.getInspectionId())
                        && Objects.equals(command.getRequestId(), record.getSourceRequestId())
                        && Objects.equals(command.getSourceTaskCode(), record.getProduceTaskCode())
                        && InspectionProcedureSourceContract.VERSION.equals(
                        command.getCommandVersion()),
                "缺少组织一致的已确认质检创建回执");
        require(
                command.getCommandSnapshot() != null
                        && command.getCommandSnapshot().length() <= 65536
                        && command.getCommandFingerprint() != null
                        && command.getCommandFingerprint().matches("[0-9a-f]{64}")
                        && hash(command.getCommandSnapshot())
                        .equals(command.getCommandFingerprint())
                        && command.getCommandFingerprint()
                        .equals(inspection.getSourceRequestFingerprint()),
                "质检创建原文或来源指纹不一致");
        Map<String, String> original = parse(command.getCommandSnapshot());
        require(
                Objects.equals(original.get("sourceRequestId"), record.getSourceRequestId())
                        && Objects.equals(
                        original.get("sourceTaskCode"), record.getProduceTaskCode())
                        && Objects.equals(original.get("taskCode"), record.getProduceTaskCode())
                        && Objects.equals(original.get("orderNo"), record.getTaskCode())
                        && Objects.equals(
                        phase(original.get("labelType")), phase(record.getInspectionType()))
                        && Objects.equals(original.get("matCode"), inspection.getMatCode())
                        && Objects.equals(inspection.getMatCode(), process.getMatCode())
                        && Objects.equals(original.get("processCode"), inspection.getProcessCode())
                        && Objects.equals(inspection.getProcessCode(), process.getRouteCode())
                        && Objects.equals(
                        original.get("standardId"),
                        Objects.toString(inspection.getStandardId(), null))
                        && Objects.equals(original.get("batchNo"), inspection.getBatchNo())
                        && Objects.equals(original.get("orderId"), inspection.getOrderId())
                        && (original.get("orderId") == null
                        || Objects.equals(
                        original.get("orderId"), String.valueOf(owner.getId()))),
                "检验身份与冻结创建原文不一致");
        int initial = positive(original.get("num"));
        require(inspection.getNum() != null && inspection.getNum() >= initial, "检验批数量不得缺失或小于原创建量");
        return new Proof(original.get("orderId") != null);
    }

    InspectionProcedureCreateParam replay(
            MesInspectionGenRecordEntity record,
            MesProduceTaskEntity process,
            MesTaskEntity owner,
            String request,
            String type) {
        scope();
        require(Boolean.TRUE.equals(commands.installed()), "质检永久创建账未就绪，不能重组恢复命令");
        var command = commands.original(TenantContextHolder.getTenantId(), request);
        if (command == null) return null;
        require(
                "0".equals(command.getDelFlag())
                        && Objects.equals(command.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(
                        command.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                        && Objects.equals(command.getRequestId(), request)
                        && Objects.equals(command.getSourceTaskCode(), process.getCode())
                        && InspectionProcedureSourceContract.VERSION.equals(
                        command.getCommandVersion())
                        && Set.of("REGISTERED", "OUTBOUND_STARTED", "QMS_CONFIRMED")
                        .contains(Objects.toString(command.getStatus(), "")),
                "冻结质检命令不属于当前来源或状态不可恢复");
        require(
                command.getCommandSnapshot() != null
                        && command.getCommandSnapshot().length() <= 65536
                        && command.getCommandFingerprint() != null
                        && hash(command.getCommandSnapshot())
                        .equals(command.getCommandFingerprint()),
                "冻结质检命令原文不可核验");
        Map<String, String> values = parse(command.getCommandSnapshot());
        InspectionProcedureCreateParam original =
                new InspectionProcedureCreateParam()
                        .setId(longValue(values.get("id")))
                        .setTaskCode(values.get("taskCode"))
                        .setSourceTaskCode(values.get("sourceTaskCode"))
                        .setSourceRequestId(values.get("sourceRequestId"))
                        .setLabelType(values.get("labelType"))
                        .setName(values.get("name"))
                        .setMatCode(values.get("matCode"))
                        .setMatName(values.get("matName"))
                        .setMatSpec(values.get("matSpec"))
                        .setProcessCode(values.get("processCode"))
                        .setProcessName(values.get("processName"))
                        .setStandardId(longValue(values.get("standardId")))
                        .setBatchNo(values.get("batchNo"))
                        .setNum(intValue(values.get("num")))
                        .setInspectionNum(intValue(values.get("inspectionNum")))
                        .setIsCreate(boolValue(values.get("isCreate")))
                        .setDeptCode(values.get("deptCode"))
                        .setOrderNo(values.get("orderNo"))
                        .setOrderId(values.get("orderId"))
                        .setMatBarcode(values.get("matBarcode"))
                        .setSourceRequestFingerprint(command.getCommandFingerprint());
        require(
                InspectionProcedureSourceContract.snapshot(original)
                        .equals(command.getCommandSnapshot()),
                "冻结质检命令不是完整的原V2参数");
        require(
                Objects.equals(original.getSourceRequestId(), request)
                        && Objects.equals(original.getTaskCode(), process.getCode())
                        && Objects.equals(original.getSourceTaskCode(), process.getCode())
                        && Objects.equals(record.getProduceTaskCode(), process.getCode())
                        && Objects.equals(record.getTaskCode(), owner.getCode())
                        && Objects.equals(original.getOrderNo(), owner.getCode())
                        && (original.getOrderId() == null
                        || Objects.equals(
                        original.getOrderId(), String.valueOf(owner.getId())))
                        && Objects.equals(
                        phase(original.getLabelType()), phase(record.getInspectionType()))
                        && Objects.equals(phase(type), phase(record.getInspectionType()))
                        && Objects.equals(original.getMatCode(), process.getMatCode())
                        && Objects.equals(original.getProcessCode(), process.getRouteCode()),
                "冻结质检命令与实际工单、工序或阶段不一致");
        return original;
    }

    record Proof(boolean rootPrimaryKeyFrozen) {
    }

    static class Rejected extends RuntimeException {
        Rejected(String message) {
            super(message);
        }
    }
}
