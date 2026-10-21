package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesMaterialCompensationCommandDTO;
import com.kitex.mom.mes.entity.MesMaterialReconcileCompensationEntity;
import com.kitex.mom.mes.entity.MesMaterialReconcileIssueEntity;
import com.kitex.mom.mes.mapper.MesMaterialReconcileCompensationMapper;
import com.kitex.mom.mes.mapper.MesMaterialReconcileIssueMapper;
import com.kitex.mom.mes.service.MesMaterialReconcileCompensationService;
import com.kitex.mom.mes.service.MesMaterialReconcileIssueService;
import com.kitex.mom.wms.api.feign.RemoteMaterialReservationService;
import com.kitex.mom.wms.dto.WmsMaterialCompensationCommandDTO;
import com.kitex.mom.wms.dto.WmsMaterialCompensationResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MesMaterialReconcileCompensationServiceImpl
        extends ServiceImpl<MesMaterialReconcileCompensationMapper, MesMaterialReconcileCompensationEntity>
        implements MesMaterialReconcileCompensationService {
    private static final Set<String> ACTIVE_ISSUE_STATUS = Set.of("OPEN", "CLAIMED");
    private static final Set<String> ACTIVE_COMPENSATION_STATUS = Set.of("DRAFT", "APPROVED", "EXECUTING");
    private static final String AUTO = "AUTOMATIC";
    private static final String MANUAL = "MANUAL";
    private static final String REPLAY_CALLBACK = "REPLAY_WMS_MES_CALLBACK";
    private static final String MANUAL_REPAIR = "MANUAL_SOURCE_REPAIR";

    private final MesMaterialReconcileIssueMapper issueMapper;
    private final MesMaterialReconcileIssueService issueService;
    private final RemoteMaterialReservationService remoteReservationService;

    @Override
    public List<MesMaterialReconcileCompensationEntity> listByIssue(Long issueId) {
        if (issueId == null) {
            throw new CheckedException("缺少对账异常id");
        }
        return list(new LambdaQueryWrapper<MesMaterialReconcileCompensationEntity>()
                .eq(MesMaterialReconcileCompensationEntity::getIssueId, issueId)
                .orderByDesc(MesMaterialReconcileCompensationEntity::getProposedTime));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesMaterialReconcileCompensationEntity prepare(MesMaterialCompensationCommandDTO command) {
        MesMaterialReconcileIssueEntity issue = requireActiveIssue(command == null ? null : command.getIssueId());
        if (StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("发起补偿必须填写差异原因或处理依据");
        }
        if (command.getReason().trim().length() > 500) {
            throw new CheckedException("差异原因或处理依据最多500字");
        }
        long activeCount = count(new LambdaQueryWrapper<MesMaterialReconcileCompensationEntity>()
                .eq(MesMaterialReconcileCompensationEntity::getIssueId, issue.getId())
                .in(MesMaterialReconcileCompensationEntity::getStatus, ACTIVE_COMPENSATION_STATUS));
        if (activeCount > 0) {
            throw new CheckedException("该异常已有待审批或执行中的补偿方案");
        }
        boolean replayable = Set.of("WMS_CONSUMED_WITHOUT_MES_CALLBACK", "WMS_MES_ISSUED_MISMATCH")
                .contains(issue.getIssueType())
                && optional(issue.getExpectedNum()).compareTo(BigDecimal.ZERO) > 0
                && optional(issue.getActualNum()).compareTo(BigDecimal.ZERO) == 0
                && StrUtil.isAllNotBlank(issue.getReservationNo(), issue.getRequirementMatCode(), issue.getActualMatCode());
        String repairMode = replayable ? AUTO : MANUAL;
        String action = replayable ? REPLAY_CALLBACK : MANUAL_REPAIR;
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("issueType", issue.getIssueType());
        plan.put("repairMode", repairMode);
        plan.put("action", action);
        plan.put("preconditions", replayable
                ? List.of("WMS存在已完成生产领料出库明细", "MES对应发料回传数量为0", "按WMS出库明细id幂等补写MES接收通知")
                : List.of("仅允许在异常来源业务页面修正", "不得直接修改数据库", "修正后必须重新执行三方对账"));
        plan.put("repairGuide", repairGuide(issue.getIssueType()));
        plan.put("issueEvidence", issue.getEvidenceSnapshot());
        UserIdentity user = currentUser();
        MesMaterialReconcileCompensationEntity entity = new MesMaterialReconcileCompensationEntity()
                .setIssueId(issue.getId())
                .setRequestId(UUID.randomUUID().toString().replace("-", ""))
                .setRepairMode(repairMode).setCompensationAction(action).setStatus("DRAFT")
                .setReason(command.getReason().trim()).setPlanSnapshot(JSONUtil.toJsonStr(plan))
                .setProposedBy(user.code()).setProposedByName(user.name()).setProposedTime(LocalDateTime.now())
                .setFactoryCode(issue.getFactoryCode());
        save(entity);
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesMaterialReconcileCompensationEntity approve(MesMaterialCompensationCommandDTO command) {
        MesMaterialReconcileCompensationEntity compensation = requireCompensation(command);
        if (!"DRAFT".equals(compensation.getStatus())) {
            throw new CheckedException("仅补偿草稿允许审批");
        }
        requireActiveIssue(compensation.getIssueId());
        UserIdentity user = currentUser();
        if (user.code().equals(compensation.getProposedBy())) {
            throw new CheckedException("补偿发起人与审批人必须分离");
        }
        update(new LambdaUpdateWrapper<MesMaterialReconcileCompensationEntity>()
                .eq(MesMaterialReconcileCompensationEntity::getId, compensation.getId())
                .eq(MesMaterialReconcileCompensationEntity::getStatus, "DRAFT")
                .set(MesMaterialReconcileCompensationEntity::getStatus, "APPROVED")
                .set(MesMaterialReconcileCompensationEntity::getApprovedBy, user.code())
                .set(MesMaterialReconcileCompensationEntity::getApprovedByName, user.name())
                .set(MesMaterialReconcileCompensationEntity::getApprovedTime, LocalDateTime.now()));
        return getById(compensation.getId());
    }

    @Override
    public MesMaterialReconcileCompensationEntity execute(MesMaterialCompensationCommandDTO command) {
        MesMaterialReconcileCompensationEntity compensation = requireCompensation(command);
        if ("SUCCEEDED".equals(compensation.getStatus())) {
            return compensation;
        }
        if (!"APPROVED".equals(compensation.getStatus()) && !"EXECUTING".equals(compensation.getStatus())) {
            throw new CheckedException("仅已批准或中断待续执行的补偿允许执行");
        }
        if (!AUTO.equals(compensation.getRepairMode()) || !REPLAY_CALLBACK.equals(compensation.getCompensationAction())) {
            throw new CheckedException("该方案需要在来源业务页面人工修正，修正后使用复核关闭，不能由系统改业务账");
        }
        issueService.scan();
        MesMaterialReconcileIssueEntity issue = issueMapper.selectById(compensation.getIssueId());
        compensation = getById(compensation.getId());
        if (issue == null) {
            throw new CheckedException("补偿关联的对账异常不存在");
        }
        if ("RESOLVED".equals(issue.getStatus())) {
            return compensation;
        }
        requireReplayable(issue);
        UserIdentity user = currentUser();
        update(new LambdaUpdateWrapper<MesMaterialReconcileCompensationEntity>()
                .eq(MesMaterialReconcileCompensationEntity::getId, compensation.getId())
                .in(MesMaterialReconcileCompensationEntity::getStatus, "APPROVED", "EXECUTING")
                .set(MesMaterialReconcileCompensationEntity::getStatus, "EXECUTING")
                .set(MesMaterialReconcileCompensationEntity::getExecutedBy, user.code())
                .set(MesMaterialReconcileCompensationEntity::getExecutedByName, user.name())
                .set(MesMaterialReconcileCompensationEntity::getExecutedTime, LocalDateTime.now())
                .set(MesMaterialReconcileCompensationEntity::getFailureMessage, null));
        try {
            WmsMaterialCompensationCommandDTO remoteCommand = new WmsMaterialCompensationCommandDTO()
                    .setRequestId(compensation.getRequestId()).setAction(REPLAY_CALLBACK)
                    .setReservationNo(issue.getReservationNo())
                    .setRequirementMatCode(issue.getRequirementMatCode()).setActualMatCode(issue.getActualMatCode());
            R<WmsMaterialCompensationResultDTO> response = remoteReservationService.compensate(remoteCommand);
            if (response == null || !response.isOk() || response.getData() == null) {
                throw new CheckedException(response == null ? "WMS补偿无响应" : response.getMsg());
            }
            update(new LambdaUpdateWrapper<MesMaterialReconcileCompensationEntity>()
                    .eq(MesMaterialReconcileCompensationEntity::getId, compensation.getId())
                    .set(MesMaterialReconcileCompensationEntity::getResultSnapshot, JSONUtil.toJsonStr(response.getData())));
            issueService.scan();
            issue = issueMapper.selectById(compensation.getIssueId());
            if (issue != null && !"RESOLVED".equals(issue.getStatus())) {
                markFailed(compensation.getId(), "WMS回调已重放，但最新三方对账仍存在差异，请查看证据后发起新的处理方案");
            }
        } catch (Exception ex) {
            markFailed(compensation.getId(), StrUtil.maxLength(StrUtil.blankToDefault(ex.getMessage(), "补偿执行失败"), 1000));
        }
        return getById(compensation.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesMaterialReconcileCompensationEntity cancel(MesMaterialCompensationCommandDTO command) {
        MesMaterialReconcileCompensationEntity compensation = requireCompensation(command);
        if (!"DRAFT".equals(compensation.getStatus()) && !"APPROVED".equals(compensation.getStatus())) {
            throw new CheckedException("仅草稿或已批准但尚未执行的补偿允许取消");
        }
        if (StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("取消补偿必须填写原因");
        }
        if (command.getReason().trim().length() > 500) {
            throw new CheckedException("取消原因最多500字");
        }
        update(new LambdaUpdateWrapper<MesMaterialReconcileCompensationEntity>()
                .eq(MesMaterialReconcileCompensationEntity::getId, compensation.getId())
                .in(MesMaterialReconcileCompensationEntity::getStatus, "DRAFT", "APPROVED")
                .set(MesMaterialReconcileCompensationEntity::getStatus, "CANCELLED")
                .set(MesMaterialReconcileCompensationEntity::getResultSnapshot,
                        JSONUtil.toJsonStr(Map.of("cancelReason", command.getReason().trim(), "cancelledBy", currentUser().code()))));
        return getById(compensation.getId());
    }

    private MesMaterialReconcileIssueEntity requireActiveIssue(Long issueId) {
        if (issueId == null) {
            throw new CheckedException("缺少对账异常id");
        }
        MesMaterialReconcileIssueEntity issue = issueMapper.selectById(issueId);
        if (issue == null || !ACTIVE_ISSUE_STATUS.contains(issue.getStatus())) {
            throw new CheckedException("仅待处理或已认领异常可以发起补偿");
        }
        return issue;
    }

    private MesMaterialReconcileCompensationEntity requireCompensation(MesMaterialCompensationCommandDTO command) {
        if (command == null || command.getCompensationId() == null) {
            throw new CheckedException("缺少补偿记录id");
        }
        MesMaterialReconcileCompensationEntity compensation = getById(command.getCompensationId());
        if (compensation == null) {
            throw new CheckedException("补偿记录不存在");
        }
        return compensation;
    }

    private void requireReplayable(MesMaterialReconcileIssueEntity issue) {
        if (!ACTIVE_ISSUE_STATUS.contains(issue.getStatus())
                || !Set.of("WMS_CONSUMED_WITHOUT_MES_CALLBACK", "WMS_MES_ISSUED_MISMATCH").contains(issue.getIssueType())
                || optional(issue.getExpectedNum()).compareTo(BigDecimal.ZERO) <= 0
                || optional(issue.getActualNum()).compareTo(BigDecimal.ZERO) != 0
                || !StrUtil.isAllNotBlank(issue.getReservationNo(), issue.getRequirementMatCode(), issue.getActualMatCode())) {
            throw new CheckedException("最新证据已不满足自动重放条件，请重新生成补偿方案");
        }
    }

    private String repairGuide(String issueType) {
        return switch (issueType) {
            case "WMS_CONSUMED_WITHOUT_MES_CALLBACK", "WMS_MES_ISSUED_MISMATCH" ->
                    "核对WMS已完成生产领料出库单；仅当MES回传数量为0时可审批重放，否则人工核对重复或部分回传。";
            case "PLAN_SOURCE_MISSING", "PLAN_WMS_RESERVATION_MISMATCH", "PLAN_REQUIREMENT_WMS_MISMATCH" ->
                    "在PLAN检查MRP需求来源、替代决策和正式预留号，不得直接改WMS预留明细。";
            case "PLAN_APPROVAL_WMS_STATUS_MISMATCH" -> "在PLAN执行替代决策撤销/重新批准，由原业务命令释放或重建预留。";
            case "ACTIVE_RESERVED_STOCK_UNAVAILABLE", "ACTIVE_RESERVED_STOCK_DEFICIT" ->
                    "在WMS处理冻结、质检、盘点或实物差异，再由原预留业务重算，不得直接改预留数量。";
            case "PICK_REQUEST_EXCEEDS_RESERVATION", "MES_LINEAGE_NOT_RESERVED", "WMS_RESERVATION_MISSING" ->
                    "撤回或更正MES领料申请，并从已冻结工单BOM和PLAN正式预留重新选择物料。";
            case "MES_RECEIVED_EXCEEDS_ISSUED" -> "在MES核对现场接收和线边入库，按原接收/退回事务冲销多余数量。";
            default -> "按证据快照在来源模块执行原业务撤销、冲销或重算命令，完成后重新对账。";
        };
    }

    private void markFailed(Long id, String message) {
        update(new LambdaUpdateWrapper<MesMaterialReconcileCompensationEntity>()
                .eq(MesMaterialReconcileCompensationEntity::getId, id)
                .set(MesMaterialReconcileCompensationEntity::getStatus, "FAILED")
                .set(MesMaterialReconcileCompensationEntity::getFailureMessage, message));
    }

    private UserIdentity currentUser() {
        MomUser user = SecurityUtils.getUser();
        return user == null ? new UserIdentity("SYSTEM", "SYSTEM")
                : new UserIdentity(StrUtil.blankToDefault(user.getUsername(), "SYSTEM"),
                StrUtil.blankToDefault(user.getName(), StrUtil.blankToDefault(user.getUsername(), "SYSTEM")));
    }

    private BigDecimal optional(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record UserIdentity(String code, String name) {
    }
}
