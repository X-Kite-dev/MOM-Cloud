package com.kitex.mom.plan.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.dto.PlnMpsExecutionDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.kitex.mom.plan.service.impl.PlnMpsRemainingSnapshotSupport.*;

/**
 * 正式粗能力责任统一投影：活动原行、当前剩余承诺、已批准待执行及失败预留。
 *
 * <p>仅依赖Mapper、独立MES来源读侧和纯计算器；禁止调用MpsService或授权门面形成循环。
 * 全需求替换与精确原行替换分别入参，不能按产品或需求号扩大替换范围。
 * 数据缺口必须伴随责任返回，由OFF/WARN/BLOCK明确处理，不能伪造空闲。</p>
 */
@Component
@RequiredArgsConstructor
public class PlnMpsEffectiveCommitmentReader {
    private final PlnMpsLineMapper lineMapper;
    private final PlnMpsVersionMapper versionMapper;
    private final PlnMpsRemainingCommitmentHeadMapper headMapper;
    private final PlnMpsRemainingCommitmentMapper commitmentMapper;
    private final PlnMpsRemainingCommitmentLineMapper scheduleMapper;
    private final PlnMpsExecutionSourceReader sourceReader;
    private final PlnMpsRemainingFulfillmentCalculator calculator;

    /**
     * 自身批准预留只按精确version排除；不能排除同需求另一个已批准版本。
     */
    public Projection read(String factory, Set<Long> replacedDemands, Set<Long> replacedLines, Set<Long> selfVersions) {
        require(Objects.equals(factory, sourceReader.actualFactory()), "粗能力责任只能查询当前实际工厂");
        Long tenant = TenantContextHolder.getTenantId();
        var active = lineMapper.selectList(new LambdaQueryWrapper<PlnMpsLineEntity>()
                .eq(PlnMpsLineEntity::getTenantId, tenant).eq(PlnMpsLineEntity::getFactoryCode, factory)
                .eq(PlnMpsLineEntity::getActive, true).orderByAsc(PlnMpsLineEntity::getId).last("LIMIT 5001"));
        require(active.size() <= 5000, "活动粗能力责任超过5000原行，禁止截断为PASS");
        var heads = headMapper.selectList(new LambdaQueryWrapper<PlnMpsRemainingCommitmentHeadEntity>()
                .eq(PlnMpsRemainingCommitmentHeadEntity::getTenantId, tenant)
                .eq(PlnMpsRemainingCommitmentHeadEntity::getFactoryCode, factory)
                .isNotNull(PlnMpsRemainingCommitmentHeadEntity::getCurrentCommitmentId)
                .orderByAsc(PlnMpsRemainingCommitmentHeadEntity::getMpsLineId).last("LIMIT 501"));
        require(heads.size() <= 500, "当前剩余责任超过完整取证500原桶上限，不静默遗漏");
        var headsByLine = heads.stream().collect(Collectors.toMap(PlnMpsRemainingCommitmentHeadEntity::getMpsLineId, row -> row));
        List<String> gaps = new ArrayList<>();
        List<PlnMpsLineEntity> rows = new ArrayList<>();
        var activeById = active.stream().collect(Collectors.toMap(PlnMpsLineEntity::getId, row -> row));
        for (var head : heads) {
            if (!activeById.containsKey(head.getMpsLineId()))
                gaps.add("剩余责任原桶已失活/缺失：" + head.getMpsLineId());
        }
        for (var original : active) {
            if (replacedLines.contains(original.getId())) continue;
            if (headsByLine.containsKey(original.getId())) {
                if (replacedDemands.contains(original.getDemandId())) {
                    gaps.add("全需求替换不能忽略已开工剩余责任原桶：" + original.getId());
                }
            } else if (!replacedDemands.contains(original.getDemandId())) {
                rows.add(copy(original, "ORIGINAL_ACTIVE"));
            }
        }
        var selectedHeads = heads.stream().filter(head -> activeById.containsKey(head.getMpsLineId())
                && !replacedLines.contains(head.getMpsLineId())).toList();
        var overdueOriginals = active.stream().filter(line -> !headsByLine.containsKey(line.getId())
                && !replacedLines.contains(line.getId()) && !replacedDemands.contains(line.getDemandId())
                && line.getPlannedDate() != null && line.getPlannedDate().isBefore(LocalDate.now())).toList();
        // 当前剩余责任与原桶逾期余额同批取证，不能把active等同于未完工，也不作N+1调用。
        Set<Long> evidenceIds = new LinkedHashSet<>();
        selectedHeads.forEach(head -> evidenceIds.add(head.getMpsLineId()));
        overdueOriginals.forEach(line -> evidenceIds.add(line.getId()));
        require(evidenceIds.size() <= 500, "当前剩余及历史逾期原桶超过500完整取证上限，禁止截断为PASS");
        var currentById = (evidenceIds.isEmpty() ? List.<PlnMpsExecutionDTO.Bucket>of()
                : sourceReader.readBuckets(new ArrayList<>(evidenceIds), 0)).stream()
                .collect(Collectors.toMap(row -> Long.valueOf(row.getLineId()), row -> row));
        for (var original : overdueOriginals) {
            rows.removeIf(row -> Objects.equals(row.getCapacityOriginalLineId(), original.getId()));
            try {
                BigDecimal remaining = calculator.currentRemainingQuantity(currentById.get(original.getId()));
                if (remaining.signum() == 0) continue;
                rows.add(currentQuantity(copy(original, "ORIGINAL_OVERDUE_CURRENT").setPlanQuantity(remaining)));
            } catch (RuntimeException exception) {
                gaps.add("逾期原桶" + original.getId() + "有效余额待核：" + exception.getMessage());
                rows.add(copy(original, "ORIGINAL_OVERDUE_UNVERIFIED"));
            }
        }
        if (!selectedHeads.isEmpty()) {
            var caseIds = selectedHeads.stream().map(PlnMpsRemainingCommitmentHeadEntity::getCurrentCommitmentId).toList();
            var cases = commitmentMapper.selectList(new LambdaQueryWrapper<PlnMpsRemainingCommitmentEntity>()
                    .eq(PlnMpsRemainingCommitmentEntity::getTenantId, tenant).eq(PlnMpsRemainingCommitmentEntity::getFactoryCode, factory)
                    .in(PlnMpsRemainingCommitmentEntity::getId, caseIds));
            var casesById = cases.stream().collect(Collectors.toMap(PlnMpsRemainingCommitmentEntity::getId, row -> row));
            var scheduleRows = scheduleMapper.selectList(new LambdaQueryWrapper<PlnMpsRemainingCommitmentLineEntity>()
                    .eq(PlnMpsRemainingCommitmentLineEntity::getTenantId, tenant).eq(PlnMpsRemainingCommitmentLineEntity::getFactoryCode, factory)
                    .in(PlnMpsRemainingCommitmentLineEntity::getCommitmentId, caseIds)
                    .orderByAsc(PlnMpsRemainingCommitmentLineEntity::getCommitmentId)
                    .orderByAsc(PlnMpsRemainingCommitmentLineEntity::getBucketNo).last("LIMIT 50001"));
            require(scheduleRows.size() <= 50000, "完整剩余责任日桶超限，禁止截断");
            var schedulesById = scheduleRows.stream().collect(Collectors.groupingBy(PlnMpsRemainingCommitmentLineEntity::getCommitmentId));
            for (var head : selectedHeads) {
                var original = activeById.get(head.getMpsLineId());
                var record = casesById.get(head.getCurrentCommitmentId());
                try {
                    require(record != null && "PUBLISHED".equals(record.getStatus())
                            && Objects.equals(record.getMpsLineId(), head.getMpsLineId())
                            && record.getToleranceMinutes() != null, "当前指针缺合法已发布案件");
                    var schedules = schedulesById.get(record.getId());
                    require(schedules != null && schedules.stream().allMatch(line ->
                                    Objects.equals(line.getMpsLineId(), record.getMpsLineId())
                                            && Objects.equals(line.getProductUnit(), record.getProductUnit())
                                            && Objects.equals(line.getCapacityResourceCode(), record.getCapacityResourceCode())),
                            "剩余日桶的原桶、单位或资源身份不一致");
                    var result = calculator.evaluate(baseline(record), currentById.get(head.getMpsLineId()),
                            schedules(schedules), LocalDateTime.now(), record.getToleranceMinutes());
                    require(result.getGaps().isEmpty(), String.join("；", result.getGaps()));
                    for (var bucket : result.getBuckets()) {
                        BigDecimal remaining = quantity(bucket.getRemainingQuantity());
                        if (remaining.signum() == 0) continue;
                        var row = copy(original, "REMAINING_CURRENT").setCapacityCommitmentId(record.getId())
                                .setBucketNo(bucket.getBucketNo()).setBucketKey("MPSR:" + record.getId() + ":" + bucket.getBucketNo())
                                .setPlannedDate(LocalDate.parse(bucket.getPromisedDate())).setPlanQuantity(remaining)
                                .setCapacityResourceCode(record.getCapacityResourceCode());
                        // 重算当前速率/换型，禁止比例缩放旧总分钟或复用旧PASS。
                        rows.add(currentQuantity(row));
                    }
                } catch (RuntimeException exception) {
                    gaps.add("剩余责任" + head.getCurrentCommitmentId() + "待核：" + exception.getMessage());
                    // 明确保留原全量保守占用作对照；待核标记仍阻止BLOCK，不以旧量证明新分期可信。
                    rows.add(copy(original, "REMAINING_UNVERIFIED_FALLBACK"));
                }
            }
        }
        var reservedVersions = versionMapper.selectList(new LambdaQueryWrapper<PlnMpsVersionEntity>()
                .eq(PlnMpsVersionEntity::getTenantId, tenant).eq(PlnMpsVersionEntity::getFactoryCode, factory)
                .in(PlnMpsVersionEntity::getStatus, List.of("APPROVED", "EXECUTING", "FAILED"))
                .orderByAsc(PlnMpsVersionEntity::getId).last("LIMIT 5001"));
        require(reservedVersions.size() <= 5000, "批准预留版本范围超限，不推断容量空闲");
        reservedVersions = reservedVersions.stream().filter(row -> !selfVersions.contains(row.getId())).toList();
        if (!reservedVersions.isEmpty()) {
            var ids = reservedVersions.stream().map(PlnMpsVersionEntity::getId).toList();
            var reservedLines = lineMapper.selectList(new LambdaQueryWrapper<PlnMpsLineEntity>()
                    .eq(PlnMpsLineEntity::getTenantId, tenant).eq(PlnMpsLineEntity::getFactoryCode, factory)
                    .in(PlnMpsLineEntity::getVersionId, ids).orderByAsc(PlnMpsLineEntity::getId).last("LIMIT 5001"));
            require(reservedLines.size() <= 5000, "批准预留原行范围超限，不静默释放");
            var byVersion = reservedLines.stream().collect(Collectors.groupingBy(PlnMpsLineEntity::getVersionId));
            for (var version : reservedVersions) {
                var lines = byVersion.getOrDefault(version.getId(), List.of());
                boolean valid = StrUtil.isNotBlank(version.getExecutionRequestId()) && StrUtil.isNotBlank(version.getReviewerCode())
                        && !Objects.equals(version.getReviewerCode(), version.getApplicantCode()) && version.getReviewedTime() != null
                        && StrUtil.isNotBlank(version.getEvidenceFingerprint()) && !lines.isEmpty();
                if (!valid) gaps.add("批准预留缺永久执行号/独立批准依据或明细：" + version.getVersionNo());
                for (var line : lines) {
                    if (Boolean.TRUE.equals(line.getActive())) {
                        if (!activeById.containsKey(line.getId())) gaps.add("预留已活动但不在正式范围：" + line.getId());
                        continue; // 精确行已激活只转一次，不同时计预留与活动。
                    }
                    if (!Boolean.TRUE.equals(line.getPending()) || line.getPlanQuantity() == null || line.getPlanQuantity().signum() <= 0
                            || line.getPlannedDate() == null || StrUtil.isBlank(line.getSourceSnapshot())
                            || !Objects.equals(hash(line.getSourceSnapshot()), line.getSourceFingerprint())
                            || StrUtil.isBlank(line.getDownstreamEvidenceSnapshot()) || StrUtil.isBlank(line.getDownstreamEvidenceFingerprint())) {
                        gaps.add("批准预留行依据不可核验：" + line.getId());
                    }
                    rows.add(copy(line, "APPROVED_RESERVATION"));
                }
            }
        }
        return new Projection(rows, gaps);
    }

    private PlnMpsLineEntity copy(PlnMpsLineEntity original, String kind) {
        return BeanUtil.toBean(original, PlnMpsLineEntity.class).setCapacityResponsibilityType(kind)
                .setCapacityOriginalLineId(original.getId()).setCapacityOriginalDate(original.getPlannedDate());
    }

    /**
     * 当前未完工量必须按现行速率重算，不能比例缩放原全量冻结分钟。
     */
    private PlnMpsLineEntity currentQuantity(PlnMpsLineEntity row) {
        return row.setCapacityVerified(false).setCapacityDemandMinutes(null).setCapacityEvidenceFingerprint(null)
                .setCapacityEvidenceSnapshot(null);
    }

    /**
     * 行为虚拟只读投影；数量来源缺口不可被后续日历核查清空。
     */
    public record Projection(List<PlnMpsLineEntity> rows, List<String> gaps) {
    }
}
