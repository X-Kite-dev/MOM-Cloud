package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.entity.QmsInspectionProcedureHandleRecordEntity;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductHandleRecordEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskEntity;
import com.kitex.mom.qms.mapper.QmsInspectionProcedureHandleRecordMapper;
import com.kitex.mom.qms.mapper.QmsInspectionProcedureTaskMapper;
import com.kitex.mom.qms.mapper.QmsInspectionProductHandleRecordMapper;
import com.kitex.mom.qms.mapper.QmsInspectionProductTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 统一读取并校验工序检/成品检处置的不可变权威事实。
 */
@Service
@RequiredArgsConstructor
public class QmsQualityDispositionSourceService {
    private final QmsInspectionProcedureTaskMapper procedureTaskMapper;
    private final QmsInspectionProductTaskMapper productTaskMapper;
    private final QmsInspectionProcedureHandleRecordMapper procedureHandleMapper;
    private final QmsInspectionProductHandleRecordMapper productHandleMapper;
    private final ObjectMapper objectMapper;

    private static String trim(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    public Source load(String inspectionType, Long inspectionTaskId, Long handleRecordId) {
        String type = StrUtil.blankToDefault(inspectionType, "").trim().toUpperCase(Locale.ROOT);
        require(inspectionTaskId != null && handleRecordId != null, "质检任务和处置记录不能为空");
        if ("PROCEDURE".equals(type)) {
            QmsInspectionProcedureTaskEntity task = procedureTaskMapper.selectById(inspectionTaskId);
            QmsInspectionProcedureHandleRecordEntity record = procedureHandleMapper.selectById(handleRecordId);
            require(task != null && record != null && Objects.equals(record.getTaskId(), task.getId()),
                    "工序质检任务与处置记录不存在或关联不一致");
            return source(type, task.getId(), record.getId(), record.getRequestId(), record.getHandleResult(),
                    record.getHandleNum(), record.getHandleRemark(), record.getHandleTime(), record.getHandlePersonId(),
                    record.getHandlePersonName(), task.getOrderNo(), task.getSourceTaskCode(), task.getProcessCode(),
                    task.getProcessName(), task.getMatCode(), task.getMatName(), task.getMatSpec(), task.getBatchNo(),
                    task.getNum(), task.getUsableNum(), task.getHandleNum(), task.getResult(),
                    task.getFactoryCode(), record.getFactoryCode());
        }
        if ("PRODUCT".equals(type)) {
            QmsInspectionProductTaskEntity task = productTaskMapper.selectById(inspectionTaskId);
            QmsInspectionProductHandleRecordEntity record = productHandleMapper.selectById(handleRecordId);
            require(task != null && record != null && Objects.equals(record.getTaskId(), task.getId()),
                    "成品质检任务与处置记录不存在或关联不一致");
            return source(type, task.getId(), record.getId(), record.getRequestId(), record.getHandleResult(),
                    record.getHandleNum(), record.getHandleRemark(), record.getHandleTime(), record.getHandlePersonId(),
                    record.getHandlePersonName(), task.getOrderNo(), task.getSourceTaskCode(), task.getProcessCode(),
                    task.getProcessName(), task.getMatCode(), task.getMatName(), task.getMatSpec(), task.getBatchNo(),
                    task.getNum(), task.getUsableNum(), task.getHandleNum(), task.getResult(),
                    task.getFactoryCode(), record.getFactoryCode());
        }
        throw new CheckedException("仅工序检和成品检的生产质量处置可进入智能排产事件链");
    }

    public Source loadIsolation(String inspectionType, Long inspectionTaskId) {
        String type = StrUtil.blankToDefault(inspectionType, "").trim().toUpperCase(Locale.ROOT);
        require(inspectionTaskId != null, "隔离事实的质检任务不能为空");
        if ("PROCEDURE".equals(type)) {
            QmsInspectionProcedureTaskEntity task = procedureTaskMapper.selectById(inspectionTaskId);
            require(task != null, "工序质检任务不存在");
            return isolation(type, task.getId(), task.getSourceRequestId(), task.getLatestInspectionTime(),
                    task.getOrderNo(), task.getSourceTaskCode(), task.getProcessCode(), task.getProcessName(),
                    task.getMatCode(), task.getMatName(), task.getMatSpec(), task.getBatchNo(), task.getNum(),
                    task.getUsableNum(), task.getHandleNum(), task.getResult(), task.getFactoryCode());
        }
        if ("PRODUCT".equals(type)) {
            QmsInspectionProductTaskEntity task = productTaskMapper.selectById(inspectionTaskId);
            require(task != null, "成品质检任务不存在");
            return isolation(type, task.getId(), task.getSourceRequestId(), task.getLatestInspectionTime(),
                    task.getOrderNo(), task.getSourceTaskCode(), task.getProcessCode(), task.getProcessName(),
                    task.getMatCode(), task.getMatName(), task.getMatSpec(), task.getBatchNo(), task.getNum(),
                    task.getUsableNum(), task.getHandleNum(), task.getResult(), task.getFactoryCode());
        }
        throw new CheckedException("仅工序检和成品检的不合格结论可形成隔离事实");
    }

    private Source isolation(String type, Long taskId, String ignoredRequestId, java.util.Date occurredTime,
                             String mesTaskCode, String mesProduceTaskCode, String processCode, String processName,
                             String matCode, String matName, String matSpec, String batchNo, Integer taskQuantity,
                             Integer usableQuantity, Integer handledQuantity, String taskResult, String factoryCode) {
        // 隔离事件可能晚于后续放行投递；任务终态变化不能让已冻结的不合格隔离事实失去重放能力。
        require(Set.of("UN_QUALIFIED", "RELEASE", "RECEIVE").contains(taskResult),
                "仅形成过不合格结论的质检任务可发布或重放隔离事实");
        return source(type, taskId, null, "QMS-ISOLATE:" + type + ':' + taskId, "ISOLATE",
                taskQuantity, "质检不合格，产出保持隔离待处置", occurredTime, null, "QMS质量系统",
                mesTaskCode, mesProduceTaskCode, processCode, processName, matCode, matName, matSpec, batchNo,
                taskQuantity, usableQuantity, handledQuantity, taskResult, factoryCode, factoryCode);
    }

    private Source source(String type, Long taskId, Long recordId, String requestId, String result,
                          Integer quantity, String reason, java.util.Date handledTime, Long operatorId,
                          String operatorName, String mesTaskCode, String mesProduceTaskCode,
                          String processCode, String processName, String matCode, String matName,
                          String matSpec, String batchNo, Integer taskQuantity, Integer usableQuantity,
                          Integer handledQuantity, String taskResult, String taskFactory, String recordFactory) {
        String kind = StrUtil.blankToDefault(result, "").trim().toUpperCase(Locale.ROOT);
        require(StrUtil.isNotBlank(requestId) && Set.of("ISOLATE", "RELEASE", "CONCESSION", "REWORK", "SMASH").contains(kind),
                "仅隔离、放行、让步、返工和已批准报废可进入质量排产事件链");
        require(quantity != null && quantity > 0 && StrUtil.isNotBlank(reason), "质量处置数量和原因不完整");
        require(StrUtil.isNotBlank(mesTaskCode) && StrUtil.isNotBlank(matCode),
                "质量处置未关联MES生产工单或产品");
        require(!"PROCEDURE".equals(type) || StrUtil.isNotBlank(mesProduceTaskCode),
                "工序质量处置未关联MES工序任务");
        require(StrUtil.isNotBlank(taskFactory) && Objects.equals(taskFactory, recordFactory),
                "质量任务与处置记录工厂不一致");
        int total = Objects.requireNonNullElse(taskQuantity, 0);
        int usable = Objects.requireNonNullElse(usableQuantity, 0);
        int handled = Objects.requireNonNullElse(handledQuantity, 0);
        require(total > 0 && usable >= 0 && handled >= 0 && usable <= handled && handled <= total,
                "质检任务总量、已处置量与可用量不守恒");
        if (Set.of("RELEASE", "CONCESSION").contains(kind)) {
            require(usable >= quantity, "本次放行或让步数量超过质检任务累计可用量");
        }
        LocalDateTime occurred = handledTime == null ? LocalDateTime.now()
                : LocalDateTime.ofInstant(handledTime.toInstant(), ZoneId.systemDefault());
        occurred = occurred.truncatedTo(ChronoUnit.MILLIS);
        return new Source(type, taskId, recordId, requestId.trim(), kind, BigDecimal.valueOf(quantity),
                reason.trim(), occurred, operatorId == null ? "QMS" : String.valueOf(operatorId),
                StrUtil.blankToDefault(operatorName, "QMS"), mesTaskCode.trim(), trim(mesProduceTaskCode),
                trim(processCode), trim(processName), matCode.trim(), trim(matName), trim(matSpec), trim(batchNo),
                BigDecimal.valueOf(total), BigDecimal.valueOf(usable), BigDecimal.valueOf(handled),
                taskResult, taskFactory);
    }

    public String snapshot(Source source) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("inspectionType", source.inspectionType());
        value.put("inspectionTaskId", source.inspectionTaskId());
        value.put("handleRecordId", source.handleRecordId());
        value.put("sourceRequestId", source.sourceRequestId());
        value.put("dispositionKind", source.dispositionKind());
        value.put("quantity", source.quantity());
        value.put("reason", source.reason());
        value.put("occurredTime", source.occurredTime());
        value.put("operatorCode", source.operatorCode());
        value.put("operatorName", source.operatorName());
        value.put("mesTaskCode", source.mesTaskCode());
        value.put("mesProduceTaskCode", source.mesProduceTaskCode());
        value.put("processCode", source.processCode());
        value.put("processName", source.processName());
        value.put("matCode", source.matCode());
        value.put("matName", source.matName());
        value.put("matSpec", source.matSpec());
        value.put("batchNo", source.batchNo());
        // 任务总量/可用量/已处置量/最终结论不属于单笔处置事实；排除它们也保持旧版报废指纹兼容。
        value.put("factoryCode", source.factoryCode());
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new CheckedException("QMS质量处置事实序列化失败");
        }
    }

    public String fingerprint(Source source) {
        return hash(snapshot(source));
    }

    public String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("QMS质量处置事实摘要生成失败");
        }
    }

    public record Source(String inspectionType, Long inspectionTaskId, Long handleRecordId, String sourceRequestId,
                         String dispositionKind, BigDecimal quantity, String reason, LocalDateTime occurredTime,
                         String operatorCode, String operatorName, String mesTaskCode, String mesProduceTaskCode,
                         String processCode, String processName, String matCode, String matName, String matSpec,
                         String batchNo, BigDecimal taskQuantity, BigDecimal usableQuantity,
                         BigDecimal handledQuantity, String taskResult, String factoryCode) {
    }
}
