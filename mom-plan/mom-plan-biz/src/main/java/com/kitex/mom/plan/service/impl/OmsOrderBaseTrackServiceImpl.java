package com.kitex.mom.plan.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmCustomerEntity;
import com.kitex.mom.mdm.enums.MatAttributeEnum;
import com.kitex.mom.mdm.feign.RemoteCustomerService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.vo.MesTaskReportOmsVO;
import com.kitex.mom.oms.dto.*;
import com.kitex.mom.oms.entity.*;
import com.kitex.mom.oms.enums.OrderStateEnum;
import com.kitex.mom.oms.enums.YesOrNoStateEnum;
import com.kitex.mom.oms.vo.*;
import com.kitex.mom.plan.constant.PlanStatusEnum;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnIssueMonthEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.OmsOrderBaseMapper;
import com.kitex.mom.plan.mapper.OmsOrderBaseReviewMapper;
import com.kitex.mom.plan.mapper.OmsOrderBaseTrackMapper;
import com.kitex.mom.plan.mapper.OmsOrderDetailsMapper;
import com.kitex.mom.plan.service.*;
import com.kitex.mom.plan.vo.OmsLifePlanVO;
import com.kitex.mom.pms.api.feign.RemotePmsPlanService;
import com.kitex.mom.pms.entity.PmsPlanEntity;
import com.kitex.mom.wms.api.feign.RemoteDeliverService;
import com.kitex.mom.wms.api.feign.RemoteLiveStockService;
import com.kitex.mom.wms.dto.WmsLiveStockByProductCodeDto;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.entity.WmsDeliverGoodsRecordEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.enums.DeliverStateEnum;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单跟踪表
 *
 * @author kitex
 * @date 2025-01-07 14:36:05
 */
@Slf4j
@Service
@AllArgsConstructor
public class OmsOrderBaseTrackServiceImpl extends ServiceImpl<OmsOrderBaseTrackMapper, OmsOrderBaseTrackEntity> implements OmsOrderBaseTrackService {

    //客户信息
    private final RemoteCustomerService remoteCustomerService;
    //实时库存
    private final RemoteLiveStockService remoteLiveStockService;
    //发货计划
    private final RemoteDeliverService remoteDeliverService;
    //序列号生成
    private final RemoteSeqConfigService remoteSeqConfigService;
    //订单交付详情表
    private final OmsOrderDetailsService omsOrderDetailsService;
    //订单主表Mapper
    private final OmsOrderBaseMapper omsOrderBaseMapper;
    //订单评审主表Mapper
    private final OmsOrderBaseReviewMapper omsOrderBaseReviewMapper;
    //订单评审明细
    private final OmsOrderDetailsReviewService omsOrderDetailsReviewService;
    // 订单交付Mapper
    private final OmsOrderDetailsMapper omsOrderDetailsMapper;
    //订单配置
    private final OmsConfigService omsConfigService;
    // 客户订单
    private final PlnProductDemandService plnProductDemandService;
    //生产工单feign
    private final RemoteMesTaskService remoteMesTaskService;
    //采购计划feign
    private final RemotePmsPlanService remotePmsPlanService;
    // 采购排产
    private final PlnIssueMonthService plnIssueMonthService;
    // 生产排产
    private final PlnIssueService plnIssueService;
    // MRP记录
    private final PlanMatMrpService planMatMrpService;
    // 排产自动触发
    private final PlnSchedulingJobService plnSchedulingJobService;


    /**
     * 订单跟踪表分页查询
     */
    @Override
    public Page<OmsOrderBaseTrackPageDto> getOmsOrderBaseTrackPage(Page page, OmsOrderBaseTrackPageDto omsOrderBaseTrack) {
        Page<OmsOrderBaseTrackPageDto> orderBaseTrackPage = this.baseMapper.getOmsOrderBaseTrackPage(page, omsOrderBaseTrack);

        if (CollUtil.isNotEmpty(orderBaseTrackPage.getRecords())) {
            //查询成品库存，拼装信息
            Set<String> productCodeSet = orderBaseTrackPage.getRecords().stream().map(OmsOrderBaseTrackPageDto::getProductCode).collect(Collectors.toSet());
            WmsLiveStockByProductCodeDto w = new WmsLiveStockByProductCodeDto();
            w.setProductCodeSet(productCodeSet);
            w.setWarehouseCode("product");
            List<WmsLiveStockEntity> liveStockList = remoteLiveStockService.getLiveStockByProductCodeList(w).getData();
            Map<String, Double> liveStockMap = new HashMap<>();
            if (CollUtil.isNotEmpty(liveStockList)) {
                // 根据matCode进行分组，并对matNum进行求和
                liveStockMap = liveStockList.stream()
                        .collect(Collectors.groupingBy(
                                WmsLiveStockEntity::getMatCode,
                                Collectors.summingDouble(entity -> entity.getMatNum() != null ? entity.getMatNum() : 0.0)
                        ));
            }
            for (OmsOrderBaseTrackPageDto record : orderBaseTrackPage.getRecords()) {
                Double matNum = liveStockMap.get(record.getProductCode());
                record.setProductLiveStockNum(matNum != null ? BigDecimal.valueOf(matNum) : null);
            }
        }
        return orderBaseTrackPage;
    }

    /**
     * 通过id查询订单跟踪表
     *
     * @param id id
     */
    @Override
    public OmsOrderBaseTrackEntity getOrderTrackById(Long id) {
        //查询主表信息
        OmsOrderBaseTrackEntity byId = this.getById(id);
        //查询子表信息
        List<OmsOrderDetailsEntity> detailsList = omsOrderDetailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>().eq(OmsOrderDetailsEntity::getOrderCode, byId.getOrderCode()));
        //查询历史未交订单
        List<String> codes = detailsList.stream().map(OmsOrderDetailsEntity::getProductCode).distinct().toList();
        List<HistoryDeliverOrderDto> historyDeliverOrderDtoList = omsOrderBaseMapper.getHistoryDeliverOrderByProductCodes(byId.getOrderCode(), codes);
        byId.setOrderDetailsList(detailsList);
        byId.setHistoryDeliverOrderDtoList(historyDeliverOrderDtoList);
        //todo 生产计划信息(已下发)
        OmsOrderMesInfoDto mesInfoDto = new OmsOrderMesInfoDto();
        mesInfoDto.setPlanNum(0);
        mesInfoDto.setDayAvgProduceNum(BigDecimal.ZERO);
        mesInfoDto.setUnProduceNum(BigDecimal.ZERO);
        mesInfoDto.setProductLiveStockNum(BigDecimal.ZERO);
        byId.setMesInfo(mesInfoDto);
        return byId;
    }


    /**
     * 通过id查询订单跟踪表
     *
     * @param id id 明细id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OmsOrderBaseTrackEntity getOrderTrackDetailsById(Long id) {
        //根据明细id查询订单详情
        OmsOrderDetailsEntity orderDetails = omsOrderDetailsService.getById(id);
        //查询主表信息
        OmsOrderBaseTrackEntity byId = this.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, orderDetails.getOrderCode()));
        //返回值 - 发货通知列表
        List<OrderToDeliverCalendarNoticeDto> noticeDtoList = new ArrayList<>();
        //返回值 - 发货单信息
        List<OrderToDeliverRecordDto> recordDtoList = new ArrayList<>();
        //构建发货信息
        try {
            //根据订单号、明细id查询所有发货计划
            R<List<WmsDeliverGoodsPlanBaseEntity>> byOrder = remoteDeliverService.getByOrder(orderDetails.getOrderCode(), orderDetails.getId());
            if (byOrder.getCode() != 0) {
                throw new RuntimeException("查询发货数据失败，请检查发货服务是否运行中！");
            }
            List<WmsDeliverGoodsPlanBaseEntity> goodsPlanBase = byOrder.getData();
            //根据发货计划查询发货记录
            if (CollUtil.isNotEmpty(goodsPlanBase)) {
                List<String> planCodes = goodsPlanBase.stream().map(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode).distinct().toList();
                List<WmsDeliverGoodsRecordEntity> deliverGoodsRecordList = remoteDeliverService.getDeliverRecordListByPlan(planCodes).getData();
                if (CollUtil.isNotEmpty(deliverGoodsRecordList)) {
                    Map<LocalDate, List<WmsDeliverGoodsRecordEntity>> deliverMap = deliverGoodsRecordList.stream().collect(Collectors.groupingBy(WmsDeliverGoodsRecordEntity::getDeliverTime, Collectors.toList()));
                    for (Map.Entry<LocalDate, List<WmsDeliverGoodsRecordEntity>> mapEntry : deliverMap.entrySet()) {
                        BigDecimal deliveredNum = mapEntry.getValue().stream().map(WmsDeliverGoodsRecordEntity::getDeliveredNum).reduce(BigDecimal.ZERO, BigDecimal::add);
                        //构建发货单信息
                        OrderToDeliverRecordDto recordDto = new OrderToDeliverRecordDto();
                        recordDto.setProductCode(orderDetails.getProductCode());
                        recordDto.setProductName(orderDetails.getProductName());
                        recordDto.setProductSpec(orderDetails.getProductSpec());
                        recordDto.setCustomerCode(byId.getCustomerCode());
                        recordDto.setCustomerName(byId.getCustomerName());
                        recordDto.setDeliveredNum(deliveredNum);
                        recordDto.setDeliverTime(mapEntry.getKey());
                        recordDtoList.add(recordDto);
                    }
                    //构建通知消息
                    noticeDtoList.addAll(buildDeliverNotice(deliverGoodsRecordList, orderDetails.getDeliverNum()));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("请求发货记录数据失败！");
        }
        //返回值 - 生产信息
        List<OrderToMesDto> mesList = new ArrayList<>();
        //构建生产信息
        try {
            List<PlnProductDemandEntity> plnProductDemandList = plnProductDemandService.list(new LambdaQueryWrapper<PlnProductDemandEntity>()
                    .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, orderDetails.getId()));
            if (CollUtil.isNotEmpty(plnProductDemandList)) {
                List<String> demandNos = plnProductDemandList.stream()
                        .map(PlnProductDemandEntity::getDemandNo).collect(Collectors.toList());
                if (CollUtil.isNotEmpty(demandNos)) {
                    List<MesTaskReportOmsVO> mesTaskList = remoteMesTaskService.getByOrders(demandNos).getData().stream()
                            .filter(item -> orderDetails.getProductCode().equals(item.getMatCode())).collect(Collectors.toList());
                    for (MesTaskReportOmsVO mesTask : mesTaskList) {
                        OrderToMesDto orderToMesDto = new OrderToMesDto();
                        BeanUtils.copyProperties(mesTask, orderToMesDto);
                        mesList.add(orderToMesDto);
                        // 根据startTime查找对应的noticeDto
                        LocalDate taskDate = mesTask.getReportTime().toInstant()
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate();
                        OrderToDeliverCalendarNoticeDto matchingNotice = noticeDtoList.stream()
                                .filter(notice -> notice.getTime().equals(taskDate))
                                .findFirst()
                                .orElse(null);
                        if (matchingNotice != null) {
                            // 更新已存在的记录
                            matchingNotice.setMesPlanNum(mesTask.getNoticeNum().setScale(2, RoundingMode.HALF_UP));
                            matchingNotice.setMesNum(matchingNotice.getMesNum().add(mesTask.getQualifiedNum()).setScale(2, RoundingMode.HALF_UP));
                        } else {
                            // 创建新的记录
                            OrderToDeliverCalendarNoticeDto newNotice = new OrderToDeliverCalendarNoticeDto();
                            newNotice.setTime(taskDate);
                            newNotice.setMesPlanNum(mesTask.getNoticeNum().setScale(2, RoundingMode.HALF_UP));
                            newNotice.setMesNum(mesTask.getQualifiedNum().setScale(2, RoundingMode.HALF_UP));
                            // 设置其他必要字段
                            newNotice.setDeliverNum(orderDetails.getDeliverNum());
                            noticeDtoList.add(newNotice);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("请求生产记录数据失败");
        }
        //构建日历信息
        //发货通知 首次进入详情页 查询本月信息，先完全构建，再截取日期范围内通知
        LocalDate now = LocalDate.now();
        LocalDate startTime = now.withDayOfMonth(1);
        LocalDate endTime = startTime.plusMonths(1).minusDays(1);
        noticeDtoList = noticeDtoList.stream().filter(item -> (item.getTime().isBefore(endTime) || item.getTime().equals(endTime))
                && (item.getTime().isAfter(startTime) || item.getTime().equals(startTime))).toList();
        byId.setNoticeList(noticeDtoList);
        byId.setOrderToDeliverRecordList(recordDtoList);
        byId.setMesList(mesList);
        return byId;
    }

    /**
     * 根据id 和 日期 更新日历信息
     */
    @Override
    public OmsOrderBaseTrackEntity getCalendarMsg(Long id, String date) {

        //根据明细id查询订单详情
        OmsOrderDetailsEntity orderDetails = omsOrderDetailsService.getById(id);
        //查询主表信息
        OmsOrderBaseTrackEntity byId = this.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, orderDetails.getOrderCode()));

        //返回值 - 发货通知列表
        List<OrderToDeliverCalendarNoticeDto> noticeDtoList = new ArrayList<>();
        //构建发货信息
        try {
            //根据订单号、明细id查询所有发货计划
            R<List<WmsDeliverGoodsPlanBaseEntity>> byOrder = remoteDeliverService.getByOrder(orderDetails.getOrderCode(), orderDetails.getId());
            if (byOrder.getCode() != 0) {
                throw new RuntimeException("查询发货数据失败，请检查发货服务是否运行中！");
            }
            List<WmsDeliverGoodsPlanBaseEntity> goodsPlanBase = byOrder.getData();
            if (CollUtil.isNotEmpty(goodsPlanBase)) {
                List<String> planCodes = goodsPlanBase.stream().map(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode).distinct().toList();
                List<WmsDeliverGoodsRecordEntity> deliverGoodsRecordList = remoteDeliverService.getDeliverRecordListByPlan(planCodes).getData();
                if (CollUtil.isNotEmpty(deliverGoodsRecordList)) {
                    //构建通知消息
                    noticeDtoList.addAll(buildDeliverNotice(deliverGoodsRecordList, orderDetails.getDeliverNum()));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("请求发货记录数据失败！");
        }
        //构建生产信息
        try {
            List<PlnProductDemandEntity> plnProductDemandList = plnProductDemandService.list(new LambdaQueryWrapper<PlnProductDemandEntity>()
                    .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, orderDetails.getId()));
            if (CollUtil.isNotEmpty(plnProductDemandList)) {
                List<String> demandNos = plnProductDemandList.stream()
                        .map(PlnProductDemandEntity::getDemandNo).collect(Collectors.toList());
                if (CollUtil.isNotEmpty(demandNos)) {
                    List<MesTaskReportOmsVO> mesTaskList = remoteMesTaskService.getByOrders(demandNos).getData().stream()
                            .filter(item -> orderDetails.getProductCode().equals(item.getMatCode())).collect(Collectors.toList());
                    for (MesTaskReportOmsVO mesTask : mesTaskList) {
                        // 根据startTime查找对应的noticeDto
                        LocalDate taskDate = mesTask.getReportTime().toInstant()
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate();
                        OrderToDeliverCalendarNoticeDto matchingNotice = noticeDtoList.stream()
                                .filter(notice -> notice.getTime().equals(taskDate))
                                .findFirst()
                                .orElse(null);
                        if (matchingNotice != null) {
                            // 更新已存在的记录
                            matchingNotice.setMesPlanNum(mesTask.getNoticeNum().setScale(2, RoundingMode.HALF_UP));
                            matchingNotice.setMesNum(matchingNotice.getMesNum().add(mesTask.getQualifiedNum()).setScale(2, RoundingMode.HALF_UP));
                        } else {
                            // 创建新的记录
                            OrderToDeliverCalendarNoticeDto newNotice = new OrderToDeliverCalendarNoticeDto();
                            newNotice.setTime(taskDate);
                            newNotice.setMesPlanNum(mesTask.getNoticeNum().setScale(2, RoundingMode.HALF_UP));
                            newNotice.setMesNum(mesTask.getQualifiedNum().setScale(2, RoundingMode.HALF_UP));
                            // 设置其他必要字段
                            newNotice.setDeliverNum(orderDetails.getDeliverNum());
                            noticeDtoList.add(newNotice);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("请求生产记录数据失败");
        }
        //todo 整合noticeDtoList - 抽离（加生产的时候以时间轴为纬度统计，不做单独统计）
        //获取时间范围
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate parse = LocalDate.parse(date, formatter);
        LocalDate startTime = parse.withDayOfMonth(1);
        LocalDate endTime = startTime.plusMonths(1).minusDays(1);
        noticeDtoList = noticeDtoList.stream().filter(item -> (item.getTime().isBefore(endTime) || item.getTime().equals(endTime))
                && (item.getTime().isAfter(startTime) || item.getTime().equals(startTime))).toList();
        byId.setNoticeList(noticeDtoList);
        return byId;
    }


    /**
     * 构建发货信息
     */
    private List<OrderToDeliverCalendarNoticeDto> buildDeliverNotice(List<WmsDeliverGoodsRecordEntity> deliverGoodsRecordList, BigDecimal deliverNum) {
        List<OrderToDeliverCalendarNoticeDto> results = new ArrayList<>();
        Map<LocalDate, List<WmsDeliverGoodsRecordEntity>> deliverMap = deliverGoodsRecordList.stream().collect(Collectors.groupingBy(WmsDeliverGoodsRecordEntity::getDeliverTime, Collectors.toList()));
        for (Map.Entry<LocalDate, List<WmsDeliverGoodsRecordEntity>> mapEntry : deliverMap.entrySet()) {
            //构建发货通知列表
            OrderToDeliverCalendarNoticeDto noticeDto = new OrderToDeliverCalendarNoticeDto();
            noticeDto.setTime(mapEntry.getKey());
            noticeDto.setDeliverNum(deliverNum);
            BigDecimal reduce = mapEntry.getValue().stream().map(WmsDeliverGoodsRecordEntity::getDeliveredNum).reduce(BigDecimal.ZERO, BigDecimal::add);
            noticeDto.setDeliveredNum(reduce);
            noticeDto.setMesPlanNum(deliverNum);
            noticeDto.setMesNum(new BigDecimal(0));
            results.add(noticeDto);
        }
        return results;
    }

    /**
     * 订单变更
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean change(OmsOrderBaseTrackEntity omsOrderBaseTrack) {
        //变更信息不能为空
        List<OmsOrderDetailsEntity> orderDetailsList = omsOrderBaseTrack.getOrderDetailsList();
        if (CollUtil.isEmpty(orderDetailsList)) {
            throw new RuntimeException("订单交付信息不能为空！");
        }
        //比对记录
        //查询变更前记录
        List<OmsOrderDetailsEntity> detailsList = omsOrderDetailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, omsOrderBaseTrack.getOrderCode()));
        //获取比对后的变化信息
        List<String> changeMsgList = comparisonChange(orderDetailsList, detailsList);
        if (CollUtil.isEmpty(changeMsgList)) {
            throw new RuntimeException("订单未发生改变！");
        }
        //是否存在评审记录
        Long aLong = omsOrderBaseReviewMapper.selectCount(new LambdaQueryWrapper<OmsOrderBaseReviewEntity>()
                .eq(OmsOrderBaseReviewEntity::getOrderCode, omsOrderBaseTrack.getOrderCode()));
        if (aLong.compareTo(0L) > 0) {
            //如果存在评审记录 更新评审记录状态、新建时间(相当于新建一条排序到前面)、是否变更以及评审交付明细记录
            omsOrderBaseReviewMapper.update(new UpdateWrapper<OmsOrderBaseReviewEntity>().lambda()
                    .set(OmsOrderBaseReviewEntity::getChangeMsg, String.join(";", changeMsgList))
                    .set(OmsOrderBaseReviewEntity::getOrderState, OrderStateEnum.WAIT_REVIEW.getCode())
                    .set(OmsOrderBaseReviewEntity::getChangeType, YesOrNoStateEnum.YES.getCode())
                    .set(BaseCore::getCreateTime, LocalDateTime.now())
                    .eq(OmsOrderBaseReviewEntity::getOrderCode, omsOrderBaseTrack.getOrderCode()));
            omsOrderDetailsReviewService.remove(new LambdaQueryWrapper<OmsOrderDetailsReviewEntity>()
                    .eq(OmsOrderDetailsReviewEntity::getOrderCode, omsOrderBaseTrack.getOrderCode()));
            List<OmsOrderDetailsReviewEntity> detailsReviewList = orderDetailsList.stream().map(item -> {
                OmsOrderDetailsReviewEntity omsOrderDetailsReview = new OmsOrderDetailsReviewEntity();
                BeanUtils.copyProperties(item, omsOrderDetailsReview, "id");
                //独有字段 OrderReviewCode 评审单号、订单号赋值
                omsOrderDetailsReview.setDeliveredNum(BigDecimal.ZERO);
                omsOrderDetailsReview.setOrderReviewCode(omsOrderBaseTrack.getOrderReviewCode());
                omsOrderDetailsReview.setOrderCode(omsOrderBaseTrack.getOrderCode());
                omsOrderDetailsReview.setFactoryCode(omsOrderBaseTrack.getFactoryCode());
                return omsOrderDetailsReview;
            }).collect(Collectors.toList());
            omsOrderDetailsReviewService.saveBatch(detailsReviewList);
        } else {
            //如果不存在评审记录 新增评审记录主子表，状态为待评审
            //主表
            OmsOrderBaseReviewEntity omsOrderBaseReview = new OmsOrderBaseReviewEntity();
            BeanUtils.copyProperties(omsOrderBaseTrack, omsOrderBaseReview, "id");
            //独有字段 状态重新赋值
            omsOrderBaseReview.setChangeMsg(String.join(";", changeMsgList));
            omsOrderBaseReview.setChangeType(YesOrNoStateEnum.YES.getCode());
            omsOrderBaseReview.setOrderState(OrderStateEnum.WAIT_REVIEW.getCode());
            String orderReviewCode = remoteSeqConfigService.create("order_review").getData();
            omsOrderBaseReview.setOrderReviewCode(orderReviewCode);
            //赋值跟踪表评审编码
            omsOrderBaseTrack.setOrderReviewCode(orderReviewCode);
            omsOrderBaseReviewMapper.insert(omsOrderBaseReview);
            //子表
            List<OmsOrderDetailsReviewEntity> detailsReviewList = orderDetailsList.stream().map(item -> {
                OmsOrderDetailsReviewEntity omsOrderDetailsReview = new OmsOrderDetailsReviewEntity();
                BeanUtils.copyProperties(item, omsOrderDetailsReview, "id");
                //独有字段
                omsOrderDetailsReview.setOrderReviewCode(orderReviewCode);
                omsOrderDetailsReview.setFactoryCode(omsOrderBaseTrack.getFactoryCode());
                return omsOrderDetailsReview;
            }).collect(Collectors.toList());
            omsOrderDetailsReviewService.saveBatch(detailsReviewList);
        }
        //更改订单、跟踪记录的状态信息 待评审
        omsOrderBaseTrack.setOrderState(OrderStateEnum.WAIT_REVIEW.getCode());
        this.updateById(omsOrderBaseTrack);
        omsOrderBaseMapper.update(new UpdateWrapper<OmsOrderBaseEntity>().lambda()
                .set(OmsOrderBaseEntity::getOrderState, OrderStateEnum.WAIT_REVIEW.getCode())
                .eq(OmsOrderBaseEntity::getOrderCode, omsOrderBaseTrack.getOrderCode()));

        // 获取订单配置
        OmsConfigEntity omsConfigEntity = omsConfigService.getOne(Wrappers.lambdaQuery(), false);
        Map<Long, Object> map = new HashMap<>();
        if (omsConfigEntity != null) {
            Integer frozenDay = omsConfigEntity.getFrozenDay();
            // 获取当前日期
            LocalDate currentDate = LocalDate.now();
            for (OmsOrderDetailsEntity omsOrderDetailsEntity : detailsList) {
                LocalDate deliverTime = omsOrderDetailsEntity.getDeliverTime();
                // 当前日期加 frozenDay 得到未来日期
                LocalDate futureDate = currentDate.plusDays(frozenDay);
                // 比较未来日期和交付时间
                if (deliverTime.isAfter(currentDate) && deliverTime.isBefore(futureDate) || futureDate.isEqual(deliverTime) || currentDate.isEqual(deliverTime)) {
                    map.put(omsOrderDetailsEntity.getId(), omsOrderDetailsEntity.getId());
                }
            }
        }
        detailsList.forEach(item -> {
            item.setChangeSign(YesOrNoStateEnum.YES.getCode());
            if (!item.getState().equals(OrderStateEnum.ISSUED.getCode())
                    && !item.getState().equals(OrderStateEnum.ISSUED_PLAN.getCode())
                    && !item.getState().equals(OrderStateEnum.ISSUED_DELIVER.getCode())
                    && !map.containsKey(item.getId())) {
                item.setState(OrderStateEnum.WAIT_REVIEW.getCode());
            }
        });
        omsOrderDetailsService.updateBatchById(detailsList);

        return Boolean.TRUE;
    }


    private List<String> comparisonChange(List<OmsOrderDetailsEntity> changeAfter, List<OmsOrderDetailsEntity> changeBefore) {
        List<String> results = new ArrayList<>();
        Map<String, List<String>> map = new HashMap<>();
        Map<String, List<OmsOrderDetailsEntity>> afterMap = changeAfter.stream().collect(Collectors.groupingBy(OmsOrderDetailsEntity::getProductCode, Collectors.toList()));
        Map<String, List<OmsOrderDetailsEntity>> beforeMap = changeBefore.stream().collect(Collectors.groupingBy(OmsOrderDetailsEntity::getProductCode, Collectors.toList()));
        //删除变更
        deleteChange(afterMap, beforeMap, map);
        //新增或更新变更
        addOrUpdateChange(afterMap, beforeMap, map);
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            List<String> stringList = entry.getValue();
            results.addAll(stringList);
        }
        return results;
    }

    private void deleteChange(Map<String, List<OmsOrderDetailsEntity>> afterMap,
                              Map<String, List<OmsOrderDetailsEntity>> beforeMap,
                              Map<String, List<String>> map) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        for (Map.Entry<String, List<OmsOrderDetailsEntity>> before : beforeMap.entrySet()) {
            //获取变更后的列表
            List<OmsOrderDetailsEntity> orderDetailsEntities = afterMap.get(before.getKey());
            //变更前列表
            List<OmsOrderDetailsEntity> value = before.getValue();
            //获取map记录
            List<String> stringList = CollUtil.isEmpty(map.get(before.getKey())) ? new ArrayList<>() : map.get(before.getKey());
            if (CollUtil.isEmpty(orderDetailsEntities)) {
                //变更前有，变更后没有，删除了
                stringList.add(String.format("删除【%s】的订单交付信息",
                        value.get(0).getProductName() + "(" + before.getKey() + ")"));
            } else {
                List<OmsOrderDetailsEntity> beforeList = before.getValue();

                Map<LocalDate, OmsOrderDetailsEntity> afterTimeMap = orderDetailsEntities.stream().collect(Collectors.toMap(OmsOrderDetailsEntity::getDeliverTime, Function.identity(), (k1, k2) -> k1));
                for (OmsOrderDetailsEntity omsOrderDetails : beforeList) {
                    OmsOrderDetailsEntity orderDetails = afterTimeMap.get(omsOrderDetails.getDeliverTime());
                    if (ObjectUtil.isEmpty(orderDetails)) {
                        //变更后没有，删除了这个产品的发货时间
                        stringList.add(String.format("【%s】取消交付时间为 %s的交付信息 ,交付数量为%s",
                                value.get(0).getProductName() + "(" + before.getKey() + ")",
                                omsOrderDetails.getDeliverTime().format(formatter),
                                omsOrderDetails.getDeliverNum()));
                    }
                }
            }
            map.put(before.getKey(), stringList);
        }
    }

    private void addOrUpdateChange(Map<String, List<OmsOrderDetailsEntity>> afterMap,
                                   Map<String, List<OmsOrderDetailsEntity>> beforeMap,
                                   Map<String, List<String>> map) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        for (Map.Entry<String, List<OmsOrderDetailsEntity>> after : afterMap.entrySet()) {
            List<OmsOrderDetailsEntity> orderDetailsEntities = beforeMap.get(after.getKey());
            List<OmsOrderDetailsEntity> value = after.getValue();
            List<String> stringList = CollUtil.isEmpty(map.get(after.getKey())) ? new ArrayList<>() : map.get(after.getKey());
            if (CollUtil.isEmpty(orderDetailsEntities)) {
                //变更前没有，变更后有，新增了
                stringList.add(String.format("新增【%s】的订单交付信息",
                        value.get(0).getProductName() + "(" + after.getKey() + ")"));
            } else {
                List<OmsOrderDetailsEntity> afterList = after.getValue();
                Map<LocalDate, OmsOrderDetailsEntity> beforeTimeMap = orderDetailsEntities.stream().collect(Collectors.toMap(OmsOrderDetailsEntity::getDeliverTime, Function.identity(), (k1, k2) -> k1));
                for (OmsOrderDetailsEntity omsOrderDetails : afterList) {
                    OmsOrderDetailsEntity orderDetails = beforeTimeMap.get(omsOrderDetails.getDeliverTime());
                    if (ObjectUtil.isEmpty(orderDetails)) {
                        //变更前没有，新增了这个产品的发货时间
                        stringList.add(String.format("【%s】新增订单交付时间 %s ,交付数量为%s",
                                value.get(0).getProductName() + "(" + after.getKey() + ")",
                                omsOrderDetails.getDeliverTime().format(formatter),
                                omsOrderDetails.getDeliverNum()));
                    } else {
                        boolean update = false;
                        List<String> msg = new ArrayList<>();
                        if (omsOrderDetails.getDeliverNum().compareTo(orderDetails.getDeliverNum()) != 0) {
                            update = true;
                            msg.add(String.format("数量发生变更【%s】->【%s】 ", orderDetails.getDeliverNum(), omsOrderDetails.getDeliverNum()));
                        }
                        if (!((ObjectUtil.isNotEmpty(omsOrderDetails.getRemark()) && ObjectUtil.isNotEmpty(orderDetails.getRemark()) && omsOrderDetails.getRemark().equals(orderDetails.getRemark()))
                                || (ObjectUtil.isEmpty(omsOrderDetails.getRemark()) && ObjectUtil.isEmpty(orderDetails.getRemark())))) {
                            update = true;
                            msg.add(String.format("备注发生变更【%s】->【%s】 ", ObjectUtil.isNotEmpty(orderDetails.getRemark()) ? orderDetails.getRemark() : "",
                                    ObjectUtil.isNotEmpty(omsOrderDetails.getRemark()) ? omsOrderDetails.getRemark() : ""));
                        }
                        if (update) {
                            stringList.add(String.format("【%s】订单在交付时间 %s 中",
                                    value.get(0).getProductName() + "(" + after.getKey() + ")",
                                    omsOrderDetails.getDeliverTime().format(formatter)) + String.join(",", msg));
                        }
                    }
                }
            }
            map.put(after.getKey(), stringList);
        }
    }


    /**
     * 订单跟踪下发 - 批量
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean send(Long id) {
        //获取子表数据
        OmsOrderDetailsEntity orderDetails = omsOrderDetailsService.getById(id);
        //获取主表数据
        OmsOrderBaseTrackEntity omsOrderBaseTrack = this.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, orderDetails.getOrderCode()));
        //获取客户信息
        Map<String, MdmCustomerEntity> customerMap = new HashMap<>();
        List<MdmCustomerEntity> mdmCustomerList = remoteCustomerService.getListByOms().getData();
        if (CollUtil.isNotEmpty(mdmCustomerList)) {
            customerMap = mdmCustomerList.stream().collect(Collectors.toMap(MdmCustomerEntity::getCustomerCode, Function.identity(), (k1, k2) -> k1));
        }

        R<List<WmsDeliverGoodsPlanBaseEntity>> deliverRdata = remoteDeliverService.getByOrder(omsOrderBaseTrack.getOrderCode(), orderDetails.getId());
        if (deliverRdata.getCode() != 0) {
            throw new RuntimeException("查询发货计划失败，请检查发货服务是否运行！");
        }

        PlnProductDemandEntity plnRdata = plnProductDemandService.getOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getSaleOrderCode, omsOrderBaseTrack.getOrderCode())
                .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, orderDetails.getId()));

        //下发到发货计划
        if (ObjectUtil.isNotEmpty(deliverRdata.getData())) {
            List<WmsDeliverGoodsPlanBaseEntity> dataList = deliverRdata.getData();
            for (WmsDeliverGoodsPlanBaseEntity data : dataList) {
                if (data.getDeliverState().equals(DeliverStateEnum.COMPLETE.getCode())) {
                    data.setDeliverState(DeliverStateEnum.ING.getCode());
                }
                if (data.getDeliverState().equals(DeliverStateEnum.CLOSE.getCode())) {
                    data.setDeliverState(data.getDeliveredNum().compareTo(BigDecimal.ZERO) == 0 ?
                            DeliverStateEnum.NOT_START.getCode() : DeliverStateEnum.ING.getCode());
                }
                data.setDeliverNum(orderDetails.getDeliverNum());
            }
            remoteDeliverService.updateBatchPlanBase(dataList);
        } else {
            String no = orderToDeliver(omsOrderBaseTrack, orderDetails, customerMap);
        }

        //下发到计划
        String demandNo;
        if (ObjectUtil.isNotEmpty(plnRdata)) {
            plnRdata.setNeedNum(orderDetails.getDeliverNum());
            plnProductDemandService.updateById(plnRdata);
            demandNo = plnRdata.getDemandNo();
        } else {
            demandNo = orderToPlan(omsOrderBaseTrack, orderDetails, customerMap);
        }
        triggerSchedulingIfEnabledAndMesOnly(demandNo, orderDetails, "订单下发后");

        //更改状态
        orderDetails.setState(OrderStateEnum.ISSUED.getCode());
        omsOrderDetailsService.updateById(orderDetails);
        return Boolean.TRUE;
    }

    /**
     * 订单跟踪下发 -计划
     *
     * @param id 子表id
     */
    @Override
    public Boolean sendPlan(Long id) {
        //获取子表数据
        OmsOrderDetailsEntity orderDetails = omsOrderDetailsService.getById(id);
        //获取主表数据
        OmsOrderBaseTrackEntity omsOrderBaseTrack = this.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, orderDetails.getOrderCode()));
        //获取客户信息
        Map<String, MdmCustomerEntity> customerMap = new HashMap<>();
        List<MdmCustomerEntity> mdmCustomerList = remoteCustomerService.getList().getData();
        if (CollUtil.isNotEmpty(mdmCustomerList)) {
            customerMap = mdmCustomerList.stream().collect(Collectors.toMap(MdmCustomerEntity::getCustomerCode, Function.identity(), (k1, k2) -> k1));
        }

        PlnProductDemandEntity plnRdata = plnProductDemandService.getOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getSaleOrderCode, omsOrderBaseTrack.getOrderCode())
                .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, orderDetails.getId()));

        //下发到计划
        String demandNo;
        if (ObjectUtil.isNotEmpty(plnRdata)) {
            plnRdata.setNeedNum(orderDetails.getDeliverNum());
            plnProductDemandService.updateById(plnRdata);
            demandNo = plnRdata.getDemandNo();
        } else {
            demandNo = orderToPlan(omsOrderBaseTrack, orderDetails, customerMap);
        }
        triggerSchedulingIfEnabledAndMesOnly(demandNo, orderDetails, "计划下发后");

        //更改状态
        if (orderDetails.getState().equals(OrderStateEnum.ISSUED_DELIVER.getCode())) {
            orderDetails.setState(OrderStateEnum.ISSUED.getCode());
        } else {
            orderDetails.setState(OrderStateEnum.ISSUED_PLAN.getCode());
        }
        omsOrderDetailsService.updateById(orderDetails);
        return Boolean.TRUE;
    }

    /**
     * 订单跟踪下发 -发货
     *
     * @param id 子表id
     */
    @Override
    public Boolean sendDeliver(Long id) {
        //获取子表数据
        OmsOrderDetailsEntity orderDetails = omsOrderDetailsService.getById(id);
        //获取主表数据
        OmsOrderBaseTrackEntity omsOrderBaseTrack = this.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, orderDetails.getOrderCode()));
        //获取客户信息
        Map<String, MdmCustomerEntity> customerMap = new HashMap<>();
        List<MdmCustomerEntity> mdmCustomerList = remoteCustomerService.getList().getData();
        if (CollUtil.isNotEmpty(mdmCustomerList)) {
            customerMap = mdmCustomerList.stream().collect(Collectors.toMap(MdmCustomerEntity::getCustomerCode, Function.identity(), (k1, k2) -> k1));
        }
        R<List<WmsDeliverGoodsPlanBaseEntity>> deliverRdata = remoteDeliverService.getByOrder(omsOrderBaseTrack.getOrderCode(), orderDetails.getId());
        if (deliverRdata.getCode() != 0) {
            throw new RuntimeException("查询发货计划失败，请检查发货服务是否运行！");
        }
        //下发到发货计划
        if (ObjectUtil.isNotEmpty(deliverRdata.getData())) {
            List<WmsDeliverGoodsPlanBaseEntity> dataList = deliverRdata.getData();

            for (WmsDeliverGoodsPlanBaseEntity data : dataList) {
                if (data.getDeliverState().equals(DeliverStateEnum.COMPLETE.getCode())) {
                    data.setDeliverState(DeliverStateEnum.ING.getCode());
                }
                if (data.getDeliverState().equals(DeliverStateEnum.CLOSE.getCode())) {
                    data.setDeliverState(data.getDeliveredNum().compareTo(BigDecimal.ZERO) == 0 ?
                            DeliverStateEnum.NOT_START.getCode() : DeliverStateEnum.ING.getCode());
                }
                data.setDeliverNum(orderDetails.getDeliverNum());
            }
            remoteDeliverService.updateBatchPlanBase(dataList);
        } else {
            String no = orderToDeliver(omsOrderBaseTrack, orderDetails, customerMap);
        }

        //更改状态
        if (orderDetails.getState().equals(OrderStateEnum.ISSUED_PLAN.getCode())) {
            orderDetails.setState(OrderStateEnum.ISSUED.getCode());
        } else {
            orderDetails.setState(OrderStateEnum.ISSUED_DELIVER.getCode());
        }
        omsOrderDetailsService.updateById(orderDetails);
        return Boolean.TRUE;
    }

    /**
     * 订单跟踪 - 撤回
     */
    @Override
    public Boolean withdraw(Long id) {
        //获取子表数据
        OmsOrderDetailsEntity orderDetails = omsOrderDetailsService.getById(id);
        //获取主表数据
        OmsOrderBaseTrackEntity omsOrderBaseTrack = this.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, orderDetails.getOrderCode()));

        R<List<WmsDeliverGoodsPlanBaseEntity>> deliverRdata = remoteDeliverService.getByOrder(omsOrderBaseTrack.getOrderCode(), orderDetails.getId());
        if (deliverRdata.getCode() != 0) {
            throw new RuntimeException("查询发货计划失败，请检查发货服务是否运行！");
        }

        PlnProductDemandEntity plnRdata = plnProductDemandService.getOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getSaleOrderCode, omsOrderBaseTrack.getOrderCode())
                .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, orderDetails.getId()));

        //下发到计划
        if (ObjectUtil.isNotEmpty(plnRdata)) {
            if (plnRdata.getPlanState().equals(PlanStatusEnum.RECEIVE.getCode())) {
                throw new RuntimeException("撤回失败，该客户订单计划已被接收！");
            }

        }
        //下发到发货计划
        if (ObjectUtil.isNotEmpty(deliverRdata.getData())) {
            //删除
            List<WmsDeliverGoodsPlanBaseEntity> deliverPlanDataList = deliverRdata.getData();
            for (WmsDeliverGoodsPlanBaseEntity deliverPlanData : deliverPlanDataList) {
                if (deliverPlanData.getDeliverState().equals(DeliverStateEnum.CREATE.getCode())
                        || deliverPlanData.getDeliveredNum().compareTo(BigDecimal.ZERO) > 0) {
                    throw new RuntimeException("撤回失败，该发货计划已下发至发货单或部分已发货，请处理发货数据！");
                }
            }
            List<Long> longs = deliverPlanDataList.stream().map(BaseEntity::getId).toList();
            remoteDeliverService.removeById(longs.toArray(new Long[0]));
        }
        if (ObjectUtil.isNotEmpty(plnRdata)) {
            plnProductDemandService.removeById(plnRdata.getId());
        }

        orderDetails.setState(OrderStateEnum.REVIEW.getCode());
        omsOrderDetailsService.updateById(orderDetails);
        return Boolean.TRUE;
    }

    /**
     * 订单下发到计划
     *
     * @param omsOrderBaseTrack 跟踪主表
     * @param orderDetails      订单明细
     * @param customerMap       客户信息
     */
    private String orderToPlan(OmsOrderBaseTrackEntity omsOrderBaseTrack, OmsOrderDetailsEntity orderDetails, Map<String, MdmCustomerEntity> customerMap) {
        PlnProductDemandEntity demand = new PlnProductDemandEntity();
        demand.setDemandNo(remoteSeqConfigService.create("plan_demand").getData());
        demand.setCustomerCode(omsOrderBaseTrack.getCustomerCode());
        demand.setCustomerName(omsOrderBaseTrack.getCustomerName());
        demand.setSaleOrderCode(omsOrderBaseTrack.getOrderCode());
        demand.setSaleOrderCodeDetails(orderDetails.getId());
        demand.setProductCode(orderDetails.getProductCode());
        demand.setProductName(orderDetails.getProductName());
        demand.setSpecification(orderDetails.getProductSpec());
        demand.setProductUnit(orderDetails.getProductUnit());
        demand.setPlanEndDate(orderDetails.getDeliverTime());
        demand.setNeedNum(orderDetails.getDeliverNum());
        demand.setPlanState(PlanStatusEnum.CANCEL.getCode());
        demand.setFactoryCode(omsOrderBaseTrack.getFactoryCode());
        Boolean data = plnProductDemandService.savePlan(demand);
        if (data != null && !data) {
            throw new CheckedException("创建需求计划失败！");
        }
        return demand.getDemandNo();
    }


    /**
     * 订单下发到发货计划
     *
     * @param omsOrderBaseTrack 跟踪主表
     * @param orderDetails      订单明细
     * @param customerMap       客户信息
     */
    private void triggerSchedulingIfEnabledAndMesOnly(String demandNo,
                                                      OmsOrderDetailsEntity orderDetails,
                                                      String scene) {
        OmsConfigEntity config = omsConfigService.getOne(Wrappers.lambdaQuery(), false);
        if (config == null || !Boolean.TRUE.equals(config.getIssue())) {
            return;
        }
        if (StrUtil.isBlank(demandNo)) {
            return;
        }

        PlanMatMrpEntity mesMrp = planMatMrpService.getOne(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getPlanDemandNo, demandNo)
                .eq(PlanMatMrpEntity::getType, MatAttributeEnum.MES.getCode())
                .last("LIMIT 1"), false);
        if (mesMrp == null) {
            return;
        }

        try {
            plnSchedulingJobService.onMrpCompleted("DAY", orderDetails.getDeliverTime());
        } catch (Exception ex) {
            log.error("{}自动触发排产失败, orderCode={}, detailId={}", scene, orderDetails.getOrderCode(), orderDetails.getId(), ex);
        }
    }

    private String orderToDeliver(OmsOrderBaseTrackEntity omsOrderBaseTrack,
                                  OmsOrderDetailsEntity orderDetails,
                                  Map<String, MdmCustomerEntity> customerMap) {
        //构建发货数据
        List<WmsDeliverGoodsPlanBaseEntity> wmsDeliverGoodsPlanBaseList = new ArrayList<>();
        String deliverPlanCode = remoteSeqConfigService.create("deliver_plan").getData();
        WmsDeliverGoodsPlanBaseEntity planBase = new WmsDeliverGoodsPlanBaseEntity();
        planBase.setDeliverPlanCode(deliverPlanCode);
        planBase.setOrderCode(omsOrderBaseTrack.getOrderCode());
        planBase.setOrderCodeDetails(orderDetails.getId());
        planBase.setCustomerCode(omsOrderBaseTrack.getCustomerCode());
        planBase.setCustomerName(omsOrderBaseTrack.getCustomerName());
        planBase.setProductCode(orderDetails.getProductCode());
        planBase.setProductName(orderDetails.getProductName());
        planBase.setProductSpec(orderDetails.getProductSpec());
        planBase.setPlanDeliverTime(orderDetails.getDeliverTime());
        planBase.setDeliverNum(orderDetails.getDeliverNum());
        planBase.setDeliveredNum(orderDetails.getDeliveredNum());
        planBase.setDeliverState(orderDetails.getDeliveredNum().compareTo(BigDecimal.ZERO) > 0 ?
                DeliverStateEnum.ING.getCode() : DeliverStateEnum.NOT_START.getCode());
        MdmCustomerEntity customer = customerMap.get(omsOrderBaseTrack.getCustomerCode());
        planBase.setCustomerWarehouse(ObjectUtil.isNotEmpty(customer) ? customer.getLocalWarehouse() : null);
        planBase.setCustomerContact(ObjectUtil.isNotEmpty(customer) ? customer.getContactPerson() : null);
        planBase.setCustomerPhone(ObjectUtil.isNotEmpty(customer) ? customer.getPhone() : null);
        planBase.setReturnNum(BigDecimal.ZERO);
        planBase.setReissueNum(BigDecimal.ZERO);
        wmsDeliverGoodsPlanBaseList.add(planBase);
        Boolean data = remoteDeliverService.saveBatchPlanBase(wmsDeliverGoodsPlanBaseList).getData();
        if (data != null && !data) {
            throw new RuntimeException("创建发货计划失败！");
        }
        return deliverPlanCode;
    }

    /**
     * 更新订单发货信息
     */
    @Override
    public Boolean updateOrderDeliver(List<OmsUpdateOrderDeliverDto> deliverDtoList) {
        //根据订单和计划时间匹配
        Set<String> orderCodes = deliverDtoList.stream().map(OmsUpdateOrderDeliverDto::getOrderCode).collect(Collectors.toSet());
        List<OmsOrderDetailsEntity> detailsList = omsOrderDetailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .in(OmsOrderDetailsEntity::getOrderCode, orderCodes));

        List<OmsOrderBaseEntity> omsOrderBaseList = omsOrderBaseMapper.selectList(new LambdaQueryWrapper<OmsOrderBaseEntity>()
                .in(OmsOrderBaseEntity::getOrderCode, orderCodes));

        for (OmsOrderDetailsEntity omsOrderDetails : detailsList) {
            OmsUpdateOrderDeliverDto orderDeliverDto = deliverDtoList.stream().filter(item ->
                    item.getOrderCode().equals(omsOrderDetails.getOrderCode())
                            && item.getProductCode().equals(omsOrderDetails.getProductCode())
                            && item.getDeliverTime().equals(omsOrderDetails.getDeliverTime())).findFirst().orElse(null);
            if (ObjectUtil.isNotEmpty(orderDeliverDto)) {
                BigDecimal deliveredNum = omsOrderDetails.getDeliveredNum();
                omsOrderDetails.setDeliveredNum(deliveredNum.add(orderDeliverDto.getOperateNum()));
                omsOrderDetails.setOperateNum(orderDeliverDto.getOperateNum());
                if (omsOrderDetails.getDeliveredNum().compareTo(omsOrderDetails.getDeliverNum()) >= 0) {
                    omsOrderDetails.setState(OrderStateEnum.COMPLETE.getCode());
                }
            }
        }

        for (OmsOrderBaseEntity omsOrderBase : omsOrderBaseList) {
            List<OmsOrderDetailsEntity> childList = detailsList.stream().filter(item -> omsOrderBase.getOrderCode().equals(item.getOrderCode())).toList();
            Long count = childList.stream().filter(item -> item.getDeliverNum().compareTo(item.getDeliveredNum()) > 0).count();
            if (count.compareTo(0L) == 0) {
                omsOrderBase.setOrderState(OrderStateEnum.COMPLETE.getCode());
            }
        }
        omsOrderBaseMapper.updateById(omsOrderBaseList);
        return omsOrderDetailsService.updateBatchById(detailsList);
    }

    /**
     * 查询各订单跟踪状态的数量
     *
     * @return
     */
    @Override
    public Map<String, Long> getCount(OmsOrderBaseTrackPageDto omsOrderBaseTrack) {
        List<String> orderBaseTrackCount = this.baseMapper.getOmsOrderBaseTrackCount(omsOrderBaseTrack);
        return orderBaseTrackCount.stream().collect(Collectors.groupingBy(item -> item, Collectors.counting()));
    }

    /**
     * 订单总览分页查询
     */
    @Override
    public Page<OmsOrderOverviewDto> getOverviewPage(Page page, OmsOrderOverviewDto omsOrderOverviewDto) {
        Page<OmsOrderOverviewDto> orderOverviewPage = new Page<>();
        // 聚合查询订单需求数量
        QueryWrapper<OmsOrderDetailsEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("product_code", "product_name", "product_spec", "SUM(deliver_num - COALESCE(delivered_num,0)) as deliver_num");
        queryWrapper.lambda()
                .like(StrUtil.isNotBlank(omsOrderOverviewDto.getProductCode()), OmsOrderDetailsEntity::getProductCode, omsOrderOverviewDto.getProductCode())
                .like(StrUtil.isNotBlank(omsOrderOverviewDto.getProductName()), OmsOrderDetailsEntity::getProductName, omsOrderOverviewDto.getProductName())
                .like(StrUtil.isNotBlank(omsOrderOverviewDto.getProductSpec()), OmsOrderDetailsEntity::getProductSpec, omsOrderOverviewDto.getProductSpec())
                .between(OmsOrderDetailsEntity::getDeliverTime, omsOrderOverviewDto.getDate().with(TemporalAdjusters.firstDayOfMonth()), omsOrderOverviewDto.getDate().with(TemporalAdjusters.lastDayOfMonth()))
                .groupBy(OmsOrderDetailsEntity::getProductCode, OmsOrderDetailsEntity::getProductName, OmsOrderDetailsEntity::getProductSpec);
        Page<OmsOrderDetailsEntity> detailPage = omsOrderDetailsMapper.selectPage(page, queryWrapper);
        // 拷贝分页信息
        BeanUtils.copyProperties(detailPage, orderOverviewPage);
        List<OmsOrderDetailsEntity> records = detailPage.getRecords();
        if (CollUtil.isNotEmpty(records)) {
            // 创建返回实体
            List<OmsOrderOverviewDto> overviewDtoList = new ArrayList<>();
            // 查询库存信息
            Set<String> productCodeSet = records.stream().map(OmsOrderDetailsEntity::getProductCode).collect(Collectors.toSet());
            WmsLiveStockByProductCodeDto liveStock = new WmsLiveStockByProductCodeDto();
            liveStock.setProductCodeSet(productCodeSet);
            List<WmsLiveStockEntity> liveStockList = remoteLiveStockService.getLiveStockByProductCodeList(liveStock).getData();
            // 根据matCode聚合求matNum的值
            Map<String, Double> liveStockMap = new HashMap<>();
            if (CollUtil.isNotEmpty(liveStockList)) {
                liveStockMap = liveStockList.stream()
                        .collect(Collectors.groupingBy(
                                WmsLiveStockEntity::getMatCode,
                                Collectors.summingDouble(entity -> entity.getMatNum() != null ? entity.getMatNum() : 0.0)
                        ));
            }
            // 构造返回list
            for (OmsOrderDetailsEntity record : records) {
                OmsOrderOverviewDto overviewDto = new OmsOrderOverviewDto();
                BeanUtils.copyProperties(record, overviewDto);
                // 设置库存数量
                Double matNum = liveStockMap.get(record.getProductCode());
                overviewDto.setLiveStockNum(matNum != null ? BigDecimal.valueOf(matNum) : BigDecimal.ZERO);

                overviewDtoList.add(overviewDto);
            }
            orderOverviewPage.setRecords(overviewDtoList);
        }
        return orderOverviewPage;
    }

    /**
     * 订单总览日期列表
     */
    @Override
    public List<Map<String, Object>> getOverviewList(String[] productCodes, LocalDate date) {
        QueryWrapper<OmsOrderDetailsEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("product_code", "deliver_time", "SUM(deliver_num - COALESCE(delivered_num,0)) as deliver_num");
        queryWrapper.lambda()
                .in(OmsOrderDetailsEntity::getProductCode, Arrays.asList(productCodes))
                .between(OmsOrderDetailsEntity::getDeliverTime, date.with(TemporalAdjusters.firstDayOfMonth()), date.with(TemporalAdjusters.lastDayOfMonth()))
                .groupBy(OmsOrderDetailsEntity::getProductCode, OmsOrderDetailsEntity::getDeliverTime);
        List<OmsOrderDetailsEntity> omsOrderDetailsEntities = omsOrderDetailsMapper.selectList(queryWrapper);
        // 按productCode分组
        Map<String, List<OmsOrderDetailsEntity>> groupByProduct = omsOrderDetailsEntities.stream()
                .collect(Collectors.groupingBy(OmsOrderDetailsEntity::getProductCode));
        // 构造结果列表
        List<Map<String, Object>> result = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");
        // 处理每个产品的数据
        for (Map.Entry<String, List<OmsOrderDetailsEntity>> entry : groupByProduct.entrySet()) {
            Map<String, Object> productMap = new HashMap<>();
            productMap.put("productCode", entry.getKey());

            // 添加日期-数量映射
            for (OmsOrderDetailsEntity detail : entry.getValue()) {
                String dateKey = detail.getDeliverTime().format(formatter);
                productMap.put(dateKey, detail.getDeliverNum().toString());
            }

            result.add(productMap);
        }
        return result;
    }

    /**
     * 订单总览日期详情
     */
    @Override
    public List<OmsOrderDetailsVO> getOverviewDetails(String productCode, LocalDate date) {
        MPJLambdaWrapper<OmsOrderDetailsEntity> wrapper = new MPJLambdaWrapper<OmsOrderDetailsEntity>()
                .selectAll(OmsOrderDetailsEntity.class)
                .select(OmsOrderBaseEntity::getCustomerCode, OmsOrderBaseEntity::getCustomerName)
                .innerJoin(OmsOrderBaseEntity.class, OmsOrderBaseEntity::getOrderCode, OmsOrderDetailsEntity::getOrderCode)
                .eq(OmsOrderDetailsEntity::getProductCode, productCode)
                .eq(OmsOrderDetailsEntity::getDeliverTime, date);
        return omsOrderDetailsMapper.selectJoinList(OmsOrderDetailsVO.class, wrapper);
    }

    /**
     * 订单生命周期
     *
     * @param id id -->明细id
     */
    @Override
    public OmsLifeVO lifePlan(Long id) {
        // 构造订单生命周期返回体
        OmsLifeVO result = new OmsLifeVO();
        //根据明细id查询订单详情
        OmsOrderDetailsEntity orderDetails = omsOrderDetailsService.getById(id);
        // 查询客户订单需求信息
        PlnProductDemandEntity productDemand = plnProductDemandService.getOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, orderDetails.getId()), false);
        if (productDemand != null) {
            String demandNo = productDemand.getDemandNo();
            // 计划查询
            this.lifePlan(demandNo, result);
            // 发货查询
            this.lifeDeliver(orderDetails.getId(), orderDetails.getOrderCode(), result);
            // 生成查询
            this.lifeMes(demandNo, result);
            // 采购查询
            this.lifePms(demandNo, result);
        }
        return result;
    }

    /**
     * 订单生命周期-计划
     *
     * @param demandNo 客户订单号
     * @param result   返回体
     */
    private void lifePlan(String demandNo, OmsLifeVO result) {
        // 查询生产排产任务
        List<PlnIssueEntity> mesPlan = plnIssueService.list(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getIssueNo, demandNo));
        // 产讯采购排产任务
        List<PlnIssueMonthEntity> pmsPlan = plnIssueMonthService.list(new LambdaQueryWrapper<PlnIssueMonthEntity>()
                .eq(PlnIssueMonthEntity::getIssueMonthNo, demandNo));
        // 使用Stream流转换生产排产任务数据
        List<OmsLifePlanVO> planList = new ArrayList<>();
        if (CollUtil.isNotEmpty(mesPlan)) {
            planList.addAll(mesPlan.stream()
                    .map(issue -> {
                        OmsLifePlanVO planVO = new OmsLifePlanVO();
                        BeanUtils.copyProperties(issue, planVO);
                        planVO.setNo(issue.getIssueNo());
                        planVO.setType("生产计划");
                        return planVO;
                    })
                    .toList());
        }
        // 使用Stream流转换采购排产任务数据
        if (CollUtil.isNotEmpty(pmsPlan)) {
            planList.addAll(pmsPlan.stream()
                    .map(issueMonth -> {
                        OmsLifePlanVO planVO = new OmsLifePlanVO();
                        BeanUtils.copyProperties(issueMonth, planVO);
                        planVO.setNo(issueMonth.getIssueMonthNo());
                        planVO.setType("采购计划");
                        return planVO;
                    })
                    .toList());
        }
        result.setPlanList(planList);
    }

    /**
     * 订单生命周期-发货
     *
     * @param id        id -->明细id
     * @param orderCode 订单号
     * @param result    返回体
     */
    private void lifeDeliver(Long id, String orderCode, OmsLifeVO result) {
        //根据订单号、明细id查询所有发货计划
        R<List<WmsDeliverGoodsPlanBaseEntity>> deliverRdata = remoteDeliverService.getByOrder(orderCode, id);
        if (deliverRdata.getCode() != 0) {
            throw new RuntimeException("查询发货计划失败，请检查发货服务是否运行！");
        }
        result.setDeliverList(deliverRdata.getData().stream()
                .map(deliver -> {
                    OmsLifeWmsDeliverVO deliverVO = new OmsLifeWmsDeliverVO();
                    BeanUtils.copyProperties(deliver, deliverVO);
                    return deliverVO;
                })
                .toList());
    }

    /**
     * 订单生命周期-生产
     *
     * @param demandNo 客户订单号
     * @param result   返回体
     */
    private void lifeMes(String demandNo, OmsLifeVO result) {
        R<List<MesTaskEntity>> mesRdata = remoteMesTaskService.omsLife(demandNo);
        if (mesRdata.getCode() != 0) {
            throw new RuntimeException("查询生产工单失败，请检查生产服务是否运行！");
        }
        result.setMesList(mesRdata.getData().stream()
                .map(task -> {
                    OmsLifeMesTaskVO taskVO = new OmsLifeMesTaskVO();
                    BeanUtils.copyProperties(task, taskVO);
                    return taskVO;
                })
                .toList());
    }

    /**
     * 订单生命周期-采购
     *
     * @param demandNo 客户订单号
     * @param result   返回体
     */
    private void lifePms(String demandNo, OmsLifeVO result) {
        R<List<PmsPlanEntity>> pmsRdata = remotePmsPlanService.omsLife(demandNo);
        if (pmsRdata.getCode() != 0) {
            throw new RuntimeException("查询采购计划失败，请检查采购服务是否运行！");
        }
        result.setPmsList(pmsRdata.getData().stream()
                .map(pms -> {
                    OmsLifePmsPlanVO pmsVO = new OmsLifePmsPlanVO();
                    BeanUtils.copyProperties(pms, pmsVO);
                    return pmsVO;
                })
                .toList());
    }
}