package com.kitex.mom.qms.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.agent.dto.QmsIncomingInspectionEvidenceDTO;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskEntity;
import com.kitex.mom.qms.service.InspectionPurchaseTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 仅查询PMS收料来源已经给出的精确来料质检任务，不按物料批次反猜任务。
 */
@Service
@RequiredArgsConstructor
public class QmsIncomingInspectionEvidenceService {

    public static final String TOOL_CODE = "QMS_INCOMING_INSPECTION_EVIDENCE_QUERY@1";
    private static final Set<String> ACCEPTED_RESULTS = Set.of("QUALIFIED", "EXEMPTION", "RELEASE", "RECEIVE");

    private final QmsAgentFactoryAccessService factoryAccess;
    private final InspectionPurchaseTaskService purchaseTaskService;
    private final ObjectMapper objectMapper;

    public QmsIncomingInspectionEvidenceDTO.Evidence query(QmsIncomingInspectionEvidenceDTO.Query query) {
        if (query == null || query.getTaskIds() == null || query.getTaskIds().isEmpty()
                || query.getTaskIds().size() > 100) {
            throw new CheckedException("来料检验取证必须提供1至100个QMS任务主键");
        }
        String factory = factoryAccess.requireRead(query.getFactoryCode());
        List<Long> ids = query.getTaskIds().stream().map(this::positiveId).distinct().toList();
        if (ids.size() != query.getTaskIds().size()) {
            throw new CheckedException("来料检验任务主键包含重复值，禁止模糊合并");
        }
        List<QmsInspectionPurchaseTaskEntity> tasks = purchaseTaskService.list(
                new LambdaQueryWrapper<QmsInspectionPurchaseTaskEntity>()
                        .eq(QmsInspectionPurchaseTaskEntity::getFactoryCode, factory)
                        .in(QmsInspectionPurchaseTaskEntity::getId, ids)
                        .orderByAsc(QmsInspectionPurchaseTaskEntity::getId));
        Set<Long> found = new HashSet<>();
        tasks.forEach(item -> found.add(item.getId()));
        List<String> gaps = new ArrayList<>();
        ids.stream().filter(id -> !found.contains(id))
                .forEach(id -> gaps.add("当前工厂没有QMS来料检验任务" + id));
        for (QmsInspectionPurchaseTaskEntity item : tasks) {
            if (item.getOrderDetailId() == null) gaps.add("QMS任务" + item.getId() + "缺少采购订单行来源");
            if (!"FINISHED".equals(item.getStatus())) gaps.add("QMS任务" + item.getId() + "尚未完成检验");
            if (!ACCEPTED_RESULTS.contains(item.getResult())) {
                gaps.add("QMS任务" + item.getId() + "当前结果" + Objects.toString(item.getResult(), "UNKNOWN")
                        + "不能作为合格/免检/紧急放行/让步接收入库证明");
            }
        }
        List<QmsIncomingInspectionEvidenceDTO.Task> rows = tasks.stream().map(this::task).toList();
        List<String> refs = tasks.stream().map(item -> "QMS:qms_inspection_purchase_task:" + item.getId()).toList();
        QmsIncomingInspectionEvidenceDTO.Evidence evidence = new QmsIncomingInspectionEvidenceDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("仅按PMS收料批次冻结的QMS来料检验任务主键读取当前状态；FINISHED且结果为QUALIFIED/EXEMPTION/RELEASE/RECEIVE才作为允许进入合格入库流程的质量证据。")
                .setFactoryCode(factory).setObservedAt(LocalDateTime.now()).setTasks(rows)
                .setDataGaps(gaps)
                .setLimitations(List.of("QMS检验允许入库不等于WMS已经完成实物入库",
                        "不合格处置、退供和NCR/CAPA仍由QMS/PMS原状态机办理"))
                .setEvidenceRefs(refs);
        evidence.setFactFingerprint(fingerprint(evidence));
        return evidence;
    }

    private QmsIncomingInspectionEvidenceDTO.Task task(QmsInspectionPurchaseTaskEntity item) {
        boolean terminal = "FINISHED".equals(item.getStatus());
        boolean accepted = terminal && ACCEPTED_RESULTS.contains(item.getResult());
        return new QmsIncomingInspectionEvidenceDTO.Task()
                .setTaskId(String.valueOf(item.getId())).setTaskCode(item.getTaskCode())
                .setPurchaseOrderDetailId(item.getOrderDetailId() == null ? null : String.valueOf(item.getOrderDetailId()))
                .setMaterialCode(item.getMatCode()).setMaterialName(item.getMatName())
                .setMaterialBatch(item.getBatchNo()).setSupplierCode(item.getSupplierCode())
                .setStatus(item.getStatus()).setResult(item.getResult())
                .setSubmittedQuantity(decimal(item.getNum())).setUsableQuantity(decimal(item.getUsableNum()))
                .setHandledQuantity(decimal(item.getHandleNum())).setHandleResult(item.getHandleResult())
                .setTerminal(terminal).setAcceptedForInbound(accepted)
                .setLatestInspectionTime(item.getLatestInspectionTime() == null ? null
                        : LocalDateTime.ofInstant(item.getLatestInspectionTime().toInstant(), ZoneId.systemDefault()));
    }

    private String fingerprint(QmsIncomingInspectionEvidenceDTO.Evidence evidence) {
        try {
            String facts = objectMapper.writeValueAsString(List.of(evidence.getFactoryCode(), evidence.getTasks(),
                    evidence.getDataGaps()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new CheckedException("来料检验事实无法生成完整指纹");
        }
    }

    private Long positiveId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) {
            throw new CheckedException("QMS来料检验任务主键无效");
        }
    }

    private BigDecimal decimal(Integer value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }
}
