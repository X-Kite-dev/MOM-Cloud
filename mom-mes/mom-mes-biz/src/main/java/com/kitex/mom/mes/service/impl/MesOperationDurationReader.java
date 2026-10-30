package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesOperationDurationDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskExecutionSegmentEntity;
import com.kitex.mom.mes.mapper.MesProduceTaskMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTaskExecutionSegmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 完整生产段只读取证，支持同资源顺序多派工，不把并行资源秒数相加当工序历时。
 *
 * <p>工序、派工、段同一重复读快照；未知只保留原证据和缺口，不推断纯加工/人时/停机原因。</p>
 */
@Service
@RequiredArgsConstructor
public class MesOperationDurationReader {
    private final MesActualFactoryAccessService factoryAccess;
    private final MesProduceTaskMapper produceMapper;
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesTaskExecutionSegmentMapper segmentMapper;
    private final MesChangeoverAttributionEvidenceReader changeoverReader;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesOperationDurationDTO.Evidence read(MesOperationDurationDTO.Query query) {
        String factory = factoryAccess.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        require(query != null && Objects.equals(factory, query.getFactoryCode()), "时间取证须指定当前实际工厂");
        List<String> codes = query.getProduceTaskCodes();
        require(codes != null && !codes.isEmpty() && codes.size() <= 500, "须指定1至500个精确工序任务编码");
        Set<String> unique = new HashSet<>();
        require(codes.stream().allMatch(code -> StrUtil.isNotBlank(code) && code.equals(code.trim())
                && code.length() <= 100 && unique.add(code)), "工序任务编码须非空、无空格且唯一");
        LocalDateTime queried = LocalDateTime.now();
        var produces = produceMapper.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, tenant).eq(MesProduceTaskEntity::getFactoryCode, factory)
                .in(MesProduceTaskEntity::getCode, codes).orderByAsc(MesProduceTaskEntity::getId).last("LIMIT 1001"));
        require(produces.size() <= 1000, "工序身份超限，不能截断取证");
        var arrangements = arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .in(MesTaskArrangeEntity::getProduceTaskCode, codes).orderByAsc(MesTaskArrangeEntity::getId)
                .last("LIMIT 10001"));
        require(arrangements.size() <= 10000, "完整派工超过10000行，不能截断取证");
        var segments = arrangements.isEmpty() ? List.<MesTaskExecutionSegmentEntity>of()
                : segmentMapper.selectList(new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
                .eq(MesTaskExecutionSegmentEntity::getTenantId, tenant)
                .eq(MesTaskExecutionSegmentEntity::getFactoryCode, factory)
                .in(MesTaskExecutionSegmentEntity::getArrangeId, arrangements.stream().map(MesTaskArrangeEntity::getId).toList())
                .orderByAsc(MesTaskExecutionSegmentEntity::getArrangeId)
                .orderByAsc(MesTaskExecutionSegmentEntity::getSegmentNo).orderByAsc(MesTaskExecutionSegmentEntity::getId)
                .last("LIMIT 100001"));
        require(segments.size() <= 100000, "完整生产段超过100000行，不能截断取证");
        var byCode = produces.stream().collect(Collectors.groupingBy(MesProduceTaskEntity::getCode));
        var byProduce = arrangements.stream().collect(Collectors.groupingBy(MesTaskArrangeEntity::getProduceTaskCode));
        var byArrange = segments.stream().collect(Collectors.groupingBy(MesTaskExecutionSegmentEntity::getArrangeId));
        List<MesOperationDurationDTO.Operation> results = new ArrayList<>();
        for (String code : codes) {
            var matches = byCode.getOrDefault(code, List.of());
            if (matches.size() != 1) {
                results.add(finish(new MesOperationDurationDTO.Operation().setProduceTaskCode(code)
                        .setEvidenceStatus("DATA_GAP").setGaps(List.of("实际组织内未找到唯一工序任务"))
                        .setArrangements(List.of())));
                continue;
            }
            results.add(operation(matches.get(0), byProduce.getOrDefault(code, List.of()), byArrange, queried));
        }
        var evidence = new MesOperationDurationDTO.Evidence().setTenantId(tenant.toString()).setFactoryCode(factory)
                .setEvidenceVersion("APS_RUN_SEGMENTS_V2").setQueriedTime(queried).setOperations(results);
        require(JSONUtil.toJsonStr(evidence).getBytes(StandardCharsets.UTF_8).length <= 8 * 1024 * 1024,
                "完整时间证据超过8MiB，不能省略原生产段");
        return evidence;
    }

    private MesOperationDurationDTO.Operation operation(MesProduceTaskEntity produce,
                                                        List<MesTaskArrangeEntity> arrangements,
                                                        java.util.Map<Long, List<MesTaskExecutionSegmentEntity>> byArrange, LocalDateTime queried) {
        var result = new MesOperationDurationDTO.Operation().setProduceTaskCode(produce.getCode())
                .setTenantId(produce.getTenantId().toString()).setFactoryCode(produce.getFactoryCode())
                .setProduceTaskId(produce.getId().toString()).setTaskCode(produce.getTaskCode())
                .setProductCode(produce.getMatCode()).setProcessCode(produce.getRouteCode())
                .setMesStatus(produce.getStatus()).setOriginalQuantity(decimal(produce.getNoticeNum()))
                .setCompletedQuantity(decimal(produce.getCompleteNum())).setApsOperationSnapshot(produce.getApsOperationSnapshot())
                .setActualStartTime(time(produce.getStartTime())).setActualFinishTime(time(produce.getFinishTime()));
        List<String> gaps = new ArrayList<>();
        List<MesOperationDurationDTO.Arrangement> proof = new ArrayList<>();
        List<MesTaskArrangeEntity> effective = new ArrayList<>();
        for (var arrange : arrangements) {
            var rows = byArrange.getOrDefault(arrange.getId(), List.of());
            var item = arrangement(arrange, rows);
            proof.add(item);
            if (!sameSource(produce, arrange)) gaps.add("派工[" + arrange.getCode() + "]组织/工单/工序/产品不符");
            if ("CLOSE".equals(arrange.getStatus()) && arrange.getStartTime() == null
                    && arrange.getFinishTime() == null && arrange.getCompleteNum() != null
                    && arrange.getCompleteNum().signum() == 0 && rows.isEmpty()
                    && sameSource(produce, arrange)) {
                item.setExclusionReason("关闭前未开工、未产出且无时间段，不参与生产历时");
            } else {
                effective.add(arrange);
            }
        }
        result.setArrangements(proof);
        if (!"FINISH".equals(produce.getStatus())) gaps.add("工序尚未正常完工，不据局部生产段计算全量历时");
        if (effective.isEmpty()) gaps.add("没有可核验的已执行派工");
        if (!positive(produce.getNoticeNum()) || produce.getCompleteNum() == null
                || produce.getNoticeNum().compareTo(produce.getCompleteNum()) != 0) gaps.add("工序原量与完成量不守恒");
        BigDecimal quantity = BigDecimal.ZERO;
        long run = 0;
        long dispatchWall = 0;
        long pauseGap = 0;
        long interDispatchGap = 0;
        String station = null;
        String device = null;
        LocalDateTime first = null;
        LocalDateTime last = null;
        effective.sort(Comparator.comparing(MesTaskArrangeEntity::getStartTime,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(MesTaskArrangeEntity::getId));
        for (var arrange : effective) {
            var rows = byArrange.getOrDefault(arrange.getId(), List.of());
            if (!Objects.equals(produce.getApsOperationSnapshot(), arrange.getApsOperationSnapshot()))
                gaps.add("派工[" + arrange.getCode() + "]与工序冻结APS依据不同");
            if (!"FINISH".equals(arrange.getStatus()) || !positive(arrange.getCompleteNum())
                    || arrange.getNoticeNum() == null || arrange.getNoticeNum().compareTo(arrange.getCompleteNum()) != 0)
                gaps.add("派工[" + arrange.getCode() + "]未正常完工或原量与产出不守恒");
            if (arrange.getCompleteNum() != null) quantity = quantity.add(arrange.getCompleteNum());
            String currentStation = arrange.getActualWorksiteCode();
            String currentDevice = StrUtil.emptyToNull(arrange.getPlannedDeviceCode());
            if (StrUtil.isBlank(currentStation)) gaps.add("派工[" + arrange.getCode() + "]缺实际工位");
            if (station == null) {
                station = currentStation;
                device = currentDevice;
            } else if (!Objects.equals(station, currentStation) || !Objects.equals(device, currentDevice))
                gaps.add("同工序跨实际工位/设备，不能汇总为同资源生产历时");
            if (!validInterval(arrange.getStartTime(), arrange.getFinishTime(), queried)) {
                gaps.add("派工[" + arrange.getCode() + "]开完工缺失、倒置或未来");
            } else {
                if (last != null && arrange.getStartTime().isBefore(last))
                    gaps.add("派工区间重叠，不能把并行人时当历时");
                else if (last != null) interDispatchGap = Math.addExact(interDispatchGap,
                        Duration.between(last, arrange.getStartTime()).getSeconds());
                if (first == null) first = arrange.getStartTime();
                if (last == null || arrange.getFinishTime().isAfter(last)) last = arrange.getFinishTime();
                dispatchWall = Math.addExact(dispatchWall, Duration.between(arrange.getStartTime(), arrange.getFinishTime()).getSeconds());
            }
            SegmentDurations durations = segments(produce, arrange, rows, queried, gaps);
            run = Math.addExact(run, durations.runSeconds());
            pauseGap = Math.addExact(pauseGap, durations.pauseGapSeconds());
        }
        if (produce.getCompleteNum() == null || quantity.compareTo(produce.getCompleteNum()) != 0)
            gaps.add("全部有效派工产出与工序完成量不守恒，不能忽略纠正/关闭事实");
        if (first != null && last != null && (!Objects.equals(first, produce.getStartTime())
                || !Objects.equals(last, produce.getFinishTime()))) gaps.add("全部派工首尾与工序实际开完工不一致");
        if (gaps.isEmpty()) {
            long wall = Duration.between(first, last).getSeconds();
            long components = Math.addExact(run, Math.addExact(pauseGap, interDispatchGap));
            if (run <= 0 || run > dispatchWall || dispatchWall > wall || components > wall)
                gaps.add("生产段/暂停/派工间隙/全量墙钟不守恒");
            else result.setRunSegmentSeconds(Long.toString(run)).setWallClockSeconds(Long.toString(wall))
                    .setPauseGapSeconds(Long.toString(pauseGap))
                    .setInterDispatchGapSeconds(Long.toString(interDispatchGap))
                    .setPrecisionRemainderSeconds(Long.toString(wall - components))
                    .setActualStartTime(time(first)).setActualFinishTime(time(last));
        }
        var changeover = changeoverReader.read(produce, arrangements, arrangements.stream()
                .flatMap(arrange -> byArrange.getOrDefault(arrange.getId(), List.of()).stream()).toList());
        result.setChangeoverEvidence(changeover.evidence());
        if (changeover.overlapsRun()) {
            gaps.add("实际换型与RUN重叠或活动时间未知，生产分钟不能采信");
            result.setRunSegmentSeconds(null).setWallClockSeconds(null).setPauseGapSeconds(null)
                    .setInterDispatchGapSeconds(null).setPrecisionRemainderSeconds(null);
        }
        result.setGaps(gaps.stream().distinct().toList()).setEvidenceStatus(gaps.isEmpty() ? "VERIFIED"
                : "FINISH".equals(produce.getStatus()) ? "DATA_GAP" : "IN_PROGRESS");
        return finish(result);
    }

    /**
     * 核对每个派工的完整段链；保留原整秒口径，不补造暂停原因或亚秒量。
     */
    private SegmentDurations segments(MesProduceTaskEntity produce, MesTaskArrangeEntity arrange,
                                      List<MesTaskExecutionSegmentEntity> rows, LocalDateTime queried, List<String> gaps) {
        if (rows.isEmpty()) {
            gaps.add("派工[" + arrange.getCode() + "]缺完整生产段");
            return new SegmentDurations(0, 0);
        }
        long sum = 0;
        long pauseGap = 0;
        LocalDateTime previous = null;
        Set<Long> ids = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            var segment = rows.get(index);
            boolean identity = Objects.equals(segment.getTenantId(), produce.getTenantId())
                    && Objects.equals(segment.getFactoryCode(), produce.getFactoryCode())
                    && Objects.equals(segment.getArrangeId(), arrange.getId())
                    && Objects.equals(segment.getArrangeCode(), arrange.getCode())
                    && Objects.equals(segment.getTaskCode(), produce.getTaskCode())
                    && Objects.equals(segment.getProduceTaskCode(), produce.getCode())
                    && Objects.equals(segment.getProcessCode(), produce.getRouteCode())
                    && Objects.equals(segment.getMaterialCode(), produce.getMatCode())
                    && Objects.equals(segment.getStationCode(), arrange.getActualWorksiteCode())
                    && Objects.equals(StrUtil.emptyToNull(segment.getDeviceCode()), StrUtil.emptyToNull(arrange.getPlannedDeviceCode()));
            boolean interval = validInterval(segment.getStartTime(), segment.getEndTime(), queried);
            boolean seconds = interval && segment.getDurationSeconds() != null && segment.getDurationSeconds() > 0
                    && segment.getDurationSeconds() == Duration.between(segment.getStartTime(), segment.getEndTime()).getSeconds();
            boolean bounds = interval && arrange.getStartTime() != null && arrange.getFinishTime() != null
                    && !segment.getStartTime().isBefore(arrange.getStartTime())
                    && !segment.getEndTime().isAfter(arrange.getFinishTime())
                    && (previous == null || !segment.getStartTime().isBefore(previous));
            if (!identity || !ids.add(segment.getId()) || !Objects.equals(segment.getSegmentNo(), index + 1)
                    || !"RUN".equals(segment.getSegmentType()) || !"CLOSED".equals(segment.getStatus())
                    || !Boolean.TRUE.equals(segment.getSampleEligible()) || !seconds || !bounds
                    || !Objects.equals(segment.getCloseReason(), index == rows.size() - 1 ? "FINISH" : "PAUSE")) {
                gaps.add("派工[" + arrange.getCode() + "]段[" + segment.getSegmentNo()
                        + "]身份/顺序/资格/闭合原因/时间或秒数不可核验");
            }
            if (seconds) sum = Math.addExact(sum, segment.getDurationSeconds());
            // 只算真实相邻段之间的间隔，整秒截断余数单列，不能作为暂停/停机。
            if (previous != null && segment.getStartTime() != null && !segment.getStartTime().isBefore(previous))
                pauseGap = Math.addExact(pauseGap, Duration.between(previous, segment.getStartTime()).getSeconds());
            previous = segment.getEndTime();
        }
        if (!Objects.equals(rows.get(0).getStartTime(), arrange.getStartTime())
                || !Objects.equals(rows.get(rows.size() - 1).getEndTime(), arrange.getFinishTime()))
            gaps.add("派工[" + arrange.getCode() + "]生产段未覆盖原开完工首尾");
        return new SegmentDurations(sum, pauseGap);
    }

    private MesOperationDurationDTO.Arrangement arrangement(MesTaskArrangeEntity row,
                                                            List<MesTaskExecutionSegmentEntity> segments) {
        return new MesOperationDurationDTO.Arrangement().setId(row.getId().toString()).setCode(row.getCode())
                .setTenantId(row.getTenantId().toString()).setFactoryCode(row.getFactoryCode())
                .setTaskCode(row.getTaskCode()).setProduceTaskCode(row.getProduceTaskCode())
                .setProcessCode(row.getRouteCode()).setProductCode(row.getMatCode())
                .setStatus(row.getStatus()).setStartTime(time(row.getStartTime())).setFinishTime(time(row.getFinishTime()))
                .setOriginalQuantity(decimal(row.getNoticeNum())).setCompletedQuantity(decimal(row.getCompleteNum()))
                .setStationCode(row.getActualWorksiteCode())
                .setDeviceCode(row.getPlannedDeviceCode()).setApsOperationSnapshot(row.getApsOperationSnapshot())
                .setSegments(segments.stream().map(segment -> new MesOperationDurationDTO.Segment()
                        .setTenantId(segment.getTenantId().toString()).setFactoryCode(segment.getFactoryCode())
                        .setArrangeId(segment.getArrangeId().toString()).setArrangeCode(segment.getArrangeCode())
                        .setTaskCode(segment.getTaskCode()).setProduceTaskCode(segment.getProduceTaskCode())
                        .setProcessCode(segment.getProcessCode()).setProductCode(segment.getMaterialCode())
                        .setId(segment.getId().toString()).setSegmentNo(segment.getSegmentNo()).setType(segment.getSegmentType())
                        .setStatus(segment.getStatus()).setStartTime(time(segment.getStartTime())).setEndTime(time(segment.getEndTime()))
                        .setDurationSeconds(segment.getDurationSeconds() == null ? null : segment.getDurationSeconds().toString())
                        .setCloseReason(segment.getCloseReason()).setEligible(segment.getSampleEligible())
                        .setExclusionReason(segment.getExclusionReason()).setStationCode(segment.getStationCode())
                        .setDeviceCode(segment.getDeviceCode())).toList());
    }

    private boolean sameSource(MesProduceTaskEntity produce, MesTaskArrangeEntity arrange) {
        return Objects.equals(produce.getTenantId(), arrange.getTenantId())
                && Objects.equals(produce.getFactoryCode(), arrange.getFactoryCode())
                && Objects.equals(produce.getTaskCode(), arrange.getTaskCode())
                && Objects.equals(produce.getCode(), arrange.getProduceTaskCode())
                && Objects.equals(produce.getRouteCode(), arrange.getRouteCode())
                && Objects.equals(produce.getMatCode(), arrange.getMatCode());
    }

    private boolean validInterval(LocalDateTime start, LocalDateTime end, LocalDateTime queried) {
        return start != null && end != null && end.isAfter(start) && !end.isAfter(queried);
    }

    private MesOperationDurationDTO.Operation finish(MesOperationDurationDTO.Operation row) {
        try {
            row.setEvidenceFingerprint(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSONUtil.toJsonStr(row).getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法生成完整生产段证据指纹", ex);
        }
        return row;
    }

    private String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String time(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }

    /**
     * 段落账秒数与实际段间暂停秒数分别累加，不用墙钟减法推断暂停。
     */
    private record SegmentDurations(long runSeconds, long pauseGapSeconds) {
    }
}
