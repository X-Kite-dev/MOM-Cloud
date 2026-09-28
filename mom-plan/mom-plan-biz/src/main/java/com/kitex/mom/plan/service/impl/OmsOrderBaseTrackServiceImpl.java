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
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
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
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.*;
import com.kitex.mom.plan.vo.OmsLifePlanVO;
import com.kitex.mom.pms.api.feign.RemotePmsPlanService;
import com.kitex.mom.pms.entity.PmsPlanEntity;
import com.kitex.mom.wms.api.feign.RemoteDeliverService;
import com.kitex.mom.wms.api.feign.RemoteLiveStockService;
import com.kitex.mom.wms.dto.WmsLiveStockByProductCodeDto;
import com.kitex.mom.wms.dto.WmsOmsDeliverabilitySnapshot;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.entity.WmsDeliverGoodsRecordEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
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
import java.util.stream.Stream;

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
    // 制造事件收件箱与排产自动触发
    private final PlnAutomationEventService plnAutomationEventService;
    private final OmsOrderChangeLedgerService changeLedger;
    private final OmsDeliveryWithdrawalMapper deliveryWithdrawalMapper;
    private final OmsDeliveryIssueMapper deliveryIssueMapper;


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
        for (OmsOrderDetailsEntity detail : detailsList) {
            if (!OrderStateEnum.ISSUED_PLAN.getCode().equals(detail.getState())) continue;
            List<PlnProductDemandEntity> linked = plnProductDemandService.list(
                    new LambdaQueryWrapper<PlnProductDemandEntity>()
                            .eq(PlnProductDemandEntity::getSaleOrderCode, byId.getOrderCode())
                            .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, detail.getId()));
            if (linked.size() != 1) {
                detail.setPlanDemandEditable(false);
                detail.setPlanDemandBlockReason("PLAN来源需求缺失或不唯一");
                continue;
            }
            PlnProductDemandEntity demand = linked.get(0);
            detail.setPlanDemandState(demand.getPlanState());
            try {
                assertNoWmsDeliveryPlan(byId.getOrderCode(), detail.getId());
                plnProductDemandService.assertOmsReviewChangeable(demand.getId(), byId.getOrderCode(), detail.getId());
                detail.setPlanDemandEditable(true);
            } catch (RuntimeException ex) {
                detail.setPlanDemandEditable(false);
                detail.setPlanDemandBlockReason(ex.getMessage());
            }
        }
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
        if (omsOrderBaseTrack == null || omsOrderBaseTrack.getId() == null
                || StrUtil.isBlank(omsOrderBaseTrack.getOrderCode())
                || StrUtil.isBlank(omsOrderBaseTrack.getChangeRequestId())
                || omsOrderBaseTrack.getOrderDetailsList() == null
                || omsOrderBaseTrack.getOrderDetailsList().isEmpty()
                || omsOrderBaseTrack.getOrderDetailsList().stream().anyMatch(Objects::isNull)) {
            throw new CheckedException("订单变更必须指定已有订单跟踪记录");
        }
        String proposalSnapshot = changeLedger.proposalFromOrder(omsOrderBaseTrack.getOrderDetailsList());
        if (changeLedger.replaySubmit(omsOrderBaseTrack.getChangeRequestId(),
                omsOrderBaseTrack.getOrderCode(), omsOrderBaseTrack.getFactoryCode(), proposalSnapshot)) {
            return Boolean.TRUE;
        }
        OmsOrderBaseTrackEntity persisted = this.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getId, omsOrderBaseTrack.getId()).last("FOR UPDATE"));
        if (persisted == null || !Objects.equals(persisted.getOrderCode(), omsOrderBaseTrack.getOrderCode())
                || !Objects.equals(persisted.getFactoryCode(), omsOrderBaseTrack.getFactoryCode())) {
            throw new CheckedException("订单跟踪记录、订单号或工厂不匹配");
        }
        if (this.count(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, persisted.getOrderCode())) != 1) {
            throw new CheckedException("订单号跨工厂或重复，旧OMS关联入口不能安全识别来源，请先治理订单编码");
        }
        if (deliveryIssueMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryIssueEntity>()
                .eq(OmsDeliveryIssueEntity::getOrderCode, persisted.getOrderCode())
                .ne(OmsDeliveryIssueEntity::getStatus, "SUCCEEDED")) != 0
                || deliveryWithdrawalMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryWithdrawalEntity>()
                .eq(OmsDeliveryWithdrawalEntity::getOrderCode, persisted.getOrderCode())
                .ne(OmsDeliveryWithdrawalEntity::getStatus, "SUCCEEDED")) != 0) {
            throw new CheckedException("订单有待处理的下发或撤回命令，须先续跑或对账，不能申请变更");
        }
        if (!OrderStateEnum.REVIEW.getCode().equals(persisted.getOrderState())
                && !OrderStateEnum.WAIT_REVIEW.getCode().equals(persisted.getOrderState())) {
            throw new CheckedException("只有已完成初审的订单可发起交付行变更");
        }
        MomUser applicant = SecurityUtils.getUser();
        if (applicant == null || StrUtil.isBlank(applicant.getUsername())) {
            throw new CheckedException("订单变更申请必须由已登录用户发起");
        }
        String selectedFactory = com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotBlank(selectedFactory) && !Objects.equals(selectedFactory, persisted.getFactoryCode())) {
            throw new CheckedException("当前工厂无权申请该订单变更");
        }
        if (!Objects.equals(persisted.getCustomerCode(), omsOrderBaseTrack.getCustomerCode())
                || !Objects.equals(persisted.getCustomerName(), omsOrderBaseTrack.getCustomerName())
                || !Objects.equals(persisted.getOrderType(), omsOrderBaseTrack.getOrderType())
                || !Objects.equals(persisted.getProductCode(), omsOrderBaseTrack.getProductCode())
                || !Objects.equals(persisted.getProductName(), omsOrderBaseTrack.getProductName())
                || !Objects.equals(persisted.getProductSpec(), omsOrderBaseTrack.getProductSpec())
                || !Objects.equals(persisted.getCustomerPartCode(), omsOrderBaseTrack.getCustomerPartCode())) {
            throw new CheckedException("订单头信息变更需独立冻结客户及下游证据；当前订单评审仅处理交付行变更");
        }
        //变更信息不能为空
        List<OmsOrderDetailsEntity> orderDetailsList = omsOrderBaseTrack.getOrderDetailsList();
        if (CollUtil.isEmpty(orderDetailsList)) {
            throw new RuntimeException("订单交付信息不能为空！");
        }
        //比对记录
        //查询变更前记录
        List<OmsOrderDetailsEntity> detailsList = omsOrderDetailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, persisted.getOrderCode())
                .orderByAsc(OmsOrderDetailsEntity::getId).last("FOR UPDATE"));
        validateOrderChangeLines(persisted, detailsList, orderDetailsList);
        Map<Long, OmsOrderDetailsEntity> currentById = detailsList.stream()
                .collect(Collectors.toMap(OmsOrderDetailsEntity::getId, Function.identity()));
        String beforeSnapshot = changeLedger.currentSnapshot(persisted.getOrderCode(), persisted.getFactoryCode());
        //获取比对后的变化信息
        List<String> changeMsgList = comparisonChange(orderDetailsList, detailsList);
        if (CollUtil.isEmpty(changeMsgList)) {
            throw new RuntimeException("订单未发生改变！");
        }
        //是否存在评审记录
        Long aLong = omsOrderBaseReviewMapper.selectCount(new LambdaQueryWrapper<OmsOrderBaseReviewEntity>()
                .eq(OmsOrderBaseReviewEntity::getOrderCode, omsOrderBaseTrack.getOrderCode()));
        if (aLong > 1) throw new CheckedException("同一订单存在多份评审主记录，须先治理历史数据");
        Long reviewId;
        if (aLong.compareTo(0L) > 0) {
            if (StrUtil.isBlank(persisted.getOrderReviewCode())) {
                throw new CheckedException("订单评审主记录缺少版本号，禁止覆盖历史评审行");
            }
            //如果存在评审记录 更新评审记录状态、新建时间(相当于新建一条排序到前面)、是否变更以及评审交付明细记录
            int changed = omsOrderBaseReviewMapper.update(new UpdateWrapper<OmsOrderBaseReviewEntity>().lambda()
                    .set(OmsOrderBaseReviewEntity::getChangeMsg, String.join(";", changeMsgList))
                    .set(OmsOrderBaseReviewEntity::getOrderState, OrderStateEnum.WAIT_REVIEW.getCode())
                    .set(OmsOrderBaseReviewEntity::getChangeType, YesOrNoStateEnum.YES.getCode())
                    .set(OmsOrderBaseReviewEntity::getChangeApplicantCode, applicant.getUsername())
                    .set(OmsOrderBaseReviewEntity::getChangeApplicantName,
                            StrUtil.blankToDefault(applicant.getName(), applicant.getUsername()))
                    .set(OmsOrderBaseReviewEntity::getChangeSubmittedTime, LocalDateTime.now())
                    .set(OmsOrderBaseReviewEntity::getChangeReviewerCode, null)
                    .set(OmsOrderBaseReviewEntity::getChangeReviewerName, null)
                    .set(OmsOrderBaseReviewEntity::getChangeReviewedTime, null)
                    .set(BaseCore::getCreateTime, LocalDateTime.now())
                    .eq(OmsOrderBaseReviewEntity::getOrderReviewCode, persisted.getOrderReviewCode())
                    .eq(OmsOrderBaseReviewEntity::getOrderCode, omsOrderBaseTrack.getOrderCode()));
            if (changed != 1) throw new CheckedException("订单变更评审主记录冻结失败");
            OmsOrderBaseReviewEntity review = omsOrderBaseReviewMapper.selectOne(
                    new LambdaQueryWrapper<OmsOrderBaseReviewEntity>()
                            .eq(OmsOrderBaseReviewEntity::getOrderCode, persisted.getOrderCode())
                            .eq(OmsOrderBaseReviewEntity::getOrderReviewCode, persisted.getOrderReviewCode()));
            if (review == null) throw new CheckedException("订单变更评审主记录回读失败");
            reviewId = review.getId();
            omsOrderDetailsReviewService.remove(new LambdaQueryWrapper<OmsOrderDetailsReviewEntity>()
                    .eq(OmsOrderDetailsReviewEntity::getOrderCode, omsOrderBaseTrack.getOrderCode()));
            List<OmsOrderDetailsReviewEntity> detailsReviewList = orderDetailsList.stream().map(item -> {
                OmsOrderDetailsReviewEntity omsOrderDetailsReview = new OmsOrderDetailsReviewEntity();
                BeanUtils.copyProperties(item, omsOrderDetailsReview, "id");
                omsOrderDetailsReview.setSourceDetailId(item.getId());
                omsOrderDetailsReview.setSourceDetailSnapshot(item.getId() == null ? null
                        : OmsOrderLineEvidence.snapshot(currentById.get(item.getId())));
                //独有字段 OrderReviewCode 评审单号、订单号赋值
                omsOrderDetailsReview.setDeliveredNum(BigDecimal.ZERO);
                omsOrderDetailsReview.setOrderReviewCode(persisted.getOrderReviewCode());
                omsOrderDetailsReview.setOrderCode(persisted.getOrderCode());
                omsOrderDetailsReview.setFactoryCode(persisted.getFactoryCode());
                return omsOrderDetailsReview;
            }).collect(Collectors.toList());
            if (!omsOrderDetailsReviewService.saveBatch(detailsReviewList)) {
                throw new CheckedException("订单变更候选交付行保存失败");
            }
        } else {
            //如果不存在评审记录 新增评审记录主子表，状态为待评审
            //主表
            OmsOrderBaseReviewEntity omsOrderBaseReview = new OmsOrderBaseReviewEntity();
            BeanUtils.copyProperties(persisted, omsOrderBaseReview, "id");
            //独有字段 状态重新赋值
            omsOrderBaseReview.setChangeMsg(String.join(";", changeMsgList));
            omsOrderBaseReview.setChangeType(YesOrNoStateEnum.YES.getCode());
            omsOrderBaseReview.setOrderState(OrderStateEnum.WAIT_REVIEW.getCode());
            omsOrderBaseReview.setChangeApplicantCode(applicant.getUsername());
            omsOrderBaseReview.setChangeApplicantName(StrUtil.blankToDefault(applicant.getName(), applicant.getUsername()));
            omsOrderBaseReview.setChangeSubmittedTime(LocalDateTime.now());
            String orderReviewCode = remoteSeqConfigService.create("order_review").getData();
            omsOrderBaseReview.setOrderReviewCode(orderReviewCode);
            //赋值跟踪表评审编码
            persisted.setOrderReviewCode(orderReviewCode);
            if (omsOrderBaseReviewMapper.insert(omsOrderBaseReview) != 1) {
                throw new CheckedException("订单变更评审主记录保存失败");
            }
            reviewId = omsOrderBaseReview.getId();
            //子表
            List<OmsOrderDetailsReviewEntity> detailsReviewList = orderDetailsList.stream().map(item -> {
                OmsOrderDetailsReviewEntity omsOrderDetailsReview = new OmsOrderDetailsReviewEntity();
                BeanUtils.copyProperties(item, omsOrderDetailsReview, "id");
                omsOrderDetailsReview.setSourceDetailId(item.getId());
                omsOrderDetailsReview.setSourceDetailSnapshot(item.getId() == null ? null
                        : OmsOrderLineEvidence.snapshot(currentById.get(item.getId())));
                //独有字段
                omsOrderDetailsReview.setOrderReviewCode(orderReviewCode);
                omsOrderDetailsReview.setFactoryCode(persisted.getFactoryCode());
                return omsOrderDetailsReview;
            }).collect(Collectors.toList());
            if (!omsOrderDetailsReviewService.saveBatch(detailsReviewList)) {
                throw new CheckedException("订单变更候选交付行保存失败");
            }
        }
        changeLedger.submit(omsOrderBaseTrack.getChangeRequestId(), persisted.getOrderCode(),
                persisted.getFactoryCode(), reviewId, beforeSnapshot, proposalSnapshot, applicant);
        //更改订单、跟踪记录的状态信息 待评审
        persisted.setOrderState(OrderStateEnum.WAIT_REVIEW.getCode());
        if (!this.updateById(persisted)) throw new CheckedException("订单变更待评审状态保存失败");
        if (omsOrderBaseMapper.update(new UpdateWrapper<OmsOrderBaseEntity>().lambda()
                .set(OmsOrderBaseEntity::getOrderState, OrderStateEnum.WAIT_REVIEW.getCode())
                .eq(OmsOrderBaseEntity::getOrderCode, omsOrderBaseTrack.getOrderCode())) != 1) {
            throw new CheckedException("订单变更主订单状态保存失败");
        }

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
        if (!omsOrderDetailsService.updateBatchById(detailsList)) {
            throw new CheckedException("订单变更原交付行待评审状态保存失败");
        }

        return Boolean.TRUE;
    }

    /**
     * 交付行变更入口先按订单行ID核验来源；已下发计划仅允许尚未接收的PLAN草案随OMS评审同步。
     */
    private void validateOrderChangeLines(OmsOrderBaseTrackEntity order, List<OmsOrderDetailsEntity> current,
                                          List<OmsOrderDetailsEntity> proposed) {
        Map<Long, OmsOrderDetailsEntity> byId = current.stream()
                .collect(Collectors.toMap(OmsOrderDetailsEntity::getId, Function.identity()));
        Map<Long, OmsOrderDetailsEntity> proposedById = new HashMap<>();
        Set<String> productDates = new HashSet<>();
        LocalDate frozenThrough = OmsOrderChangePolicy.frozenThrough(omsConfigService, order.getFactoryCode());
        for (OmsOrderDetailsEntity row : proposed) {
            if (row == null || StrUtil.isBlank(row.getProductCode()) || row.getDeliverTime() == null
                    || row.getDeliverNum() == null || row.getDeliverNum().signum() <= 0
                    || (StrUtil.isNotBlank(row.getOrderCode())
                    && !Objects.equals(row.getOrderCode(), order.getOrderCode()))) {
                throw new CheckedException("订单变更交付行必须提供当前订单、产品、正数数量和交付日期");
            }
            String productDate = row.getProductCode() + "\u0001" + row.getDeliverTime();
            if (!productDates.add(productDate)) throw new CheckedException("订单变更交付行产品与日期重复");
            if (row.getId() != null) {
                if (!byId.containsKey(row.getId()) || proposedById.putIfAbsent(row.getId(), row) != null) {
                    throw new CheckedException("订单变更包含不属于本订单或重复的来源行ID：" + row.getId());
                }
            } else if (frozenThrough != null && !row.getDeliverTime().isAfter(frozenThrough)) {
                throw new CheckedException("新交付行日期位于冻结区，须先走冻结区例外审批");
            }
            row.setOrderCode(order.getOrderCode());
            row.setFactoryCode(order.getFactoryCode());
        }
        for (OmsOrderDetailsEntity old : current) {
            OmsOrderDetailsEntity next = proposedById.get(old.getId());
            boolean changed = next == null || !sameCoreDelivery(old, next);
            if (!changed) continue;
            if (frozenThrough != null && (old.getDeliverTime() == null
                    || !old.getDeliverTime().isAfter(frozenThrough)
                    || next != null && !next.getDeliverTime().isAfter(frozenThrough))) {
                throw new CheckedException("订单行[" + old.getId() + "]处于交期冻结区，须先走冻结区例外审批");
            }
            String state = old.getState();
            if (OrderStateEnum.ISSUED.getCode().equals(state)
                    || OrderStateEnum.ISSUED_DELIVER.getCode().equals(state)
                    || OrderStateEnum.COMPLETE.getCode().equals(state)
                    || Optional.ofNullable(old.getDeliveredNum()).orElse(BigDecimal.ZERO).signum() > 0) {
                throw new CheckedException("订单行[" + old.getId()
                        + "]已有发货计划或交付事实，须走OMS-WMS受控变更，不能在评审中静默覆盖");
            }
            List<PlnProductDemandEntity> linked = plnProductDemandService.list(
                    new LambdaQueryWrapper<PlnProductDemandEntity>()
                            .eq(PlnProductDemandEntity::getSaleOrderCode, order.getOrderCode())
                            .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, old.getId()));
            if (OrderStateEnum.ISSUED_PLAN.getCode().equals(state)) {
                if (next == null || linked.size() != 1 || !sameProductIdentity(old, next)
                        || Optional.ofNullable(old.getDeliveredNum()).orElse(BigDecimal.ZERO).signum() != 0) {
                    throw new CheckedException("已下发计划的订单行只允许保留原行ID及产品，变更交期或数量；删除、换品或存在交付量须走分阶段撤回");
                }
                PlnProductDemandEntity demand = linked.get(0);
                if (!Objects.equals(demand.getFactoryCode(), order.getFactoryCode())
                        || !Objects.equals(demand.getProductCode(), old.getProductCode())
                        || !Objects.equals(demand.getPlanEndDate(), old.getDeliverTime())
                        || demand.getNeedNum() == null
                        || demand.getNeedNum().compareTo(old.getDeliverNum()) != 0) {
                    throw new CheckedException("OMS订单行与PLAN需求来源数量、产品、日期或工厂不一致，禁止提交变更");
                }
                assertNoWmsDeliveryPlan(order.getOrderCode(), old.getId());
                plnProductDemandService.assertOmsReviewChangeable(demand.getId(), order.getOrderCode(), old.getId());
            } else if (!linked.isEmpty()) {
                throw new CheckedException("订单行[" + old.getId() + "]状态与PLAN来源需求不一致，禁止静默改写");
            }
        }
    }

    private boolean sameCoreDelivery(OmsOrderDetailsEntity old, OmsOrderDetailsEntity next) {
        return sameProductIdentity(old, next)
                && Objects.equals(old.getDeliverTime(), next.getDeliverTime())
                && old.getDeliverNum() != null && next.getDeliverNum() != null
                && old.getDeliverNum().compareTo(next.getDeliverNum()) == 0;
    }

    private void assertNoWmsDeliveryPlan(String orderCode, Long detailId) {
        R<List<WmsDeliverGoodsPlanBaseEntity>> response = remoteDeliverService.getByOrder(orderCode, detailId);
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new CheckedException("WMS发货来源暂不可核验，OMS交付行变更故障关闭");
        }
        if (!response.getData().isEmpty()) {
            throw new CheckedException("订单行[" + detailId + "]已有WMS发货计划，须走OMS-WMS受控变更");
        }
    }

    private boolean sameProductIdentity(OmsOrderDetailsEntity old, OmsOrderDetailsEntity next) {
        return Objects.equals(old.getProductCode(), next.getProductCode())
                && Objects.equals(old.getProductName(), next.getProductName())
                && Objects.equals(old.getProductSpec(), next.getProductSpec())
                && Objects.equals(old.getProductUnit(), next.getProductUnit());
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
    public Boolean send(Long id) {
        throw new CheckedException("旧全部下发入口已停用，请使用带永久请求号的OMS下发命令");
    }

    /**
     * 订单跟踪下发 -计划
     *
     * @param id 子表id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean sendPlan(Long id) {
        //获取子表数据
        OmsOrderDetailsEntity orderDetails = requireNoPendingOmsChange(omsOrderDetailsService.getById(id));
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

        requireExpectedIssueSources(orderDetails, plnRdata, List.of(), true, false);

        //下发到计划
        String demandNo;
        if (ObjectUtil.isNotEmpty(plnRdata)) {
            synchronizeOmsDemandFields(plnRdata, omsOrderBaseTrack, orderDetails);
            if (!Boolean.TRUE.equals(plnProductDemandService.updateFromOms(plnRdata))) {
                throw new CheckedException("OMS下发同步PLAN草案失败");
            }
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
        if (!omsOrderDetailsService.updateById(orderDetails)) throw new CheckedException("OMS计划下发状态保存失败");
        return Boolean.TRUE;
    }

    /**
     * 订单跟踪下发 -发货
     *
     * @param id 子表id
     */
    @Override
    public Boolean sendDeliver(Long id) {
        throw new CheckedException("旧发货下发入口已停用，请使用带永久请求号的OMS下发命令");
    }

    /**
     * 订单跟踪 - 撤回
     */
    @Override
    public Boolean withdraw(Long id) {
        throw new CheckedException("旧撤回入口已停用，请使用带请求号与原因的OMS撤回命令");
    }

    private OmsOrderDetailsEntity requireNoPendingOmsChange(OmsOrderDetailsEntity detail) {
        if (detail == null) throw new CheckedException("订单交付行不存在");
        OmsOrderBaseTrackEntity track = this.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, detail.getOrderCode())
                .last("FOR UPDATE"), false);
        if (track == null || this.count(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, detail.getOrderCode())) != 1) {
            throw new CheckedException("订单交付行缺少唯一的跟踪主记录");
        }
        OmsOrderDetailsEntity locked = omsOrderDetailsService.getOne(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getId, detail.getId()).last("FOR UPDATE"), false);
        if (locked == null || !Objects.equals(locked.getOrderCode(), track.getOrderCode())
                || !Objects.equals(locked.getFactoryCode(), track.getFactoryCode())) {
            throw new CheckedException("订单交付行或工厂在等待取锁期间变化，请刷新");
        }
        String selectedFactory = com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotBlank(selectedFactory) && !Objects.equals(selectedFactory, track.getFactoryCode())) {
            throw new CheckedException("当前工厂无权下发或撤回该订单交付行");
        }
        if (OrderStateEnum.WAIT_REVIEW.getCode().equals(track.getOrderState())
                || YesOrNoStateEnum.YES.getCode().equals(locked.getChangeSign())) {
            throw new CheckedException("订单交付行存在待评审变更，禁止继续下发或撤回；请先完成OMS评审");
        }
        if (deliveryIssueMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryIssueEntity>()
                .eq(OmsDeliveryIssueEntity::getOrderDetailId, detail.getId())
                .ne(OmsDeliveryIssueEntity::getStatus, "SUCCEEDED")) != 0
                || deliveryWithdrawalMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryWithdrawalEntity>()
                .eq(OmsDeliveryWithdrawalEntity::getOrderDetailId, detail.getId())
                .ne(OmsDeliveryWithdrawalEntity::getStatus, "SUCCEEDED")) != 0) {
            throw new CheckedException("订单交付行有进行中或待处理的下发/撤回命令，请先续跑或人工对账");
        }
        return locked;
    }

    private void requireExpectedIssueSources(OmsOrderDetailsEntity detail, PlnProductDemandEntity demand,
                                             List<WmsDeliverGoodsPlanBaseEntity> plans,
                                             boolean checkPlan, boolean checkWms) {
        String state = detail.getState();
        if (checkPlan && (OrderStateEnum.ISSUED.getCode().equals(state)
                || OrderStateEnum.ISSUED_PLAN.getCode().equals(state)) && demand == null) {
            throw new CheckedException("OMS订单行标记已下发PLAN，但来源需求缺失，须先对账");
        }
        if (checkWms && (OrderStateEnum.ISSUED.getCode().equals(state)
                || OrderStateEnum.ISSUED_DELIVER.getCode().equals(state)) && plans.isEmpty()) {
            throw new CheckedException("OMS订单行标记已下发WMS，但发货计划缺失，须先对账/续跑");
        }
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
        if (!Boolean.TRUE.equals(data)) {
            throw new CheckedException("创建需求计划失败！");
        }
        return demand.getDemandNo();
    }

    private void synchronizeOmsDemandFields(PlnProductDemandEntity demand, OmsOrderBaseTrackEntity order,
                                            OmsOrderDetailsEntity detail) {
        demand.setCustomerCode(order.getCustomerCode());
        demand.setCustomerName(order.getCustomerName());
        demand.setProductCode(detail.getProductCode());
        demand.setProductName(detail.getProductName());
        demand.setSpecification(detail.getProductSpec());
        demand.setProductUnit(detail.getProductUnit());
        demand.setPlanEndDate(detail.getDeliverTime());
        demand.setNeedNum(detail.getDeliverNum());
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
            plnAutomationEventService.receiveMrpReady(omsFactoryCode(orderDetails), demandNo,
                    "DAY", orderDetails.getDeliverTime(), scene);
        } catch (Exception ex) {
            // 当前调用处仍位于订单/MRP业务事务内：收件失败必须显式记录，不能静默假装已进入自动排产。
            log.error("{}制造事件收件失败, orderCode={}, detailId={}", scene, orderDetails.getOrderCode(), orderDetails.getId(), ex);
            throw ex;
        }
    }

    private String omsFactoryCode(OmsOrderDetailsEntity orderDetails) {
        OmsOrderBaseTrackEntity track = this.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, orderDetails.getOrderCode()).last("LIMIT 1"), false);
        if (track == null || StrUtil.isBlank(track.getFactoryCode())) {
            throw new CheckedException("订单未维护工厂，不能投递MRP就绪事件");
        }
        return track.getFactoryCode();
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
        OmsLifeVO result = new OmsLifeVO();
        OmsOrderDetailsEntity orderDetails = omsOrderDetailsService.getById(id);
        if (orderDetails == null) throw new CheckedException("订单交付行不存在");
        String selectedFactory = com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotBlank(selectedFactory) && !Objects.equals(selectedFactory, orderDetails.getFactoryCode())) {
            throw new CheckedException("当前工厂无权查看该订单交付履约");
        }
        result.setPlanList(new ArrayList<>());
        result.setDeliverList(new ArrayList<>());
        result.setMesList(new ArrayList<>());
        result.setPmsList(new ArrayList<>());
        List<String> risks = new ArrayList<>();
        List<PlnProductDemandEntity> demands = plnProductDemandService.list(
                new LambdaQueryWrapper<PlnProductDemandEntity>()
                        .eq(PlnProductDemandEntity::getSaleOrderCode, orderDetails.getOrderCode())
                        .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, orderDetails.getId())
                        .orderByAsc(PlnProductDemandEntity::getId));
        PlnProductDemandEntity productDemand = demands.size() == 1 ? demands.get(0) : null;
        String planDataStatus = "AVAILABLE";
        if (demands.size() > 1) {
            planDataStatus = "INCONSISTENT";
            risks.add("同一订单交付行关联了多条PLAN来源需求，须先治理来源唯一性");
        } else if (productDemand == null) {
            planDataStatus = "NOT_STARTED";
            if (OrderStateEnum.ISSUED_PLAN.getCode().equals(orderDetails.getState())
                    || OrderStateEnum.ISSUED.getCode().equals(orderDetails.getState())) {
                planDataStatus = "INCONSISTENT";
                risks.add("OMS交付行标记已下发PLAN，但找不到来源需求");
            }
        }
        String demandNo = productDemand == null ? null : productDemand.getDemandNo();
        if (productDemand != null) {
            if (!Objects.equals(productDemand.getFactoryCode(), orderDetails.getFactoryCode())
                    || !Objects.equals(productDemand.getProductCode(), orderDetails.getProductCode())
                    || productDemand.getNeedNum() == null || orderDetails.getDeliverNum() == null
                    || productDemand.getNeedNum().compareTo(orderDetails.getDeliverNum()) != 0
                    || !Objects.equals(productDemand.getPlanEndDate(), orderDetails.getDeliverTime())) {
                planDataStatus = "INCONSISTENT";
                risks.add("OMS交付行与PLAN来源需求的工厂、产品、数量或交期不一致");
            }
            try {
                this.lifePlan(demandNo, result);
            } catch (RuntimeException ex) {
                if (!"INCONSISTENT".equals(planDataStatus)) planDataStatus = "UNAVAILABLE";
                risks.add("PLAN生命周期读取失败：" + safeMessage(ex));
            }
        }
        String wmsDataStatus;
        WmsOmsDeliverabilitySnapshot deliverability = null;
        try {
            this.lifeDeliver(orderDetails.getId(), orderDetails.getOrderCode(), result);
            wmsDataStatus = "AVAILABLE";
        } catch (RuntimeException ex) {
            wmsDataStatus = "UNAVAILABLE";
            risks.add("WMS发货事实读取失败：" + safeMessage(ex));
        }
        try {
            deliverability = this.lifeDeliverability(orderDetails.getId(), orderDetails.getOrderCode());
        } catch (RuntimeException ex) {
            risks.add("WMS质量净可交事实读取失败：" + safeMessage(ex));
        }
        String mesDataStatus = productDemand == null ? "NOT_APPLICABLE" : "AVAILABLE";
        if (productDemand != null) try {
            this.lifeMes(demandNo, result);
        } catch (RuntimeException ex) {
            mesDataStatus = "UNAVAILABLE";
            risks.add("MES生产事实读取失败：" + safeMessage(ex));
        }
        String pmsDataStatus = productDemand == null ? "NOT_APPLICABLE" : "AVAILABLE";
        if (productDemand != null) try {
            this.lifePms(demandNo, result);
        } catch (RuntimeException ex) {
            pmsDataStatus = "UNAVAILABLE";
            risks.add("PMS供应事实读取失败：" + safeMessage(ex));
        }
        buildFulfillment(orderDetails, productDemand, result, planDataStatus, mesDataStatus,
                pmsDataStatus, wmsDataStatus, deliverability, risks);
        return result;
    }

    private void buildFulfillment(OmsOrderDetailsEntity detail, PlnProductDemandEntity demand, OmsLifeVO result,
                                  String planDataStatus, String mesDataStatus, String pmsDataStatus,
                                  String wmsDataStatus, WmsOmsDeliverabilitySnapshot deliverability,
                                  List<String> risks) {
        BigDecimal ordered = optional(detail.getDeliverNum());
        List<OmsLifeMesTaskVO> rootTasks = Optional.ofNullable(result.getMesList()).orElse(List.of()).stream()
                .filter(row -> Objects.equals(row.getMatCode(), detail.getProductCode())).toList();
        boolean mesAvailable = "AVAILABLE".equals(mesDataStatus);
        boolean pmsAvailable = "AVAILABLE".equals(pmsDataStatus);
        boolean wmsAvailable = "AVAILABLE".equals(wmsDataStatus);
        BigDecimal produced = mesAvailable ? rootTasks.stream().map(row -> optional(row.getCompleteNum()))
                .reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        BigDecimal stocked = mesAvailable ? rootTasks.stream().map(row -> optional(row.getInStockNum()))
                .reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        BigDecimal delivered = wmsAvailable ? Optional.ofNullable(result.getDeliverList()).orElse(List.of()).stream()
                .map(row -> optional(row.getDeliveredNum())).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        BigDecimal remaining = wmsAvailable ? ordered.subtract(delivered).max(BigDecimal.ZERO) : null;
        if (wmsAvailable && delivered.compareTo(ordered) > 0) {
            risks.add("WMS累计发货数量超过OMS交付数量，请核对超交或历史基线");
        }
        if (mesAvailable && produced.compareTo(ordered) > 0) {
            risks.add("MES成品完工数量超过本订单行数量，请核对工单来源分配");
        }
        String qualityStatus = deliverability == null ? "UNAVAILABLE" : deliverability.getStatus();
        if ("INCONSISTENT".equals(qualityStatus)) {
            risks.add("WMS质量净可交事实不一致：" + deliverability.getMessage());
        } else if ("AVAILABLE".equals(qualityStatus) && deliverability.getShortageQuantity() != null
                && deliverability.getShortageQuantity().signum() > 0) {
            risks.add("WMS质量净可交量短缺" + deliverability.getShortageQuantity().stripTrailingZeros().toPlainString()
                    + "；已扣待检/隔离、锁定、过期、活动预留和更早承诺订单");
        }
        boolean sourceUnavailable = Stream.of(planDataStatus, mesDataStatus, pmsDataStatus, wmsDataStatus, qualityStatus)
                .anyMatch("UNAVAILABLE"::equals);
        boolean inconsistent = "INCONSISTENT".equals(planDataStatus);
        boolean late = wmsAvailable && detail.getDeliverTime() != null && detail.getDeliverTime().isBefore(LocalDate.now())
                && remaining.signum() > 0;
        if (late) risks.add("客户承诺交期已过，仍有" + remaining.stripTrailingZeros().toPlainString() + "未交付");
        String overall = wmsAvailable && delivered.compareTo(ordered) >= 0 && ordered.signum() > 0 ? "COMPLETED"
                : inconsistent ? "BLOCKED"
                : wmsAvailable && delivered.signum() > 0 ? "DELIVERING"
                : "AVAILABLE".equals(qualityStatus) && deliverability.getNetDeliverableQuantity() != null
                && deliverability.getNetDeliverableQuantity().signum() > 0 ? "READY_TO_DELIVER"
                : mesAvailable && (produced.signum() > 0 || !rootTasks.isEmpty()) ? "PRODUCING"
                : pmsAvailable && result.getPmsList() != null && !result.getPmsList().isEmpty() ? "SUPPLYING"
                : result.getPlanList() != null && !result.getPlanList().isEmpty() ? "PLANNING"
                : "NOT_STARTED";
        if (late || inconsistent) overall = "BLOCKED";
        String risk = late || inconsistent ? "CRITICAL" : sourceUnavailable ? "UNKNOWN"
                : risks.isEmpty() ? "NORMAL" : "WARNING";
        int mrpCount = demand == null || StrUtil.isBlank(demand.getDemandNo()) ? 0
                : (int) planMatMrpService.count(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getPlanDemandNo, demand.getDemandNo()));
        OmsFulfillmentSummaryVO summary = new OmsFulfillmentSummaryVO();
        summary.setOrderDetailId(detail.getId());
        summary.setOrderCode(detail.getOrderCode());
        summary.setFactoryCode(detail.getFactoryCode());
        summary.setProductCode(detail.getProductCode());
        summary.setDemandNo(demand == null ? null : demand.getDemandNo());
        summary.setPromisedDate(detail.getDeliverTime());
        summary.setOrderedQuantity(ordered);
        summary.setProducedQuantity(produced);
        summary.setStockedQuantity(stocked);
        summary.setDeliveredQuantity(delivered);
        summary.setRemainingQuantity(remaining);
        if (deliverability != null) {
            summary.setPhysicalStockQuantity(deliverability.getPhysicalQuantity());
            summary.setQualifiedStockQuantity(deliverability.getQualifiedAvailableQuantity());
            summary.setQualityHeldStockQuantity(deliverability.getQualityHeldQuantity());
            summary.setOtherWarehouseStockQuantity(deliverability.getOtherWarehouseQuantity());
            summary.setLockedOrExpiredStockQuantity(optional(deliverability.getLockedQuantity())
                    .add(optional(deliverability.getExpiredQuantity())));
            summary.setReservedStockQuantity(deliverability.getReservedQuantity());
            summary.setProductionReservedStockQuantity(deliverability.getProductionReservedQuantity());
            summary.setSalesReservedStockQuantity(deliverability.getSalesReservedQuantity());
            summary.setCurrentOrderReservedStockQuantity(deliverability.getCurrentOrderReservedQuantity());
            summary.setPriorCommittedQuantity(deliverability.getPriorCommittedQuantity());
            summary.setNetDeliverableQuantity(deliverability.getNetDeliverableQuantity());
            summary.setDeliverableShortageQuantity(deliverability.getShortageQuantity());
            summary.setDeliverabilityUnit(deliverability.getUnitCode());
            summary.setDeliverabilityPolicy(deliverability.getAllocationPolicy());
            summary.setDeliverabilityMessage(deliverability.getMessage());
            summary.setDeliverabilityCalculatedTime(deliverability.getObservedAt());
        }
        summary.setFulfillmentRate(!wmsAvailable ? null : ordered.signum() == 0 ? BigDecimal.ZERO
                : delivered.multiply(new BigDecimal("100")).divide(ordered, 2, RoundingMode.HALF_UP));
        summary.setMrpLineCount(mrpCount);
        summary.setPlanCount("UNAVAILABLE".equals(planDataStatus) ? null : size(result.getPlanList()));
        summary.setMesTaskCount(mesAvailable ? size(result.getMesList()) : null);
        summary.setPurchasePlanCount(pmsAvailable ? size(result.getPmsList()) : null);
        summary.setDeliverPlanCount(wmsAvailable ? size(result.getDeliverList()) : null);
        summary.setOverallStatus(overall);
        summary.setRiskLevel(risk);
        summary.setPlanDataStatus(planDataStatus);
        summary.setMesDataStatus(mesDataStatus);
        summary.setPmsDataStatus(pmsDataStatus);
        summary.setWmsDataStatus(wmsDataStatus);
        summary.setQualityDeliverabilityStatus(qualityStatus);
        summary.setRiskMessages(List.copyOf(risks));
        summary.setCalculatedTime(LocalDateTime.now());
        result.setFulfillmentSummary(summary);
        result.setFulfillmentMilestones(buildMilestones(detail, demand, result, summary, rootTasks));
    }

    private List<OmsFulfillmentMilestoneVO> buildMilestones(OmsOrderDetailsEntity detail,
                                                            PlnProductDemandEntity demand, OmsLifeVO result, OmsFulfillmentSummaryVO summary,
                                                            List<OmsLifeMesTaskVO> rootTasks) {
        List<OmsFulfillmentMilestoneVO> rows = new ArrayList<>();
        rows.add(milestone(1, "ORDER_CONFIRMED", "订单交付行确认", "COMPLETED", "OMS",
                detail.getOrderCode(), summary.getOrderedQuantity(), summary.getOrderedQuantity(), "交付行已形成"));
        String planStatus = "UNAVAILABLE".equals(summary.getPlanDataStatus()) ? "UNKNOWN"
                : "INCONSISTENT".equals(summary.getPlanDataStatus()) ? "BLOCKED"
                : demand == null ? "NOT_STARTED" : "0".equals(demand.getPlanState()) ? "IN_PROGRESS" : "COMPLETED";
        rows.add(milestone(2, "PLAN_DEMAND", "PLAN需求", planStatus, "PLAN",
                demand == null ? null : demand.getDemandNo(), summary.getOrderedQuantity(),
                demand == null ? BigDecimal.ZERO : optional(demand.getNeedNum()),
                demand == null ? "尚未形成PLAN来源需求" : "需求状态=" + demand.getPlanState()));
        String mrpStatus = "UNAVAILABLE".equals(summary.getPlanDataStatus()) ? "UNKNOWN"
                : summary.getMrpLineCount() > 0 ? "COMPLETED" : demand == null ? "NOT_STARTED" : "IN_PROGRESS";
        rows.add(milestone(3, "MRP", "物料需求与齐套", mrpStatus, "PLAN", summary.getDemandNo(),
                null, null, summary.getMrpLineCount() + "条MRP明细"));
        List<OmsLifePlanVO> productionPlans = Optional.ofNullable(result.getPlanList()).orElse(List.of()).stream()
                .filter(row -> "生产计划".equals(row.getType())).toList();
        rows.add(milestone(4, "APS_PLAN", "有限产能排程", "UNAVAILABLE".equals(summary.getPlanDataStatus()) ? "UNKNOWN"
                        : productionPlans.isEmpty() ? "NOT_STARTED" : productionPlans.stream().allMatch(row -> "3".equals(row.getStatus()))
                        ? "COMPLETED" : "IN_PROGRESS", "PLAN", joinNos(productionPlans), summary.getOrderedQuantity(),
                "UNAVAILABLE".equals(summary.getPlanDataStatus()) ? null
                        : productionPlans.stream().map(row -> optional(row.getFinishNum())).reduce(BigDecimal.ZERO, BigDecimal::add),
                productionPlans.size() + "张生产计划"));
        rows.add(milestone(5, "SUPPLY", "采购与外协供应", "UNAVAILABLE".equals(summary.getPmsDataStatus()) ? "UNKNOWN"
                        : result.getPmsList().isEmpty() ? "NOT_STARTED" : result.getPmsList().stream().allMatch(row -> "3".equals(row.getStatus()))
                        ? "COMPLETED" : "IN_PROGRESS", "PMS", null, null, null,
                result.getPmsList().size() + "条采购/外协计划"));
        String productionStatus = "UNAVAILABLE".equals(summary.getMesDataStatus()) ? "UNKNOWN" : rootTasks.isEmpty()
                ? "NOT_STARTED" : summary.getProducedQuantity().compareTo(summary.getOrderedQuantity()) >= 0
                ? "COMPLETED" : "IN_PROGRESS";
        rows.add(milestone(6, "PRODUCTION", "MES生产与入库", productionStatus, "MES",
                rootTasks.stream().map(OmsLifeMesTaskVO::getCode).filter(StrUtil::isNotBlank).collect(Collectors.joining(",")),
                summary.getOrderedQuantity(), summary.getProducedQuantity(),
                "UNAVAILABLE".equals(summary.getMesDataStatus()) ? "MES事实不可用"
                        : "已入库" + summary.getStockedQuantity()));
        String qualityStatus = "UNAVAILABLE".equals(summary.getQualityDeliverabilityStatus()) ? "UNKNOWN"
                : "INCONSISTENT".equals(summary.getQualityDeliverabilityStatus()) ? "BLOCKED"
                : "NOT_STARTED".equals(summary.getQualityDeliverabilityStatus()) ? "NOT_STARTED"
                : summary.getRemainingQuantity() != null && summary.getRemainingQuantity().signum() == 0 ? "COMPLETED"
                : summary.getDeliverableShortageQuantity() != null && summary.getDeliverableShortageQuantity().signum() == 0
                ? "COMPLETED" : "BLOCKED";
        rows.add(milestone(7, "QUALITY_DELIVERABILITY", "质量净可交库存", qualityStatus, "WMS",
                summary.getProductCode(), summary.getRemainingQuantity(), summary.getNetDeliverableQuantity(),
                summary.getDeliverabilityMessage()));
        String deliveryStatus = "UNAVAILABLE".equals(summary.getWmsDataStatus()) ? "UNKNOWN"
                : result.getDeliverList().isEmpty() ? "NOT_STARTED"
                : summary.getDeliveredQuantity().compareTo(summary.getOrderedQuantity()) >= 0 ? "COMPLETED"
                : summary.getDeliveredQuantity().signum() > 0 ? "IN_PROGRESS"
                : detail.getDeliverTime() != null && detail.getDeliverTime().isBefore(LocalDate.now()) ? "BLOCKED" : "IN_PROGRESS";
        rows.add(milestone(8, "DELIVERY", "WMS发运交付", deliveryStatus, "WMS",
                result.getDeliverList().stream().map(OmsLifeWmsDeliverVO::getDeliverPlanCode)
                        .filter(StrUtil::isNotBlank).collect(Collectors.joining(",")),
                summary.getOrderedQuantity(), summary.getDeliveredQuantity(),
                "UNAVAILABLE".equals(summary.getWmsDataStatus()) ? "WMS事实不可用"
                        : "剩余" + summary.getRemainingQuantity()));
        return rows;
    }

    private OmsFulfillmentMilestoneVO milestone(int sequence, String code, String name, String status,
                                                String source, String sourceNo, BigDecimal target, BigDecimal actual, String message) {
        OmsFulfillmentMilestoneVO row = new OmsFulfillmentMilestoneVO();
        row.setSequence(sequence);
        row.setMilestoneCode(code);
        row.setMilestoneName(name);
        row.setStatus(status);
        row.setSourceSystem(source);
        row.setSourceNo(StrUtil.sub(sourceNo, 0, 500));
        row.setTargetQuantity(target);
        row.setActualQuantity(actual);
        row.setMessage(message);
        return row;
    }

    private String joinNos(List<OmsLifePlanVO> rows) {
        return rows.stream().map(OmsLifePlanVO::getNo).filter(StrUtil::isNotBlank)
                .collect(Collectors.joining(","));
    }

    private int size(Collection<?> rows) {
        return rows == null ? 0 : rows.size();
    }

    private BigDecimal optional(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String safeMessage(Throwable error) {
        return StrUtil.sub(StrUtil.blankToDefault(error == null ? null : error.getMessage(), "数据源无有效响应"), 0, 200);
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
        if (deliverRdata == null || !deliverRdata.isOk() || deliverRdata.getData() == null) {
            throw new CheckedException("WMS未返回可核验的发货计划");
        }
        result.setDeliverList(deliverRdata.getData().stream()
                .map(deliver -> {
                    OmsLifeWmsDeliverVO deliverVO = new OmsLifeWmsDeliverVO();
                    BeanUtils.copyProperties(deliver, deliverVO);
                    return deliverVO;
                })
                .toList());
    }

    private WmsOmsDeliverabilitySnapshot lifeDeliverability(Long id, String orderCode) {
        R<WmsOmsDeliverabilitySnapshot> response = remoteDeliverService.omsDeliverability(orderCode, id);
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new CheckedException("WMS未返回可核验的质量净可交量");
        }
        return response.getData();
    }

    /**
     * 订单生命周期-生产
     *
     * @param demandNo 客户订单号
     * @param result   返回体
     */
    private void lifeMes(String demandNo, OmsLifeVO result) {
        R<List<MesTaskEntity>> mesRdata = remoteMesTaskService.omsLife(demandNo);
        if (mesRdata == null || !mesRdata.isOk() || mesRdata.getData() == null) {
            throw new CheckedException("MES未返回可核验的生产工单");
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
        if (pmsRdata == null || !pmsRdata.isOk() || pmsRdata.getData() == null) {
            throw new CheckedException("PMS未返回可核验的采购/外协计划");
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
