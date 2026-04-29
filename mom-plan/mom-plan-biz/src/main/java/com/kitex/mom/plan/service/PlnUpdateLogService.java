package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.plan.entity.PlnUpdateLogEntity;

import java.time.LocalDate;
import java.util.List;

public interface PlnUpdateLogService extends IService<PlnUpdateLogEntity> {

    /**
     * 查询修改日志列表
     *
     * @param time 时间
     * @return
     */
    List<PlnUpdateLogEntity> getPlnUpdateLogList(LocalDate time);

    /**
     * 保存计划修改日志
     *
     * @param plnUpdateLogs 实体
     * @return
     */
    Boolean saveUpdateLog(List<PlnUpdateLogEntity> plnUpdateLogs);
}