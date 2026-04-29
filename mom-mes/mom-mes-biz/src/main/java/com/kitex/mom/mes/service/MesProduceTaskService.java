package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.GanttResultDTO;
import com.kitex.mom.mes.dto.RouteProcessDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;

import java.util.List;

/**
 * 生产工序任务单
 *
 * @author kitex
 * @since 2025-05-06
 */
public interface MesProduceTaskService extends IService<MesProduceTaskEntity> {

    /**
     * 查询列表
     *
     * @param query
     * @return
     */
    List<MesProduceTaskEntity> queryList(MesProduceTaskEntity query);

    /**
     * 查询工序任务列表
     *
     * @param taskCode
     * @return
     */
    List<RouteProcessDTO> queryProcessList(String taskCode);


    /**
     * 查询甘特图
     *
     * @param query
     * @return
     */
    GanttResultDTO getGanttList(MesProduceTaskEntity query);

}
