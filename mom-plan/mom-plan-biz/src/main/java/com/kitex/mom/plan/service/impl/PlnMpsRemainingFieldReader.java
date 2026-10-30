package com.kitex.mom.plan.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesTaskCommitmentSourceService;
import com.kitex.mom.mes.dto.MesTaskCommitmentSourceDTO;
import com.kitex.mom.plan.dto.PlnMpsExecutionDTO;
import com.kitex.mom.plan.dto.PlnMpsRemainingCommitmentDTO;
import com.kitex.mom.plan.dto.PlnMpsRemainingFieldDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnMpsRemainingCommitmentEntity;
import com.kitex.mom.plan.entity.PlnMpsRemainingCommitmentHeadEntity;
import com.kitex.mom.plan.entity.PlnMpsRemainingCommitmentLineEntity;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnMpsRemainingCommitmentHeadMapper;
import com.kitex.mom.plan.mapper.PlnMpsRemainingCommitmentLineMapper;
import com.kitex.mom.plan.mapper.PlnMpsRemainingCommitmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.kitex.mom.plan.service.impl.PlnMpsRemainingSnapshotSupport.*;

/**
 * 现场有限摘要：由MES实名反查真实工单和完整来源，再匹配原桶及当前发布版本。
 * 不调用完整PLAN授权门面、不输出审批快照，不允许客户端扩展计划ID范围。
 * 数量、原交期、内部兑现及时间依据分别展示；未知不作为剩余零量或完工证明。
 */
@Component
@RequiredArgsConstructor
public class PlnMpsRemainingFieldReader {
    private final RemoteMesTaskCommitmentSourceService mesService;
    private final PlnIssueMapper issueMapper;
    private final PlnMpsRemainingCommitmentHeadMapper headMapper;
    private final PlnMpsRemainingCommitmentMapper caseMapper;
    private final PlnMpsRemainingCommitmentLineMapper scheduleMapper;
    private final PlnMpsExecutionSourceReader sourceReader;
    private final PlnMpsRemainingFulfillmentCalculator calculator;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PlnMpsRemainingFieldDTO.Summary read(PlnMpsRemainingFieldDTO.Query query) {
        require(query != null && query.getTaskCode() != null, "真实物理工单编码必填");
        String factory = sourceReader.actualFactory();
        Long tenant = TenantContextHolder.getTenantId();
        var response = mesService.sources(query.getTaskCode());
        require(response != null && response.isOk() && response.getData() != null, "MES工单来源不可核验，不能猜计划范围");
        var evidence = response.getData();
        require(Objects.equals(evidence.getTenantId(), tenant.toString()) && Objects.equals(evidence.getFactoryCode(), factory)
                        && Objects.equals(evidence.getTaskCode(), query.getTaskCode()) && evidence.getSources() != null
                        && !evidence.getSources().isEmpty() && evidence.getSources().size() <= 1000,
                "MES来源的实际组织、任务或完整范围不一致");
        var numbers = evidence.getSources().stream().map(MesTaskCommitmentSourceDTO.Source::getIssueNo).toList();
        require(new HashSet<>(numbers).size() == numbers.size(), "真实工单存在重复计划来源，禁止合并猜量");
        var roots = issueMapper.selectList(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getTenantId, tenant).eq(PlnIssueEntity::getFactoryCode, factory)
                .in(PlnIssueEntity::getIssueNo, numbers).last("LIMIT 2001"));
        require(roots.size() <= 2000, "来源计划身份范围异常，不能截断摘要");
        var rootsByNo = roots.stream().collect(Collectors.groupingBy(PlnIssueEntity::getIssueNo));
        var lineIds = roots.stream().map(PlnIssueEntity::getMpsLineId).filter(Objects::nonNull).distinct().toList();
        require(lineIds.size() <= 500, "真实工单MPS来源超过500完整取证上限，禁止截断");
        var currentById = (lineIds.isEmpty() ? List.<PlnMpsExecutionDTO.Bucket>of() : sourceReader.readBuckets(lineIds, 0)).stream()
                .collect(Collectors.toMap(row -> Long.valueOf(row.getLineId()), row -> row));
        var heads = lineIds.isEmpty() ? List.<PlnMpsRemainingCommitmentHeadEntity>of()
                : headMapper.selectList(new LambdaQueryWrapper<PlnMpsRemainingCommitmentHeadEntity>()
                .eq(PlnMpsRemainingCommitmentHeadEntity::getTenantId, tenant).eq(PlnMpsRemainingCommitmentHeadEntity::getFactoryCode, factory)
                .in(PlnMpsRemainingCommitmentHeadEntity::getMpsLineId, lineIds).isNotNull(PlnMpsRemainingCommitmentHeadEntity::getCurrentCommitmentId));
        var headByLine = heads.stream().collect(Collectors.toMap(PlnMpsRemainingCommitmentHeadEntity::getMpsLineId, row -> row));
        var caseIds = heads.stream().map(PlnMpsRemainingCommitmentHeadEntity::getCurrentCommitmentId).distinct().toList();
        var cases = caseIds.isEmpty() ? List.<PlnMpsRemainingCommitmentEntity>of()
                : caseMapper.selectList(new LambdaQueryWrapper<PlnMpsRemainingCommitmentEntity>()
                .eq(PlnMpsRemainingCommitmentEntity::getTenantId, tenant).eq(PlnMpsRemainingCommitmentEntity::getFactoryCode, factory)
                .in(PlnMpsRemainingCommitmentEntity::getId, caseIds));
        var casesById = cases.stream().collect(Collectors.toMap(PlnMpsRemainingCommitmentEntity::getId, row -> row));
        var lines = caseIds.isEmpty() ? List.<PlnMpsRemainingCommitmentLineEntity>of()
                : scheduleMapper.selectList(new LambdaQueryWrapper<PlnMpsRemainingCommitmentLineEntity>()
                .eq(PlnMpsRemainingCommitmentLineEntity::getTenantId, tenant).eq(PlnMpsRemainingCommitmentLineEntity::getFactoryCode, factory)
                .in(PlnMpsRemainingCommitmentLineEntity::getCommitmentId, caseIds)
                .orderByAsc(PlnMpsRemainingCommitmentLineEntity::getBucketNo).last("LIMIT 50001"));
        require(lines.size() <= 50000, "完整内部日桶超限，不能截断");
        var linesByCase = lines.stream().collect(Collectors.groupingBy(PlnMpsRemainingCommitmentLineEntity::getCommitmentId));
        LocalDateTime now = LocalDateTime.now();
        var result = new PlnMpsRemainingFieldDTO.Summary().setTenantId(tenant.toString()).setFactoryCode(factory)
                .setTaskCode(evidence.getTaskCode()).setObservedAt(now.toString());
        for (var source : evidence.getSources()) {
            var row = new PlnMpsRemainingFieldDTO.Row().setIssueNo(source.getIssueNo()).setRelationType(source.getRelationType())
                    .setAllocationId(source.getAllocationId()).setTaskAllocatedQuantity(source.getAllocatedQuantity());
            result.getSources().add(row);
            try {
                var matched = rootsByNo.getOrDefault(source.getIssueNo(), List.of());
                require(matched.size() == 1, "原来源计划不存在或不唯一");
                var root = matched.get(0);
                require(Objects.equals(root.getProductCode(), evidence.getProductCode())
                                && (source.getIssueId() == null || Objects.equals(source.getIssueId(), root.getId().toString()))
                                && root.getNum() != null && root.getNum().compareTo(quantity(source.getAllocatedQuantity())) >= 0,
                        "物理工单来源与原计划身份、产品或数量不一致");
                if (root.getMpsLineId() == null) {
                    row.setResult("NOT_MPS");
                    continue;
                }
                row.setLineId(root.getMpsLineId().toString());
                var head = headByLine.get(root.getMpsLineId());
                var record = head == null ? null : casesById.get(head.getCurrentCommitmentId());
                var schedules = record == null ? null : linesByCase.get(record.getId());
                if (head != null) {
                    require(record != null && "PUBLISHED".equals(record.getStatus())
                                    && Objects.equals(record.getMpsLineId(), root.getMpsLineId()) && Objects.equals(record.getRootIssueId(), root.getId()),
                            "当前剩余责任头缺合法已发布版本");
                    publishedIdentity(row, record, schedules);
                }
                var current = currentById.get(root.getMpsLineId());
                require(current != null && Objects.equals(current.getRootIssueId(), root.getId().toString()), "MPS原桶与独立根来源不一致");
                if (head == null) {
                    row.setProductUnit(current.getProductUnit()).setOriginalQuantity(current.getPlanQuantity())
                            .setOriginalPlannedDate(current.getPlannedDate()).setCustomerDueDate(current.getSourceDueDate());
                }
                var links = current.getLinks().stream().filter(link -> Objects.equals(link.getTaskId(), evidence.getTaskId())
                        && Objects.equals(link.getAllocationId(), source.getAllocationId())
                        && Objects.equals(link.getRelationType(), source.getRelationType())).toList();
                require(links.size() == 1 && quantity(links.get(0).getSourceQuantity()).compareTo(quantity(source.getAllocatedQuantity())) == 0,
                        "原桶当前MES来源与实际任务分配不一致");
                BigDecimal remaining = calculator.currentRemainingQuantity(current);
                row.setTaskReportedQuantity(links.get(0).getCompletedQuantity()).setCurrentQuantity(current.getCompletedQuantity())
                        .setRemainingQuantity(decimal(remaining)).setResult(remaining.signum() == 0 ? "FULFILLED" : "ORIGINAL");
                if (head != null) {
                    var evaluation = calculator.evaluate(baseline(record), current, schedules(schedules), now, record.getToleranceMinutes());
                    row.setCommitmentNo(record.getCommitmentNo()).setCommitmentVersion(record.getCommitmentVersion())
                            .setBaselineQuantity(decimal(record.getBaselineQuantity())).setCommittedRemainingQuantity(decimal(record.getRemainingQuantity()))
                            .setResult(evaluation.getResult()).setRemainingQuantity(evaluation.getRemainingQuantity()).setGaps(evaluation.getGaps());
                    // 现场只留桶量/结论/时间依据，不扩展到其他任务报工ID、凭证、审批及快照。
                    if (evaluation.getGaps().isEmpty()) {
                        row.setBuckets(evaluation.getBuckets().stream().map(bucket -> BeanUtil.toBean(bucket, PlnMpsRemainingCommitmentDTO.Fulfillment.class)
                                .setShares(new ArrayList<>())).toList());
                    }
                }
            } catch (CheckedException exception) {
                row.setResult("DATA_GAP").setRemainingQuantity(null);
                row.getGaps().add(exception.getMessage());
            }
        }
        return result;
    }

    /**
     * 正式版本存在与当前兑现可核验是两件事；待核仍保留冻结版本/正量日桶，不假称无分期。
     */
    private void publishedIdentity(PlnMpsRemainingFieldDTO.Row row, PlnMpsRemainingCommitmentEntity record,
                                   List<PlnMpsRemainingCommitmentLineEntity> lines) {
        baseline(record); // 核验不可变基线正文与结构数量/身份，不能只凭指针名称展示正式版本。
        require(lines != null && !lines.isEmpty() && lines.size() <= 100 && lines.stream().allMatch(line ->
                        Objects.equals(line.getMpsLineId(), record.getMpsLineId()) && Objects.equals(line.getProductUnit(), record.getProductUnit())
                                && Objects.equals(line.getCapacityResourceCode(), record.getCapacityResourceCode())
                                && line.getQuantity() != null && line.getQuantity().signum() > 0 && line.getPromisedDate() != null),
                "内部日桶缺失或身份/单位漂移");
        require(lines.stream().map(PlnMpsRemainingCommitmentLineEntity::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(record.getRemainingQuantity()) == 0, "冻结内部日桶与原R不守恒");
        row.setCommitmentNo(record.getCommitmentNo()).setCommitmentVersion(record.getCommitmentVersion())
                .setProductUnit(record.getProductUnit()).setOriginalQuantity(decimal(record.getOriginalQuantity()))
                .setBaselineQuantity(decimal(record.getBaselineQuantity())).setCommittedRemainingQuantity(decimal(record.getRemainingQuantity()))
                .setOriginalPlannedDate(record.getOriginalPlannedDate().toString()).setCustomerDueDate(record.getCustomerDueDate().toString());
        row.setBuckets(lines.stream().map(line -> new PlnMpsRemainingCommitmentDTO.Fulfillment().setBucketNo(line.getBucketNo())
                .setPromisedDate(line.getPromisedDate().toString()).setPromisedQuantity(decimal(line.getQuantity()))
                .setStatus("DATA_GAP").setTimeBasis("UNVERIFIED")).toList());
    }
}
