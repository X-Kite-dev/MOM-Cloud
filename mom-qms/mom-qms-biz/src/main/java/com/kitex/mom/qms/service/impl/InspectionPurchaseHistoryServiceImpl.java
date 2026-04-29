package com.kitex.mom.qms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseHistoryEntity;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskEntity;
import com.kitex.mom.qms.enums.InspectionHistoryTypeEnum;
import com.kitex.mom.qms.enums.InspectionTaskResultEnum;
import com.kitex.mom.qms.mapper.QmsInspectionPurchaseHistoryMapper;
import com.kitex.mom.qms.service.InspectionPurchaseHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.stereotype.Service;

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
public class InspectionPurchaseHistoryServiceImpl extends ServiceImpl<QmsInspectionPurchaseHistoryMapper, QmsInspectionPurchaseHistoryEntity> implements InspectionPurchaseHistoryService {

    /**
     * 创建任务
     */
    public void create(QmsInspectionPurchaseTaskEntity task) {
        val history = new QmsInspectionPurchaseHistoryEntity().setTime(new Date()).setTaskId(task.getId())
                .setType(InspectionHistoryTypeEnum.CREATE.name()).setNum(task.getNum());
        this.save(history);
    }

    /**
     * 追加任务
     */
    public void append(QmsInspectionPurchaseTaskEntity task, Integer num) {
        val history = new QmsInspectionPurchaseHistoryEntity().setTaskId(task.getId()).setTime(new Date())
                .setType(InspectionHistoryTypeEnum.APPEND.name()).setNum(num);
        this.save(history);
    }

    /**
     * 质检操作
     */
    public void check(QmsInspectionPurchaseTaskEntity task) {
        val history = new QmsInspectionPurchaseHistoryEntity().setTaskId(task.getId()).setTime(new Date())
                .setType(InspectionHistoryTypeEnum.RESULT.name()).setNum(task.getNum())
                .setQualifiedStatus(Objects.equals(task.getResult(), InspectionTaskResultEnum.QUALIFIED.name()));
        this.save(history);
    }

    /**
     * 作废
     */
    public void canceled(QmsInspectionPurchaseTaskEntity task, String reason) {
        val history = new QmsInspectionPurchaseHistoryEntity().setTaskId(task.getId()).setTime(new Date())
                .setType(InspectionHistoryTypeEnum.CANCELED.name()).setNum(task.getNum()).setCancelReason(reason);
        this.save(history);
    }

    /**
     * 免检
     */
    public void exemption(QmsInspectionPurchaseTaskEntity task) {
        val history = new QmsInspectionPurchaseHistoryEntity().setTaskId(task.getId()).setTime(new Date())
                .setNum(task.getNum()).setType(InspectionHistoryTypeEnum.EXEMPTION.name());
        this.save(history);
    }

    /**
     * 根据任务id 查询质检历史列表
     *
     * @return
     */
    public List<QmsInspectionPurchaseHistoryEntity> findAllByTaskId(Long taskId) {
        return this.lambdaQuery().eq(QmsInspectionPurchaseHistoryEntity::getTaskId, taskId)
                .orderByAsc(QmsInspectionPurchaseHistoryEntity::getCreateTime).list();
    }

    /**
     * 质检任务历史详情
     */
    public QmsInspectionPurchaseHistoryEntity findById(Long id) {
        QmsInspectionPurchaseHistoryEntity result = this.getById(id);
        if (Objects.isNull(result)) {
            throw new RuntimeException("未找到质检任务历史详情");
        }
        return result;
    }

}
