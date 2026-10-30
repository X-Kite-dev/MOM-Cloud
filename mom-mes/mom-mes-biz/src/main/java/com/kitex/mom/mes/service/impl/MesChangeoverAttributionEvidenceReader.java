package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.mes.dto.MesChangeoverAttributionDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesChangeoverAttributionMapper;
import com.kitex.mom.mes.mapper.MesChangeoverAttributionOperationMapper;
import com.kitex.mom.mes.mapper.MesChangeoverExecutionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesChangeoverAttributionFactsReader.*;

/**
 * APS新观察的换型归属证明。与原RUN读侧同一事务；只读当前案件和完整原源，旧观察不改写。
 * 真实换型与RUN相交直接阻止时间采信，不从RUN减掉一段猜“纯加工”。
 */
@Component
@RequiredArgsConstructor
public class MesChangeoverAttributionEvidenceReader {
    private final MesChangeoverAttributionMapper caseMapper;
    private final MesChangeoverAttributionOperationMapper operationMapper;
    private final MesChangeoverExecutionMapper executionMapper;
    private final MesChangeoverAttributionFactsReader factsReader;

    public Result read(MesProduceTaskEntity produce, List<MesTaskArrangeEntity> arrangements,
                       List<MesTaskExecutionSegmentEntity> segments) {
        var rows = caseMapper.selectList(new LambdaQueryWrapper<MesChangeoverAttributionEntity>()
                .eq(MesChangeoverAttributionEntity::getTenantId, produce.getTenantId())
                .eq(MesChangeoverAttributionEntity::getFactoryCode, produce.getFactoryCode())
                .eq(MesChangeoverAttributionEntity::getProduceTaskCode, produce.getCode())
                .orderByAsc(MesChangeoverAttributionEntity::getId).last("LIMIT 1001"));
        require(rows.size() <= 1000, "工序完整换型归属超过1000笔，不能截断");
        List<Map<String, Object>> records = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        List<MesChangeoverExecutionEntity> trusted = new ArrayList<>();
        List<MesChangeoverAttributionEntity> approved = new ArrayList<>();
        boolean contested = false;
        for (var row : rows) {
            var operations = operationMapper.selectList(new LambdaQueryWrapper<MesChangeoverAttributionOperationEntity>()
                    .eq(MesChangeoverAttributionOperationEntity::getTenantId, produce.getTenantId())
                    .eq(MesChangeoverAttributionOperationEntity::getFactoryCode, produce.getFactoryCode())
                    .eq(MesChangeoverAttributionOperationEntity::getCaseId, row.getId())
                    .orderByAsc(MesChangeoverAttributionOperationEntity::getId).last("LIMIT 1001"));
            require(operations.size() <= 1000, "完整归属操作履历超限");
            Map<String, Object> record = new TreeMap<>();
            record.put("case", exact(row));
            record.put("operations", exact(operations));
            records.add(record);
            if ("ASSIGN".equals(row.getCaseType()) && "PENDING".equals(row.getStatus())) {
                gaps.add("归属[" + row.getId() + "]尚待独立核定，完整工序换型范围未明确");
            }
            if (!"ASSIGN".equals(row.getCaseType()) || !"APPROVED".equals(row.getStatus())) continue;
            approved.add(row);
            if (rows.stream().anyMatch(candidate -> "REVOKE".equals(candidate.getCaseType())
                    && "PENDING".equals(candidate.getStatus()) && Objects.equals(candidate.getOriginalCaseId(), row.getId()))) {
                contested = true;
                gaps.add("归属[" + row.getId() + "]存在待审撤销，不能继续采信");
            }
            try {
                var facts = factsReader.read(row.getExecutionId(), row.getArrangeId(), produce.getFactoryCode(), false);
                record.put("currentSource", facts.preview().getSourceSnapshot());
                record.put("currentTarget", facts.preview().getTargetSnapshot());
                record.put("currentFingerprint", facts.preview().getExpectedFingerprint());
                record.put("currentGaps", facts.preview().getGaps());
                if (!Boolean.TRUE.equals(facts.preview().getEligible())
                        || !Objects.equals(row.getBasisFingerprint(), facts.preview().getExpectedFingerprint())
                        || !Objects.equals(facts.arrange().getProduceTaskCode(), produce.getCode())
                        || !Objects.equals(facts.arrange().getTaskCode(), produce.getTaskCode()))
                    gaps.add("归属[" + row.getId() + "]原事实/稳定身份不可重采核验");
                else trusted.add(facts.source());
            } catch (RuntimeException exception) {
                gaps.add("归属[" + row.getId() + "]当前完整凭证不可核验");
                record.put("currentGaps", List.of("当前完整凭证不可核验"));
            }
        }
        var overlap = overlapEvents(produce, segments);
        records.addAll(overlap.records());
        if (overlap.overlaps()) gaps.add("原设备换型活动与RUN段重叠；不能用生命周期段冒充无换型生产时间");
        trusted.sort(Comparator.comparing(MesChangeoverExecutionEntity::getStartedTime));
        for (int index = 1; index < trusted.size(); index++) {
            if (trusted.get(index).getStartedTime().isBefore(trusted.get(index - 1).getCompletedTime()))
                gaps.add("核定换型原区间相互重叠，不能累加为真实换型历时");
        }
        var evidence = new MesChangeoverAttributionDTO.Evidence().setRecords(records).setGaps(gaps)
                .setStatus(contested ? "CONTESTED" : gaps.isEmpty() ? approved.isEmpty() ? "UNBOUND" : "VERIFIED" : "DATA_GAP");
        if (gaps.isEmpty() && !trusted.isEmpty() && trusted.size() == approved.size()) {
            evidence.setSeconds(Long.toString(trusted.stream().mapToLong(MesChangeoverExecutionEntity::getDurationSeconds).reduce(0, Math::addExact)))
                    .setExecutionCount(trusted.size());
            var effective = arrangements.stream().filter(row -> !("CLOSE".equals(row.getStatus()) && row.getStartTime() == null
                            && row.getFinishTime() == null && row.getCompleteNum() != null && row.getCompleteNum().signum() == 0
                            && segments.stream().noneMatch(segment -> Objects.equals(segment.getArrangeId(), row.getId()))))
                    .sorted(Comparator.comparing(MesTaskArrangeEntity::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(MesTaskArrangeEntity::getId)).toList();
            evidence.setFirstDispatchOnly(trusted.size() == 1 && approved.size() == 1 && !effective.isEmpty()
                    && Objects.equals(approved.get(0).getArrangeId(), effective.get(0).getId())
                    && effective.get(0).getStartTime() != null
                    && !trusted.get(0).getCompletedTime().isAfter(effective.get(0).getStartTime()));
        } else evidence.setFirstDispatchOnly(false);
        require(bytes(json(evidence)) <= 8 * 1024 * 1024, "换型完整取证超过8MiB，不能裁剪履历");
        return new Result(evidence, overlap.overlaps());
    }

    /**
     * 完整扫描每个实际RUN资源相交活动，包括未结束/已取消账；未绑定也不能被忽略。
     */
    private Overlap overlapEvents(MesProduceTaskEntity produce, List<MesTaskExecutionSegmentEntity> segments) {
        List<Map<String, Object>> records = new ArrayList<>();
        boolean overlaps = false;
        var resources = segments.stream().filter(segment -> segment.getStartTime() != null && segment.getEndTime() != null
                        && segment.getDeviceCode() != null && segment.getStationCode() != null)
                .collect(java.util.stream.Collectors.groupingBy(segment -> List.of(segment.getStationCode(), segment.getDeviceCode())));
        for (var resource : resources.entrySet()) {
            LocalDateTime first = resource.getValue().stream().map(MesTaskExecutionSegmentEntity::getStartTime).min(Comparator.naturalOrder()).orElseThrow();
            LocalDateTime last = resource.getValue().stream().map(MesTaskExecutionSegmentEntity::getEndTime).max(Comparator.naturalOrder()).orElseThrow();
            var events = executionMapper.selectList(new LambdaQueryWrapper<MesChangeoverExecutionEntity>()
                    .eq(MesChangeoverExecutionEntity::getTenantId, produce.getTenantId())
                    .eq(MesChangeoverExecutionEntity::getFactoryCode, produce.getFactoryCode())
                    .eq(MesChangeoverExecutionEntity::getWorksiteCode, resource.getKey().get(0))
                    .eq(MesChangeoverExecutionEntity::getDeviceCode, resource.getKey().get(1))
                    .and(query -> query.isNull(MesChangeoverExecutionEntity::getStartedTime)
                            .or().lt(MesChangeoverExecutionEntity::getStartedTime, last))
                    .and(query -> query.ge(MesChangeoverExecutionEntity::getCompletedTime, first)
                            .or().ge(MesChangeoverExecutionEntity::getCancelledTime, first)
                            .or(none -> none.isNull(MesChangeoverExecutionEntity::getCompletedTime)
                                    .isNull(MesChangeoverExecutionEntity::getCancelledTime)))
                    .orderByAsc(MesChangeoverExecutionEntity::getId).last("LIMIT 1001"));
            require(events.size() <= 1000, "RUN资源相交换型超过1000笔，不截断");
            for (var event : events) {
                LocalDateTime end = "CANCELLED".equals(event.getStatus()) ? event.getCancelledTime() : event.getCompletedTime();
                boolean intersects = event.getStartedTime() == null || end != null && !end.isAfter(event.getStartedTime())
                        || resource.getValue().stream().anyMatch(segment -> event.getStartedTime().isBefore(segment.getEndTime())
                        && (end == null || end.isAfter(segment.getStartTime())));
                records.add(Map.of("resourceEvent", exact(event), "runOverlapOrUnknown", intersects));
                overlaps |= intersects;
            }
        }
        return new Overlap(records, overlaps);
    }

    public record Result(MesChangeoverAttributionDTO.Evidence evidence, boolean overlapsRun) {
    }

    private record Overlap(List<Map<String, Object>> records, boolean overlaps) {
    }
}
