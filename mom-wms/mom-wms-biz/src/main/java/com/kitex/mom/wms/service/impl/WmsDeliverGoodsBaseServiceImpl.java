package com.kitex.mom.wms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.oms.dto.OmsUpdateOrderDeliverDto;
import com.kitex.mom.oms.enums.YesOrNoStateEnum;
import com.kitex.mom.oms.feign.RemoteOmsOrderService;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.*;
import com.kitex.mom.wms.mapper.WmsDeliverGoodsBaseMapper;
import com.kitex.mom.wms.mapper.WmsOutBillDetailMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeDetailMapper;
import com.kitex.mom.wms.service.WmsDeliverGoodsBaseService;
import com.kitex.mom.wms.service.WmsDeliverGoodsDetailsService;
import com.kitex.mom.wms.service.WmsDeliverGoodsPlanBaseService;
import com.kitex.mom.wms.service.WmsDeliverGoodsRecordService;
import com.kitex.mom.wms.service.outBill.WmsOutBillFactory;
import com.kitex.mom.wms.service.outBill.WmsOutBillHandler;
import com.kitex.mom.wms.service.outNotice.WmsOutNoticeFactory;
import com.kitex.mom.wms.service.outNotice.WmsOutNoticeHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 发货单基础表
 *
 * @author kitex
 * @date 2025-01-13 09:31:20
 */
@Service
@AllArgsConstructor
public class WmsDeliverGoodsBaseServiceImpl extends ServiceImpl<WmsDeliverGoodsBaseMapper, WmsDeliverGoodsBaseEntity> implements WmsDeliverGoodsBaseService {

    private final WmsDeliverGoodsDetailsService wmsDeliverGoodsDetailsService;

    private final WmsDeliverGoodsPlanBaseService wmsDeliverGoodsPlanBaseService;
    // 销售出库编码
    private final String billType = OutBillTypeEnum.SALES_OUT.getCode();
    //物料基础信息
    private final RemoteMaterialService remoteMaterialService;
    //出库单
    private final WmsOutBillMapper wmsOutBillMapper;
    //出库详情
    private final WmsOutBillDetailMapper wmsOutBillDetailMapper;
    //通知详情
    private final WmsOutNoticeDetailMapper wmsOutNoticeDetailMapper;
    //订单
    private final RemoteOmsOrderService remoteOmsOrderService;
    //发货记录
    private final WmsDeliverGoodsRecordService wmsDeliverGoodsRecordService;


    /**
     * 分页查询
     *
     * @param toMpPage            分页对象
     * @param wmsDeliverGoodsBase 发货单基础表
     */
    @Override
    public Page<WmsDeliverGoodsBaseEntity> getWmsDeliverGoodsBasePage(Page toMpPage, WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase) {
        LambdaQueryWrapper<WmsDeliverGoodsBaseEntity> wrapper = Wrappers.lambdaQuery();
        //加入查询条件 - 发货计划单号
        if (StrUtil.isNotBlank(wmsDeliverGoodsBase.getDeliverPlanCode())) {
            List<WmsDeliverGoodsDetailsEntity> detailsList = wmsDeliverGoodsDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                    .eq(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode, wmsDeliverGoodsBase.getDeliverPlanCode()));
            Set<String> deliverCodes = detailsList.stream().map(WmsDeliverGoodsDetailsEntity::getDeliverCode).collect(Collectors.toSet());
            if (CollUtil.isEmpty(deliverCodes)) {
                return new Page<>();
            }
            wrapper.in(WmsDeliverGoodsBaseEntity::getDeliverCode, deliverCodes);
        }
        wrapper.eq(StrUtil.isNotBlank(wmsDeliverGoodsBase.getDeliverState()), WmsDeliverGoodsBaseEntity::getDeliverState, wmsDeliverGoodsBase.getDeliverState());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsBase.getDeliverCode()), WmsDeliverGoodsBaseEntity::getDeliverCode, wmsDeliverGoodsBase.getDeliverCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsBase.getCustomerCode()), WmsDeliverGoodsBaseEntity::getCustomerCode, wmsDeliverGoodsBase.getCustomerCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsBase.getCustomerName()), WmsDeliverGoodsBaseEntity::getCustomerName, wmsDeliverGoodsBase.getCustomerName());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        wrapper.orderByAsc(WmsDeliverGoodsBaseEntity::getDeliverState);
        return this.page(toMpPage, wrapper);
    }

    @Override
    public Map<String, Long> getCount(WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase) {
        LambdaQueryWrapper<WmsDeliverGoodsBaseEntity> wrapper = Wrappers.lambdaQuery();
        //加入查询条件 - 发货计划单号
        if (StrUtil.isNotBlank(wmsDeliverGoodsBase.getDeliverPlanCode())) {
            List<WmsDeliverGoodsDetailsEntity> detailsList = wmsDeliverGoodsDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                    .eq(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode, wmsDeliverGoodsBase.getDeliverPlanCode()));
            Set<String> deliverCodes = detailsList.stream().map(WmsDeliverGoodsDetailsEntity::getDeliverCode).collect(Collectors.toSet());
            if (CollUtil.isEmpty(deliverCodes)) {
                return new HashMap<>();
            }
            wrapper.in(WmsDeliverGoodsBaseEntity::getDeliverCode, deliverCodes);
        }
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsBase.getDeliverCode()), WmsDeliverGoodsBaseEntity::getDeliverCode, wmsDeliverGoodsBase.getDeliverCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsBase.getCustomerCode()), WmsDeliverGoodsBaseEntity::getCustomerCode, wmsDeliverGoodsBase.getCustomerCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsBase.getCustomerName()), WmsDeliverGoodsBaseEntity::getCustomerName, wmsDeliverGoodsBase.getCustomerName());
        return this.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(WmsDeliverGoodsBaseEntity::getDeliverState, Collectors.counting()));
    }

    /**
     * 通过id查询发货单基础表
     *
     * @param id id
     * @return WmsDeliverGoodsBaseEntity
     */
    @Override
    public WmsDeliverGoodsBaseEntity getDeliverBaseById(Long id) {
        //查询主表
        WmsDeliverGoodsBaseEntity byId = this.getById(id);
        //查询子表
        List<WmsDeliverGoodsDetailsEntity> detailsList = wmsDeliverGoodsDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                .eq(WmsDeliverGoodsDetailsEntity::getDeliverCode, byId.getDeliverCode()));
        List<WmsOutBillDetailEntity> deliverProductList = new ArrayList<>();
        List<WmsOutBillEntity> wmsOutBillList = wmsOutBillMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
                .eq(WmsOutBillEntity::getDocumentId, byId.getId())
                .eq(WmsOutBillEntity::getDocumentTable, DocumentEnum.DELIVER.getCode())
                .eq(WmsOutBillEntity::getState, InOutBillStateEnum.PASSED.getCode()));
        if (ObjectUtil.isNotEmpty(wmsOutBillList)) {
            List<Long> billIds = wmsOutBillList.stream().map(BaseEntity::getId).toList();
            deliverProductList = wmsOutBillDetailMapper.selectList(new LambdaQueryWrapper<WmsOutBillDetailEntity>()
                    .in(WmsOutBillDetailEntity::getBillId, billIds));
        }
        byId.setDeliverDetailsList(detailsList);
        byId.setDeliverProductList(deliverProductList);
        return byId;
    }

    /**
     * 新增发货单
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeliver(WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase) {
        List<WmsDeliverGoodsDetailsEntity> detailsList = wmsDeliverGoodsBase.getDeliverDetailsList();
        if (CollUtil.isEmpty(detailsList)) {
            throw new RuntimeException("发货明细不能为空！");
        }
        //新增主表
        wmsDeliverGoodsBase.setDeliverState(DeliverStateEnum.NOT_START.getCode());
        wmsDeliverGoodsBase.setReissueSign(YesOrNoStateEnum.NO.getCode());
        wmsDeliverGoodsBase.setIssued(YesOrNoStateEnum.NO.getCode());
        wmsDeliverGoodsBase.setSource(DeliverBaseSourceEnum.ADD.getCode());
        this.save(wmsDeliverGoodsBase);
        //新增子表
        detailsList.forEach(item -> {
            item.setDeliverCode(wmsDeliverGoodsBase.getDeliverCode());
            item.setCustomerCode(wmsDeliverGoodsBase.getCustomerCode());
            item.setCustomerName(wmsDeliverGoodsBase.getCustomerName());
            item.setDeliveredNum(BigDecimal.ZERO);
            item.setDeliverState(DeliverStateEnum.NOT_START.getCode());
            item.setReturnNum(BigDecimal.ZERO);
        });
        wmsDeliverGoodsDetailsService.saveBatch(detailsList);
        return Boolean.TRUE;
    }

    @Override
    public Boolean editDeliver(WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase) {
        List<WmsDeliverGoodsDetailsEntity> detailsList = wmsDeliverGoodsBase.getDeliverDetailsList();
        if (CollUtil.isEmpty(detailsList)) {
            throw new RuntimeException("发货明细不能为空！");
        }
        //保存主表
        this.updateById(wmsDeliverGoodsBase);
        //保存子表
        wmsDeliverGoodsDetailsService.remove(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                .eq(WmsDeliverGoodsDetailsEntity::getDeliverCode, wmsDeliverGoodsBase.getDeliverCode()));
        detailsList.forEach(item -> {
            item.setDeliverCode(wmsDeliverGoodsBase.getDeliverCode());
            item.setCustomerCode(wmsDeliverGoodsBase.getCustomerCode());
            item.setCustomerName(wmsDeliverGoodsBase.getCustomerName());
            item.setDeliveredNum(BigDecimal.ZERO);
            item.setDeliverState(DeliverStateEnum.NOT_START.getCode());
            item.setReturnNum(BigDecimal.ZERO);
        });
        wmsDeliverGoodsDetailsService.saveBatch(detailsList);
        return Boolean.TRUE;
    }

    /**
     * 发货单操作校验
     */
    private List<String> checkExistOutNoticeOrBill(WmsDeliverGoodsBaseEntity byId) {
        //校验 : 如果存在未处理的销售通知单、或者未审批的发货单，抛出异常，请先处理
        List<String> errorMsg = new ArrayList<>();
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        List<WmsOutNoticeEntity> listByDuId = handler.outNoticeListByDuId(byId.getId());
        if (CollUtil.isNotEmpty(listByDuId)) {
            List<WmsOutNoticeEntity> outNoticeList = listByDuId.stream().filter(item -> !InOutNoticeStateEnum.CANCELED.getCode().equals(item.getState())
                    && !InOutNoticeStateEnum.FINISHED.getCode().equals(item.getState())).collect(Collectors.toList());
            if (outNoticeList.size() > 0) {
                List<String> collect = outNoticeList.stream().map(item -> String.valueOf(item.getId())).collect(Collectors.toList());
                errorMsg.add("发货单存在未完成的通知单，通知单号为：" + String.join(",", collect));
            }
        }
        WmsOutBillHandler billHandler = WmsOutBillFactory.getHandler(billType);
        List<WmsOutBillEntity> billListByDuId = billHandler.outBillListByDuId(byId.getId());
        if (CollUtil.isNotEmpty(billListByDuId)) {
            List<WmsOutBillEntity> outBillList = billListByDuId.stream().filter(item -> !InOutBillStateEnum.PASSED.getCode().equals(item.getState())).collect(Collectors.toList());
            if (outBillList.size() > 0) {
                List<String> collect = outBillList.stream().map(WmsOutBillEntity::getBillNo).collect(Collectors.toList());
                errorMsg.add("发货单存在未完成的出库单，出库单号为：" + String.join(",", collect));
            }
        }
        return errorMsg;
    }

    /**
     * 发货单关闭
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean close(Long id) {
        WmsDeliverGoodsBaseEntity byId = this.getById(id);
        List<String> errorMsg = checkExistOutNoticeOrBill(byId);
        if (CollUtil.isNotEmpty(errorMsg)) {
            throw new RuntimeException("关闭失败！失败原因：" + String.join(";", errorMsg));
        }

        //关闭主表
        byId.setDeliverState(DeliverStateEnum.CLOSE.getCode());
        this.updateById(byId);
        //关闭子表
        List<WmsDeliverGoodsDetailsEntity> goodsDetailsList = wmsDeliverGoodsDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                .eq(WmsDeliverGoodsDetailsEntity::getDeliverCode, byId.getDeliverCode()));
        goodsDetailsList.forEach(item -> {
            item.setDeliverState(DeliverStateEnum.CLOSE.getCode());
        });
        wmsDeliverGoodsDetailsService.updateBatchById(goodsDetailsList);
        //修改计划状态
        List<String> planCodes = goodsDetailsList.stream().map(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode).collect(Collectors.toList());
        List<WmsDeliverGoodsPlanBaseEntity> planBaseList = wmsDeliverGoodsPlanBaseService.list(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                .in(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, planCodes));
        planBaseList.forEach(item -> {
            if (!DeliverStateEnum.COMPLETE.getCode().equals(item.getDeliverState())) {
                if (item.getDeliveredNum().compareTo(BigDecimal.ZERO) > 0) {
                    item.setDeliverState(DeliverStateEnum.ING.getCode());
                } else {
                    item.setDeliverState(DeliverStateEnum.NOT_START.getCode());
                }
            }
        });
        wmsDeliverGoodsPlanBaseService.updateBatchById(planBaseList);
        return Boolean.TRUE;
    }

    /**
     * 根据出库单更新发货数据
     *
     * @param bill 出库单 主表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeliverByBill(WmsOutBillEntity bill) {
        //获取对应发货单
        WmsDeliverGoodsBaseEntity goodsBase = this.getById(bill.getDocumentId());
        //1.数据准备
        //出库单 bill
        //出库详情
        List<WmsOutBillDetailEntity> billDetailList = wmsOutBillDetailMapper.selectList(new LambdaQueryWrapper<WmsOutBillDetailEntity>()
                .eq(WmsOutBillDetailEntity::getBillId, bill.getId()));
        //根据出库详情查询通知单
        List<Long> noticeIds = billDetailList.stream().map(WmsOutBillDetailEntity::getNoticeDetailId).distinct().collect(Collectors.toList());
        List<WmsOutNoticeDetailEntity> noticeDetailList = wmsOutNoticeDetailMapper.selectList(new LambdaQueryWrapper<WmsOutNoticeDetailEntity>()
                .in(BaseEntity::getId, noticeIds));
        Map<Long, WmsOutNoticeDetailEntity> noticeDetailMap = noticeDetailList.stream().collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
        //构建Map 发货明细id - 操作数量
        Map<Long, BigDecimal> operateMap = new HashMap<>();
        for (WmsOutBillDetailEntity wmsOutBillDetailEntity : billDetailList) {
            WmsOutNoticeDetailEntity wmsOutNoticeDetail = noticeDetailMap.get(wmsOutBillDetailEntity.getNoticeDetailId());
            if (ObjectUtil.isNotEmpty(wmsOutNoticeDetail)) {
                BigDecimal bigDecimal = operateMap.get(wmsOutNoticeDetail.getDocumentId());
                if (ObjectUtil.isNotEmpty(bigDecimal)) {
                    operateMap.put(wmsOutNoticeDetail.getDocumentId(), bigDecimal.add(BigDecimal.valueOf(wmsOutBillDetailEntity.getOperateNum())));
                } else {
                    operateMap.put(wmsOutNoticeDetail.getDocumentId(), BigDecimal.valueOf(wmsOutBillDetailEntity.getOperateNum()));
                }
            }
        }
        //构建Map 发货明细id - 通知明细（获取仓库信息）
        Map<Long, WmsOutNoticeDetailEntity> wmsNoticeDetailMap = noticeDetailList.stream().collect(Collectors.toMap(WmsOutNoticeDetailEntity::getDocumentId, Function.identity()));
        //2.更新发货单数据
        //主表
        WmsDeliverGoodsBaseEntity deliverGoodsBase = Optional.ofNullable(this.getById(bill.getDocumentId())).orElseThrow(() -> new CheckedException("未查找到对应发货单！"));
        //明细
        List<WmsDeliverGoodsDetailsEntity> deliverGoodsDetailsList = wmsDeliverGoodsDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                .eq(WmsDeliverGoodsDetailsEntity::getDeliverCode, deliverGoodsBase.getDeliverCode()));
        //更新明细数据，构建发货记录
        List<WmsDeliverGoodsRecordEntity> recordList = new ArrayList<>();
        Map<String, BigDecimal> operatePlanMap = new HashMap<>();
        deliverGoodsDetailsList.forEach(item -> {
            BigDecimal operateNum = operateMap.get(item.getId());
            WmsOutNoticeDetailEntity wmsOutNoticeDetail = wmsNoticeDetailMap.get(item.getId());
            if (ObjectUtil.isNotEmpty(operateNum)) {
                //构建发货计划使用的Map
                if (StrUtil.isNotBlank(item.getDeliverPlanCode())) {
                    operatePlanMap.put(item.getDeliverPlanCode(), operateNum);
                }
                //更新数量 和状态
                BigDecimal deliveredNum = item.getDeliveredNum();
                item.setDeliveredNum(deliveredNum.add(operateNum));
                if (item.getDeliverNum().compareTo(item.getDeliveredNum()) <= 0) {
                    item.setDeliverState(DeliverStateEnum.COMPLETE.getCode());
                } else {
                    item.setDeliverState(DeliverStateEnum.ING.getCode());
                }
                //构建发货记录
                WmsDeliverGoodsRecordEntity wmsDeliverGoodsRecord = new WmsDeliverGoodsRecordEntity();
                wmsDeliverGoodsRecord.setDeliverCode(goodsBase.getDeliverCode());
                wmsDeliverGoodsRecord.setOrderCode(item.getOrderCode());
                wmsDeliverGoodsRecord.setDeliverPlanCode(item.getDeliverPlanCode());
                wmsDeliverGoodsRecord.setDeliveredNum(operateNum);
                wmsDeliverGoodsRecord.setDeliverTime(deliverGoodsBase.getDeliverTime());
                wmsDeliverGoodsRecord.setCustomerCode(item.getCustomerCode());
                wmsDeliverGoodsRecord.setCustomerName(item.getCustomerName());
                wmsDeliverGoodsRecord.setProductCode(item.getProductCode());
                wmsDeliverGoodsRecord.setProductName(item.getProductName());
                wmsDeliverGoodsRecord.setWarehouseCode(ObjectUtil.isNotEmpty(wmsOutNoticeDetail) ? wmsOutNoticeDetail.getWarehouseCode() : null);
                wmsDeliverGoodsRecord.setWarehouseName(ObjectUtil.isNotEmpty(wmsOutNoticeDetail) ? wmsOutNoticeDetail.getWarehouseName() : null);
                wmsDeliverGoodsRecord.setReissueSign(deliverGoodsBase.getReissueSign());
                wmsDeliverGoodsRecord.setFactoryCode(goodsBase.getFactoryCode());
                recordList.add(wmsDeliverGoodsRecord);
            }
        });
        wmsDeliverGoodsRecordService.saveBatch(recordList);
        wmsDeliverGoodsDetailsService.updateBatchById(deliverGoodsDetailsList);
        //更新主表数据
        Long unComplete = deliverGoodsDetailsList.stream().filter(item -> !DeliverStateEnum.COMPLETE.getCode().equals(item.getDeliverState())).count();
        if (unComplete.compareTo(0L) > 0) {
            deliverGoodsBase.setDeliverState(DeliverStateEnum.ING.getCode());
        } else {
            deliverGoodsBase.setDeliverState(DeliverStateEnum.COMPLETE.getCode());
        }
        this.updateById(deliverGoodsBase);

        List<String> planCodes = deliverGoodsDetailsList.stream().map(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode).collect(Collectors.toList());
        //如果是补发单据，更新发货计划的补发数量
        if (YesOrNoStateEnum.YES.getCode().equals(deliverGoodsBase.getReissueSign())) {
            //更新发货计划数据
            if (CollUtil.isNotEmpty(planCodes)) {
                List<WmsDeliverGoodsPlanBaseEntity> planBaseList = wmsDeliverGoodsPlanBaseService.list(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                        .in(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, planCodes));
                planBaseList.forEach(item -> {
                    BigDecimal operateNum = operatePlanMap.get(item.getDeliverPlanCode());
                    if (ObjectUtil.isNotEmpty(operateNum)) {
                        //更新数量 和状态
                        BigDecimal reissueNum = item.getReissueNum();
                        item.setReissueNum(reissueNum.add(operateNum));
                    }
                });
                wmsDeliverGoodsPlanBaseService.updateBatchById(planBaseList);
            }
        }
        //如果是非补发单据，更新发货计划的发货数量和状态，订单数据根据订单号、产品、发货时间进行匹配更新，匹配不到的不做更新
        if (YesOrNoStateEnum.NO.getCode().equals(deliverGoodsBase.getReissueSign())) {
            //3.更新发货计划数据
            if (CollUtil.isNotEmpty(planCodes)) {
                List<WmsDeliverGoodsPlanBaseEntity> planBaseList = wmsDeliverGoodsPlanBaseService.list(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                        .in(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, planCodes));
                planBaseList.forEach(item -> {
                    BigDecimal operateNum = operatePlanMap.get(item.getDeliverPlanCode());
                    if (ObjectUtil.isNotEmpty(operateNum)) {
                        //更新数量 和状态
                        BigDecimal deliveredNum = item.getDeliveredNum();
                        item.setDeliveredNum(deliveredNum.add(operateNum));
                        if (item.getDeliverNum().compareTo(item.getDeliveredNum()) <= 0) {
                            item.setDeliverState(DeliverStateEnum.COMPLETE.getCode());
                        } else {
                            item.setDeliverState(DeliverStateEnum.ING.getCode());
                        }
                    }
                });
                wmsDeliverGoodsPlanBaseService.updateBatchById(planBaseList);

                //4.更新订单数据 - 退货的数据不更新到订单
                List<WmsDeliverGoodsPlanBaseEntity> planBaseEntities = planBaseList.stream().filter(item -> StrUtil.isNotBlank(item.getOrderCode())).collect(Collectors.toList());
                if (CollUtil.isNotEmpty(planBaseEntities)) {
                    List<OmsUpdateOrderDeliverDto> orderDeliverDtos = planBaseEntities.stream().map(item -> {
                        BigDecimal operateNum = operatePlanMap.get(item.getDeliverPlanCode());
                        OmsUpdateOrderDeliverDto omsUpdateOrderDeliverDto = new OmsUpdateOrderDeliverDto();
                        omsUpdateOrderDeliverDto.setWmsBillNo(bill.getBillNo());
                        omsUpdateOrderDeliverDto.setOrderCode(item.getOrderCode());
                        omsUpdateOrderDeliverDto.setProductCode(item.getProductCode());
                        omsUpdateOrderDeliverDto.setDeliverTime(item.getPlanDeliverTime());
                        omsUpdateOrderDeliverDto.setOperateNum(ObjectUtil.isNotEmpty(operateNum) ? operateNum : BigDecimal.ZERO);
                        return omsUpdateOrderDeliverDto;
                    }).collect(Collectors.toList());
                    R<Boolean> booleanR = remoteOmsOrderService.updateOrderDeliver(orderDeliverDtos);
                    if (booleanR.getCode() != 0) {
                        throw new RuntimeException("更新订单信息失败!");
                    }
                }
            }
        }
        return Boolean.TRUE;
    }

    /**
     * 创建销售通知单
     */
    @Override
    public Boolean create(Long id) {
        WmsDeliverGoodsBaseEntity deliverGoodsBase = this.getById(id);
        List<String> errorMsg = checkExistOutNoticeOrBill(deliverGoodsBase);
        if (CollUtil.isNotEmpty(errorMsg)) {
            throw new RuntimeException("操作失败！失败原因：" + String.join(";", errorMsg));
        }
        List<WmsDeliverGoodsDetailsEntity> deliverDetailsList = wmsDeliverGoodsDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                .eq(WmsDeliverGoodsDetailsEntity::getDeliverCode, deliverGoodsBase.getDeliverCode()));
        // 3.同步创建成品仓库 销售发货通知单
        List<MdmMaterialEntity> matList = remoteMaterialService.getList().getData();
        if (CollUtil.isEmpty(matList)) {
            throw new RuntimeException("获取物料数据失败！物料数据未维护或者访问数据失败！");
        }
        Map<String, MdmMaterialEntity> matMap = matList.stream().collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(), (k1, k2) -> k1));
        try {
            //构建数据
            BigDecimal deliverNum = deliverDetailsList.stream().map(WmsDeliverGoodsDetailsEntity::getDeliverNum).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal deliveredNum = deliverDetailsList.stream().map(WmsDeliverGoodsDetailsEntity::getDeliveredNum).reduce(BigDecimal.ZERO, BigDecimal::add);
            //主表
            WmsOutNoticeEntity wmsOutNotice = new WmsOutNoticeEntity();
            wmsOutNotice.setState(InOutNoticeStateEnum.READY.getCode());
            wmsOutNotice.setWarehouseCode(deliverGoodsBase.getWarehouseCode());
            wmsOutNotice.setWarehouseName(deliverGoodsBase.getWarehouseName());
            wmsOutNotice.setNoticeOutNum(Double.valueOf(String.valueOf(deliverNum.subtract(deliveredNum))));
            wmsOutNotice.setAllOutNum(0.0);
            wmsOutNotice.setAllNotOutNum(Double.valueOf(String.valueOf(deliverNum.subtract(deliveredNum))));
            wmsOutNotice.setBillType(billType);
            wmsOutNotice.setDocumentId(deliverGoodsBase.getId());
            wmsOutNotice.setDocumentTable(DocumentEnum.DELIVER.getCode());
            wmsOutNotice.setFactoryCode(deliverGoodsBase.getFactoryCode());
            //子表
            List<WmsOutNoticeDetailEntity> noticeDetailList = deliverDetailsList.stream().map(item -> {
                WmsOutNoticeDetailEntity wmsOutNoticeDetail = new WmsOutNoticeDetailEntity();
                wmsOutNoticeDetail.setMatCode(item.getProductCode());
                wmsOutNoticeDetail.setMatName(item.getProductName());
                MdmMaterialEntity mdmMaterial = matMap.get(item.getProductCode());
                wmsOutNoticeDetail.setMatUnit(ObjectUtil.isNotEmpty(mdmMaterial) ? mdmMaterial.getBasicUnit() : null);
                wmsOutNoticeDetail.setMatSpec(ObjectUtil.isNotEmpty(mdmMaterial) ? mdmMaterial.getSpecificationModel() : null);
                wmsOutNoticeDetail.setMatGroup(ObjectUtil.isNotEmpty(mdmMaterial) ? mdmMaterial.getGroupCode() : null);
                wmsOutNoticeDetail.setMatGroupName(ObjectUtil.isNotEmpty(mdmMaterial) ? mdmMaterial.getGroupName() : null);
                wmsOutNoticeDetail.setNoticeOutNum(Double.valueOf(String.valueOf(item.getDeliverNum().subtract(item.getDeliveredNum()))));
                wmsOutNoticeDetail.setDocumentId(item.getId());
                wmsOutNoticeDetail.setDocumentTable(DocumentEnum.DELIVER.getCode());
                wmsOutNoticeDetail.setFactoryCode(deliverGoodsBase.getFactoryCode());
                return wmsOutNoticeDetail;
            }).collect(Collectors.toList());
            noticeDetailList = noticeDetailList.stream().filter(item -> item.getNoticeOutNum().compareTo(0.0) > 0).collect(Collectors.toList());
            wmsOutNotice.setDetailList(noticeDetailList);
            //保存
            WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
            handler.saveDeep(wmsOutNotice);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
        deliverGoodsBase.setIssued(YesOrNoStateEnum.YES.getCode());
        return this.updateById(deliverGoodsBase);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean retract(Long id) {
        WmsDeliverGoodsBaseEntity deliverGoodsBase = this.getById(id);
        List<WmsOutBillEntity> wmsOutBillEntities = wmsOutBillMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
                .eq(WmsOutBillEntity::getDocumentId, id)
                .eq(WmsOutBillEntity::getDocumentTable, DocumentEnum.DELIVER.getCode())
                .and(wq -> wq.eq(WmsOutBillEntity::getState, InOutBillStateEnum.PASSED.getCode())
                        .or().eq(WmsOutBillEntity::getState, InOutBillStateEnum.FINISHED.getCode())
                        .or().eq(WmsOutBillEntity::getState, InOutBillStateEnum.APP_RUNNING.getCode())));

        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        List<WmsOutNoticeEntity> listByDuId = handler.outNoticeListByDuId(id);
        List<WmsOutNoticeEntity> outNoticeList = listByDuId.stream().filter(item -> !InOutNoticeStateEnum.CANCELED.getCode().equals(item.getState())
                && !InOutNoticeStateEnum.FINISHED.getCode().equals(item.getState())).collect(Collectors.toList());
        if (CollUtil.isNotEmpty(wmsOutBillEntities)) {
            if (CollUtil.isNotEmpty(outNoticeList)) {
                throw new RuntimeException("下发数据正在出库或已经部分出库，请处理仓库单据！ 销售通知单单号：" + String.join(",", outNoticeList.stream().map(item -> String.valueOf(item.getId())).toList()));
            }
        }
        //删除 除已审核、已完成的出库单 以及明细
        WmsOutBillHandler billHandler = WmsOutBillFactory.getHandler(billType);
        billHandler.outBillRemoveByDuId(id);
        //作废 待处理 、处理中的出库通知单
        handler.outNoticeRemoveByDuId(id);
        //更改主表状态为未下发
        deliverGoodsBase.setIssued(YesOrNoStateEnum.NO.getCode());
        return this.updateById(deliverGoodsBase);
    }

}