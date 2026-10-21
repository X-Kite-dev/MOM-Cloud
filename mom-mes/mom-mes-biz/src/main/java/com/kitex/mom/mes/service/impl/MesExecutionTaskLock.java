package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 派工、报工、载具、退派、冲销及退回路线共用的工单事务锁入口。
 *
 * <p>先按实际租户工厂无锁定位所有者，再取稳定工单锁；调用方之后才能锁行，
 * 并重新核对所有者。禁止先锁报工/派工行再补取工单锁。</p>
 */
@Component
@RequiredArgsConstructor
public class MesExecutionTaskLock {

    private final MesActualFactoryAccessService factoryAccess;
    private final MesIdentifierBindingGroupService bindingGroups;
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesTaskReportMapper reportMapper;

    /** 按派工编码定位并锁其真实工单，不能由客户端taskCode决定锁目标。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MesTaskArrangeEntity arrange(String code) {
        String factory = factoryAccess.require("write");
        var rows = arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory).eq(MesTaskArrangeEntity::getCode, code)
                .last("LIMIT 2"));
        require(rows.size() == 1, "当前租户工厂未找到唯一派工任务");
        bindingGroups.lockDispatchTask(rows.get(0).getTaskCode());
        return rows.get(0);
    }

    /** 退派按精确派工ID定位真实工单。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MesTaskArrangeEntity arrange(Long id) {
        String factory = factoryAccess.require("write");
        var row = arrangeMapper.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory).eq(MesTaskArrangeEntity::getId, id));
        require(row != null, "当前租户工厂未找到派工任务");
        bindingGroups.lockDispatchTask(row.getTaskCode());
        return row;
    }

    /** 冲销前以原报工真实工单为锁目标，不先锁报工行。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MesTaskReportEntity report(Long id) {
        String factory = factoryAccess.require("write");
        var row = reportMapper.selectOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, factory).eq(MesTaskReportEntity::getId, id));
        require(row != null, "当前租户工厂未找到原报工记录");
        bindingGroups.lockDispatchTask(row.getTaskCode());
        return row;
    }

    /** 锁行后发现身份变化直接回滚，不在持有行锁时改取另一个工单锁。 */
    public void assertArrange(MesTaskArrangeEntity before, MesTaskArrangeEntity locked) {
        require(before != null && locked != null && Objects.equals(before.getId(), locked.getId())
                && Objects.equals(before.getTaskCode(), locked.getTaskCode())
                && Objects.equals(before.getTenantId(), locked.getTenantId())
                && Objects.equals(before.getFactoryCode(), locked.getFactoryCode()), "派工归属已变化，请原号重核");
    }

    public void assertTaskCode(String before, String locked) {
        require(Objects.equals(before, locked), "业务事实的工单归属已变化，请原号重核");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
