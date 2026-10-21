package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesPlanExecutionEvidenceDTO;
import com.kitex.mom.mes.dto.MesReportSourceAssignmentDTO;
import com.kitex.mom.mes.entity.MesOfflineReportReplayEntity;
import com.kitex.mom.mes.entity.MesAutoReportEventEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.MesOfflineReportReplayMapper;
import com.kitex.mom.mes.mapper.MesAutoReportEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 完整来源产出事件读侧，不计算第二套报工量、不回写生产事实。
 *
 * <p>复用唯一末序和最新有效来源核定。弱网账在同一MES快照中一次批量读取，
 * 客户端时刻不提升为现场完成证明；未知时间和数量身份缺口分开保留。</p>
 */
@Component
@RequiredArgsConstructor
public class MesPlanProductionEventReader {
    private final MesOfflineReportReplayMapper offlineMapper;
    private final MesAutoReportEventMapper autoEventMapper;

    /** 读取实际原事件审计依据，不把AUTO文字或用户传入时刻直接当作设备采集证明。 */
    public AuditEvidence auditEvidence(List<MesReportSourceAssignmentDTO.Preview> proofs, String factory) {
        var offline = offlineEvidence(proofs, factory);
        List<Long> reports = proofs.stream().flatMap(proof -> proof.getReports().stream())
                .filter(report -> "AUTO".equals(report.getReportType()))
                .map(report -> Long.parseLong(report.getReportId())).distinct().toList();
        require(reports.size() <= 100000, "设备产出事件范围超过完整取证上限");
        List<MesAutoReportEventEntity> events = new ArrayList<>();
        for (int offset = 0; offset < reports.size(); offset += 5000) {
            events.addAll(autoEventMapper.selectList(new LambdaQueryWrapper<MesAutoReportEventEntity>()
                    .eq(MesAutoReportEventEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesAutoReportEventEntity::getFactoryCode, factory)
                    .in(MesAutoReportEventEntity::getReportId, reports.subList(offset, Math.min(offset + 5000, reports.size())))
                    .orderByAsc(MesAutoReportEventEntity::getId).last("LIMIT " + (100001 - events.size()))));
            require(events.size() <= 100000, "设备原事件依据超过完整取证上限，禁止截断");
        }
        return new AuditEvidence(offline, events.stream().collect(Collectors.groupingBy(MesAutoReportEventEntity::getReportId)));
    }

    /** 整版分块批量读取，不按工单/报工逐次查询；避开PG绑定参数上限，保持同一事务快照。 */
    public Map<String, List<MesOfflineReportReplayEntity>> offlineEvidence(
            List<MesReportSourceAssignmentDTO.Preview> proofs, String factory) {
        List<String> requests = proofs.stream().flatMap(proof -> proof.getReports().stream())
                .map(MesReportSourceAssignmentDTO.Report::getRequestId).filter(StrUtil::isNotBlank)
                .distinct().toList();
        require(requests.size() <= 100000, "产出事件永久号超过完整取证上限，禁止截断");
        if (requests.isEmpty()) return Map.of();
        List<MesOfflineReportReplayEntity> rows = new ArrayList<>();
        for (int offset = 0; offset < requests.size(); offset += 5000) {
            rows.addAll(offlineMapper.selectList(new LambdaQueryWrapper<MesOfflineReportReplayEntity>()
                    .eq(MesOfflineReportReplayEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesOfflineReportReplayEntity::getFactoryCode, factory)
                    .in(MesOfflineReportReplayEntity::getRequestId, requests.subList(offset, Math.min(offset + 5000, requests.size())))
                    .orderByAsc(MesOfflineReportReplayEntity::getId).last("LIMIT " + (100001 - rows.size()))));
            require(rows.size() <= 100000, "弱网受理证据超过完整取证上限，禁止截断");
        }
        return rows.stream().collect(Collectors.groupingBy(MesOfflineReportReplayEntity::getRequestId));
    }

    /** 只选本来源份额，完整合计严格与来源当前报工量相同。 */
    public void populate(MesPlanExecutionEvidenceDTO.Link link, MesReportSourceAssignmentDTO.Preview proof,
                         AuditEvidence audits, LocalDateTime observedAt) {
        List<String> gaps = new ArrayList<>();
        List<MesPlanExecutionEvidenceDTO.ProductionEvent> events = new ArrayList<>();
        link.setProductionEvents(events).setProductionEventGaps(gaps).setProductionEventsComplete(false);
        String identity = proof.getSourceIdentityFingerprint();
        if (identity == null || !identity.matches("[0-9a-f]{64}")) {
            gaps.add("缺少完整来源/唯一末序身份指纹");
            return;
        }
        String sourceFingerprint = hash(JSONUtil.toJsonStr(Arrays.asList(identity, link.getRelationType(),
                link.getAllocationId(), link.getSourceIssueId(), link.getProductCode(), link.getSourceQuantity())));
        link.setSourceFingerprint(sourceFingerprint);
        if (!Boolean.TRUE.equals(proof.getSourceComplete()) || link.getCompletedQuantity() == null) {
            gaps.add("来源未核清或核定失效，不能生成完整产出事件");
            return;
        }
        Set<String> ids = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var report : proof.getReports()) {
            if ("REVERSED".equals(report.getStatus())) continue;
            if (!"EFFECTIVE".equals(report.getStatus()) || !ids.add(report.getReportId())
                    || !Set.of("DIRECT", "ASSIGNED").contains(StrUtil.blankToDefault(report.getAssignmentState(), "UNKNOWN"))) {
                gaps.add("报工" + report.getReportId() + "缺完整唯一当前来源依据");
                continue;
            }
            BigDecimal quantity = contribution(report, link.getAllocationId());
            if (quantity == null) {
                gaps.add("报工" + report.getReportId() + "当前来源份额不可核验");
                continue;
            }
            if (quantity.signum() == 0) continue;
            String mode;
            try {
                mode = ReportModeEnum.fromCode(report.getReportMode()).getCode();
            } catch (IllegalArgumentException exception) {
                gaps.add("报工" + report.getReportId() + "未冻结合法报工模式，不能从当前路线补猜");
                continue;
            }
            if (StrUtil.isBlank(report.getReportUnit()) || report.getEventFingerprint() == null
                    || !report.getEventFingerprint().matches("[0-9a-f]{64}")) {
                gaps.add("报工" + report.getReportId() + "单位或完整事实指纹缺失");
                continue;
            }
            var event = new MesPlanExecutionEvidenceDTO.ProductionEvent().setReportId(report.getReportId())
                    .setTaskId(link.getTaskId().toString())
                    .setAllocationId(link.getAllocationId() == null ? null : link.getAllocationId().toString())
                    .setQuantity(decimal(quantity)).setPhysicalEffectiveQuantity(report.getEffectiveQuantity())
                    .setReportMode(mode).setReportUnit(report.getReportUnit().trim())
                    .setReportTime(report.getReportTime()).setSourceEventId(report.getSourceEventId())
                    .setSourceEventTime(report.getSourceEventTime()).setFactFingerprint(report.getEventFingerprint())
                    .setSourceFingerprint(sourceFingerprint).setAssignmentId(report.getAssignmentId())
                    .setProvenanceFingerprint(hash(JSONUtil.toJsonStr(Arrays.asList(sourceFingerprint,
                            report.getAssignmentState(), report.getAssignmentId(), report.getAllocations()))));
            timeEvidence(event, report, report.getRequestId() == null ? List.of()
                    : audits.offline().getOrDefault(report.getRequestId(), List.of()),
                    audits.device().getOrDefault(Long.parseLong(report.getReportId()), List.of()), observedAt);
            events.add(event);
            total = total.add(quantity);
        }
        if (total.compareTo(quantity(link.getCompletedQuantity())) != 0) {
            gaps.add("完整逐报工来源贡献与来源当前有效报工量不守恒");
        }
        if (events.stream().map(MesPlanExecutionEvidenceDTO.ProductionEvent::getReportUnit).distinct().count() > 1) {
            gaps.add("同一来源末序报工冻结单位不一致，禁止无依据换算");
        }
        link.setProductionEventsComplete(gaps.isEmpty());
    }

    private BigDecimal contribution(MesReportSourceAssignmentDTO.Report report, Long allocationId) {
        if ("DIRECT".equals(report.getAssignmentState())) return quantity(report.getEffectiveQuantity());
        if (allocationId == null || report.getAllocations() == null) return null;
        var shares = report.getAllocations().stream()
                .filter(share -> Objects.equals(share.getAllocationId(), allocationId.toString())).toList();
        return shares.isEmpty() ? BigDecimal.ZERO : shares.size() == 1 ? quantity(shares.get(0).getQuantity()) : null;
    }

    /** 时间分类不能用客户端capture或核定创建时刻替换原报工的时间依据。 */
    private void timeEvidence(MesPlanExecutionEvidenceDTO.ProductionEvent event,
            MesReportSourceAssignmentDTO.Report report, List<MesOfflineReportReplayEntity> offline,
            List<MesAutoReportEventEntity> deviceEvents, LocalDateTime observedAt) {
        event.setTimeBasis("UNVERIFIED");
        if (!offline.isEmpty()) {
            event.setTimeBasis("OFFLINE_UNVERIFIED").setTimeGap("弱网客户端采集时刻未独立核验，接收时刻不证明现场完成");
            if (offline.size() == 1 && "ACCEPTED".equals(offline.get(0).getStatus())
                    && Objects.equals(Objects.toString(offline.get(0).getReportId(), null), report.getReportId())) {
                event.setOfflineCapturedAt(time(offline.get(0).getCapturedAt()));
            } else {
                event.setTimeGap("弱网受理账与原报工引用或状态不一致，须核对原永久号");
            }
            return;
        }
        LocalDateTime reportTime = parse(report.getReportTime());
        LocalDateTime deviceTime = parse(report.getSourceEventTime());
        if (reportTime == null || reportTime.isAfter(observedAt)) {
            event.setTimeGap("原报工时刻缺失、格式损坏或处于采集时刻之后");
        } else if (StrUtil.isNotBlank(report.getSourceEventId()) || report.getSourceEventTime() != null) {
            if ("AUTO".equals(report.getReportType()) && deviceTime != null && deviceTime.equals(reportTime)
                    && StrUtil.isNotBlank(report.getSourceEventId()) && !deviceTime.isAfter(observedAt)
                    && deviceEvents.size() == 1 && verifiedDevice(deviceEvents.get(0), report)) {
                var source = deviceEvents.get(0);
                event.setTimeBasis("DEVICE_EVENT").setFactFingerprint(hash(JSONUtil.toJsonStr(Arrays.asList(
                        event.getFactFingerprint(), source.getId(), source.getSourceEventId(), time(source.getSourceEventTime()),
                        decimal(source.getResolvedQuantity()), source.getArrangeCode(), source.getRuleId(), source.getConversionSnapshot()))));
            } else {
                event.setTimeGap("设备事件号、通道、SUCCESS原事件或冻结换算证据与原报工不一致");
            }
        } else if (Set.of("MANUAL", "API").contains(StrUtil.blankToDefault(report.getReportType(), "UNKNOWN"))) {
            event.setTimeBasis("REPORT_REGISTERED").setTimeGap("仅证明服务端报工登记时间，不证明现场实际完成时间");
        } else {
            event.setTimeGap("历史报工未冻结合法通道，不能猜测时间依据");
        }
    }

    private boolean verifiedDevice(MesAutoReportEventEntity source, MesReportSourceAssignmentDTO.Report report) {
        return "SUCCESS".equals(source.getStatus()) && Objects.equals(source.getSourceEventId(), report.getSourceEventId())
                && Objects.equals(time(source.getSourceEventTime()), report.getSourceEventTime())
                && Objects.equals(source.getArrangeCode(), report.getArrangeCode())
                && source.getResolvedQuantity() != null && source.getResolvedQuantity().compareTo(quantity(report.getOriginalQuantity())) == 0
                && source.getRuleId() != null && StrUtil.isNotBlank(source.getDeviceCode())
                && StrUtil.isNotBlank(source.getConversionSnapshot());
    }

    private LocalDateTime parse(String value) {
        try {
            return value == null ? null : LocalDateTime.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String time(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private BigDecimal quantity(String value) {
        require(value != null && value.matches("[0-9]{1,22}(\\.[0-9]{1,8})?"), "产出事件数量缺失或超过8位精度，禁止舍入");
        return new BigDecimal(value);
    }

    private String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("产出事件指纹计算失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    /** 仅业务读侧内部使用，来源表行不直接返回到客户端。 */
    public record AuditEvidence(Map<String, List<MesOfflineReportReplayEntity>> offline,
                                Map<Long, List<MesAutoReportEventEntity>> device) {
    }
}
