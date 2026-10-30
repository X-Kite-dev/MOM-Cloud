package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmProductionLotPolicyEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.plan.constant.PlanStatusEnum;
import com.kitex.mom.plan.dto.PlnDemandReplanEvidenceDTO;
import com.kitex.mom.plan.dto.PlnMpsCapacityBalanceDTO;
import com.kitex.mom.plan.dto.PlnMpsCommand;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 全量单需求MPS滚动版本：政策版本、来源冻结、异岗发布、MRP门禁及不可变操作账。
 */
@Service
@RequiredArgsConstructor
public class PlnMpsService {
    private final PlnMpsPolicyMapper policyMapper;
    private final PlnMpsCapacityPublicationLock publicationLock;
    private final PlnMpsVersionMapper versionMapper;
    private final PlnMpsLineMapper lineMapper;
    private final PlnMpsOperationMapper operationMapper;
    private final PlnProductDemandMapper demandMapper;
    private final PlnProductDemandChangeMapper demandChangeMapper;
    private final PlanMatMrpMapper mrpMapper;
    private final PlnDemandReplanCoordinator replanCoordinator;
    private final PlnMpsRccpService rccpService;
    private final PlnMpsLotSizingService lotSizingService;
    private final RemoteWarehouseService warehouseService;
    private final RemoteMaterialService materialService;
    private final RemoteMdmFactoryAccessService factoryAccessService;

    @Transactional(rollbackFor = Exception.class)
    public PlnMpsPolicyEntity configure(PlnMpsCommand.Policy command) {
        require(command != null && command.getHorizonDays() != null && command.getFreezeDays() != null
                && command.getEnforceMps() != null, "MPS策略必须提供滚动天数、冻结天数和MRP门禁");
        String requestId = required(command.getRequestId(), 64, "策略请求号");
        String factory = required(command.getFactoryCode(), 100, "工厂");
        boolean legacyCapacityPayload = command.getRccpMode() == null
                && command.getRccpMaxLoadRate() == null;
        String rccpMode = normalizeRccpMode(command.getRccpMode());
        BigDecimal rccpMaxLoadRate = Optional.ofNullable(command.getRccpMaxLoadRate())
                .orElse(BigDecimal.valueOf(100));
        requireFactoryAccess(factory, "admin");
        require(command.getHorizonDays() >= 2 && command.getHorizonDays() <= 365
                        && command.getFreezeDays() >= 0 && command.getFreezeDays() <= 90
                        && command.getFreezeDays() + 1 < command.getHorizonDays(),
                "MPS滚动窗口必须在2至365天，且冻结区后至少保留一个可投放日");
        require(rccpMaxLoadRate.compareTo(BigDecimal.valueOf(50)) >= 0
                        && rccpMaxLoadRate.compareTo(BigDecimal.valueOf(200)) <= 0,
                "RCCP最大负荷率必须在50%至200%之间");
        MomUser user = currentUser();
        List<Object> fingerprintParts = new ArrayList<>(Arrays.asList(factory, command.getHorizonDays(),
                command.getFreezeDays(), command.getEnforceMps()));
        // 兼容升级前相同请求号的重放；新客户端显式携带RCCP字段后才扩展请求指纹。
        if (!legacyCapacityPayload) {
            fingerprintParts.add(rccpMode);
            fingerprintParts.add(normalized(rccpMaxLoadRate));
        }
        String fingerprint = hash(JSONUtil.toJsonStr(fingerprintParts));
        policyMapper.lockKey("REQUEST:" + requestId);
        PlnMpsPolicyEntity replay = policyMapper.selectOne(new LambdaQueryWrapper<PlnMpsPolicyEntity>()
                .eq(PlnMpsPolicyEntity::getRequestId, requestId));
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), fingerprint), "MPS策略幂等号已用于不同内容");
            requireFactory(replay.getFactoryCode());
            return replay;
        }
        require(operationByRequest(requestId) == null && versionByRequest(requestId) == null,
                "MPS策略请求号已用于其他操作");
        publicationLock.acquire(factory);
        policyMapper.lockKey("POLICY:" + factory);
        PlnMpsPolicyEntity old = activePolicy(factory, true);
        if (old != null && old.getHorizonDays().equals(command.getHorizonDays())
                && old.getFreezeDays().equals(command.getFreezeDays())
                && old.getEnforceMps().equals(command.getEnforceMps())
                && Objects.equals(normalizeRccpMode(old.getRccpMode()), rccpMode)
                && Optional.ofNullable(old.getRccpMaxLoadRate()).orElse(BigDecimal.valueOf(100))
                .compareTo(rccpMaxLoadRate) == 0) {
            throw new CheckedException("MPS策略无变化，无需生成新版本");
        }
        if (old != null) {
            old.setActive(false);
            require(policyMapper.updateById(old) == 1, "旧MPS策略退役失败");
        }
        PlnMpsPolicyEntity saved = new PlnMpsPolicyEntity()
                .setFactoryCode(factory).setPolicyVersion(old == null ? 1 : old.getPolicyVersion() + 1)
                .setHorizonDays(command.getHorizonDays()).setFreezeDays(command.getFreezeDays())
                .setEnforceMps(command.getEnforceMps()).setActive(true)
                .setRccpMode(rccpMode).setRccpMaxLoadRate(rccpMaxLoadRate)
                .setRequestId(requestId).setRequestFingerprint(fingerprint)
                .setConfiguredBy(user.getUsername()).setConfiguredTime(LocalDateTime.now());
        require(policyMapper.insert(saved) == 1, "MPS策略版本保存失败");
        audit(null, saved.getId(), requestId, fingerprint, "POLICY_ACTIVATE",
                old == null ? null : String.valueOf(old.getPolicyVersion()),
                String.valueOf(saved.getPolicyVersion()), JSONUtil.toJsonStr(command), factory, user);
        return saved;
    }

    public PlnMpsPolicyEntity policy(String factory) {
        requireFactoryAccess(factory, "read");
        return activePolicy(factory, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public PlnMpsVersionEntity submit(PlnMpsCommand.Submit command) {
        require(command != null && command.getHorizonStart() != null && command.getHorizonEnd() != null
                        && command.getLines() != null && !command.getLines().isEmpty(),
                "MPS候选必须有滚动窗口和至少一条需求");
        String requestId = required(command.getRequestId(), 64, "MPS提交请求号");
        String factory = required(command.getFactoryCode(), 100, "工厂");
        String reason = required(command.getReason(), 500, "编制说明");
        requireFactoryAccess(factory, "write");
        require(command.getLines().stream().noneMatch(Objects::isNull), "MPS候选不得包含空需求行");
        List<PlnMpsCommand.Line> targets = command.getLines().stream()
                .sorted(Comparator.comparing(PlnMpsCommand.Line::getDemandId,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(PlnMpsCommand.Line::getBucketNo,
                                Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
        require(targets.size() <= 500 && targets.stream().allMatch(item -> item != null
                        && item.getDemandId() != null && item.getBucketNo() != null && item.getBucketNo() > 0
                        && item.getPlanQuantity() != null && item.getPlanQuantity().signum() > 0
                        && fitsQuantity(item.getPlanQuantity()) && item.getPlannedDate() != null
                        && item.getMrpPriority() != null && item.getMrpPriority() >= 1
                        && item.getMrpPriority() <= 999)
                        && targets.stream().map(item -> item.getDemandId() + ":" + item.getBucketNo())
                        .distinct().count() == targets.size(),
                "MPS需求ID、桶序号、正数数量、计划日或优先级缺失，桶重复或超过500行");
        Map<Long, List<PlnMpsCommand.Line>> targetsByDemand = targets.stream()
                .collect(java.util.stream.Collectors.groupingBy(PlnMpsCommand.Line::getDemandId,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        require(targetsByDemand.values().stream().allMatch(this::continuousBuckets),
                "同一需求的跨期桶必须从1开始连续编号");
        String commandFingerprint = hash(JSONUtil.toJsonStr(Arrays.asList(factory,
                command.getHorizonStart(), command.getHorizonEnd(), reason,
                targets.stream().map(item -> Arrays.asList(item.getDemandId(), item.getBucketNo(),
                        normalized(item.getPlanQuantity()), item.getPlannedDate(), item.getMrpPriority(),
                        StrUtil.emptyToNull(StrUtil.trim(item.getCapacityResourceCode())))).toList())));
        policyMapper.lockKey("REQUEST:" + requestId);
        PlnMpsVersionEntity replay = versionByRequest(requestId);
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), commandFingerprint),
                    "MPS提交幂等号已被不同候选使用");
            requireFactory(replay.getFactoryCode());
            return replay;
        }
        require(operationByRequest(requestId) == null
                        && policyMapper.selectCount(new LambdaQueryWrapper<PlnMpsPolicyEntity>()
                        .eq(PlnMpsPolicyEntity::getRequestId, requestId)) == 0,
                "MPS提交请求号已用于其他操作");
        publicationLock.acquire(factory);
        PlnMpsPolicyEntity policy = requirePolicy(factory);
        LocalDate today = LocalDate.now();
        require(!command.getHorizonStart().isBefore(today)
                        && !command.getHorizonEnd().isBefore(command.getHorizonStart())
                        && ChronoUnit.DAYS.between(command.getHorizonStart(), command.getHorizonEnd())
                        < policy.getHorizonDays(),
                "MPS滚动窗口必须从今日起，且不得超过当前策略天数");
        LocalDate freezeThrough = today.plusDays(policy.getFreezeDays());
        MomUser user = currentUser();
        policyMapper.lockKey("SCOPE:" + factory + ":" + command.getHorizonStart() + ":" + command.getHorizonEnd());
        int revision = versionMapper.selectList(new LambdaQueryWrapper<PlnMpsVersionEntity>()
                        .eq(PlnMpsVersionEntity::getFactoryCode, factory)
                        .eq(PlnMpsVersionEntity::getHorizonStart, command.getHorizonStart())
                        .eq(PlnMpsVersionEntity::getHorizonEnd, command.getHorizonEnd())
                        .orderByDesc(PlnMpsVersionEntity::getRevision)).stream()
                .findFirst().map(row -> row.getRevision() + 1).orElse(1);
        List<PlnMpsLineEntity> lines = new ArrayList<>();
        for (Map.Entry<Long, List<PlnMpsCommand.Line>> entry : targetsByDemand.entrySet()) {
            PlnProductDemandEntity demand = demand(entry.getKey(), true);
            validateSource(demand, factory);
            require(lineMapper.selectCount(new LambdaQueryWrapper<PlnMpsLineEntity>()
                            .eq(PlnMpsLineEntity::getDemandId, demand.getId())
                            .eq(PlnMpsLineEntity::getPending, true)) == 0,
                    "产品需求已有待复核MPS候选：" + demand.getDemandNo());
            List<PlnMpsLineEntity> oldLines = activeLines(demand.getId(), false);
            require(oldLines.stream().allMatch(old -> old.getPlannedDate() != null
                            && old.getPlannedDate().isAfter(protectedThrough(old, policy))),
                    "原MPS时间桶已进入冻结区，不能用滚动版本直接覆盖：" + demand.getDemandNo());
            BigDecimal total = entry.getValue().stream().map(PlnMpsCommand.Line::getPlanQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            require(total.compareTo(demand.getNeedNum()) == 0,
                    "需求[" + demand.getDemandNo() + "]各时间桶数量之和必须等于全量需求["
                            + normalized(demand.getNeedNum()) + "]");
            PlnDemandReplanEvidenceDTO.Evidence downstream = replanCoordinator.preview(
                    demand.getId(), oldLines.stream().map(PlnMpsLineEntity::getId).toList());
            String snapshot = sourceSnapshot(demand);
            for (PlnMpsCommand.Line target : entry.getValue()) {
                require(!target.getPlannedDate().isBefore(command.getHorizonStart())
                                && !target.getPlannedDate().isAfter(command.getHorizonEnd())
                                && target.getPlannedDate().isAfter(freezeThrough),
                        "计划日期须位于滚动窗口且越过冻结区：" + demand.getDemandNo()
                                + "#" + target.getBucketNo());
                lines.add(new PlnMpsLineEntity()
                        .setDemandId(demand.getId()).setDemandNo(demand.getDemandNo())
                        .setBucketNo(target.getBucketNo()).setBucketKey(bucketKey(requestId,
                                demand.getId(), target.getBucketNo()))
                        .setProductCode(demand.getProductCode()).setWarehouseCode(demand.getDemandWarehouseCode())
                        .setPlanQuantity(target.getPlanQuantity()).setSourceDueDate(demand.getPlanEndDate())
                        .setPlannedDate(target.getPlannedDate()).setMrpPriority(target.getMrpPriority())
                        .setCapacityResourceCode(StrUtil.emptyToNull(StrUtil.trim(
                                target.getCapacityResourceCode())))
                        .setProjectedLateDays((int) Math.max(0, ChronoUnit.DAYS.between(
                                demand.getPlanEndDate(), target.getPlannedDate())))
                        .setSourceSnapshot(snapshot).setSourceFingerprint(hash(snapshot))
                        .setRevisionMode(downstream.getMode())
                        .setDownstreamEvidenceFingerprint(downstream.getEvidenceFingerprint())
                        .setDownstreamEvidenceSnapshot(replanCoordinator.snapshot(downstream))
                        .setExecutionStatus("DIRECT".equals(downstream.getMode()) ? "NOT_APPLICABLE" : "PENDING")
                        .setPending(true).setActive(false).setFactoryCode(factory));
            }
        }
        // 正式生产批量策略一旦启用，候选桶必须满足最小、最大和整批倍数；不再借用发料批量。
        PlnMpsLotSizingService.Catalog lotCatalog = lotSizingService.validate(factory, lines);
        freezeLotPolicy(lines, lotCatalog);
        // 发布锁已在需求/版本行锁之前取得，统一保护候选、批准预留及活动责任。
        rccpService.assess(factory, policy, lines);
        String evidence = evidence(policy, command.getHorizonStart(), command.getHorizonEnd(),
                freezeThrough, lines);
        PlnMpsVersionEntity version = new PlnMpsVersionEntity()
                .setVersionNo("MPS-" + IdUtil.getSnowflakeNextIdStr()).setRevision(revision)
                .setHorizonStart(command.getHorizonStart()).setHorizonEnd(command.getHorizonEnd())
                .setFreezeThrough(freezeThrough).setPolicyId(policy.getId())
                .setPolicyVersion(policy.getPolicyVersion()).setStatus("SUBMITTED")
                .setSubmitRequestId(requestId).setRequestFingerprint(commandFingerprint)
                .setEvidenceFingerprint(evidence).setReason(reason)
                .setApplicantCode(user.getUsername()).setSubmittedTime(LocalDateTime.now())
                .setFactoryCode(factory);
        require(versionMapper.insert(version) == 1, "MPS候选版本保存失败");
        for (PlnMpsLineEntity line : lines) {
            line.setVersionId(version.getId());
            require(lineMapper.insert(line) == 1, "MPS候选需求行保存失败");
        }
        audit(version.getId(), null, requestId, commandFingerprint, "SUBMIT", null,
                "SUBMITTED", reason, factory, user);
        return version;
    }

    @Transactional(rollbackFor = Exception.class)
    public PlnMpsVersionEntity review(PlnMpsCommand.Review command) {
        require(command != null && command.getVersionId() != null && command.getApproved() != null,
                "MPS复核必须提供版本和结论");
        String requestId = required(command.getRequestId(), 64, "MPS复核请求号");
        String reason = required(command.getReason(), 500, "复核意见");
        String expected = required(command.getEvidenceFingerprint(), 64, "预览证据指纹");
        String fingerprint = hash(JSONUtil.toJsonStr(Arrays.asList(command.getVersionId(),
                command.getApproved(), expected, reason)));
        policyMapper.lockKey("REQUEST:" + requestId);
        PlnMpsOperationEntity replay = operationByRequest(requestId);
        if (replay != null) {
            require(Objects.equals(replay.getVersionId(), command.getVersionId())
                            && Objects.equals(replay.getRequestFingerprint(), fingerprint),
                    "MPS复核请求号已被不同操作使用");
            PlnMpsVersionEntity existing = version(command.getVersionId(), false);
            requireFactoryAccess(existing.getFactoryCode(), "write");
            return existing;
        }
        require(versionByRequest(requestId) == null, "复核号已用于MPS候选提交");
        PlnMpsVersionEntity located = version(command.getVersionId(), false);
        requireFactoryAccess(located.getFactoryCode(), "write");
        publicationLock.acquire(located.getFactoryCode());
        PlnMpsVersionEntity version = version(command.getVersionId(), true);
        requireFactoryAccess(version.getFactoryCode(), "write");
        require("SUBMITTED".equals(version.getStatus())
                        && Objects.equals(version.getEvidenceFingerprint(), expected),
                "MPS候选状态或冻结证据已变化，请刷新");
        MomUser user = currentUser();
        require(!Objects.equals(user.getUsername(), version.getApplicantCode()), "MPS编制人与复核人必须分岗");
        List<PlnMpsLineEntity> lines = lines(version.getId(), true);
        require(!lines.isEmpty() && lines.stream().allMatch(line -> Boolean.TRUE.equals(line.getPending())
                        && !Boolean.TRUE.equals(line.getActive())),
                "MPS候选明细状态异常，不能复核");
        PlnMpsPolicyEntity policy = null;
        if (Boolean.TRUE.equals(command.getApproved())) {
            require(lines.stream().allMatch(line -> StrUtil.isNotBlank(line.getSourceSnapshot())
                            && Objects.equals(hash(line.getSourceSnapshot()), line.getSourceFingerprint())),
                    "MPS候选来源快照或指纹已损坏，禁止发布；可驳回后重编");
            PlnMpsPolicyEntity frozenPolicy = policyMapper.selectById(version.getPolicyId());
            require(frozenPolicy != null && Objects.equals(frozenPolicy.getFactoryCode(), version.getFactoryCode())
                            && Objects.equals(frozenPolicy.getPolicyVersion(), version.getPolicyVersion())
                            && Objects.equals(version.getEvidenceFingerprint(), evidence(frozenPolicy,
                            version.getHorizonStart(), version.getHorizonEnd(), version.getFreezeThrough(), lines)),
                    "MPS历史策略或候选行冻结事实已变化，禁止发布；可驳回后重编");
            PlnMpsLotSizingService.Catalog lotCatalog = lotSizingService.validate(
                    version.getFactoryCode(), lines);
            verifyLotPolicySnapshot(lines, lotCatalog);
            rccpService.verify(version.getFactoryCode(), frozenPolicy, lines);
            for (PlnMpsLineEntity line : lines) {
                PlnProductDemandEntity demand = demand(line.getDemandId(), true);
                validateSource(demand, version.getFactoryCode());
                require(Objects.equals(line.getSourceFingerprint(), hash(sourceSnapshot(demand))),
                        "需求来源事实在复核前变化：" + line.getDemandNo());
                PlnDemandReplanEvidenceDTO.Evidence downstream = replanCoordinator.verify(
                        line.getDownstreamEvidenceSnapshot());
                require(Objects.equals(line.getDownstreamEvidenceFingerprint(),
                                downstream.getEvidenceFingerprint())
                                && Objects.equals(line.getRevisionMode(), downstream.getMode()),
                        "MPS候选下游证据指纹或模式不一致：" + line.getDemandNo());
            }
            policy = activePolicy(version.getFactoryCode(), true);
            require(policy != null && Objects.equals(policy.getId(), version.getPolicyId()),
                    "MPS工厂策略已换版，原候选不可发布；可驳回或由编制人取消后重编");
            for (PlnMpsLineEntity line : lines) {
                LocalDate nowFreeze = LocalDate.now().plusDays(policy.getFreezeDays());
                require(line.getPlannedDate().isAfter(nowFreeze),
                        "MPS候选在复核前进入冻结区：" + line.getDemandNo());
            }
        }
        String after = Boolean.TRUE.equals(command.getApproved()) ? "APPROVED" : "REJECTED";
        if (Boolean.TRUE.equals(command.getApproved())) {
            version.setExecutionRequestId("MPS-EXEC-" + version.getId()).setRetryCount(0).setLastError(null);
        } else {
            for (PlnMpsLineEntity line : lines) {
                line.setPending(false);
                require(lineMapper.updateById(line) == 1, "MPS驳回行保存失败");
            }
        }
        version.setStatus(after).setReviewerCode(user.getUsername())
                .setReviewReason(reason).setReviewedTime(LocalDateTime.now());
        require(versionMapper.updateById(version) == 1, "MPS复核结果保存失败");
        audit(version.getId(), null, requestId, fingerprint,
                Boolean.TRUE.equals(command.getApproved()) ? "APPROVE" : "REJECT",
                "SUBMITTED", after, reason, version.getFactoryCode(), user);
        return version;
    }

    /**
     * 继续执行失败版本，或恢复审批提交后尚未启动的已批准版本。
     */
    @Transactional(rollbackFor = Exception.class)
    public PlnMpsVersionEntity retry(PlnMpsCommand.Retry command) {
        require(command != null && command.getVersionId() != null, "续跑必须提供MPS版本");
        String requestId = required(command.getRequestId(), 64, "续跑请求号");
        String reason = required(command.getReason(), 500, "续跑原因");
        String fingerprint = hash(command.getVersionId() + "|" + reason);
        policyMapper.lockKey("REQUEST:" + requestId);
        PlnMpsOperationEntity replay = operationByRequest(requestId);
        if (replay != null) {
            require(Objects.equals(replay.getVersionId(), command.getVersionId())
                            && Objects.equals(replay.getRequestFingerprint(), fingerprint),
                    "MPS续跑请求号已被不同操作使用");
            PlnMpsVersionEntity existing = version(command.getVersionId(), false);
            requireFactoryAccess(existing.getFactoryCode(), "write");
            return existing;
        }
        require(versionByRequest(requestId) == null, "续跑号已用于MPS候选提交");
        PlnMpsVersionEntity located = version(command.getVersionId(), false);
        requireFactoryAccess(located.getFactoryCode(), "write");
        publicationLock.acquire(located.getFactoryCode());
        PlnMpsVersionEntity version = version(command.getVersionId(), true);
        requireFactoryAccess(version.getFactoryCode(), "write");
        require(Set.of("APPROVED", "FAILED").contains(version.getStatus()),
                "仅已批准待执行或跨模块执行失败的MPS版本允许继续执行");
        MomUser user = currentUser();
        String before = version.getStatus();
        version.setStatus("APPROVED").setLastError(null)
                .setRetryCount(Optional.ofNullable(version.getRetryCount()).orElse(0) + 1);
        require(versionMapper.updateById(version) == 1, "MPS续跑状态保存失败");
        audit(version.getId(), null, requestId, fingerprint,
                "FAILED".equals(before) ? "RETRY" : "RESUME", before,
                "APPROVED", reason, version.getFactoryCode(), user);
        return version;
    }

    @Transactional(rollbackFor = Exception.class)
    public PlnMpsVersionEntity cancel(PlnMpsCommand.Cancel command) {
        require(command != null && command.getVersionId() != null, "取消必须提供MPS版本");
        String requestId = required(command.getRequestId(), 64, "取消请求号");
        String reason = required(command.getReason(), 500, "取消原因");
        String fingerprint = hash(command.getVersionId() + "|" + reason);
        policyMapper.lockKey("REQUEST:" + requestId);
        PlnMpsOperationEntity replay = operationByRequest(requestId);
        if (replay != null) {
            require(Objects.equals(replay.getVersionId(), command.getVersionId())
                            && Objects.equals(replay.getRequestFingerprint(), fingerprint),
                    "MPS取消请求号已被不同操作使用");
            PlnMpsVersionEntity existing = version(command.getVersionId(), false);
            requireFactoryAccess(existing.getFactoryCode(), "write");
            return existing;
        }
        require(versionByRequest(requestId) == null, "取消号已用于MPS候选提交");
        PlnMpsVersionEntity located = version(command.getVersionId(), false);
        requireFactoryAccess(located.getFactoryCode(), "write");
        publicationLock.acquire(located.getFactoryCode());
        PlnMpsVersionEntity version = version(command.getVersionId(), true);
        requireFactoryAccess(version.getFactoryCode(), "write");
        MomUser user = currentUser();
        require("SUBMITTED".equals(version.getStatus())
                        && Objects.equals(version.getApplicantCode(), user.getUsername()),
                "只有编制人可取消待复核MPS版本");
        for (PlnMpsLineEntity line : lines(version.getId(), true)) {
            require(Boolean.TRUE.equals(line.getPending()) && !Boolean.TRUE.equals(line.getActive()),
                    "MPS候选行状态异常");
            line.setPending(false);
            require(lineMapper.updateById(line) == 1, "MPS取消行保存失败");
        }
        version.setStatus("CANCELED").setReviewReason(reason).setReviewedTime(LocalDateTime.now());
        require(versionMapper.updateById(version) == 1, "MPS版本取消失败");
        audit(version.getId(), null, requestId, fingerprint, "CANCEL", "SUBMITTED",
                "CANCELED", reason, version.getFactoryCode(), user);
        return version;
    }

    /**
     * MRP在持有需求行锁后调用；即使工厂尚未强制MPS，已纳入版本的需求也不得绕过待审/生效线。
     */
    public void lockPoliciesForMrp(List<PlnProductDemandEntity> demands) {
        require(demands != null && !demands.isEmpty()
                        && demands.stream().allMatch(item -> item != null && item.getId() != null
                        && StrUtil.isNotBlank(item.getFactoryCode())),
                "MRP需求缺少ID或工厂，不能核验MPS门禁");
        demands.stream().map(PlnProductDemandEntity::getFactoryCode).distinct().sorted()
                .forEach(factory -> {
                    requireFactory(factory);
                    policyMapper.lockKey("POLICY:" + factory);
                    PlnMpsPolicyEntity policy = activePolicy(factory, true);
                    boolean requiresAccess = policy != null && Boolean.TRUE.equals(policy.getEnforceMps());
                    if (!requiresAccess) {
                        List<Long> ids = demands.stream()
                                .filter(item -> Objects.equals(item.getFactoryCode(), factory))
                                .map(PlnProductDemandEntity::getId).toList();
                        for (int i = 0; i < ids.size() && !requiresAccess; i += 500) {
                            requiresAccess = lineMapper.selectCount(new LambdaQueryWrapper<PlnMpsLineEntity>()
                                    .in(PlnMpsLineEntity::getDemandId, ids.subList(i, Math.min(i + 500, ids.size())))
                                    .and(w -> w.eq(PlnMpsLineEntity::getPending, true)
                                            .or().eq(PlnMpsLineEntity::getActive, true))) != 0;
                        }
                    }
                    if (requiresAccess) {
                        requireFactoryAccess(factory, "write");
                    }
                });
    }

    public List<PlnMpsLineEntity> forMrp(PlnProductDemandEntity demand) {
        require(demand != null && demand.getId() != null, "MRP需求不能为空");
        requireFactory(demand.getFactoryCode());
        require(lineMapper.selectCount(new LambdaQueryWrapper<PlnMpsLineEntity>()
                        .eq(PlnMpsLineEntity::getDemandId, demand.getId())
                        .eq(PlnMpsLineEntity::getPending, true)) == 0,
                "需求有待复核MPS版本，MRP不得越过计划审批");
        List<PlnMpsLineEntity> activeLines = activeLines(demand.getId(), false);
        PlnMpsPolicyEntity policy = activePolicy(demand.getFactoryCode(), false);
        if (activeLines.isEmpty()) {
            require(policy == null || !Boolean.TRUE.equals(policy.getEnforceMps()),
                    "当前工厂启用了MPS门禁，需求须先发布MPS版本才能运算MRP");
            return List.of();
        }
        // 发布后物料或领料仓可能被停用，MRP开算时仍须重新核验权威主数据。
        validateSource(demand, demand.getFactoryCode());
        require(activeLines.stream().map(PlnMpsLineEntity::getVersionId).distinct().count() == 1,
                "同一需求的活动MPS桶分属不同版本，禁止继续MRP");
        PlnMpsVersionEntity version = version(activeLines.get(0).getVersionId(), false);
        PlnMpsPolicyEntity frozenPolicy = policyMapper.selectById(version.getPolicyId());
        List<PlnMpsLineEntity> frozenLines = lines(version.getId(), false);
        require(frozenPolicy != null && !frozenLines.isEmpty()
                        && frozenLines.stream().allMatch(item -> StrUtil.isNotBlank(item.getSourceSnapshot())
                        && Objects.equals(hash(item.getSourceSnapshot()), item.getSourceFingerprint()))
                        && Objects.equals(version.getEvidenceFingerprint(), evidence(frozenPolicy,
                        version.getHorizonStart(), version.getHorizonEnd(), version.getFreezeThrough(), frozenLines)),
                "MPS已发布版本或候选冻结证据发生变化，MRP必须停止");
        BigDecimal total = activeLines.stream().map(PlnMpsLineEntity::getPlanQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        require("PUBLISHED".equals(version.getStatus()) && continuousActiveBuckets(activeLines)
                        && total.compareTo(demand.getNeedNum()) == 0
                        && activeLines.stream().allMatch(line ->
                        Objects.equals(line.getFactoryCode(), demand.getFactoryCode())
                                && Objects.equals(line.getDemandNo(), demand.getDemandNo())
                                && Objects.equals(line.getProductCode(), demand.getProductCode())
                                && Objects.equals(line.getWarehouseCode(), demand.getDemandWarehouseCode())
                                && StrUtil.isNotBlank(line.getBucketKey())
                                && Objects.equals(line.getSourceFingerprint(), hash(sourceSnapshot(demand)))),
                "已发布MPS来源需求、数量或仓库变化，须先受控修订版本");
        return activeLines;
    }

    /**
     * 已发布MPS的计划日进入冻结区或已有MRP时，需求旧变更流程不得制造不可恢复的失效版本。
     */
    public boolean demandChangeAllowed(PlnProductDemandEntity demand) {
        if (demand == null || demand.getId() == null) return false;
        if (lineMapper.selectCount(new LambdaQueryWrapper<PlnMpsLineEntity>()
                .eq(PlnMpsLineEntity::getDemandId, demand.getId())
                .eq(PlnMpsLineEntity::getPending, true)) != 0) return false;
        List<PlnMpsLineEntity> lines = activeLines(demand.getId(), false);
        if (lines.isEmpty()) return true;
        requireFactoryAccess(demand.getFactoryCode(), "write");
        PlnMpsPolicyEntity policy = activePolicy(demand.getFactoryCode(), false);
        return policy != null && lines.stream().allMatch(line -> line.getPlannedDate() != null
                && line.getPlannedDate().isAfter(protectedThrough(line, policy)))
                && mrpMapper.selectCount(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getPlanDemandNo, demand.getDemandNo())) == 0;
    }

    /**
     * OMS交付承诺变更不得绕开已发布MPS版本；须先走MPS受控修订。
     */
    public boolean hasActiveLine(Long demandId) {
        return demandId != null && !activeLines(demandId, false).isEmpty();
    }

    public Page<PlnMpsVersionEntity> page(SearchPage page, String factory, String status) {
        requireFactoryAccess(factory, "read");
        return versionMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<PlnMpsVersionEntity>()
                .eq(PlnMpsVersionEntity::getFactoryCode, factory)
                .eq(StrUtil.isNotBlank(status), PlnMpsVersionEntity::getStatus, status)
                .orderByDesc(PlnMpsVersionEntity::getSubmittedTime));
    }

    /**
     * MPS提交前预览当前需求组合的RCCP占用，不保存候选版本。
     */
    public com.kitex.mom.plan.dto.PlnMpsCapacityDTO.Preview capacityPreview(PlnMpsCommand.Submit command) {
        require(command != null && command.getLines() != null && !command.getLines().isEmpty(),
                "RCCP预览至少需要一条需求");
        String factory = required(command.getFactoryCode(), 100, "工厂");
        requireFactoryAccess(factory, "read");
        PlnMpsPolicyEntity policy = requirePolicy(factory);
        List<PlnMpsLineEntity> candidates = capacityCandidates(command, factory);
        lotSizingService.validate(factory, candidates);
        return rccpService.preview(policy, candidates);
    }

    /**
     * 生成数量守恒的RCCP移期/拆量建议，不保存也不绕过MPS审批。
     */
    public PlnMpsCapacityBalanceDTO.Result capacityBalance(PlnMpsCommand.Submit command) {
        require(command != null && command.getHorizonStart() != null && command.getHorizonEnd() != null
                        && command.getLines() != null && !command.getLines().isEmpty(),
                "RCCP负荷平衡必须提供滚动窗口和候选时间桶");
        String factory = required(command.getFactoryCode(), 100, "工厂");
        requireFactoryAccess(factory, "read");
        PlnMpsPolicyEntity policy = requirePolicy(factory);
        LocalDate today = LocalDate.now();
        LocalDate freezeThrough = today.plusDays(policy.getFreezeDays());
        require(!command.getHorizonStart().isBefore(today)
                        && !command.getHorizonEnd().isBefore(command.getHorizonStart())
                        && ChronoUnit.DAYS.between(command.getHorizonStart(), command.getHorizonEnd())
                        < policy.getHorizonDays()
                        && command.getHorizonEnd().isAfter(freezeThrough),
                "RCCP负荷平衡窗口必须从今日起、不得超过策略天数且须越过冻结区");
        List<PlnMpsLineEntity> lines = capacityCandidates(command, factory);
        require(lines.stream().allMatch(line -> !line.getPlannedDate().isBefore(command.getHorizonStart())
                        && !line.getPlannedDate().isAfter(command.getHorizonEnd())
                        && line.getPlannedDate().isAfter(freezeThrough)),
                "候选计划日必须位于滚动窗口且越过冻结区");
        return rccpService.balance(factory, policy, lines, command.getHorizonStart(),
                command.getHorizonEnd(), freezeThrough);
    }

    private List<PlnMpsLineEntity> capacityCandidates(PlnMpsCommand.Submit command, String factory) {
        List<PlnMpsCommand.Line> targets = command.getLines().stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(PlnMpsCommand.Line::getDemandId,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(PlnMpsCommand.Line::getBucketNo,
                                Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
        require(targets.size() == command.getLines().size() && targets.size() <= 500
                        && targets.stream().allMatch(item -> item.getDemandId() != null
                        && item.getBucketNo() != null && item.getBucketNo() > 0
                        && item.getPlanQuantity() != null && item.getPlanQuantity().signum() > 0
                        && fitsQuantity(item.getPlanQuantity()) && item.getPlannedDate() != null)
                        && targets.stream().map(item -> item.getDemandId() + ":" + item.getBucketNo())
                        .distinct().count() == targets.size(),
                "RCCP预览需求ID、桶序号、正数数量或计划日缺失，桶重复或超过500行");
        Map<Long, List<PlnMpsCommand.Line>> grouped = targets.stream()
                .collect(java.util.stream.Collectors.groupingBy(PlnMpsCommand.Line::getDemandId,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        require(grouped.values().stream().allMatch(this::continuousBuckets),
                "同一需求的跨期桶必须从1开始连续编号");
        List<PlnMpsLineEntity> lines = new ArrayList<>();
        for (Map.Entry<Long, List<PlnMpsCommand.Line>> entry : grouped.entrySet()) {
            PlnProductDemandEntity demand = demand(entry.getKey(), false);
            validateSource(demand, factory);
            BigDecimal total = entry.getValue().stream().map(PlnMpsCommand.Line::getPlanQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            require(total.compareTo(demand.getNeedNum()) == 0,
                    "需求[" + demand.getDemandNo() + "]各时间桶数量之和必须等于全量需求");
            for (PlnMpsCommand.Line target : entry.getValue()) {
                lines.add(new PlnMpsLineEntity().setDemandId(demand.getId()).setDemandNo(demand.getDemandNo())
                        .setBucketNo(target.getBucketNo()).setBucketKey("PREVIEW-" + demand.getId()
                                + "-" + target.getBucketNo())
                        .setProductCode(demand.getProductCode()).setWarehouseCode(demand.getDemandWarehouseCode())
                        .setPlanQuantity(target.getPlanQuantity()).setSourceDueDate(demand.getPlanEndDate())
                        .setPlannedDate(target.getPlannedDate())
                        .setCapacityResourceCode(StrUtil.emptyToNull(StrUtil.trim(
                                target.getCapacityResourceCode())))
                        .setMrpPriority(normalizePriority(target.getMrpPriority() == null
                                ? demand.getMrpPriority() : target.getMrpPriority()))
                        .setFactoryCode(factory));
            }
        }
        return lines;
    }

    /**
     * 查询可编入 MPS 的已接收需求和已形成可撤回 MRP 的需求。
     */
    public Page<PlnProductDemandEntity> eligibleDemands(SearchPage page, String factory,
                                                        String productCode, String warehouseCode) {
        requireFactoryAccess(factory, "read");
        return demandMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getFactoryCode, factory)
                .in(PlnProductDemandEntity::getPlanState,
                        PlanStatusEnum.RECEIVE.getCode(), PlanStatusEnum.MRP.getCode())
                .like(StrUtil.isNotBlank(productCode), PlnProductDemandEntity::getProductCode, productCode)
                .eq(StrUtil.isNotBlank(warehouseCode), PlnProductDemandEntity::getDemandWarehouseCode, warehouseCode)
                .and(wrapper -> wrapper.isNull(PlnProductDemandEntity::getCompleteNum)
                        .or().eq(PlnProductDemandEntity::getCompleteNum, BigDecimal.ZERO))
                .orderByAsc(PlnProductDemandEntity::getPlanEndDate)
                .orderByAsc(PlnProductDemandEntity::getId));
    }

    public PlnMpsCommand.Detail detail(Long id) {
        PlnMpsVersionEntity version = version(id, false);
        requireFactoryAccess(version.getFactoryCode(), "read");
        return new PlnMpsCommand.Detail().setVersion(version).setLines(lines(id, false))
                .setOperations(operationMapper.selectList(new LambdaQueryWrapper<PlnMpsOperationEntity>()
                        .eq(PlnMpsOperationEntity::getVersionId, id)
                        .orderByAsc(PlnMpsOperationEntity::getOperationTime)
                        .orderByAsc(PlnMpsOperationEntity::getId)));
    }

    private PlnMpsPolicyEntity requirePolicy(String factory) {
        PlnMpsPolicyEntity policy = activePolicy(factory, false);
        require(policy != null && policy.getHorizonDays() != null && policy.getFreezeDays() != null
                && policy.getEnforceMps() != null, "工厂MPS策略未配置或配置不完整");
        return policy;
    }

    private PlnMpsPolicyEntity activePolicy(String factory, boolean lock) {
        List<PlnMpsPolicyEntity> rows = policyMapper.selectList(new LambdaQueryWrapper<PlnMpsPolicyEntity>()
                .eq(PlnMpsPolicyEntity::getFactoryCode, factory)
                .eq(PlnMpsPolicyEntity::getActive, true)
                .last(lock ? "FOR UPDATE" : ""));
        require(rows.size() <= 1, "工厂存在多份活动MPS策略，请先治理");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private PlnMpsVersionEntity version(Long id, boolean lock) {
        require(id != null, "MPS版本ID不能为空");
        PlnMpsVersionEntity row = versionMapper.selectOne(new LambdaQueryWrapper<PlnMpsVersionEntity>()
                .eq(PlnMpsVersionEntity::getId, id).last(lock ? "FOR UPDATE" : ""));
        require(row != null, "MPS版本不存在");
        requireFactory(row.getFactoryCode());
        return row;
    }

    private List<PlnMpsLineEntity> lines(Long versionId, boolean lock) {
        return lineMapper.selectList(new LambdaQueryWrapper<PlnMpsLineEntity>()
                .eq(PlnMpsLineEntity::getVersionId, versionId)
                .orderByAsc(PlnMpsLineEntity::getDemandId)
                .orderByAsc(PlnMpsLineEntity::getBucketNo)
                .last(lock ? "FOR UPDATE" : ""));
    }

    private List<PlnMpsLineEntity> activeLines(Long demandId, boolean lock) {
        List<PlnMpsLineEntity> rows = lineMapper.selectList(new LambdaQueryWrapper<PlnMpsLineEntity>()
                .eq(PlnMpsLineEntity::getDemandId, demandId)
                .eq(PlnMpsLineEntity::getActive, true)
                .orderByAsc(PlnMpsLineEntity::getBucketNo)
                .orderByAsc(PlnMpsLineEntity::getId)
                .last(lock ? "FOR UPDATE" : ""));
        return rows;
    }

    private PlnProductDemandEntity demand(Long id, boolean lock) {
        PlnProductDemandEntity row = demandMapper.selectOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getId, id).last(lock ? "FOR UPDATE" : ""));
        require(row != null, "MPS产品需求不存在：" + id);
        requireFactory(row.getFactoryCode());
        return row;
    }

    private void validateSource(PlnProductDemandEntity row, String factory) {
        require(Objects.equals(row.getFactoryCode(), factory)
                        && Set.of(PlanStatusEnum.RECEIVE.getCode(), PlanStatusEnum.MRP.getCode())
                        .contains(row.getPlanState())
                        && StrUtil.isNotBlank(row.getDemandNo())
                        && StrUtil.isNotBlank(row.getProductCode())
                        && StrUtil.isNotBlank(row.getDemandWarehouseCode())
                        && row.getPlanEndDate() != null
                        && row.getNeedNum() != null && row.getNeedNum().signum() > 0
                        && fitsQuantity(row.getNeedNum())
                        && (row.getCompleteNum() == null || row.getCompleteNum().signum() == 0),
                "MPS只能编排同工厂、已接收、未完成且数量/仓库/交期完整的产品需求");
        require(demandChangeMapper.selectCount(new LambdaQueryWrapper<PlnProductDemandChangeEntity>()
                        .eq(PlnProductDemandChangeEntity::getDemandId, row.getId())
                        .eq(PlnProductDemandChangeEntity::getStatus, "SUBMITTED")) == 0,
                "需求有待复核变更，须先处理再编排MPS");
        require(demandMapper.selectCount(new LambdaQueryWrapper<PlnProductDemandEntity>()
                        .eq(PlnProductDemandEntity::getDemandNo, row.getDemandNo())) == 1,
                "产品需求号不唯一，禁止MPS来源与MRP计划归属歧义");
        R<MdmWarehouseEntity> warehouseResponse = warehouseService.getByCode(row.getDemandWarehouseCode());
        require(warehouseResponse != null && warehouseResponse.isOk()
                        && warehouseResponse.getData() != null
                        && Boolean.TRUE.equals(warehouseResponse.getData().getStatus())
                        && (StrUtil.isBlank(warehouseResponse.getData().getFactoryCode())
                        || Objects.equals(warehouseResponse.getData().getFactoryCode(), factory)),
                "MPS需求领料仓不可核验、已停用或跨工厂");
        R<MdmMaterialEntity> materialResponse = materialService.getDetails(row.getProductCode());
        require(materialResponse != null && materialResponse.isOk()
                        && materialResponse.getData() != null
                        && Boolean.TRUE.equals(materialResponse.getData().getStatus())
                        && (StrUtil.isBlank(materialResponse.getData().getFactoryCode())
                        || Objects.equals(materialResponse.getData().getFactoryCode(), factory)),
                "MPS产品物料不可核验、已停用或跨工厂");
    }

    private String sourceSnapshot(PlnProductDemandEntity row) {
        return JSONUtil.toJsonStr(Arrays.asList(row.getId(), row.getDemandNo(), row.getFactoryCode(),
                row.getProductCode(), row.getDemandWarehouseCode(), row.getPlanEndDate(),
                normalized(row.getNeedNum()), normalizePriority(row.getMrpPriority()),
                PlanStatusEnum.RECEIVE.getCode(),
                normalized(row.getCompleteNum()), row.getSaleOrderCode(), row.getSaleOrderCodeDetails(),
                row.getSourceType(), row.getSourceId(), row.getSourceNo(), row.getSourceEvidenceFingerprint()));
    }

    private String evidence(PlnMpsPolicyEntity policy, LocalDate start, LocalDate end,
                            LocalDate freeze, List<PlnMpsLineEntity> lines) {
        List<Object> parts = new ArrayList<>();
        boolean capacityAware = policy.getRccpMode() != null || policy.getRccpMaxLoadRate() != null;
        List<Object> policyEvidence = new ArrayList<>(Arrays.asList(policy.getId(), policy.getPolicyVersion(),
                policy.getHorizonDays(), policy.getFreezeDays(), policy.getEnforceMps(), start, end, freeze));
        if (capacityAware) {
            policyEvidence.add(normalizeRccpMode(policy.getRccpMode()));
            policyEvidence.add(normalized(Optional.ofNullable(policy.getRccpMaxLoadRate())
                    .orElse(BigDecimal.valueOf(100))));
        }
        parts.add(policyEvidence);
        for (PlnMpsLineEntity line : lines) {
            List<Object> lineEvidence = new ArrayList<>(Arrays.asList(line.getDemandId(), line.getDemandNo(),
                    line.getSourceFingerprint(), normalized(line.getPlanQuantity()), line.getSourceDueDate(),
                    line.getPlannedDate(), line.getMrpPriority(), line.getProjectedLateDays(), line.getFactoryCode()));
            // 历史行没有桶字段，保持旧证据指纹；新候选冻结独立桶身份。
            if (line.getBucketKey() != null || line.getBucketNo() != null) {
                lineEvidence.add(line.getBucketNo());
                lineEvidence.add(line.getBucketKey());
            }
            if (capacityAware) {
                lineEvidence.add(line.getCapacityResourceCode());
                lineEvidence.add(line.getCapacityBasis());
                lineEvidence.add(normalized(line.getCapacityDemandMinutes()));
                lineEvidence.add(normalized(line.getCapacityCommittedMinutes()));
                lineEvidence.add(normalized(line.getCapacityAvailableMinutes()));
                lineEvidence.add(normalized(line.getCapacityLoadRate()));
                lineEvidence.add(line.getCapacityStatus());
                lineEvidence.add(line.getCapacityVerified());
                lineEvidence.add(line.getCapacityEvidenceFingerprint());
            }
            // 新候选永久冻结提交时采用的批量策略；历史行字段为空时保持旧证据算法。
            if (line.getLotPolicyEvidenceFingerprint() != null) {
                lineEvidence.add(line.getLotPolicyId());
                lineEvidence.add(line.getLotPolicyNo());
                lineEvidence.add(line.getLotPolicyVersion());
                lineEvidence.add(normalized(line.getMinimumBatchQuantity()));
                lineEvidence.add(normalized(line.getEconomicBatchQuantity()));
                lineEvidence.add(normalized(line.getMaximumBatchQuantity()));
                lineEvidence.add(normalized(line.getBatchMultiple()));
                lineEvidence.add(line.getLotPolicyValidStartTime());
                lineEvidence.add(line.getLotPolicyValidEndTime());
                lineEvidence.add(line.getLotPolicyEvidenceFingerprint());
            }
            parts.add(lineEvidence);
        }
        return hash(JSONUtil.toJsonStr(parts));
    }

    private void freezeLotPolicy(List<PlnMpsLineEntity> lines, PlnMpsLotSizingService.Catalog catalog) {
        for (PlnMpsLineEntity line : lines) {
            MdmProductionLotPolicyEntity policy = catalog.policies().get(line.getProductCode());
            line.setLotPolicyEvidenceFingerprint(catalog.evidenceFingerprint());
            if (policy == null) continue;
            line.setLotPolicyId(policy.getId()).setLotPolicyNo(policy.getPolicyNo())
                    .setLotPolicyVersion(policy.getVersionNo())
                    .setMinimumBatchQuantity(policy.getMinimumBatchQuantity())
                    .setEconomicBatchQuantity(policy.getEconomicBatchQuantity())
                    .setMaximumBatchQuantity(policy.getMaximumBatchQuantity())
                    .setBatchMultiple(policy.getBatchMultiple())
                    .setLotPolicyValidStartTime(policy.getValidStartTime())
                    .setLotPolicyValidEndTime(policy.getValidEndTime());
        }
    }

    /**
     * 复核时既检查当前数量，也检查提交时策略版本和参数，禁止换版后的陈旧批准。
     */
    private void verifyLotPolicySnapshot(List<PlnMpsLineEntity> lines,
                                         PlnMpsLotSizingService.Catalog current) {
        require(lines.stream().allMatch(line -> StrUtil.isNotBlank(line.getLotPolicyEvidenceFingerprint())
                        && Objects.equals(line.getLotPolicyEvidenceFingerprint(), current.evidenceFingerprint())),
                "生产批量策略目录在复核前已变化，请驳回后重新编制");
        for (PlnMpsLineEntity line : lines) {
            MdmProductionLotPolicyEntity policy = current.policies().get(line.getProductCode());
            if (policy == null) {
                require(line.getLotPolicyId() == null && line.getLotPolicyNo() == null
                                && line.getLotPolicyVersion() == null && line.getMinimumBatchQuantity() == null
                                && line.getEconomicBatchQuantity() == null && line.getMaximumBatchQuantity() == null
                                && line.getBatchMultiple() == null && line.getLotPolicyValidStartTime() == null
                                && line.getLotPolicyValidEndTime() == null,
                        "未配置产品的生产批量快照异常：" + line.getProductCode());
                continue;
            }
            require(Objects.equals(line.getLotPolicyId(), policy.getId())
                            && Objects.equals(line.getLotPolicyNo(), policy.getPolicyNo())
                            && Objects.equals(line.getLotPolicyVersion(), policy.getVersionNo())
                            && sameQuantity(line.getMinimumBatchQuantity(), policy.getMinimumBatchQuantity())
                            && sameQuantity(line.getEconomicBatchQuantity(), policy.getEconomicBatchQuantity())
                            && sameQuantity(line.getMaximumBatchQuantity(), policy.getMaximumBatchQuantity())
                            && sameQuantity(line.getBatchMultiple(), policy.getBatchMultiple())
                            && Objects.equals(line.getLotPolicyValidStartTime(), policy.getValidStartTime())
                            && Objects.equals(line.getLotPolicyValidEndTime(), policy.getValidEndTime()),
                    "生产批量策略冻结快照与当前正式版本不一致：" + line.getProductCode());
        }
    }

    private boolean sameQuantity(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private String normalizeRccpMode(String value) {
        String mode = StrUtil.blankToDefault(value, "OFF").trim().toUpperCase(Locale.ROOT);
        require(Set.of("OFF", "WARN", "BLOCK").contains(mode), "RCCP模式仅支持OFF、WARN或BLOCK");
        return mode;
    }

    void maybeSupersede(Long versionId, MomUser user) {
        if (lineMapper.selectCount(new LambdaQueryWrapper<PlnMpsLineEntity>()
                .eq(PlnMpsLineEntity::getVersionId, versionId)
                .eq(PlnMpsLineEntity::getActive, true)) != 0) return;
        PlnMpsVersionEntity old = version(versionId, true);
        if (!"PUBLISHED".equals(old.getStatus())) return;
        old.setStatus("SUPERSEDED");
        require(versionMapper.updateById(old) == 1, "旧MPS版本退役失败");
        String requestId = "MPS-SUP:" + old.getId();
        audit(old.getId(), null, requestId, hash(requestId), "SUPERSEDE",
                "PUBLISHED", "SUPERSEDED", "所有生效需求行已由新版本取代", old.getFactoryCode(), user);
    }

    private LocalDate protectedThrough(PlnMpsLineEntity line, PlnMpsPolicyEntity activePolicy) {
        PlnMpsVersionEntity version = version(line.getVersionId(), false);
        PlnMpsPolicyEntity frozenPolicy = policyMapper.selectById(version.getPolicyId());
        require(frozenPolicy != null && Objects.equals(frozenPolicy.getFactoryCode(), line.getFactoryCode())
                        && frozenPolicy.getFreezeDays() != null && activePolicy.getFreezeDays() != null,
                "原MPS冻结策略不可核验，禁止改写生效计划");
        LocalDate currentBoundary = LocalDate.now().plusDays(activePolicy.getFreezeDays());
        LocalDate originalBoundary = LocalDate.now().plusDays(frozenPolicy.getFreezeDays());
        return currentBoundary.isAfter(originalBoundary) ? currentBoundary : originalBoundary;
    }

    private PlnMpsVersionEntity versionByRequest(String requestId) {
        return versionMapper.selectOne(new LambdaQueryWrapper<PlnMpsVersionEntity>()
                .eq(PlnMpsVersionEntity::getSubmitRequestId, requestId));
    }

    private PlnMpsOperationEntity operationByRequest(String requestId) {
        return operationMapper.selectOne(new LambdaQueryWrapper<PlnMpsOperationEntity>()
                .eq(PlnMpsOperationEntity::getRequestId, requestId));
    }

    void audit(Long versionId, Long policyId, String requestId, String fingerprint,
               String type, String before, String after, String detail, String factory, MomUser user) {
        require(operationMapper.insert(new PlnMpsOperationEntity()
                .setVersionId(versionId).setPolicyId(policyId).setRequestId(requestId)
                .setRequestFingerprint(fingerprint).setOperationType(type)
                .setBeforeStatus(before).setAfterStatus(after).setDetail(detail)
                .setOperatorCode(user.getUsername()).setOperationTime(LocalDateTime.now())
                .setFactoryCode(factory)) == 1, "MPS操作履历保存失败");
    }

    MomUser currentUser() {
        MomUser user;
        try {
            user = SecurityUtils.getUser();
        } catch (Exception ex) {
            user = null;
        }
        require(user != null && StrUtil.isNotBlank(user.getUsername()), "MPS受控操作必须由已登录用户执行");
        return user;
    }

    private void requireFactory(String factory) {
        String selected = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory) && (StrUtil.isBlank(selected) || Objects.equals(selected, factory)),
                "当前工厂无权操作该MPS计划");
    }

    void requireFactoryAccess(String factory, String permission) {
        requireFactory(factory);
        MomUser user = currentUser();
        require(user.getId() != null, "无法核验当前用户工厂授权");
        R<Boolean> access;
        try {
            access = factoryAccessService.check(new MdmFactoryAccessCheck()
                    .setUserId(user.getId()).setFactoryCode(factory).setRequiredPermission(permission));
        } catch (Exception ex) {
            throw new CheckedException("MDM工厂权限服务不可用，MPS操作已故障关闭");
        }
        require(access != null && access.isOk() && Boolean.TRUE.equals(access.getData()),
                "当前用户未获该工厂" + permission + "授权，或MDM工厂授权不可核验");
    }

    private String required(String value, int max, String label) {
        String normalized = value == null ? null : value.trim();
        require(StrUtil.isNotBlank(normalized) && normalized.length() <= max,
                label + "不能为空且不能超过" + max + "字");
        return normalized;
    }

    private String normalized(BigDecimal quantity) {
        return quantity == null ? "" : quantity.stripTrailingZeros().toPlainString();
    }

    private boolean fitsQuantity(BigDecimal quantity) {
        if (quantity == null) return false;
        BigDecimal normalized = quantity.stripTrailingZeros();
        return normalized.scale() <= 6
                && normalized.precision() - normalized.scale() <= 18;
    }

    private int normalizePriority(Integer priority) {
        return priority == null ? 100 : priority;
    }

    private boolean continuousBuckets(List<PlnMpsCommand.Line> lines) {
        if (lines == null || lines.isEmpty()) return false;
        List<Integer> numbers = lines.stream().map(PlnMpsCommand.Line::getBucketNo).sorted().toList();
        for (int i = 0; i < numbers.size(); i++) if (numbers.get(i) != i + 1) return false;
        return true;
    }

    private boolean continuousActiveBuckets(List<PlnMpsLineEntity> lines) {
        if (lines == null || lines.isEmpty()) return false;
        List<Integer> numbers = lines.stream().map(PlnMpsLineEntity::getBucketNo)
                .filter(Objects::nonNull).sorted().toList();
        // 历史单桶行在第206节上线前没有桶号，继续兼容为1号桶。
        if (numbers.isEmpty()) return lines.size() == 1;
        if (numbers.size() != lines.size()) return false;
        for (int i = 0; i < numbers.size(); i++) if (numbers.get(i) != i + 1) return false;
        return true;
    }

    private String bucketKey(String requestId, Long demandId, Integer bucketNo) {
        return "MPSB-" + hash(requestId + "|" + demandId + "|" + bucketNo).substring(0, 32);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("MPS证据指纹生成失败");
        }
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
