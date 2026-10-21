package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesOperationDurationService;
import com.kitex.mom.mes.dto.MesApsOperationSnapshotDTO;
import com.kitex.mom.mes.dto.MesOperationDurationDTO;
import com.kitex.mom.plan.dto.PlnSchedulingEffectReviewDTO;
import com.kitex.mom.plan.entity.PlnSchedulingOperationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 独立时间证明读侧，只附加观察，不依赖MPS/RCCP或更改既有评分和计划范围指纹。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlnSchedulingDurationEvidenceReader {
    private static final String VERSION = "APS_RUN_SEGMENTS_V2";
    private final RemoteMesOperationDurationService mesDuration;

    public void enrich(List<PlnSchedulingOperationEntity> planned,
            List<PlnSchedulingEffectReviewDTO.OperationEvidence> actual, String factory) {
        List<String> codes = actual.stream().map(PlnSchedulingEffectReviewDTO.OperationEvidence::getMesProduceTaskCode)
                .filter(StrUtil::isNotBlank).distinct().toList();
        if (codes.isEmpty()) {
            actual.forEach(row -> row.setDurationEvidence(unavailable("尚无精确MES工序来源，未采集分钟证据")));
            return;
        }
        if (codes.size() > 500) {
            actual.forEach(row -> row.setDurationEvidence(unavailable("本次完整工序超过500个，未截断取证")));
            return;
        }
        MesOperationDurationDTO.Evidence bundle;
        try {
            var response = mesDuration.query(new MesOperationDurationDTO.Query().setFactoryCode(factory).setProduceTaskCodes(codes));
            bundle = response == null || !response.isOk() ? null : response.getData();
            if (bundle == null || !Objects.equals(bundle.getFactoryCode(), factory)
                    || !Objects.equals(bundle.getTenantId(), String.valueOf(TenantContextHolder.getTenantId()))
                    || !VERSION.equals(bundle.getEvidenceVersion()) || bundle.getOperations() == null
                    || bundle.getOperations().size() != codes.size()
                    || !bundle.getOperations().stream().map(MesOperationDurationDTO.Operation::getProduceTaskCode)
                    .collect(java.util.stream.Collectors.toSet()).equals(new java.util.HashSet<>(codes))) {
                actual.forEach(row -> row.setDurationEvidence(unavailable("MES完整时间证据缺失、重复或实际组织/范围不符")));
                return;
            }
        } catch (RuntimeException ex) {
            log.warn("APS连续生产段取证失败，factoryCode={}，不改写原开完工评分", factory);
            actual.forEach(row -> row.setDurationEvidence(unavailable("MES分钟证据接口不可用或实名/岗位权限未通过")));
            return;
        }
        for (var row : actual) {
            var targets = planned.stream().filter(op -> Objects.equals(op.getIssueNo(), row.getIssueNo())
                    && Objects.equals(op.getProcessCode(), row.getProcessCode())).toList();
            var sources = bundle.getOperations().stream().filter(source -> Objects.equals(source.getProduceTaskCode(),
                    row.getMesProduceTaskCode())).toList();
            if (targets.size() != 1 || sources.size() != 1) {
                row.setDurationEvidence(unavailable("缺少唯一计划工序与MES精确来源"));
                continue;
            }
            row.setDurationEvidence(evaluate(targets.get(0), row, sources.get(0)));
        }
    }

    private PlnSchedulingEffectReviewDTO.DurationEvidence evaluate(PlnSchedulingOperationEntity plan,
            PlnSchedulingEffectReviewDTO.OperationEvidence actual, MesOperationDurationDTO.Operation source) {
        var proof = new PlnSchedulingEffectReviewDTO.DurationEvidence().setEvidenceVersion(VERSION)
                .setSourceFingerprint(source.getEvidenceFingerprint()).setSourceSnapshot(JSONUtil.toJsonStr(source));
        var changeover = source.getChangeoverEvidence();
        proof.setChangeoverStatus(changeover == null ? "UNBOUND" : changeover.getStatus())
                .setChangeoverGaps(changeover == null ? List.of("未提供新归属证明，不猜配历史") : changeover.getGaps());
        if (changeover != null && "VERIFIED".equals(changeover.getStatus()) && changeover.getSeconds() != null) {
            try { proof.setActualToolingChangeoverMinutes(minutes(changeover.getSeconds())); }
            catch (RuntimeException exception) {
                proof.setChangeoverStatus("DATA_GAP").setChangeoverGaps(List.of("归属原秒数不可完整解析"));
            }
        }
        // 计划冻结组成独立于现场是否完工；在制也可看已知计划，但不能据此补实际或差值。
        List<String> planGaps = plannedMinutes(plan, source, proof);
        List<String> gaps = new ArrayList<>(source.getGaps() == null ? List.of("MES未提供完整缺口状态") : source.getGaps());
        if (!Objects.equals(source.getProduceTaskId(), actual.getMesProduceTaskId())
                || !Objects.equals(source.getTaskCode(), actual.getMesTaskCode())
                || !Objects.equals(source.getProcessCode(), plan.getProcessCode())
                || !Objects.equals(source.getProductCode(), plan.getProductCode())
                || !equalQuantity(source.getCompletedQuantity(), actual.getCompleteQuantity())
                || source.getEvidenceFingerprint() == null || !source.getEvidenceFingerprint().matches("[a-f0-9]{64}"))
            gaps.add("本次时间取证与开完工观察的精确工序/产品/量或指纹不同");
        if (!"VERIFIED".equals(source.getEvidenceStatus()) || !gaps.isEmpty()) {
            if (gaps.isEmpty()) gaps.add("MES分钟依据未核验");
            gaps.addAll(planGaps);
            return proof.setStatus("IN_PROGRESS".equals(source.getEvidenceStatus()) ? "IN_PROGRESS" : "DATA_GAP")
                    .setGaps(gaps);
        }
        if (source.getActualStartTime() == null || source.getActualFinishTime() == null
                || !Objects.equals(source.getActualStartTime(), actual.getMesActualStartTimeText())
                || !Objects.equals(source.getActualFinishTime(), actual.getMesActualFinishTimeText()))
            return proof.setStatus("DATA_GAP").setGaps(List.of("两次取证开完工时刻不同，须重新采集，不混合前后事实"));
        try {
            proof.setRunSegmentMinutes(minutes(source.getRunSegmentSeconds()))
                    .setWallClockMinutes(minutes(source.getWallClockSeconds()))
                    .setPauseGapMinutes(minutes(source.getPauseGapSeconds()))
                    .setInterDispatchGapMinutes(minutes(source.getInterDispatchGapSeconds()))
                    .setPrecisionRemainderMinutes(minutes(source.getPrecisionRemainderSeconds()));
            gaps.addAll(planGaps);
            if (!"STATION".equals(plan.getResourceType()) || source.getArrangements() == null
                    || source.getArrangements().stream().filter(item -> item.getExclusionReason() == null)
                    .anyMatch(item -> !Objects.equals(plan.getResourceCode(), item.getStationCode())
                            || !Objects.equals(StrUtil.emptyToNull(plan.getDeviceCode()), StrUtil.emptyToNull(item.getDeviceCode()))))
                gaps.add("实际工位/派工冻结设备与原计划不符，或计划不是单工位粒度");
            if (!equalQuantity(source.getOriginalQuantity(), plan.getPlannedQuantity() == null ? null : plan.getPlannedQuantity().toPlainString()))
                gaps.add("物理工序原量与本计划工序量不同，禁止按比例分摊时间");
            if (gaps.isEmpty()) {
                proof.setRunDifferenceMinutes(new BigDecimal(proof.getRunSegmentMinutes())
                        .subtract(new BigDecimal(proof.getPlannedRunMinutes())).stripTrailingZeros().toPlainString());
            }
            return proof.setStatus(gaps.isEmpty() ? "COMPARABLE" : "VERIFIED_NOT_COMPARABLE").setGaps(gaps);
        } catch (RuntimeException ex) {
            return proof.setStatus("VERIFIED_NOT_COMPARABLE").setGaps(List.of("计划分钟快照或秒数无法完整解析，不猜测差值"));
        }
    }

    /** 只提取本次计划精确对应的原下达组成，缺失或异常均未知，不读取当前MDM参数。 */
    private List<String> plannedMinutes(PlnSchedulingOperationEntity plan, MesOperationDurationDTO.Operation source,
            PlnSchedulingEffectReviewDTO.DurationEvidence proof) {
        List<String> gaps = new ArrayList<>();
        try {
            MesApsOperationSnapshotDTO frozen = JSONUtil.toBean(source.getApsOperationSnapshot(), MesApsOperationSnapshotDTO.class);
            boolean samePlan = matches(plan, frozen);
            if (!samePlan) gaps.add("MES冻结APS候选/工序、产品、量、资源或计划时刻与本次发布范围不同/缺失");
            if (frozen == null || frozen.getRunMinutes() == null || frozen.getSetupMinutes() == null
                    || frozen.getDisassemblyMinutes() == null || frozen.getChangeoverMinutes() == null
                    || frozen.getActiveMinutes() == null) {
                gaps.add("历史下达快照缺完整计划分钟组成，不回填当前参数");
                return gaps;
            }
            // 既有排程引擎将换型并入setup；拆出固定准备展示，不能再把换型加一次。
            long total = Math.addExact(Math.addExact(frozen.getRunMinutes(), frozen.getSetupMinutes()),
                    frozen.getDisassemblyMinutes());
            boolean valid = frozen.getRunMinutes() > 0 && frozen.getSetupMinutes() >= 0
                    && frozen.getDisassemblyMinutes() >= 0 && frozen.getChangeoverMinutes() >= 0
                    && frozen.getSetupMinutes() >= frozen.getChangeoverMinutes()
                    && total == frozen.getActiveMinutes();
            if (!valid) gaps.add("冻结计划分钟组成非正/负数或与有效分钟不守恒");
            else if (samePlan) {
                proof.setPlannedRunMinutes(frozen.getRunMinutes().toString())
                        .setPlannedSetupMinutes(Long.toString(frozen.getSetupMinutes() - frozen.getChangeoverMinutes()))
                        .setPlannedDisassemblyMinutes(frozen.getDisassemblyMinutes().toString())
                        .setPlannedChangeoverMinutes(frozen.getChangeoverMinutes().toString());
            }
            if (frozen.getSetupMinutes() > frozen.getChangeoverMinutes() || frozen.getDisassemblyMinutes() != 0)
                gaps.add("RUN生命周期未单独区分准备/拆卸，不能认定纯运行差值");
            var changeover = source.getChangeoverEvidence();
            boolean uniqueFirst = changeover != null && "VERIFIED".equals(changeover.getStatus())
                    && Boolean.TRUE.equals(changeover.getFirstDispatchOnly())
                    && Objects.equals(changeover.getExecutionCount(), 1) && changeover.getSeconds() != null;
            if (frozen.getChangeoverMinutes() != 0 && !uniqueFirst)
                gaps.add("原计划含换型，但实际换型缺工序级唯一归属，不能认定运行对照差值");
        } catch (RuntimeException ex) {
            gaps.add("计划分钟快照无法完整解析，不猜测组成或差值");
        }
        return gaps;
    }

    /** 比较原下达冻结身份；不读取现在的运行参数来冒充原发布基线。 */
    private boolean matches(PlnSchedulingOperationEntity plan, MesApsOperationSnapshotDTO frozen) {
        return frozen != null && Objects.equals(frozen.getId(), plan.getId()) && Objects.equals(frozen.getTaskId(), plan.getTaskId())
                && Objects.equals(frozen.getProductCode(), plan.getProductCode())
                && Objects.equals(frozen.getProcessCode(), plan.getProcessCode())
                && Objects.equals(frozen.getResourceType(), plan.getResourceType())
                && Objects.equals(frozen.getResourceCode(), plan.getResourceCode())
                && Objects.equals(frozen.getScheduledStartTime(), plan.getScheduledStartTime())
                && Objects.equals(frozen.getScheduledEndTime(), plan.getScheduledEndTime())
                && frozen.getPlannedQuantity() != null && plan.getPlannedQuantity() != null
                && frozen.getPlannedQuantity().compareTo(plan.getPlannedQuantity()) == 0;
    }

    private boolean equalQuantity(String first, String second) {
        try { return first != null && second != null && new BigDecimal(first).compareTo(new BigDecimal(second)) == 0; }
        catch (NumberFormatException ex) { return false; }
    }

    private String minutes(String seconds) {
        if (seconds == null || !seconds.matches("[0-9]+")) throw new IllegalArgumentException("未知或非法秒数");
        return new BigDecimal(seconds).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private PlnSchedulingEffectReviewDTO.DurationEvidence unavailable(String gap) {
        return new PlnSchedulingEffectReviewDTO.DurationEvidence().setEvidenceVersion(VERSION).setStatus("UNAVAILABLE")
                .setGaps(List.of(gap));
    }
}
