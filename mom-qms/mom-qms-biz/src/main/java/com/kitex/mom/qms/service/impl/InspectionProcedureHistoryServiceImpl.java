package com.kitex.mom.qms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.dto.QmsFirstInspectionDecisionDTO;
import com.kitex.mom.qms.entity.QmsInspectionProcedureHistoryEntity;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.enums.InspectionHistoryTypeEnum;
import com.kitex.mom.qms.enums.InspectionTaskResultEnum;
import com.kitex.mom.qms.mapper.QmsInspectionProcedureHistoryMapper;
import com.kitex.mom.qms.mapper.QmsInspectionProcedureTaskMapper;
import com.kitex.mom.qms.service.InspectionProcedureHistoryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 质检任务历史记录
 *
 * @author kitex
 * @date 2022/12/9
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionProcedureHistoryServiceImpl
        extends ServiceImpl<
                QmsInspectionProcedureHistoryMapper, QmsInspectionProcedureHistoryEntity>
        implements InspectionProcedureHistoryService {

    private final QmsInspectionProcedureTaskMapper taskMapper;
    private final QmsInspectionHistorySupport support;
    private final QmsInspectionEventService events;

    /** 创建任务 */
    @Transactional(rollbackFor = Exception.class)
    public void create(QmsInspectionProcedureTaskEntity task) {
        task = source(task);
        QmsInspectionHistorySupport.require(
                task.getNum() != null && task.getNum() > 0, "检验历史数量必须为正数");
        val history =
                new QmsInspectionProcedureHistoryEntity()
                        .setTime(new Date())
                        .setTaskId(task.getId())
                        .setType(InspectionHistoryTypeEnum.CREATE.name())
                        .setNum(task.getNum());
        history.setTenantId(task.getTenantId());
        history.setFactoryCode(task.getFactoryCode());
        if (!this.save(history)) throw new CheckedException("质检历史保存失败，业务变更已回滚");
        events.capture("PROCEDURE", history.getId());
    }

    /** 追加任务 */
    @Transactional(rollbackFor = Exception.class)
    public void append(QmsInspectionProcedureTaskEntity task, Integer num) {
        task = source(task);
        QmsInspectionHistorySupport.require(
                num != null && num > 0 && task.getNum() != null && num <= task.getNum(),
                "追加历史数量不正确");
        val history =
                new QmsInspectionProcedureHistoryEntity()
                        .setTaskId(task.getId())
                        .setTime(new Date())
                        .setType(InspectionHistoryTypeEnum.APPEND.name())
                        .setNum(num);
        history.setTenantId(task.getTenantId());
        history.setFactoryCode(task.getFactoryCode());
        if (!this.save(history)) throw new CheckedException("质检历史保存失败，业务变更已回滚");
        events.capture("PROCEDURE", history.getId());
    }

    /** 质检操作 */
    @Transactional(rollbackFor = Exception.class)
    public void check(QmsInspectionProcedureTaskEntity task) {
        task = source(task);
        QmsInspectionHistorySupport.require(
                task.getNum() != null && task.getNum() > 0, "检验历史数量必须为正数");
        QmsInspectionHistorySupport.require(
                InspectionTaskResultEnum.QUALIFIED.name().equals(task.getResult())
                        || InspectionTaskResultEnum.UN_QUALIFIED.name().equals(task.getResult()),
                "只有明确合格或不合格判定可记检验结果历史");
        val history =
                new QmsInspectionProcedureHistoryEntity()
                        .setTaskId(task.getId())
                        .setTime(new Date())
                        .setType(InspectionHistoryTypeEnum.RESULT.name())
                        .setNum(task.getNum())
                        .setQualifiedStatus(
                                Objects.equals(
                                        task.getResult(),
                                        InspectionTaskResultEnum.QUALIFIED.name()));
        history.setTenantId(task.getTenantId());
        history.setFactoryCode(task.getFactoryCode());
        if (!this.save(history)) throw new CheckedException("质检历史保存失败，业务变更已回滚");
        events.capture("PROCEDURE", history.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public com.kitex.mom.qms.entity.QmsInspectionEventEntity checkRevision(
            QmsInspectionProcedureTaskEntity task, Long requestId, Long executorId, Date time) {
        QmsInspectionHistorySupport.require(
                org.springframework.transaction.support.TransactionSynchronizationManager
                                .isActualTransactionActive()
                        && requestId != null
                        && requestId > 0
                        && executorId != null
                        && executorId > 0
                        && time != null,
                "正式修订历史缺少事务或冻结执行身份");
        task = source(task);
        QmsInspectionHistorySupport.require(
                "UN_QUALIFIED".equals(task.getResult())
                        && "UNHANDLED".equals(task.getStatus())
                        && task.getNum() != null
                        && task.getNum() > 0,
                "正式修订历史与任务不一致");
        var row =
                new QmsInspectionProcedureHistoryEntity()
                        .setTaskId(task.getId())
                        .setType("RESULT")
                        .setNum(task.getNum())
                        .setTime(new Date(time.getTime()))
                        .setQualifiedStatus(false)
                        .setDetailName("受控修订#" + requestId);
        row.setTenantId(task.getTenantId());
        row.setFactoryCode(task.getFactoryCode());
        row.setCreateBy(executorId.toString());
        QmsInspectionHistorySupport.require(save(row), "修订RESULT历史未保存");
        return events.capture("PROCEDURE", row.getId());
    }

    /** 作废 */
    @Transactional(rollbackFor = Exception.class)
    public void canceled(QmsInspectionProcedureTaskEntity task, String reason) {
        task = source(task);
        QmsInspectionHistorySupport.require(
                InspectionTaskResultEnum.CANCELED.name().equals(task.getResult()),
                "历史动作与实际检验状态不一致");
        val history =
                new QmsInspectionProcedureHistoryEntity()
                        .setTaskId(task.getId())
                        .setTime(new Date())
                        .setType(InspectionHistoryTypeEnum.CANCELED.name())
                        .setNum(task.getNum())
                        .setCancelReason(reason);
        history.setTenantId(task.getTenantId());
        history.setFactoryCode(task.getFactoryCode());
        if (!this.save(history)) throw new CheckedException("质检历史保存失败，业务变更已回滚");
        events.capture("PROCEDURE", history.getId());
    }

    /** 免检 */
    @Transactional(rollbackFor = Exception.class)
    public void exemption(QmsInspectionProcedureTaskEntity task) {
        task = source(task);
        QmsInspectionHistorySupport.require(
                task.getNum() != null && task.getNum() > 0, "检验历史数量必须为正数");
        QmsInspectionHistorySupport.require(
                InspectionTaskResultEnum.EXEMPTION.name().equals(task.getResult()),
                "历史动作与实际检验状态不一致");
        val history =
                new QmsInspectionProcedureHistoryEntity()
                        .setTaskId(task.getId())
                        .setTime(new Date())
                        .setNum(task.getNum())
                        .setType(InspectionHistoryTypeEnum.EXEMPTION.name());
        history.setTenantId(task.getTenantId());
        history.setFactoryCode(task.getFactoryCode());
        if (!this.save(history)) throw new CheckedException("质检历史保存失败，业务变更已回滚");
        events.capture("PROCEDURE", history.getId());
    }

    /**
     * 根据任务id 查询质检历史列表
     *
     * @return
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<QmsInspectionProcedureHistoryEntity> findAllByTaskId(Long taskId) {
        support.requireRead();
        parent(taskId);
        var rows =
                this.list(
                        scope().eq(QmsInspectionProcedureHistoryEntity::getTaskId, taskId)
                                .last("ORDER BY time ASC NULLS FIRST,id ASC LIMIT 5001"));
        QmsInspectionHistorySupport.bounded(rows.size());
        return rows;
    }

    /** 质检任务历史详情 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QmsInspectionProcedureHistoryEntity findById(Long id) {
        support.requireRead();
        QmsInspectionHistorySupport.require(id != null && id > 0, "检验历史ID无效");
        var result = this.getOne(scope().eq(QmsInspectionProcedureHistoryEntity::getId, id));
        if (result == null) throw new CheckedException("未找到当前组织的质检历史");
        parent(result.getTaskId());
        return result;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QmsFirstInspectionDecisionDTO firstDecision(Long taskId) {
        support.requireRead();
        parent(taskId);
        var rows =
                this.list(
                        scope().eq(QmsInspectionProcedureHistoryEntity::getTaskId, taskId)
                                .eq(
                                        QmsInspectionProcedureHistoryEntity::getType,
                                        InspectionHistoryTypeEnum.RESULT.name())
                                .last("ORDER BY time ASC NULLS FIRST,id ASC LIMIT 5001"));
        return support.first(
                "PROCEDURE",
                taskId,
                rows.stream()
                        .map(
                                row ->
                                        new QmsInspectionHistorySupport.Fact(
                                                row.getId(),
                                                row.getTime(),
                                                row.getNum(),
                                                row.getQualifiedStatus()))
                        .toList());
    }

    private LambdaQueryWrapper<QmsInspectionProcedureHistoryEntity> scope() {
        return new LambdaQueryWrapper<QmsInspectionProcedureHistoryEntity>()
                .eq(
                        QmsInspectionProcedureHistoryEntity::getTenantId,
                        TenantContextHolder.getTenantId())
                .eq(
                        QmsInspectionProcedureHistoryEntity::getFactoryCode,
                        FactoryContextHolder.getFactoryCode());
    }

    private QmsInspectionProcedureTaskEntity parent(Long taskId) {
        QmsInspectionHistorySupport.require(taskId != null && taskId > 0, "质检历史缺少实际任务ID");
        var task =
                taskMapper.selectOne(
                        new LambdaQueryWrapper<QmsInspectionProcedureTaskEntity>()
                                .eq(QmsInspectionProcedureTaskEntity::getId, taskId)
                                .eq(
                                        QmsInspectionProcedureTaskEntity::getTenantId,
                                        TenantContextHolder.getTenantId())
                                .eq(
                                        QmsInspectionProcedureTaskEntity::getFactoryCode,
                                        FactoryContextHolder.getFactoryCode()));
        if (task == null) throw new CheckedException("未找到当前组织的工序检验任务");
        return task;
    }

    private QmsInspectionProcedureTaskEntity source(QmsInspectionProcedureTaskEntity supplied) {
        support.requireScope();
        QmsInspectionHistorySupport.require(supplied != null, "质检历史缺少实际任务");
        var task = parent(supplied.getId());
        QmsInspectionHistorySupport.require(
                (supplied.getTenantId() == null
                                || Objects.equals(task.getTenantId(), supplied.getTenantId()))
                        && (supplied.getFactoryCode() == null
                                || Objects.equals(task.getFactoryCode(), supplied.getFactoryCode()))
                        && Objects.equals(task.getNum(), supplied.getNum())
                        && Objects.equals(task.getResult(), supplied.getResult()),
                "质检历史调用快照与实际任务不一致");
        return task;
    }
}
