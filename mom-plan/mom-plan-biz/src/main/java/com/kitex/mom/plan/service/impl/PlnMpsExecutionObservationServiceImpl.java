package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.plan.dto.PlnMpsExecutionDTO;
import com.kitex.mom.plan.entity.PlnMpsExecutionObservationEntity;
import com.kitex.mom.plan.mapper.PlnMpsExecutionObservationMapper;
import com.kitex.mom.plan.service.PlnMpsExecutionObservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * MPS执行兑现不可变观察账。采集、幂等和独立核验属于业务闭环，不修改正式MPS/APS或MES。
 *
 * <p>请求锁与保存使用READ_COMMITTED，证据读取器另开只读一致性快照，避免并发请求锁后
 * 仍读不到前一个已提交回执。独立确认重采事实，最多30分钟有效，不能把未知改成正常。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnMpsExecutionObservationServiceImpl
        extends ServiceImpl<PlnMpsExecutionObservationMapper, PlnMpsExecutionObservationEntity>
        implements PlnMpsExecutionObservationService {
    private static final Set<String> CHECKS = Set.of("SOURCE_MATCHED", "QUANTITY_SEPARATED", "DEVIATION_ROUTED");
    private final PlnMpsExecutionEvidenceReader reader;
    private final PlnMpsService mpsService;

    @Override
    public PlnMpsExecutionDTO.Evidence preview(Long versionId, int toleranceMinutes) {
        return reader.read(versionId, toleranceMinutes);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnMpsExecutionObservationEntity capture(PlnMpsExecutionDTO.Capture command) {
        require(command != null && command.getVersionId() != null && command.getToleranceMinutes() != null
                && command.getToleranceMinutes() >= 0 && command.getToleranceMinutes() <= 1440, "版本和0至1440分钟容差必填");
        text(command.getRequestId(), 100, "采集请求号");
        text(command.getReason(), 500, "采集说明");
        String factory = reader.actualFactory();
        mpsService.requireFactoryAccess(factory, "write");
        String fingerprint = PlnMpsExecutionEvidenceReader.hash(JSONUtil.toJsonStr(Arrays.asList(command.getVersionId(),
                command.getToleranceMinutes(), command.getReason().trim())));
        baseMapper.lockRequest("MPS_EXECUTION:" + TenantContextHolder.getTenantId() + ":" + factory + ":" + command.getRequestId());
        PlnMpsExecutionObservationEntity replay = getOne(scope().eq(PlnMpsExecutionObservationEntity::getRequestId, command.getRequestId()));
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), fingerprint), "采集请求号已使用但版本/容差/说明不同");
            return replay;
        }
        PlnMpsExecutionDTO.Evidence evidence = reader.read(command.getVersionId(), command.getToleranceMinutes());
        var record = new PlnMpsExecutionObservationEntity().setObservationNo("MPSEX-" + IdUtil.getSnowflakeNextIdStr())
                .setVersionId(command.getVersionId()).setVersionNo(evidence.getVersionNo()).setRequestId(command.getRequestId())
                .setRequestFingerprint(fingerprint).setToleranceMinutes(command.getToleranceMinutes())
                .setObservedTime(LocalDateTime.parse(evidence.getObservedAt())).setEvidenceFingerprint(evidence.getFingerprint())
                .setEvidenceSnapshot(JSONUtil.toJsonStr(evidence)).setResult(evidence.getResult())
                .setBucketCount(evidence.getBuckets().size())
                .setLateBucketCount((int) evidence.getBuckets().stream().filter(row -> Boolean.TRUE.equals(row.getOverdue())).count())
                .setDataGapBucketCount((int) evidence.getBuckets().stream().filter(row -> !row.getGaps().isEmpty()
                        || !row.getQuantityGaps().isEmpty()).count()).setStatus("PENDING")
                .setReason(command.getReason().trim()).setFactoryCode(factory);
        record.setTenantId(TenantContextHolder.getTenantId());
        record.setCreateBy(SecurityUtils.getUser().getUsername());
        record.setCreateTime(LocalDateTime.now());
        require(save(record), "执行兑现观察保存失败");
        return record;
    }

    @Override
    public PlnMpsExecutionObservationEntity findCapture(String requestId) {
        authorize("read");
        text(requestId, 100, "原采集请求号");
        return getOne(scope().eq(PlnMpsExecutionObservationEntity::getRequestId, requestId));
    }

    @Override
    public Page<PlnMpsExecutionObservationEntity> history(Long versionId, long current, long size) {
        authorize("read");
        require(versionId != null && current > 0 && size > 0 && size <= 100, "版本必填，页码大于0，每页最多100条");
        return page(new Page<>(current, size), scope().eq(PlnMpsExecutionObservationEntity::getVersionId, versionId)
                .select(PlnMpsExecutionObservationEntity.class, field -> !"evidenceSnapshot".equals(field.getProperty()))
                .orderByDesc(PlnMpsExecutionObservationEntity::getObservedTime)
                .orderByDesc(PlnMpsExecutionObservationEntity::getId));
    }

    @Override
    public PlnMpsExecutionObservationEntity detail(Long id) {
        authorize("read");
        return record(id, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnMpsExecutionObservationEntity review(PlnMpsExecutionDTO.Review command) {
        authorize("write");
        require(command != null && command.getId() != null, "执行观察主键必填");
        text(command.getRequestId(), 100, "核验请求号");
        text(command.getRemark(), 2000, "核验说明");
        require("CONFIRM".equals(command.getDecision()) || "REJECT".equals(command.getDecision()), "核验动作无效");
        require(command.getChecks() == null || command.getChecks().stream().allMatch(Objects::nonNull), "检查项不能包含空值");
        require(command.getChecks() == null || CHECKS.containsAll(command.getChecks()), "核验检查项只能使用固定目录");
        String checks = JSONUtil.toJsonStr(command.getChecks() == null ? List.of()
                : command.getChecks().stream().distinct().sorted().toList());
        var record = record(command.getId(), true);
        String operator = SecurityUtils.getUser().getUsername();
        if (record.getReviewRequestId() != null) {
            require(Objects.equals(record.getReviewRequestId(), command.getRequestId())
                    && Objects.equals(record.getReviewedBy(), operator)
                    && Objects.equals(record.getReviewRemark(), command.getRemark().trim())
                    && Objects.equals(record.getReviewChecks(), checks)
                    && ("REJECT".equals(command.getDecision()) ? "REJECTED".equals(record.getStatus())
                    : Set.of("CONFIRMED", "STALE").contains(record.getStatus())), "观察已核验或原请求内容不同");
            return record;
        }
        require("PENDING".equals(record.getStatus()) && !Objects.equals(record.getCreateBy(), operator),
                "只允许独立人员核验待处理观察，采集人与核验人不得相同");
        String status = "REJECTED";
        if ("CONFIRM".equals(command.getDecision())) {
            require(command.getChecks() != null && command.getChecks().size() == 3
                    && new HashSet<>(command.getChecks()).equals(CHECKS), "须完成来源、数量口径及处置路径三项固定核验");
            Duration age = Duration.between(record.getObservedTime(), LocalDateTime.now());
            if (age.isNegative() || age.compareTo(Duration.ofMinutes(30)) > 0) {
                status = "STALE";
            } else {
                var latest = reader.read(record.getVersionId(), record.getToleranceMinutes());
                status = Objects.equals(latest.getFingerprint(), record.getEvidenceFingerprint()) ? "CONFIRMED" : "STALE";
            }
        }
        record.setStatus(status).setReviewRequestId(command.getRequestId()).setReviewedBy(operator)
                .setReviewedTime(LocalDateTime.now()).setReviewRemark(command.getRemark().trim()).setReviewChecks(checks);
        require(updateById(record), "独立核验保存失败");
        return record;
    }

    private PlnMpsExecutionObservationEntity record(Long id, boolean lock) {
        require(id != null, "执行观察主键必填");
        var query = scope().eq(PlnMpsExecutionObservationEntity::getId, id);
        if (lock) {
            query.last("FOR UPDATE");
        }
        var record = getOne(query);
        require(record != null, "当前实际租户、工厂未找到执行观察");
        return record;
    }

    private void authorize(String level) {
        mpsService.requireFactoryAccess(reader.actualFactory(), level);
    }

    private LambdaQueryWrapper<PlnMpsExecutionObservationEntity> scope() {
        return new LambdaQueryWrapper<PlnMpsExecutionObservationEntity>()
                .eq(PlnMpsExecutionObservationEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(PlnMpsExecutionObservationEntity::getFactoryCode, reader.actualFactory());
    }

    private void text(String value, int limit, String name) {
        require(StrUtil.isNotBlank(value) && value.length() <= limit, name + "不能为空或超长");
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }
}
