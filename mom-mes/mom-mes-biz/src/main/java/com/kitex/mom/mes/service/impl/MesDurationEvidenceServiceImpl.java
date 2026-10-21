package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesDurationEvidenceDTO;
import com.kitex.mom.mes.entity.MesChangeoverExecutionEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskExecutionSegmentEntity;
import com.kitex.mom.mes.mapper.MesChangeoverExecutionMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTaskExecutionSegmentMapper;
import com.kitex.mom.mes.service.MesDurationEvidenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MesDurationEvidenceServiceImpl implements MesDurationEvidenceService {
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesTaskExecutionSegmentMapper segmentMapper;
    private final MesChangeoverExecutionMapper changeoverMapper;

    @Override
    public MesDurationEvidenceDTO.Bundle query(MesDurationEvidenceDTO.Query query) {
        validate(query);
        int limit = query.getLimit() == null ? 500 : query.getLimit();
        List<MesTaskArrangeEntity> arrangements = arrangeMapper.selectList(
                new LambdaQueryWrapper<MesTaskArrangeEntity>()
                        .eq(MesTaskArrangeEntity::getFactoryCode, query.getFactoryCode().trim())
                        .eq(MesTaskArrangeEntity::getStatus, "FINISH")
                        .ge(MesTaskArrangeEntity::getFinishTime, query.getFromTime())
                        .lt(MesTaskArrangeEntity::getFinishTime, query.getToTime())
                        .eq(StrUtil.isNotBlank(query.getProcessCode()), MesTaskArrangeEntity::getRouteCode,
                                StrUtil.trim(query.getProcessCode()))
                        .eq(StrUtil.isNotBlank(query.getDeviceCode()), MesTaskArrangeEntity::getPlannedDeviceCode,
                                StrUtil.trim(query.getDeviceCode()))
                        .orderByDesc(MesTaskArrangeEntity::getFinishTime).last("limit " + limit));
        Map<Long, List<MesTaskExecutionSegmentEntity>> segments = arrangements.isEmpty() ? Map.of()
                : segmentMapper.selectList(new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
                        .in(MesTaskExecutionSegmentEntity::getArrangeId,
                                arrangements.stream().map(MesTaskArrangeEntity::getId).toList())
                        .orderByAsc(MesTaskExecutionSegmentEntity::getArrangeId)
                        .orderByAsc(MesTaskExecutionSegmentEntity::getSegmentNo)).stream()
                .collect(Collectors.groupingBy(MesTaskExecutionSegmentEntity::getArrangeId,
                        LinkedHashMap::new, Collectors.toList()));
        List<MesDurationEvidenceDTO.OperationSample> operationSamples = arrangements.stream()
                .map(row -> operation(row, segments.getOrDefault(row.getId(), List.of()))).toList();

        List<MesChangeoverExecutionEntity> changes = changeoverMapper.selectList(
                new LambdaQueryWrapper<MesChangeoverExecutionEntity>()
                        .eq(MesChangeoverExecutionEntity::getFactoryCode, query.getFactoryCode().trim())
                        .eq(MesChangeoverExecutionEntity::getStatus, "COMPLETED")
                        .ge(MesChangeoverExecutionEntity::getCompletedTime, query.getFromTime())
                        .lt(MesChangeoverExecutionEntity::getCompletedTime, query.getToTime())
                        .eq(StrUtil.isNotBlank(query.getDeviceCode()), MesChangeoverExecutionEntity::getDeviceCode,
                                StrUtil.trim(query.getDeviceCode()))
                        .orderByDesc(MesChangeoverExecutionEntity::getCompletedTime).last("limit " + limit));
        List<MesDurationEvidenceDTO.ChangeoverSample> changeoverSamples = changes.stream()
                .map(this::changeover).toList();
        String fingerprint = sha256(JSONUtil.toJsonStr(Map.of(
                "factoryCode", query.getFactoryCode().trim(), "fromTime", query.getFromTime().toString(),
                "toTime", query.getToTime().toString(), "operations", operationSamples,
                "changeovers", changeoverSamples)));
        return new MesDurationEvidenceDTO.Bundle().setGeneratedTime(LocalDateTime.now())
                .setEvidenceFingerprint(fingerprint).setOperationSamples(operationSamples)
                .setChangeoverSamples(changeoverSamples)
                .setExcludedOperationCount((int) operationSamples.stream()
                        .filter(item -> !Boolean.TRUE.equals(item.getSourceEligible())).count())
                .setExcludedChangeoverCount((int) changeoverSamples.stream()
                        .filter(item -> !Boolean.TRUE.equals(item.getSourceEligible())).count());
    }

    private MesDurationEvidenceDTO.OperationSample operation(MesTaskArrangeEntity row,
                                                             List<MesTaskExecutionSegmentEntity> segments) {
        boolean complete = !segments.isEmpty() && segments.stream().allMatch(segment ->
                "CLOSED".equals(segment.getStatus()) && Boolean.TRUE.equals(segment.getSampleEligible())
                        && segment.getDurationSeconds() != null && segment.getDurationSeconds() >= 0);
        long active = segments.stream().filter(segment -> segment.getDurationSeconds() != null)
                .mapToLong(MesTaskExecutionSegmentEntity::getDurationSeconds).sum();
        long wall = row.getStartTime() == null || row.getFinishTime() == null ? 0L
                : Math.max(0L, Duration.between(row.getStartTime(), row.getFinishTime()).getSeconds());
        boolean quantityValid = row.getCompleteNum() != null && row.getCompleteNum().signum() > 0;
        boolean eligible = complete && quantityValid && active > 0 && active <= wall;
        String reason = eligible ? null : !quantityValid ? "完工数量为空或不大于0"
                : segments.isEmpty() ? "缺少连续生产区间"
                : active > wall ? "连续生产区间合计超过任务墙钟时间"
                : segments.stream().filter(segment -> !Boolean.TRUE.equals(segment.getSampleEligible()))
                .map(MesTaskExecutionSegmentEntity::getExclusionReason).filter(Objects::nonNull)
                .findFirst().orElse("存在未关闭、取消或零时长区间");
        MesDurationEvidenceDTO.OperationSample sample = new MesDurationEvidenceDTO.OperationSample()
                .setSampleId("OP:" + row.getId() + ":" + row.getFinishTime()).setArrangeId(row.getId())
                .setArrangeCode(row.getCode()).setTaskCode(row.getTaskCode())
                .setProduceTaskCode(row.getProduceTaskCode()).setProcessCode(row.getRouteCode())
                .setMaterialCode(row.getMatCode())
                .setStationCode(StrUtil.blankToDefault(row.getActualWorksiteCode(), row.getPlannedWorksiteCode()))
                .setDeviceCode(row.getPlannedDeviceCode()).setWorkerCodes(row.getWorkerCode())
                .setCompletedQuantity(row.getCompleteNum()).setActualStartTime(row.getStartTime())
                .setActualEndTime(row.getFinishTime()).setActiveSeconds(active).setWallClockSeconds(wall)
                .setExcludedPauseSeconds(Math.max(0L, wall - active)).setPlannedStartTime(row.getPlannedStartTime())
                .setPlannedEndTime(row.getPlannedEndTime()).setApsOperationSnapshot(row.getApsOperationSnapshot())
                .setSegmentCount(segments.size()).setSourceEligible(eligible).setExclusionReason(reason);
        sample.setSourceFingerprint(sha256(JSONUtil.toJsonStr(sample)));
        return sample;
    }

    private MesDurationEvidenceDTO.ChangeoverSample changeover(MesChangeoverExecutionEntity row) {
        MesDurationEvidenceDTO.ChangeoverSample sample = new MesDurationEvidenceDTO.ChangeoverSample()
                .setSampleId("CHANGEOVER:" + row.getId() + ":" + row.getCompletedTime())
                .setExecutionId(row.getId()).setRequestId(row.getRequestId()).setWorksiteCode(row.getWorksiteCode())
                .setDeviceCode(row.getDeviceCode()).setFromToolingCode(row.getFromToolingCode())
                .setToToolingCode(row.getToToolingCode()).setStartedTime(row.getStartedTime())
                .setCompletedTime(row.getCompletedTime()).setDurationSeconds(row.getDurationSeconds())
                .setSourceEligible(row.getSampleEligible()).setExclusionReason(row.getExclusionReason());
        sample.setSourceFingerprint(sha256(JSONUtil.toJsonStr(sample)));
        return sample;
    }

    private void validate(MesDurationEvidenceDTO.Query query) {
        if (query == null || StrUtil.isBlank(query.getFactoryCode()) || query.getFromTime() == null
                || query.getToTime() == null || !query.getToTime().isAfter(query.getFromTime())) {
            throw new CheckedException("实际工时证据查询的工厂和时间范围不完整");
        }
        if (Duration.between(query.getFromTime(), query.getToTime()).toDays() > 366) {
            throw new CheckedException("单次实际工时证据查询不能超过366天");
        }
        int limit = query.getLimit() == null ? 500 : query.getLimit();
        if (limit < 1 || limit > 2000) throw new CheckedException("单类证据上限必须在1至2000之间");
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成实际工时证据指纹", ex);
        }
    }
}
