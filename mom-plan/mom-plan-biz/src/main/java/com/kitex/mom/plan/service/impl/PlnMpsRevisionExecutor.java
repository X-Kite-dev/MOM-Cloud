package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.plan.constant.PlanStatusEnum;
import com.kitex.mom.plan.dto.PlnDemandReplanEvidenceDTO;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnMpsLineEntity;
import com.kitex.mom.plan.entity.PlnMpsVersionEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.PlanMatMrpMapper;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnMpsLineMapper;
import com.kitex.mom.plan.mapper.PlnMpsVersionMapper;
import com.kitex.mom.plan.mapper.PlnProductDemandMapper;
import com.kitex.mom.plan.service.PlnProductDemandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 已批准 MPS 滚动版本的跨模块执行器。
 *
 * <p>审批事务只形成不可变决策；本执行器随后以独立事务撤回旧下游、切换活动 MPS 行并重算 MRP。
 * 远端成功但本地事务失败时，版本进入 FAILED，人工续跑继续使用原 executionRequestId 派生的下游命令。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnMpsRevisionExecutor {

    private final PlnMpsVersionMapper versionMapper;
    private final PlnMpsLineMapper lineMapper;
    private final PlnProductDemandMapper demandMapper;
    private final PlanMatMrpMapper mrpMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnDemandReplanCoordinator replanCoordinator;
    private final PlnProductDemandService demandService;
    private final PlnMpsService mpsService;
    private final PlnMpsCapacityPublicationLock publicationLock;
    private final PlatformTransactionManager transactionManager;

    /** 执行已批准版本；失败时返回持久化的 FAILED 回执而不是丢失原申请。 */
    public PlnMpsVersionEntity execute(Long versionId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            transaction.executeWithoutResult(status -> executeInTransaction(versionId));
        } catch (RuntimeException ex) {
            TransactionTemplate failure = new TransactionTemplate(transactionManager);
            failure.executeWithoutResult(status -> markFailed(versionId, ex));
        }
        return requireVersion(versionId, false);
    }

    private void executeInTransaction(Long versionId) {
        var located = requireVersion(versionId, false);
        mpsService.requireFactoryAccess(located.getFactoryCode(), "write");
        publicationLock.acquire(located.getFactoryCode());
        PlnMpsVersionEntity version = requireVersion(versionId, true);
        mpsService.requireFactoryAccess(version.getFactoryCode(), "write");
        if ("PUBLISHED".equals(version.getStatus())) return;
        require("APPROVED".equals(version.getStatus()), "MPS版本尚未批准或当前状态不可执行");
        require(StrUtil.isNotBlank(version.getExecutionRequestId()), "MPS版本缺少永久执行请求号");
        List<PlnMpsLineEntity> lines = lines(version.getId(), true);
        require(!lines.isEmpty() && lines.stream().allMatch(line -> Boolean.TRUE.equals(line.getPending())
                        && !Boolean.TRUE.equals(line.getActive())
                        && Set.of("DIRECT", "WITHDRAW_REPLAN").contains(line.getRevisionMode())
                        && StrUtil.isNotBlank(line.getDownstreamEvidenceSnapshot())
                        && StrUtil.isNotBlank(line.getDownstreamEvidenceFingerprint())),
                "MPS候选行状态或下游冻结证据不完整");

        version.setStatus("EXECUTING").setLastError(null);
        require(versionMapper.updateById(version) == 1, "MPS执行状态保存失败");
        List<Long> replanDemandIds = new ArrayList<>();
        Map<Long, List<PlnMpsLineEntity>> linesByDemand = lines.stream().collect(
                java.util.stream.Collectors.groupingBy(PlnMpsLineEntity::getDemandId,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        for (Map.Entry<Long, List<PlnMpsLineEntity>> entry : linesByDemand.entrySet()) {
            List<PlnMpsLineEntity> demandLines = entry.getValue();
            PlnMpsLineEntity representative = demandLines.get(0);
            require(demandLines.stream().allMatch(line ->
                            Objects.equals(line.getRevisionMode(), representative.getRevisionMode())
                            && Objects.equals(line.getDownstreamEvidenceFingerprint(),
                            representative.getDownstreamEvidenceFingerprint())
                            && Objects.equals(line.getDownstreamEvidenceSnapshot(),
                            representative.getDownstreamEvidenceSnapshot())),
                    "同一需求的MPS桶下游证据不一致：" + representative.getDemandNo());
            PlnDemandReplanEvidenceDTO.Receipt receipt = replanCoordinator.execute(
                    representative.getDownstreamEvidenceSnapshot(), executionKey(version, representative),
                    version.getReason());
            if ("WITHDRAW_REPLAN".equals(representative.getRevisionMode())) replanDemandIds.add(entry.getKey());
            for (PlnMpsLineEntity line : demandLines) {
                line.setExecutionStatus("WITHDRAW_REPLAN".equals(representative.getRevisionMode())
                                ? "SUCCEEDED" : "NOT_APPLICABLE")
                        .setExecutionMessage(limit(receipt.getMessage(), 1000))
                        .setExecutionTime(LocalDateTime.now());
                require(lineMapper.updateById(line) == 1,
                        "MPS行执行回执保存失败：" + line.getDemandNo() + "#" + line.getBucketNo());
            }
        }

        Set<Long> supersededVersions = new HashSet<>();
        for (Map.Entry<Long, List<PlnMpsLineEntity>> entry : linesByDemand.entrySet()) {
            for (PlnMpsLineEntity old : activeLines(entry.getKey(), true)) {
                old.setActive(false);
                require(lineMapper.updateById(old) == 1, "原MPS生效桶退役失败：" + old.getDemandNo());
                supersededVersions.add(old.getVersionId());
            }
            for (PlnMpsLineEntity line : entry.getValue()) {
            line.setPending(false).setActive(true);
                require(lineMapper.updateById(line) == 1,
                        "新MPS活动桶发布失败：" + line.getDemandNo() + "#" + line.getBucketNo());
                createRootIssue(line);
            }
        }

        if (!replanDemandIds.isEmpty()) {
            R<?> response = demandService.mrp(replanDemandIds.toArray(Long[]::new));
            require(response != null && response.isOk(), "MPS修订后的MRP重算失败");
            for (PlnMpsLineEntity line : lines) {
                if (!replanDemandIds.contains(line.getDemandId())) continue;
                PlnProductDemandEntity demand = requireDemand(line.getDemandId(), true);
                require(PlanStatusEnum.MRP.getCode().equals(demand.getPlanState()),
                        "MPS修订需求未形成有效MRP：" + line.getDemandNo());
                List<PlanMatMrpEntity> mrpRows = mrpMapper.selectList(new LambdaQueryWrapper<PlanMatMrpEntity>()
                        .eq(PlanMatMrpEntity::getPlanDemandNo, line.getDemandNo())
                        .orderByAsc(PlanMatMrpEntity::getId));
                Set<Long> expectedLineIds = linesByDemand.get(line.getDemandId()).stream()
                        .map(PlnMpsLineEntity::getId).collect(java.util.stream.Collectors.toSet());
                require(!mrpRows.isEmpty() && mrpRows.stream().allMatch(row ->
                                Objects.equals(row.getMpsVersionId(), version.getId())
                                        && expectedLineIds.contains(row.getMpsLineId())
                                        && StrUtil.isNotBlank(row.getPlanningSourceKey())),
                        "新MRP未精确引用本次MPS版本/桶：" + line.getDemandNo());
            }
        }

        MomUser user = mpsService.currentUser();
        for (Long oldVersionId : supersededVersions) mpsService.maybeSupersede(oldVersionId, user);
        version.setStatus("PUBLISHED").setPublishedTime(LocalDateTime.now())
                .setCompletedTime(LocalDateTime.now()).setLastError(null);
        require(versionMapper.updateById(version) == 1, "MPS发布完成回执保存失败");
        String requestId = version.getExecutionRequestId();
        mpsService.audit(version.getId(), null, requestId, hash(requestId), "EXECUTE",
                "APPROVED", "PUBLISHED", replanDemandIds.isEmpty()
                        ? "MPS直接发布完成" : "旧下游已撤回，新MPS已发布并重算MRP：" + replanDemandIds,
                version.getFactoryCode(), user);
    }

    private void markFailed(Long versionId, RuntimeException error) {
        var located = requireVersion(versionId, false);
        mpsService.requireFactoryAccess(located.getFactoryCode(), "write");
        publicationLock.acquire(located.getFactoryCode());
        PlnMpsVersionEntity version = requireVersion(versionId, true);
        if ("PUBLISHED".equals(version.getStatus())) return;
        require(Set.of("APPROVED", "EXECUTING").contains(version.getStatus()),
                "MPS执行失败时版本状态异常：" + version.getStatus());
        String before = version.getStatus();
        String message = limit(Objects.toString(error.getMessage(), "MPS跨模块执行失败"), 1000);
        version.setStatus("FAILED").setLastError(message);
        require(versionMapper.updateById(version) == 1, "MPS失败回执保存失败");
        for (PlnMpsLineEntity line : lines(version.getId(), true)) {
            line.setExecutionStatus("FAILED").setExecutionMessage(message);
            require(lineMapper.updateById(line) == 1, "MPS行失败回执保存失败");
        }
        MomUser user = mpsService.currentUser();
        int retry = Optional.ofNullable(version.getRetryCount()).orElse(0);
        String requestId = limit("MPS-FAIL-" + version.getId() + "-" + retry, 64);
        mpsService.audit(version.getId(), null, requestId, hash(requestId + "|" + message), "EXECUTE_FAIL",
                before, "FAILED", message, version.getFactoryCode(), user);
    }

    private String executionKey(PlnMpsVersionEntity version, PlnMpsLineEntity line) {
        return limit("MPSR-" + version.getId() + "-" + line.getId(), 56);
    }

    private PlnMpsVersionEntity requireVersion(Long id, boolean lock) {
        LambdaQueryWrapper<PlnMpsVersionEntity> query = new LambdaQueryWrapper<PlnMpsVersionEntity>()
                .eq(PlnMpsVersionEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnMpsVersionEntity version = versionMapper.selectOne(query);
        require(version != null, "MPS版本不存在");
        return version;
    }

    private List<PlnMpsLineEntity> lines(Long versionId, boolean lock) {
        LambdaQueryWrapper<PlnMpsLineEntity> query = new LambdaQueryWrapper<PlnMpsLineEntity>()
                .eq(PlnMpsLineEntity::getVersionId, versionId)
                .orderByAsc(PlnMpsLineEntity::getDemandId);
        if (lock) query.last("FOR UPDATE");
        return lineMapper.selectList(query);
    }

    private List<PlnMpsLineEntity> activeLines(Long demandId, boolean lock) {
        LambdaQueryWrapper<PlnMpsLineEntity> query = new LambdaQueryWrapper<PlnMpsLineEntity>()
                .eq(PlnMpsLineEntity::getDemandId, demandId)
                .eq(PlnMpsLineEntity::getActive, true)
                .orderByAsc(PlnMpsLineEntity::getBucketNo)
                .orderByAsc(PlnMpsLineEntity::getId);
        if (lock) query.last("FOR UPDATE");
        return lineMapper.selectList(query);
    }

    /** 每个活动MPS桶形成一条独立根生产计划，APS和MES继续以计划ID/唯一计划号下钻。 */
    private void createRootIssue(PlnMpsLineEntity line) {
        PlnIssueEntity existing = issueMapper.selectOne(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getMpsLineId, line.getId())
                .eq(PlnIssueEntity::getType, "issue").last("LIMIT 1"));
        if (existing != null) {
            require(Objects.equals(existing.getPlanningSourceKey(), line.getBucketKey())
                            && existing.getNum() != null
                            && existing.getNum().compareTo(line.getPlanQuantity()) == 0,
                    "MPS桶已存在载荷不一致的根生产计划：" + line.getBucketKey());
            return;
        }
        PlnProductDemandEntity demand = requireDemand(line.getDemandId(), false);
        PlnIssueEntity issue = new PlnIssueEntity();
        issue.setIssueNo("MPS-" + line.getId());
        issue.setSourceDemandNo(demand.getDemandNo());
        issue.setPlanningSourceKey(line.getBucketKey());
        issue.setMpsLineId(line.getId());
        issue.setCustomerCode(demand.getCustomerCode());
        issue.setCustomerName(demand.getCustomerName());
        issue.setProductCode(demand.getProductCode());
        issue.setProductName(demand.getProductName());
        issue.setSpecificationModel(demand.getSpecification());
        issue.setNum(line.getPlanQuantity());
        issue.setIssueTime(LocalDateTime.now());
        issue.setDemandTime(line.getPlannedDate());
        issue.setStatus("0");
        issue.setType("issue");
        issue.setFactoryCode(line.getFactoryCode());
        require(issueMapper.insert(issue) == 1, "MPS桶根生产计划创建失败：" + line.getBucketKey());
    }

    private PlnProductDemandEntity requireDemand(Long id, boolean lock) {
        LambdaQueryWrapper<PlnProductDemandEntity> query = new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnProductDemandEntity demand = demandMapper.selectOne(query);
        require(demand != null, "MPS来源需求不存在：" + id);
        return demand;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("MPS执行履历指纹生成失败");
        }
    }

    private String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
