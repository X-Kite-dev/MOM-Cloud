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
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
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
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
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
    private final WmsSalesStockReservationService salesStockReservationService;


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
        Page<WmsDeliverGoodsPlanBaseEntity> result = this.page(toMpPage, wrapper);
        enrichSalesReservation(result.getRecords());
        return result;
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
        enrichSalesReservation(List.of(planBaseById));
        return planBaseById;
    }

    private void enrichSalesReservation(List<WmsDeliverGoodsPlanBaseEntity> plans) {
        if (CollUtil.isEmpty(plans)) return;
        Map<Long, com.kitex.mom.wms.entity.WmsSalesStockReservationEntity> reservations =
                salesStockReservationService.reservationsByPlanIds(plans.stream()
                        .map(WmsDeliverGoodsPlanBaseEntity::getId).collect(Collectors.toSet()));
        for (WmsDeliverGoodsPlanBaseEntity plan : plans) {
            com.kitex.mom.wms.entity.WmsSalesStockReservationEntity row = reservations.get(plan.getId());
            if (row == null) {
                plan.setSalesReservationStatus("NONE");
                plan.setSalesReservedNum(BigDecimal.ZERO);
                plan.setSalesConsumedNum(BigDecimal.ZERO);
                plan.setSalesReleasedNum(BigDecimal.ZERO);
                plan.setSalesActiveReservedNum(BigDecimal.ZERO);
                continue;
            }
            BigDecimal reserved = Optional.ofNullable(row.getReservedNum()).orElse(BigDecimal.ZERO);
            BigDecimal consumed = Optional.ofNullable(row.getConsumedNum()).orElse(BigDecimal.ZERO);
            BigDecimal released = Optional.ofNullable(row.getReleasedNum()).orElse(BigDecimal.ZERO);
            plan.setSalesReservationNo(row.getReservationNo());
            plan.setSalesReservationStatus(row.getStatus());
            plan.setSalesReservedNum(reserved);
            plan.setSalesConsumedNum(consumed);
            plan.setSalesReleasedNum(released);
            plan.setSalesActiveReservedNum(reserved.subtract(consumed).subtract(released).max(BigDecimal.ZERO));
        }
    }

    /**
     * 新增发货计划表
     *
     * @param wmsDeliverGoodsPlanBase 发货计划表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeliverPlan(WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase) {
        requireManualPlanInput(wmsDeliverGoodsPlanBase);
        if (wmsDeliverGoodsPlanBase.getOrderCodeDetails() != null) {
            throw new CheckedException("OMS来源订单行只能由OMS下发创建WMS发货计划");
        }
        requireNoMatchingOmsLine(wmsDeliverGoodsPlanBase);
        wmsDeliverGoodsPlanBase.setId(null);
        wmsDeliverGoodsPlanBase.setDeliveredNum(BigDecimal.ZERO);
        wmsDeliverGoodsPlanBase.setReturnNum(BigDecimal.ZERO);
        wmsDeliverGoodsPlanBase.setReissueNum(BigDecimal.ZERO);
        wmsDeliverGoodsPlanBase.setDeliverState(DeliverStateEnum.NOT_START.getCode());
        return this.save(wmsDeliverGoodsPlanBase);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updatePlanById(WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase) {
        if (wmsDeliverGoodsPlanBase == null || wmsDeliverGoodsPlanBase.getId() == null) {
            throw new CheckedException("编辑发货计划必须指定ID");
        }
        WmsDeliverGoodsPlanBaseEntity current = this.getOne(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                .eq(WmsDeliverGoodsPlanBaseEntity::getId, wmsDeliverGoodsPlanBase.getId())
                .last("FOR UPDATE"), false);
        if (current == null) throw new CheckedException("发货计划不存在");
        requireFactory(current.getFactoryCode());
        requireUnstartedAndUnlinked(current);
        requireNoActiveSalesReservation(current);
        if (StrUtil.isNotBlank(wmsDeliverGoodsPlanBase.getFactoryCode())
                && !Objects.equals(current.getFactoryCode(), wmsDeliverGoodsPlanBase.getFactoryCode())) {
            throw new CheckedException("不得改写发货计划所属工厂");
        }
        if (current.getOrderCodeDetails() != null) {
            if (!Objects.equals(current.getOrderCode(), wmsDeliverGoodsPlanBase.getOrderCode())
                    || !Objects.equals(current.getOrderCodeDetails(), wmsDeliverGoodsPlanBase.getOrderCodeDetails())
                    || !Objects.equals(current.getDeliverPlanCode(), wmsDeliverGoodsPlanBase.getDeliverPlanCode())
                    || !Objects.equals(current.getProductCode(), wmsDeliverGoodsPlanBase.getProductCode())
                    || !Objects.equals(current.getPlanDeliverTime(), wmsDeliverGoodsPlanBase.getPlanDeliverTime())
                    || current.getDeliverNum() == null || wmsDeliverGoodsPlanBase.getDeliverNum() == null
                    || current.getDeliverNum().compareTo(wmsDeliverGoodsPlanBase.getDeliverNum()) != 0) {
                throw new CheckedException("OMS来源发货计划的订单、产品、交期和数量只能经OMS变更");
            }
        } else {
            requireManualPlanInput(wmsDeliverGoodsPlanBase);
            if (wmsDeliverGoodsPlanBase.getOrderCodeDetails() != null
                    || !Objects.equals(current.getDeliverPlanCode(), wmsDeliverGoodsPlanBase.getDeliverPlanCode())) {
                throw new CheckedException("手工发货计划不得伪造OMS来源或改写计划号");
            }
            requireNoMatchingOmsLine(wmsDeliverGoodsPlanBase);
            current.setOrderCode(wmsDeliverGoodsPlanBase.getOrderCode());
            current.setCustomerCode(wmsDeliverGoodsPlanBase.getCustomerCode());
            current.setCustomerName(wmsDeliverGoodsPlanBase.getCustomerName());
            current.setProductCode(wmsDeliverGoodsPlanBase.getProductCode());
            current.setProductName(wmsDeliverGoodsPlanBase.getProductName());
            current.setProductSpec(wmsDeliverGoodsPlanBase.getProductSpec());
            current.setPlanDeliverTime(wmsDeliverGoodsPlanBase.getPlanDeliverTime());
            current.setDeliverNum(wmsDeliverGoodsPlanBase.getDeliverNum());
        }
        current.setCustomerWarehouse(wmsDeliverGoodsPlanBase.getCustomerWarehouse());
        current.setCustomerAddress(wmsDeliverGoodsPlanBase.getCustomerAddress());
        current.setCustomerContact(wmsDeliverGoodsPlanBase.getCustomerContact());
        current.setCustomerPhone(wmsDeliverGoodsPlanBase.getCustomerPhone());
        if (!this.updateById(current)) throw new CheckedException("发货计划编辑失败");
        return Boolean.TRUE;
    }

    private void requireManualPlanInput(WmsDeliverGoodsPlanBaseEntity plan) {
        if (plan == null || StrUtil.isBlank(plan.getDeliverPlanCode()) || StrUtil.isBlank(plan.getOrderCode())
                || StrUtil.isBlank(plan.getProductCode()) || plan.getPlanDeliverTime() == null
                || plan.getDeliverNum() == null || plan.getDeliverNum().signum() <= 0) {
            throw new CheckedException("手工发货计划必须明确计划号、订单、产品、交期和正数量");
        }
        String selected = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotBlank(selected)) {
            if (StrUtil.isNotBlank(plan.getFactoryCode()) && !Objects.equals(selected, plan.getFactoryCode())) {
                throw new CheckedException("手工发货计划工厂与当前工厂不一致");
            }
            plan.setFactoryCode(selected);
        }
        requireFactory(plan.getFactoryCode());
    }

    private void requireNoMatchingOmsLine(WmsDeliverGoodsPlanBaseEntity plan) {
        R<OmsOrderDetailsEntity> result = remoteOmsOrderService.getOrderDetails(
                plan.getOrderCode(), plan.getProductCode(), plan.getPlanDeliverTime().toString());
        if (result == null || !result.isOk()) {
            throw new CheckedException("OMS订单来源不可核验，手工发货计划故障关闭");
        }
        if (result.getData() != null) {
            throw new CheckedException("已匹配OMS订单行，请从OMS下发或变更发货计划");
        }
    }

    /**
     * 通过id删除发货计划表
     *
     * @param idList id列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removePlanById(ArrayList<Long> idList) {
        return removePlans(idList, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeFromOms(ArrayList<Long> ids) {
        return removePlans(ids, true);
    }

    private Boolean removePlans(ArrayList<Long> ids, boolean omsSource) {
        if (CollUtil.isEmpty(ids) || ids.size() > 50 || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size() != ids.size()) {
            throw new CheckedException("发货计划删除必须指定最多50个不重复的ID");
        }
        for (Long id : ids.stream().sorted().toList()) {
            WmsDeliverGoodsPlanBaseEntity plan = this.getOne(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                    .eq(WmsDeliverGoodsPlanBaseEntity::getId, id).last("FOR UPDATE"), false);
            if (plan == null) throw new CheckedException("发货计划不存在：" + id);
            requireFactory(plan.getFactoryCode());
            if (omsSource != (plan.getOrderCodeDetails() != null)) {
                throw new CheckedException("OMS来源发货计划须由OMS撤回，非OMS计划不得使用OMS内部撤回入口");
            }
            requireUnstartedAndUnlinked(plan);
            requireNoActiveSalesReservation(plan);
            if (!this.removeById(id)) throw new CheckedException("发货计划删除失败：" + id);
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveFromOms(List<WmsDeliverGoodsPlanBaseEntity> plans) {
        List<WmsDeliverGoodsPlanBaseEntity> ordered = orderedOmsPlans(plans, false);
        for (WmsDeliverGoodsPlanBaseEntity incoming : ordered) {
            baseMapper.lockOmsSource(omsSourceKey(incoming));
            List<WmsDeliverGoodsPlanBaseEntity> existing = this.list(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                    .eq(WmsDeliverGoodsPlanBaseEntity::getOrderCode, incoming.getOrderCode())
                    .eq(WmsDeliverGoodsPlanBaseEntity::getOrderCodeDetails, incoming.getOrderCodeDetails())
                    .last("FOR UPDATE"));
            if (existing.size() > 1) throw new CheckedException("OMS来源发货计划重复，须先治理历史数据");
            if (!existing.isEmpty()) {
                if (!sameOmsFacts(existing.get(0), incoming)) {
                    throw new CheckedException("OMS来源发货计划已存在但事实不同，须走受控同步或变更");
                }
                continue;
            }
            if (incoming.getId() != null || !DeliverStateEnum.NOT_START.getCode().equals(incoming.getDeliverState())
                    || !zero(incoming.getDeliveredNum()) || !zero(incoming.getReturnNum())
                    || !zero(incoming.getReissueNum())) {
                throw new CheckedException("OMS新发货计划不得携带旧ID、执行数量或非初始状态");
            }
            incoming.setDeliveredNum(BigDecimal.ZERO);
            incoming.setReturnNum(BigDecimal.ZERO);
            incoming.setReissueNum(BigDecimal.ZERO);
            if (!this.save(incoming)) throw new CheckedException("OMS来源发货计划保存失败");
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateFromOms(List<WmsDeliverGoodsPlanBaseEntity> plans) {
        List<WmsDeliverGoodsPlanBaseEntity> ordered = orderedOmsPlans(plans, true);
        for (WmsDeliverGoodsPlanBaseEntity incoming : ordered) {
            baseMapper.lockOmsSource(omsSourceKey(incoming));
            WmsDeliverGoodsPlanBaseEntity current = this.getOne(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                    .eq(WmsDeliverGoodsPlanBaseEntity::getId, incoming.getId()).last("FOR UPDATE"), false);
            if (current == null || !Objects.equals(current.getOrderCode(), incoming.getOrderCode())
                    || !Objects.equals(current.getOrderCodeDetails(), incoming.getOrderCodeDetails())
                    || !Objects.equals(current.getFactoryCode(), incoming.getFactoryCode())
                    || !Objects.equals(current.getDeliverPlanCode(), incoming.getDeliverPlanCode())) {
                throw new CheckedException("OMS发货计划来源ID、订单行或工厂不匹配");
            }
            if (!Objects.equals(current.getDeliverState(), incoming.getDeliverState())
                    || !sameQuantity(current.getDeliveredNum(), incoming.getDeliveredNum())
                    || !sameQuantity(current.getReturnNum(), incoming.getReturnNum())
                    || !sameQuantity(current.getReissueNum(), incoming.getReissueNum())) {
                throw new CheckedException("OMS不得绕过WMS发货单或退补货流程改写计划执行状态和数量");
            }
            if (sameOmsFacts(current, incoming)) continue;
            requireUnstartedAndUnlinked(current);
            requireNoActiveSalesReservation(current);
            current.setCustomerCode(incoming.getCustomerCode());
            current.setCustomerName(incoming.getCustomerName());
            current.setProductCode(incoming.getProductCode());
            current.setProductName(incoming.getProductName());
            current.setProductSpec(incoming.getProductSpec());
            current.setPlanDeliverTime(incoming.getPlanDeliverTime());
            current.setDeliverNum(incoming.getDeliverNum());
            if (!this.updateById(current)) throw new CheckedException("OMS发货计划同步失败");
        }
        return Boolean.TRUE;
    }

    private List<WmsDeliverGoodsPlanBaseEntity> orderedOmsPlans(List<WmsDeliverGoodsPlanBaseEntity> plans,
                                                                boolean requireId) {
        if (CollUtil.isEmpty(plans) || plans.size() > 50 || plans.stream().anyMatch(Objects::isNull)) {
            throw new CheckedException("OMS发货计划批量命令必须包含1到50条非空记录");
        }
        Set<String> keys = new HashSet<>();
        for (WmsDeliverGoodsPlanBaseEntity plan : plans) {
            if (StrUtil.isBlank(plan.getOrderCode()) || plan.getOrderCodeDetails() == null
                    || StrUtil.isBlank(plan.getFactoryCode()) || StrUtil.isBlank(plan.getDeliverPlanCode())
                    || StrUtil.isBlank(plan.getProductCode()) || plan.getPlanDeliverTime() == null
                    || plan.getDeliverNum() == null || plan.getDeliverNum().signum() <= 0
                    || (requireId && plan.getId() == null) || !keys.add(omsSourceKey(plan))) {
                throw new CheckedException("OMS发货计划缺少唯一来源、正数量、产品、交期或计划号");
            }
            requireFactory(plan.getFactoryCode());
        }
        return plans.stream().sorted(Comparator.comparing(this::omsSourceKey)).toList();
    }

    private String omsSourceKey(WmsDeliverGoodsPlanBaseEntity plan) {
        return plan.getFactoryCode() + ':' + plan.getOrderCode() + ':' + plan.getOrderCodeDetails();
    }

    private boolean sameOmsFacts(WmsDeliverGoodsPlanBaseEntity current, WmsDeliverGoodsPlanBaseEntity incoming) {
        return Objects.equals(current.getFactoryCode(), incoming.getFactoryCode())
                && Objects.equals(current.getOrderCode(), incoming.getOrderCode())
                && Objects.equals(current.getOrderCodeDetails(), incoming.getOrderCodeDetails())
                && Objects.equals(current.getCustomerCode(), incoming.getCustomerCode())
                && Objects.equals(current.getCustomerName(), incoming.getCustomerName())
                && Objects.equals(current.getProductCode(), incoming.getProductCode())
                && Objects.equals(current.getProductName(), incoming.getProductName())
                && Objects.equals(current.getProductSpec(), incoming.getProductSpec())
                && Objects.equals(current.getPlanDeliverTime(), incoming.getPlanDeliverTime())
                && current.getDeliverNum() != null && incoming.getDeliverNum() != null
                && current.getDeliverNum().compareTo(incoming.getDeliverNum()) == 0;
    }

    private void requireUnstartedAndUnlinked(WmsDeliverGoodsPlanBaseEntity plan) {
        if (!DeliverStateEnum.NOT_START.getCode().equals(plan.getDeliverState())
                || !zero(plan.getDeliveredNum()) || !zero(plan.getReturnNum())
                || !zero(plan.getReissueNum())
                || wmsDeliverGoodsDetailsService.count(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                .eq(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode, plan.getDeliverPlanCode())) != 0) {
            throw new CheckedException("WMS发货计划已有发货单、交付/退补数量或非未开始状态，禁止直接变更/删除");
        }
    }

    private boolean zero(BigDecimal value) {
        return value != null && value.signum() == 0;
    }

    private boolean sameQuantity(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private void requireFactory(String factoryCode) {
        String selected = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isBlank(factoryCode)
                || (StrUtil.isNotBlank(selected) && !Objects.equals(selected, factoryCode))) {
            throw new CheckedException("当前工厂无权操作该WMS发货计划");
        }
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
                .eq(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, deliverPlanCode)
                .last("FOR UPDATE"), false);
        if (planBase == null) throw new CheckedException("发货计划不存在");
        requireFactory(planBase.getFactoryCode());
        requireNoActiveSalesReservation(planBase);
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
        WmsDeliverGoodsPlanBaseEntity planBase = this.getOne(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                .eq(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, deliverPlanCode)
                .last("FOR UPDATE"), false);
        if (planBase == null) throw new CheckedException("发货计划不存在");
        requireFactory(planBase.getFactoryCode());
        requireNoActiveSalesReservation(planBase);
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

    private void requireNoActiveSalesReservation(WmsDeliverGoodsPlanBaseEntity plan) {
        BigDecimal active = salesStockReservationService.activeReservedByPlanIds(Set.of(plan.getId()))
                .getOrDefault(plan.getId(), BigDecimal.ZERO);
        if (active.signum() > 0) {
            throw new CheckedException("发货计划仍有销售库存正式预留"
                    + active.stripTrailingZeros().toPlainString() + "，须先填写原因并受控释放");
        }
    }

    /**
     * 生成发货单
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean createDeliver(WmsDeliverGoodsBaseEntity deliverGoodsBase) {
        if (deliverGoodsBase == null || StrUtil.isBlank(deliverGoodsBase.getDeliverCode())
                || CollUtil.isEmpty(deliverGoodsBase.getDeliverDetailsList())) {
            throw new CheckedException("发货单号和发货明细不能为空");
        }
        List<WmsDeliverGoodsDetailsEntity> deliverDetailsList = deliverGoodsBase.getDeliverDetailsList();
        if (deliverDetailsList.stream().anyMatch(Objects::isNull)) {
            throw new CheckedException("发货明细不能包含空行");
        }
        //校验：计划只能同时存在一个发货单（关闭 和 完成 状态除外）//改为多次发货
        List<String> deliverPlanCodes = deliverDetailsList.stream().map(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode).distinct().collect(Collectors.toList());
        if (deliverPlanCodes.stream().anyMatch(StrUtil::isBlank)
                || deliverPlanCodes.size() != deliverDetailsList.size()) {
            throw new CheckedException("发货单须逐发货计划指定唯一明细，不能重复或缺少来源计划号");
        }
        List<WmsDeliverGoodsPlanBaseEntity> lockedPlans = this.list(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                .in(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, deliverPlanCodes)
                .orderByAsc(WmsDeliverGoodsPlanBaseEntity::getId).last("FOR UPDATE"));
        if (lockedPlans.size() != deliverPlanCodes.size()) {
            throw new CheckedException("发货计划缺失、重复或已被OMS撤回，请刷新后重试");
        }
        Map<String, WmsDeliverGoodsPlanBaseEntity> planByCode = lockedPlans.stream().collect(Collectors.toMap(
                WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, Function.identity(), (a, b) -> {
                    throw new CheckedException("发货计划号重复，禁止生成发货单");
                }));
        for (WmsDeliverGoodsDetailsEntity incoming : deliverDetailsList) {
            WmsDeliverGoodsPlanBaseEntity plan = planByCode.get(incoming.getDeliverPlanCode());
            if (plan == null || !(DeliverStateEnum.NOT_START.getCode().equals(plan.getDeliverState())
                    || DeliverStateEnum.CREATE.getCode().equals(plan.getDeliverState())
                    || DeliverStateEnum.ING.getCode().equals(plan.getDeliverState()))
                    || incoming.getDeliverNum() == null
                    || incoming.getDeliverNum().signum() <= 0
                    || !Objects.equals(incoming.getOrderCode(), plan.getOrderCode())
                    || !Objects.equals(incoming.getProductCode(), plan.getProductCode())
                    || !Objects.equals(incoming.getCustomerCode(), plan.getCustomerCode())) {
                throw new CheckedException("发货明细与当前计划的订单、客户、产品、状态或数量不一致");
            }
            if (plan.getDeliverNum() == null || plan.getDeliveredNum() == null
                    || incoming.getDeliverNum().compareTo(
                    plan.getDeliverNum().subtract(plan.getDeliveredNum())) > 0) {
                throw new CheckedException("发货单数量超过计划剩余可发量：" + plan.getDeliverPlanCode());
            }
            requireFactory(plan.getFactoryCode());
            incoming.setOrderCode(plan.getOrderCode());
            incoming.setCustomerCode(plan.getCustomerCode());
            incoming.setCustomerName(plan.getCustomerName());
            incoming.setProductCode(plan.getProductCode());
            incoming.setProductName(plan.getProductName());
            incoming.setProductSpec(plan.getProductSpec());
            incoming.setFactoryCode(plan.getFactoryCode());
        }
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


        // 未开始计划进入已下发；此前已部分发货的计划保持进行中，不倒退状态。
        for (WmsDeliverGoodsPlanBaseEntity plan : lockedPlans) {
            if (DeliverStateEnum.NOT_START.getCode().equals(plan.getDeliverState())) {
                plan.setDeliverState(DeliverStateEnum.CREATE.getCode());
                if (!this.updateById(plan)) throw new CheckedException("发货计划下发状态写回失败");
            }
        }
        // 2.根据计划单生产发货单及其明细信息，发货单初始状态为未开始，明细信息初始状态为进行中
        //主表
        deliverGoodsBase.setDeliverState(DeliverStateEnum.NOT_START.getCode());
        deliverGoodsBase.setReissueSign(YesOrNoStateEnum.NO.getCode());
        deliverGoodsBase.setIssued(YesOrNoStateEnum.NO.getCode());
        deliverGoodsBase.setSource(DeliverBaseSourceEnum.PLAN.getCode());
        deliverGoodsBase.setFactoryCode(deliverDetailsList.get(0).getFactoryCode());
        deliverGoodsBase.setCustomerCode(deliverDetailsList.get(0).getCustomerCode());
        deliverGoodsBase.setCustomerName(deliverDetailsList.get(0).getCustomerName());
        if (wmsDeliverGoodsBaseMapper.insert(deliverGoodsBase) != 1) {
            throw new CheckedException("发货单主记录保存失败");
        }
        //明细表
        deliverDetailsList.forEach(item -> {
            MdmMaterialEntity mdmMaterial = materialMap.get(item.getProductCode());
            item.setDeliverCode(deliverGoodsBase.getDeliverCode());
            //改为多次发货，已发货数量重置为0
            item.setDeliveredNum(BigDecimal.ZERO);
            item.setReturnNum(BigDecimal.ZERO);
            if (StrUtil.isBlank(item.getProductSpec())) {
                item.setProductSpec(ObjectUtil.isNotEmpty(mdmMaterial) ? mdmMaterial.getSpecificationModel() : null);
            }
            item.setProductUnit(ObjectUtil.isNotEmpty(mdmMaterial) ? mdmMaterial.getBasicUnit() : null);
        });
        if (!wmsDeliverGoodsDetailsService.saveBatch(deliverDetailsList)) {
            throw new CheckedException("发货单明细保存失败");
        }
        return Boolean.TRUE;
    }


}
