package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.vo.MesTaskReportOmsVO;

import java.util.List;


/**
 * 工单获取
 *
 * @author kitex
 * @since 2025-05-06
 */
public interface MesTaskService extends IService<MesTaskEntity> {

    /**
     * 查询分页
     *
     * @param pageParam
     * @param query
     * @return
     */
    IPage<MesTaskEntity> queryPage(Page<MesTaskEntity> pageParam, MesTaskEntity query);

    /**
     * 生产计划接收生成生产工单
     *
     * @param receive
     */
    void add(MesTaskReceiveDTO receive);

    /**
     * 生产工单编辑
     *
     * @param mesTask
     */
    void edit(MesTaskEntity mesTask);

    /**
     * 查询详情
     *
     * @param id
     * @return
     */
    MesTaskDTO findBydId(Long id);

    /**
     * 生产工单下达任务
     *
     * @param id
     */
    void issue(Long id);

    /**
     * 关闭任务
     *
     * @param id
     */
    void close(Long id);

    /**
     * 生产工单对接生产计划接口
     *
     * @param planMesQueryDTO 查询条件
     * @return R
     */
    List<MesTaskEntity> planMesList(PlanMesQueryDTO planMesQueryDTO);

    /**
     * 根据生产订单号查询生产工单最后一道工序的报工记录
     *
     * @param orders 生产订单列表
     * @return R
     */
    List<MesTaskReportOmsVO> getByOrders(List<String> orders);

    /**
     * 生产工单入库
     *
     * @param mesTaskInWmsDTO 生产工单入库DTO
     * @return R
     */
    Boolean inWms(MesTaskInWmsDTO mesTaskInWmsDTO);

    /**
     * 根据生产计划单号查询生产工单
     *
     * @param planNo 生产计划单号
     * @return R
     */
    List<MesTaskEntity> omsLife(String planNo);

    /**
     * 根据产品编码和工位编码查询当前用户需要做的工单信息
     * 如果有已经开始的则查询开始的，没有开始的则查询最近需要开始的工单信息
     *
     * @param queryDTO 查询参数
     * @return 当前用户需要做的工单信息
     */
    MesTaskCurrentWorkVO getCurrentWorkByProductAndWorksite(MesTaskQueryByProductWorksiteDTO queryDTO);

    /**
     * 根据额任务编码获取工单详情
     *
     * @param taskCode
     * @return
     */
    MesTaskDTO getByTaskCode(String taskCode);
}
