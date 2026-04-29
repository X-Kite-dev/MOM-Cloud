package com.kitex.mom.wms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import com.kitex.mom.oms.enums.YesOrNoStateEnum;
import com.kitex.mom.oms.feign.RemoteOmsOrderService;
import com.kitex.mom.wms.entity.WmsDeliverGoodsBaseEntity;
import com.kitex.mom.wms.entity.WmsDeliverGoodsDetailsEntity;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.enums.DeliverBaseSourceEnum;
import com.kitex.mom.wms.enums.DeliverStateEnum;
import com.kitex.mom.wms.mapper.WmsDeliverGoodsBaseMapper;
import com.kitex.mom.wms.mapper.WmsDeliverGoodsPlanBaseMapper;
import com.kitex.mom.wms.service.WmsDeliverGoodsDetailsService;
import com.kitex.mom.wms.service.WmsDeliverGoodsPlanBaseService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 发货计划表
 *
 * @author kitex
 * @date 2025-01-13 09:19:26
 */
@Service
@AllArgsConstructor
public class WmsDeliverGoodsPlanBaseServiceImpl extends ServiceImpl<WmsDeliverGoodsPlanBaseMapper, WmsDeliverGoodsPlanBaseEntity> implements WmsDeliverGoodsPlanBaseService {

    //发货明细表
    private final WmsDeliverGoodsDetailsService wmsDeliverGoodsDetailsService;
    //发货单基础表Mapper
    private final WmsDeliverGoodsBaseMapper wmsDeliverGoodsBaseMapper;
    //订单
    private final RemoteOmsOrderService remoteOmsOrderService;

    private final RemoteMaterialService remoteMaterialService;


    @Override
    public Page<WmsDeliverGoodsPlanBaseEntity> getWmsDeliverGoodsPlanBasePage(Page<WmsDeliverGoodsPlanBaseEntity> toMpPage, WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase) {
        LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity> wrapper = Wrappers.lambdaQuery();
        //增加发货单号查询
        if (StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getDeliverCode())) {
            List<WmsDeliverGoodsDetailsEntity> detailList = wmsDeliverGoodsDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                    .eq(WmsDeliverGoodsDetailsEntity::getDeliverCode, wmsDeliverGoodsPlanBase.getDeliverCode()));
            if (CollUtil.isEmpty(detailList)) {
                return new Page<>();
            }
            Set<String> planCodes = detailList.stream().map(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode).collect(Collectors.toSet());
            wrapper.in(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, planCodes);
        }
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getDeliverPlanCode()), WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, wmsDeliverGoodsPlanBase.getDeliverPlanCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getOrderCode()), WmsDeliverGoodsPlanBaseEntity::getOrderCode, wmsDeliverGoodsPlanBase.getOrderCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getCustomerCode()), WmsDeliverGoodsPlanBaseEntity::getCustomerCode, wmsDeliverGoodsPlanBase.getCustomerCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getCustomerName()), WmsDeliverGoodsPlanBaseEntity::getCustomerName, wmsDeliverGoodsPlanBase.getCustomerName());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getProductCode()), WmsDeliverGoodsPlanBaseEntity::getProductCode, wmsDeliverGoodsPlanBase.getProductCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getProductName()), WmsDeliverGoodsPlanBaseEntity::getProductName, wmsDeliverGoodsPlanBase.getProductName());
        wrapper.eq(StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getDeliverState()), WmsDeliverGoodsPlanBaseEntity::getDeliverState, wmsDeliverGoodsPlanBase.getDeliverState());
        wrapper.orderByAsc(WmsDeliverGoodsPlanBaseEntity::getDeliverState);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return this.page(toMpPage, wrapper);
    }

    @Override
    public Map<String, Long> getCount(WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase) {
        LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity> wrapper = Wrappers.lambdaQuery();
        //增加发货单号查询
        if (StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getDeliverCode())) {
            List<WmsDeliverGoodsDetailsEntity> detailList = wmsDeliverGoodsDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                    .eq(WmsDeliverGoodsDetailsEntity::getDeliverCode, wmsDeliverGoodsPlanBase.getDeliverCode()));
            if (CollUtil.isEmpty(detailList)) {
                return new HashMap<>();
            }
            Set<String> planCodes = detailList.stream().map(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode).collect(Collectors.toSet());
            wrapper.in(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, planCodes);
        }
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getDeliverPlanCode()), WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, wmsDeliverGoodsPlanBase.getDeliverPlanCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getOrderCode()), WmsDeliverGoodsPlanBaseEntity::getOrderCode, wmsDeliverGoodsPlanBase.getOrderCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getCustomerCode()), WmsDeliverGoodsPlanBaseEntity::getCustomerCode, wmsDeliverGoodsPlanBase.getCustomerCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getCustomerName()), WmsDeliverGoodsPlanBaseEntity::getCustomerName, wmsDeliverGoodsPlanBase.getCustomerName());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getProductCode()), WmsDeliverGoodsPlanBaseEntity::getProductCode, wmsDeliverGoodsPlanBase.getProductCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getProductName()), WmsDeliverGoodsPlanBaseEntity::getProductName, wmsDeliverGoodsPlanBase.getProductName());
        return this.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(WmsDeliverGoodsPlanBaseEntity::getDeliverState, Collectors.counting()));
    }

    @Override
    public WmsDeliverGoodsPlanBaseEntity getPlanBaseById(Long id) {
        //查询发货计划表
        WmsDeliverGoodsPlanBaseEntity planBaseById = this.getById(id);
        //查询发货单
        List<WmsDeliverGoodsDetailsEntity> detailsEntities = wmsDeliverGoodsDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                .eq(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode, planBaseById.getDeliverPlanCode()));

        List<String> deliverCodeList = detailsEntities.stream().map(WmsDeliverGoodsDetailsEntity::getDeliverCode).distinct().toList();
        List<WmsDeliverGoodsBaseEntity> deliverList = new ArrayList<>();
        if (CollUtil.isNotEmpty(deliverCodeList)) {
            deliverList = wmsDeliverGoodsBaseMapper.selectList(new LambdaQueryWrapper<WmsDeliverGoodsBaseEntity>()
                    .in(WmsDeliverGoodsBaseEntity::getDeliverCode, deliverCodeList));
        }
        planBaseById.setDeliverList(deliverList);
        return planBaseById;
    }

    /**
     * 新增发货计划表
     *
     * @param wmsDeliverGoodsPlanBase 发货计划表
     * @return R
     */
    @Override
    public Boolean saveDeliverPlan(WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase) {
        //根据订单号，产品、发货时间，查询订单明细
        R<OmsOrderDetailsEntity> omsOrderDetailsEntityR = remoteOmsOrderService.getOrderDetails(wmsDeliverGoodsPlanBase.getOrderCode(), wmsDeliverGoodsPlanBase.getProductCode(), wmsDeliverGoodsPlanBase.getPlanDeliverTime());
        if (ObjectUtil.isNotEmpty(omsOrderDetailsEntityR.getData())) {
            OmsOrderDetailsEntity data = omsOrderDetailsEntityR.getData();
            wmsDeliverGoodsPlanBase.setOrderCodeDetails(data.getId());
        }
        //状态初始化
        wmsDeliverGoodsPlanBase.setDeliveredNum(BigDecimal.ZERO);
        wmsDeliverGoodsPlanBase.setReturnNum(BigDecimal.ZERO);
        wmsDeliverGoodsPlanBase.setReissueNum(BigDecimal.ZERO);
        wmsDeliverGoodsPlanBase.setDeliverState(DeliverStateEnum.NOT_START.getCode());
        return this.save(wmsDeliverGoodsPlanBase);
    }

    @Override
    public Boolean updatePlanById(WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase) {
        //根据订单号，产品、发货时间，查询订单明细
        R<OmsOrderDetailsEntity> omsOrderDetailsEntityR = remoteOmsOrderService.getOrderDetails(wmsDeliverGoodsPlanBase.getOrderCode(), wmsDeliverGoodsPlanBase.getProductCode(), wmsDeliverGoodsPlanBase.getPlanDeliverTime());
        if (ObjectUtil.isNotEmpty(omsOrderDetailsEntityR.getData())) {
            OmsOrderDetailsEntity data = omsOrderDetailsEntityR.getData();
            wmsDeliverGoodsPlanBase.setOrderCodeDetails(data.getId());
        }
        return this.updateById(wmsDeliverGoodsPlanBase);
    }

    /**
     * 通过id删除发货计划表
     *
     * @param idList id列表
     * @return R
     */
    @Override
    public Boolean removePlanById(ArrayList<Long> idList) {
        if (CollUtil.isEmpty(idList)) {
            throw new RuntimeException("删除项不能为空！");
        }
        List<WmsDeliverGoodsPlanBaseEntity> planBaseList = this.listByIds(idList);
        Long count = planBaseList.stream().filter(item -> !DeliverStateEnum.NOT_START.getCode().equals(item.getDeliverState())).count();
        if (count.compareTo(0L) > 0) {
            throw new RuntimeException("只有未开始的计划才可以删除！");
        }
        return this.removeBatchByIds(idList);
    }


    private List<String> checkExistDeliverGoodsBase(String deliverPlanCode) {
        List<String> errorMsg = new ArrayList<>();
        List<WmsDeliverGoodsDetailsEntity> deliverGoodsDetailList = wmsDeliverGoodsDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                .eq(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode, deliverPlanCode));
        if (ObjectUtil.isNotEmpty(deliverGoodsDetailList)) {
            Set<String> deliverCodes = deliverGoodsDetailList.stream().map(WmsDeliverGoodsDetailsEntity::getDeliverCode).collect(Collectors.toSet());
            if (CollUtil.isNotEmpty(deliverCodes)) {
                List<WmsDeliverGoodsBaseEntity> dbGoodsBaseList = wmsDeliverGoodsBaseMapper.selectList(new LambdaQueryWrapper<WmsDeliverGoodsBaseEntity>().in(WmsDeliverGoodsBaseEntity::getDeliverCode, deliverCodes));
                List<WmsDeliverGoodsBaseEntity> ingGoodsBases = dbGoodsBaseList.stream().filter(item -> DeliverStateEnum.NOT_START.getCode().equals(item.getDeliverState())
                        || DeliverStateEnum.ING.getCode().equals(item.getDeliverState())).toList();
                if (CollUtil.isNotEmpty(ingGoodsBases)) {
                    List<String> collect = ingGoodsBases.stream().map(WmsDeliverGoodsBaseEntity::getDeliverCode).distinct().collect(Collectors.toList());
                    errorMsg.add("发货计划存在发货单：" + String.join(",", collect) + " 未关闭！请先完成或关闭发货单！");
                }
            }
        }
        return errorMsg;
    }

    /**
     * 通过发货计划号关闭计划
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean closePlan(String deliverPlanCode) {
        //二次校验，完成状态不可关闭
        WmsDeliverGoodsPlanBaseEntity planBase = this.getOne(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                .eq(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, deliverPlanCode));
        if (DeliverStateEnum.COMPLETE.getCode().equals(planBase.getDeliverState())) {
            throw new RuntimeException("完成状态不可关闭！");
        }
        //判断是否存在未关闭的发货单
        List<String> errorMsg = checkExistDeliverGoodsBase(deliverPlanCode);
        if (CollUtil.isNotEmpty(errorMsg)) {
            throw new RuntimeException(String.join(";", errorMsg));
        }
        //关闭逻辑 - 发货计划变为已关闭状态
        planBase.setDeliverState(DeliverStateEnum.CLOSE.getCode());
        this.updateById(planBase);
        return Boolean.TRUE;
    }


    /**
     * 通过发货计划号强制完成计划
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean completePlan(String deliverPlanCode) {
        //判断是否存在未关闭的发货单
        List<String> errorMsg = checkExistDeliverGoodsBase(deliverPlanCode);
        if (CollUtil.isNotEmpty(errorMsg)) {
            throw new RuntimeException(String.join(";", errorMsg));
        }
        //完成逻辑 - 发货计划变为已完成状态
        this.update(new UpdateWrapper<WmsDeliverGoodsPlanBaseEntity>().lambda()
                .set(WmsDeliverGoodsPlanBaseEntity::getDeliverState, DeliverStateEnum.COMPLETE.getCode())
                .eq(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, deliverPlanCode));
        return Boolean.TRUE;
    }

    /**
     * 生成发货单
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean createDeliver(WmsDeliverGoodsBaseEntity deliverGoodsBase) {
        if (CollUtil.isEmpty(deliverGoodsBase.getDeliverDetailsList())) {
            throw new RuntimeException("发货明细信息不能为空！");
        }
        List<WmsDeliverGoodsDetailsEntity> deliverDetailsList = deliverGoodsBase.getDeliverDetailsList();
        //后端二次校验：只有未开始和进行中的数据才可以进行生成发货单
        Long stateCount = deliverDetailsList.stream().filter(item -> DeliverStateEnum.COMPLETE.getCode().equals(item.getDeliverState())
                || DeliverStateEnum.CLOSE.getCode().equals(item.getDeliverCode())).count();
        if (stateCount.compareTo(0L) > 0) {
            throw new RuntimeException("只有未开始和进行中的数据才可以进行生成发货单！");
        }
        //校验：计划只能同时存在一个发货单（关闭 和 完成 状态除外）//改为多次发货
        List<String> deliverPlanCodes = deliverDetailsList.stream().map(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode).distinct().collect(Collectors.toList());
        List<WmsDeliverGoodsDetailsEntity> dbGoodsDetailsList = wmsDeliverGoodsDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                .in(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode, deliverPlanCodes));
        Set<String> deliverCodes = dbGoodsDetailsList.stream().map(WmsDeliverGoodsDetailsEntity::getDeliverCode).collect(Collectors.toSet());
        if (CollUtil.isNotEmpty(deliverCodes)) {
            List<WmsDeliverGoodsBaseEntity> dbGoodsBaseList = wmsDeliverGoodsBaseMapper.selectList(new LambdaQueryWrapper<WmsDeliverGoodsBaseEntity>()
                    .eq(WmsDeliverGoodsBaseEntity::getReissueSign, YesOrNoStateEnum.NO.getCode())
                    .in(WmsDeliverGoodsBaseEntity::getDeliverCode, deliverCodes));
            List<WmsDeliverGoodsBaseEntity> ingGoodsBases = dbGoodsBaseList.stream().filter(item -> DeliverStateEnum.NOT_START.getCode().equals(item.getDeliverState())
                    || DeliverStateEnum.ING.getCode().equals(item.getDeliverState())).toList();
            if (CollUtil.isNotEmpty(ingGoodsBases)) {
                List<String> collect = ingGoodsBases.stream().map(WmsDeliverGoodsBaseEntity::getDeliverCode).distinct().collect(Collectors.toList());
                throw new RuntimeException("生成发货单失败！发货计划存在未完成的发货单，发货单号为：" + String.join(",", collect));
            }
        }

        //后端二次校验：所选择的客户必须为同一个客户
        Set<String> customerCodeList = deliverDetailsList.stream().map(WmsDeliverGoodsDetailsEntity::getCustomerCode).collect(Collectors.toSet());
        if (customerCodeList.size() > 1) {
            throw new RuntimeException("选择的发货计划必须为同一个客户才可以进行统一发货！");
        }

        //获取所有产品信息
        List<MdmMaterialEntity> materialList = remoteMaterialService.getList().getData();
        if (CollUtil.isEmpty(materialList)) {
            throw new RuntimeException("物料信息访问失败！物料信息为空或服务请求失败！");
        }
        Map<String, MdmMaterialEntity> materialMap = materialList.stream().collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(), (k1, k2) -> k1));
        //业务逻辑：


        // 1.查询计划单改为进行中状态更新
        this.update(new UpdateWrapper<WmsDeliverGoodsPlanBaseEntity>().lambda()
                .set(WmsDeliverGoodsPlanBaseEntity::getDeliverState, DeliverStateEnum.CREATE.getCode())
                .in(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, deliverPlanCodes));
        // 2.根据计划单生产发货单及其明细信息，发货单初始状态为未开始，明细信息初始状态为进行中
        //主表
        deliverGoodsBase.setDeliverState(DeliverStateEnum.NOT_START.getCode());
        deliverGoodsBase.setReissueSign(YesOrNoStateEnum.NO.getCode());
        deliverGoodsBase.setIssued(YesOrNoStateEnum.NO.getCode());
        deliverGoodsBase.setSource(DeliverBaseSourceEnum.PLAN.getCode());
        deliverGoodsBase.setFactoryCode(deliverDetailsList.get(0).getFactoryCode());
        wmsDeliverGoodsBaseMapper.insert(deliverGoodsBase);
        //明细表
        deliverDetailsList.forEach(item -> {
            MdmMaterialEntity mdmMaterial = materialMap.get(item.getProductCode());
            item.setDeliverCode(deliverGoodsBase.getDeliverCode());
            //改为多次发货，已发货数量重置为0
            item.setDeliveredNum(BigDecimal.ZERO);
            item.setReturnNum(BigDecimal.ZERO);
            item.setProductSpec(ObjectUtil.isNotEmpty(mdmMaterial) ? mdmMaterial.getSpecificationModel() : null);
            item.setProductUnit(ObjectUtil.isNotEmpty(mdmMaterial) ? mdmMaterial.getBasicUnit() : null);
        });
        wmsDeliverGoodsDetailsService.saveBatch(deliverDetailsList);
        return Boolean.TRUE;
    }


}