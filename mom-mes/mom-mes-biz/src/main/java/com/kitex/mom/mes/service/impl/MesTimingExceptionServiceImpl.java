package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesTimingExceptionActionDTO;
import com.kitex.mom.mes.dto.MesTimingExceptionDetailDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTimingExceptionEntity;
import com.kitex.mom.mes.entity.MesTimingExceptionOperationEntity;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.mapper.MesTimingExceptionMapper;
import com.kitex.mom.mes.mapper.MesTimingExceptionOperationMapper;
import com.kitex.mom.mes.service.MesTimingExceptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/** 独立时间例外的申请、异岗决定和永久原号恢复；许可消费由真实报告事务的数据库守卫完成。 */
@Service
@RequiredArgsConstructor
public class MesTimingExceptionServiceImpl extends ServiceImpl<MesTimingExceptionMapper, MesTimingExceptionEntity>
        implements MesTimingExceptionService {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> ACTIONS = Set.of("SUBMIT", "WITHDRAW", "APPROVE", "REJECT", "REVOKE");
    private static final Set<String> STATES = Set.of("DETECTED", "SUBMITTED", "APPROVED", "REJECTED",
            "WITHDRAWN", "REVOKED", "CONSUMED");
    private final MesActualFactoryAccessService factoryAccess;
    private final PermissionService permissions;
    private final MesTimingExceptionOperationMapper operations;
    private final MesTaskArrangeMapper arranges;
    private final MesTaskReportMapper reportClock;
    private final MesExecutionTaskLock taskLock;
    private final MesTimingExceptionSourceReader sourceReader;

    @Override
    public IPage<MesTimingExceptionEntity> pageCases(long current, long size, String status, String taskCode) {
        String factory = factoryAccess.require("read");
        require(permissions.hasPermission("mesTimingException:view"), "没有超时观察查看权限");
        require(current >= 1 && current <= 1_000_000 && size >= 1 && size <= 100, "超时观察分页范围无效");
        require(status == null || status.isBlank() || STATES.contains(status), "超时观察状态无效");
        require(taskCode == null || taskCode.length() <= 100, "工单号过长");
        return page(new Page<>(current, size), scoped(factory)
                .eq(status != null && !status.isBlank(), MesTimingExceptionEntity::getStatus, status)
                .eq(taskCode != null && !taskCode.isBlank(), MesTimingExceptionEntity::getTaskCode, taskCode)
                .orderByDesc(MesTimingExceptionEntity::getObservedAt, MesTimingExceptionEntity::getId));
    }

    @Override
    public MesTimingExceptionDetailDTO detail(Long id) {
        require(permissions.hasPermission("mesTimingException:view"), "没有超时观察查看权限");
        return full(row(id, factoryAccess.require("read"), false));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MesTimingExceptionDetailDTO operation(String requestId) {
        String factory = factoryAccess.require("read");
        lockRequest(requestId);
        var op = operationRow(requestId, factory);
        require(op != null, "未找到已保存的原动作；不能据此改号，仍可使用同一原文重试");
        require(Objects.equals(op.getOperatorId(), SecurityUtils.getUser().getId())
                        && Objects.equals(op.getOperatorCode(), SecurityUtils.getUser().getUsername()),
                "只能核对本人原动作");
        return full(row(op.getCaseId(), factory, false));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MesTimingExceptionDetailDTO action(String raw) {
        var command = parse(raw);
        String factory = factoryAccess.require("write");
        boolean applying = Set.of("SUBMIT", "WITHDRAW").contains(command.action());
        require(permissions.hasPermission(applying ? "mesTimingException:apply" : "mesTimingException:review"),
                "没有该超时例外动作的独立权限");
        var actor = SecurityUtils.getUser();
        String body = MesTimingExceptionFacts.json(command);
        String fingerprint = MesTimingExceptionFacts.hash("MES_TIMING_ACTION_V1:" + TenantContextHolder.getTenantId()
                + ":" + factory + ":" + actor.getId() + ":" + actor.getUsername() + ":" + body);
        lockRequest(command.requestId());
        var previous = operationRow(command.requestId(), factory);
        if (previous != null) {
            require(Objects.equals(previous.getCommandJson(), body) && Objects.equals(previous.getFingerprint(), fingerprint)
                            && Objects.equals(previous.getOperatorId(), actor.getId()), "原动作号已绑定不同正文或操作者");
            return full(row(previous.getCaseId(), factory, false));
        }
        Long id = Long.valueOf(command.caseId());
        var before = row(id, factory, false);
        var owner = taskLock.arrange(before.getArrangeCode());
        var arrange = arranges.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, owner.getId()).eq(MesTaskArrangeEntity::getTenantId, owner.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory).last("FOR UPDATE"));
        taskLock.assertArrange(owner, arrange);
        var value = row(id, factory, true);
        require(Objects.equals(value.getTaskCode(), arrange.getTaskCode())
                && Objects.equals(value.getProduceTaskCode(), arrange.getProduceTaskCode()), "原超时观察与实际派工归属不一致");
        if (applying) {
            require(Objects.equals(value.getActorId(), actor.getId())
                    && Objects.equals(value.getActorCode(), actor.getUsername()), "只有原报工人可以申请或撤回");
        } else {
            require(!Objects.equals(value.getActorId(), actor.getId())
                    && !Objects.equals(value.getActorCode(), actor.getUsername()), "申请与复核须由不同实名人员办理");
        }
        int version = value.getVersionNo();
        LocalDateTime now = reportClock.timingClock();
        String failure = null;
        // 业务性不受理也留永久结果，避免版本冲突/来源失效导致端侧UNKNOWN永远无法核账。
        try {
            require(Integer.toString(version).equals(command.expectedVersion()), "页面版本已变化，请核原动作后刷新");
            transition(value, arrange, command, now);
        } catch (CheckedException rejected) {
            failure = rejected.getMessage();
        } catch (DataAccessException rejected) {
            failure = "原来源数据库核验失败，未批准；请核查原动态操作、命令和过站事实";
        }
        if (failure == null) {
            value.setVersionNo(version + 1);
            require(update(new LambdaUpdateWrapper<MesTimingExceptionEntity>()
                    .eq(MesTimingExceptionEntity::getId, id).eq(MesTimingExceptionEntity::getTenantId, value.getTenantId())
                    .eq(MesTimingExceptionEntity::getFactoryCode, factory).eq(MesTimingExceptionEntity::getVersionNo, version)
                    .set(MesTimingExceptionEntity::getStatus, value.getStatus())
                    .set(MesTimingExceptionEntity::getVersionNo, value.getVersionNo())
                    .set(MesTimingExceptionEntity::getProposedUntil, value.getProposedUntil())
                    .set(MesTimingExceptionEntity::getApprovedUntil, value.getApprovedUntil())
                    .set(MesTimingExceptionEntity::getRequestReason, value.getRequestReason())
                    .set(MesTimingExceptionEntity::getReviewReason, value.getReviewReason())
                    .set(MesTimingExceptionEntity::getApprovedBy, value.getApprovedBy())
                    .set(MesTimingExceptionEntity::getApprovedByCode, value.getApprovedByCode())
                    .set(MesTimingExceptionEntity::getUpdateBy, actor.getUsername())
                    .set(MesTimingExceptionEntity::getUpdateTime, now)), "超时例外状态保存失败");
        } else {
            value = row(id, factory, true);
        }
        var op = new MesTimingExceptionOperationEntity().setFactoryCode(factory).setCaseId(id)
                .setRequestId(command.requestId()).setAction(command.action()).setCommandJson(body).setFingerprint(fingerprint)
                .setOldVersion(version).setNewVersion(value.getVersionNo()).setOperatorId(actor.getId())
                .setOperatorCode(actor.getUsername()).setReason(command.reason()).setCreatedAt(now)
                .setProposedUntil("SUBMIT".equals(command.action()) ? LocalDateTime.parse(command.proposedUntil()) : null)
                .setOutcome(failure == null ? "APPLIED" : "DECLINED").setErrorMessage(failure)
                .setResultJson(MesTimingExceptionFacts.json(value));
        op.setId(IdWorker.getId());
        op.setTenantId(value.getTenantId());
        op.setDelFlag("0");
        op.setCreateBy(actor.getUsername());
        op.setCreateTime(now);
        require(operations.insert(op) == 1, "时间例外操作账必要写失败");
        return full(row(id, factory, false));
    }

    /** 所有前提先检查，再改变内存状态；拒绝动作不应留下半份新审批字段。 */
    private void transition(MesTimingExceptionEntity row, MesTaskArrangeEntity arrange,
                            MesTimingExceptionActionDTO command, LocalDateTime now) {
        switch (command.action()) {
            case "SUBMIT" -> {
                require(Set.of("DETECTED", "REJECTED", "WITHDRAWN", "REVOKED").contains(row.getStatus()), "当前状态不能重新申请");
                LocalDateTime until = LocalDateTime.parse(command.proposedUntil());
                verifySources(row, arrange, until, now);
                row.setStatus("SUBMITTED").setProposedUntil(until).setRequestReason(command.reason())
                        .setApprovedUntil(null).setApprovedBy(null).setApprovedByCode(null).setReviewReason(null);
            }
            case "APPROVE" -> {
                require("SUBMITTED".equals(row.getStatus()), "只有待审核申请可以批准");
                verifySources(row, arrange, row.getProposedUntil(), now);
                row.setStatus("APPROVED").setApprovedUntil(row.getProposedUntil()).setReviewReason(command.reason())
                        .setApprovedBy(SecurityUtils.getUser().getId()).setApprovedByCode(SecurityUtils.getUser().getUsername());
            }
            case "WITHDRAW" -> {
                require("SUBMITTED".equals(row.getStatus()), "只有待审核申请可以撤回");
                row.setStatus("WITHDRAWN").setReviewReason(command.reason());
            }
            case "REJECT" -> {
                require("SUBMITTED".equals(row.getStatus()), "只有待审核申请可以驳回");
                row.setStatus("REJECTED").setReviewReason(command.reason());
            }
            case "REVOKE" -> {
                require("APPROVED".equals(row.getStatus()), "只有尚未消费的批准可以撤销");
                row.setStatus("REVOKED").setReviewReason(command.reason());
            }
            default -> throw new CheckedException("不支持的时间例外动作");
        }
    }

    /** 独立只读事务验证真实来源，数据库拒绝不污染外层记录永久DECLINED的事务。 */
    private void verifySources(MesTimingExceptionEntity row, MesTaskArrangeEntity arrange,
                               LocalDateTime until, LocalDateTime now) {
        require(until != null && until.isAfter(now) && row.getLimitUntil() != null
                && !until.isAfter(row.getLimitUntil()), "截止须晚于现在且不得超过原工程例外上限");
        var observed = sourceReader.inspect(arrange, row.getReportMode(), row.getIdentifier(), row.getDependencyJson());
        require(!observed.lateRules().isEmpty() && Objects.equals(observed.dependencyJson(), row.getDependencyJson())
                        && observed.limitUntil() != null && !observed.limitUntil().isBefore(row.getLimitUntil()),
                "原规则或真实来源已变化，不能沿旧观察批准");
        // 新追加的真实暂停可提高当前工程上限，但不自动延长首次观察和本次申请的绝对截止。
        require(until.isAfter(reportClock.timingClock()), "来源核验期间申请截止已过，不再批准");
    }

    private MesTimingExceptionActionDTO parse(String raw) {
        require(raw != null && raw.length() <= 16_384, "时间例外命令缺失或过长");
        try {
            var tree = JSON.readTree(raw);
            require(tree != null && tree.isObject() && tree.size() == 6, "时间例外命令必须是完整六字段");
            var fields = tree.elements();
            while (fields.hasNext()) require(fields.next().isTextual(), "时间例外命令字段须为文本");
            var c = JSON.readValue(raw, MesTimingExceptionActionDTO.class);
            require(c.requestId().matches("[A-Za-z0-9_-]{16,64}") && c.caseId().matches("[1-9][0-9]{0,18}")
                    && Long.parseLong(c.caseId()) > 0 && c.expectedVersion().matches("[1-9][0-9]{0,8}")
                    && ACTIONS.contains(c.action()), "原号、案件ID、版本或动作无效");
            require(!c.reason().isBlank() && c.reason().length() <= 1000, "请填写1—1000字的现场原因或审核意见");
            if ("SUBMIT".equals(c.action())) {
                require(c.proposedUntil().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"), "截止须为本地ISO秒格式");
                LocalDateTime.parse(c.proposedUntil());
            } else {
                require(c.proposedUntil().isEmpty(), "非申请动作不能改变原截止时间");
            }
            return c;
        } catch (CheckedException error) {
            throw error;
        } catch (Exception error) {
            throw new CheckedException("时间例外命令格式错误，不允许缺字段或自动转换数值");
        }
    }

    private void lockRequest(String request) {
        require(request != null && request.matches("[A-Za-z0-9_-]{16,64}"), "原动作号无效");
        require(Integer.valueOf(1).equals(operations.lockRequest("MES_TIMING_ACTION_V1:"
                + TenantContextHolder.getTenantId() + ":" + request)), "未取得时间例外原动作锁");
    }

    private LambdaQueryWrapper<MesTimingExceptionEntity> scoped(String factory) {
        return new LambdaQueryWrapper<MesTimingExceptionEntity>().eq(MesTimingExceptionEntity::getTenantId,
                TenantContextHolder.getTenantId()).eq(MesTimingExceptionEntity::getFactoryCode, factory);
    }

    private MesTimingExceptionEntity row(Long id, String factory, boolean lock) {
        var row = getOne(scoped(factory).eq(MesTimingExceptionEntity::getId, id).last(lock ? "FOR UPDATE" : ""));
        require(row != null, "当前工厂未找到原超时观察");
        return row;
    }

    private MesTimingExceptionOperationEntity operationRow(String request, String factory) {
        return operations.selectOne(new LambdaQueryWrapper<MesTimingExceptionOperationEntity>()
                .eq(MesTimingExceptionOperationEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTimingExceptionOperationEntity::getFactoryCode, factory).eq(MesTimingExceptionOperationEntity::getRequestId, request));
    }

    private MesTimingExceptionDetailDTO full(MesTimingExceptionEntity row) {
        return new MesTimingExceptionDetailDTO(row, operations.selectList(new LambdaQueryWrapper<MesTimingExceptionOperationEntity>()
                .eq(MesTimingExceptionOperationEntity::getTenantId, row.getTenantId())
                .eq(MesTimingExceptionOperationEntity::getFactoryCode, row.getFactoryCode())
                .eq(MesTimingExceptionOperationEntity::getCaseId, row.getId()).orderByAsc(MesTimingExceptionOperationEntity::getId)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
