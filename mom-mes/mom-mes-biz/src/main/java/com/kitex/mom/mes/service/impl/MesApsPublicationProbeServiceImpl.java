package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.mes.dto.MesApsPublicationProbeDTO;
import com.kitex.mom.mes.entity.MesApsScheduleSyncEntity;
import com.kitex.mom.mes.mapper.MesApsScheduleSyncMapper;
import com.kitex.mom.mes.service.MesApsPublicationProbeService;
import com.kitex.mom.mes.service.MesApsScheduleSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 原应用证据与当前计划分开取证。
 *
 * <p>案件锁先于永久号锁，与apply顺序一致。默认READ_COMMITTED在等待应用提交后读取新回执；
 * 当前工序由另一个只读一致性事务采集，不持有MES工单锁等待PLAN，避免反向调用死锁。</p>
 */
@Service
@RequiredArgsConstructor
public class MesApsPublicationProbeServiceImpl
        extends ServiceImpl<MesApsScheduleSyncMapper, MesApsScheduleSyncEntity>
        implements MesApsPublicationProbeService {
    private final MesApsScheduleSyncService scheduleService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesApsPublicationProbeDTO.Reply probe(MesApsPublicationProbeDTO.Query query) {
        require(query != null && query.getExpectedCommand() != null, "跨账取证必须提供完整原MES命令");
        MesApsLocalScheduleDTO.ApplyCommand command = query.getExpectedCommand();
        validateScope(command.getFactoryCode(), command.getPlanNo(), command.getReplanCaseId());
        require(StrUtil.isNotBlank(command.getRequestId()) && command.getRequestId().length() <= 100,
                "原MES请求号不能为空且不得超过100位");
        require(query.getExpectedProcessCodes() != null && !query.getExpectedProcessCodes().isEmpty()
                        && query.getExpectedProcessCodes().size() <= 1000
                        && query.getExpectedProcessCodes().stream().allMatch(StrUtil::isNotBlank),
                "须提供1至1000个完整原根计划工序，不允许缺范围或截断");
        List<String> nodes = query.getExpectedSourceRouteNodeCodes();
        if (nodes == null) {
            require(new HashSet<>(query.getExpectedProcessCodes()).size() == query.getExpectedProcessCodes().size(),
                    "旧取证合同只允许唯一原主工序");
        } else {
            require(nodes.size() == query.getExpectedProcessCodes().size()
                            && nodes.stream().allMatch(node -> node != null && node.matches("RN-[1-9][0-9]*"))
                            && new HashSet<>(nodes).size() == nodes.size(), "原节点全集与主工序清单不完整或重复");
        }
        baseMapper.lockCase(caseKey(command.getFactoryCode(), command.getReplanCaseId()));
        baseMapper.lockRequest(command.getRequestId());
        var log = getOne(scope(command.getFactoryCode()).eq(MesApsScheduleSyncEntity::getRequestId, command.getRequestId()));
        var reply = new MesApsPublicationProbeDTO.Reply().setObservedTime(LocalDateTime.now()).setCommandMatched(false);
        if (log == null) {
            return reply.setState("NOT_OBSERVED");
        }
        require(Objects.equals(log.getPlanNo(), command.getPlanNo())
                        && Objects.equals(log.getReplanCaseId(), command.getReplanCaseId())
                        && "SUCCESS".equals(log.getSyncStatus())
                        && Objects.equals(log.getRequestSnapshot(), JSONUtil.toJsonStr(command)),
                "原永久号的案件、根计划、状态或完整载荷不一致，禁止替换回执");
        var current = scheduleService.verifyAppliedSchedule(command, query.getExpectedProcessCodes(), nodes);
        var receipt = new MesApsLocalScheduleDTO.ApplyReceipt().setRequestId(log.getRequestId()).setPlanNo(log.getPlanNo())
                .setReplanCaseId(log.getReplanCaseId()).setUpdatedOperationCount(log.getUpdatedOperationCount())
                .setBeforeExecutionFingerprint(log.getBeforeExecutionFingerprint())
                .setAfterExecutionFingerprint(log.getAfterExecutionFingerprint()).setAppliedTime(log.getAppliedTime());
        return reply.setState(Boolean.TRUE.equals(current.getMatches()) ? "APPLIED_MATCHED" : "APPLIED_DRIFTED")
                .setReceipt(receipt).setCommandMatched(true).setAppliedBy(log.getCreateBy()).setCurrent(current)
                .setAppliedTimeExact(log.getAppliedTime() == null ? null : log.getAppliedTime().toString());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<MesApsPublicationProbeDTO.ReceiptRef> catalog(MesApsPublicationProbeDTO.Scope command) {
        require(command != null, "发布链组织范围必填");
        validateScope(command.getFactoryCode(), command.getPlanNo(), command.getCaseId());
        baseMapper.lockCase(caseKey(command.getFactoryCode(), command.getCaseId()));
        var logs = list(scope(command.getFactoryCode()).eq(MesApsScheduleSyncEntity::getReplanCaseId, command.getCaseId())
                .eq(MesApsScheduleSyncEntity::getPlanNo, command.getPlanNo()).eq(MesApsScheduleSyncEntity::getSyncStatus, "SUCCESS")
                .likeLeft(MesApsScheduleSyncEntity::getRequestId, ":MES")
                .select(MesApsScheduleSyncEntity::getRequestId, MesApsScheduleSyncEntity::getCreateBy, MesApsScheduleSyncEntity::getAppliedTime)
                .orderByAsc(MesApsScheduleSyncEntity::getId).last("LIMIT 201"));
        require(logs.size() <= 200, "原案件发布链超过200条，禁止截断或取末条猜测有效决定");
        return logs.stream().map(log -> new MesApsPublicationProbeDTO.ReceiptRef()
                .setBusinessRequestId(log.getRequestId().substring(0, log.getRequestId().length() - 4))
                .setMesRequestId(log.getRequestId()).setAppliedBy(log.getCreateBy()).setAppliedTime(log.getAppliedTime())).toList();
    }

    private LambdaQueryWrapper<MesApsScheduleSyncEntity> scope(String factory) {
        return new LambdaQueryWrapper<MesApsScheduleSyncEntity>()
                .eq(MesApsScheduleSyncEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesApsScheduleSyncEntity::getFactoryCode, factory);
    }

    /** 不接受跳过组织隔离或匿名恢复；调用端还须已有MES读取岗位权限。 */
    private void validateScope(String factory, String planNo, Long caseId) {
        require(TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && StrUtil.isNotBlank(FactoryContextHolder.getFactoryCode())
                        && Objects.equals(FactoryContextHolder.getFactoryCode(), factory)
                        && StrUtil.isNotBlank(planNo) && caseId != null && caseId > 0
                        && SecurityUtils.getUser() != null && StrUtil.isNotBlank(SecurityUtils.getUser().getUsername()),
                "跨账取证须实名选择实际租户、工厂及精确案件/根计划");
    }

    static String caseKey(String factory, Long caseId) {
        return "MES_APS_CASE:" + TenantContextHolder.getTenantId() + ':' + factory + ':' + caseId;
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }
}
