package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.oms.dto.OmsOrderBaseTrackPageDto;
import com.kitex.mom.oms.dto.OmsOrderOverviewDto;
import com.kitex.mom.oms.dto.OmsUpdateOrderDeliverDto;
import com.kitex.mom.oms.entity.OmsOrderBaseTrackEntity;
import com.kitex.mom.oms.vo.OmsLifeVO;
import com.kitex.mom.oms.vo.OmsOrderDetailsVO;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface OmsOrderBaseTrackService extends IService<OmsOrderBaseTrackEntity> {

    /**
     * 订单跟踪表分页查询
     */
    Page<OmsOrderBaseTrackPageDto> getOmsOrderBaseTrackPage(Page page, OmsOrderBaseTrackPageDto omsOrderBaseTrack);

    /**
     * 通过id查询订单跟踪表
     *
     * @param id id
     */
    OmsOrderBaseTrackEntity getOrderTrackById(Long id);


    /**
     * 通过id查询订单跟踪表
     *
     * @param id id 明细id
     */
    OmsOrderBaseTrackEntity getOrderTrackDetailsById(Long id);

    /**
     * 根据id 和 日期 更新日历信息
     */
    OmsOrderBaseTrackEntity getCalendarMsg(Long id, String date);

    /**
     * 订单变更
     */
    Boolean change(OmsOrderBaseTrackEntity omsOrderBaseTrack);

    /**
     * 订单跟踪下发
     */
    Boolean send(Long id);

    /**
     * 订单跟踪下发 -计划
     */
    Boolean sendPlan(Long id);

    /**
     * 订单跟踪下发 -发货
     */
    Boolean sendDeliver(Long id);

    /**
     * 订单跟踪 - 撤回
     */
    Boolean withdraw(Long id);

    /**
     * 更新订单发货信息
     */
    Boolean updateOrderDeliver(List<OmsUpdateOrderDeliverDto> deliverDtoList);

    /**
     * 查询各订单跟踪状态的数量
     *
     * @return
     */
    Map<String, Long> getCount(OmsOrderBaseTrackPageDto omsOrderBaseTrack);

    /**
     * 订单总览分页查询
     */
    Page<OmsOrderOverviewDto> getOverviewPage(Page page, OmsOrderOverviewDto omsOrderOverviewDto);

    /**
     * 订单总览日期列表
     */
    List<Map<String, Object>> getOverviewList(String[] productCodes, LocalDate date);

    /**
     * 订单总览日期详情
     */
    List<OmsOrderDetailsVO> getOverviewDetails(String productCode, LocalDate date);

    /**
     * 订单生命周期
     *
     * @param id id -->明细id
     */
    OmsLifeVO lifePlan(Long id);
}