package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.wms.dto.WmsOmsDeliveryWithdrawalCommand;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.entity.WmsOmsDeliveryWithdrawalEntity;
import com.kitex.mom.wms.mapper.WmsOmsDeliveryWithdrawalMapper;
import com.kitex.mom.wms.service.WmsDeliverGoodsPlanBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * OMS来源WMS撤回的永久幂等执行回执；计划删除与回执同一WMS事务提交。
 */
@Service
@RequiredArgsConstructor
public class WmsOmsDeliveryWithdrawalService {
    private final WmsOmsDeliveryWithdrawalMapper mapper;
    private final WmsDeliverGoodsPlanBaseService planService;

    @Transactional(rollbackFor = Exception.class)
    public WmsOmsDeliveryWithdrawalCommand.Receipt withdraw(WmsOmsDeliveryWithdrawalCommand.Command command) {
        if (command == null || command.getExpectedPlan() == null
                || command.getExpectedPlan().getId() == null
                || command.getExpectedPlan().getOrderCodeDetails() == null
                || StrUtil.isBlank(command.getExpectedPlan().getFactoryCode())) {
            throw new CheckedException("OMS撤回WMS计划必须指定原计划ID、来源订单行和工厂");
        }
        String requestId = required(command.getRequestId(), 64, "OMS-WMS撤回请求号");
        String reason = required(command.getReason(), 500, "OMS-WMS撤回原因");
        WmsDeliverGoodsPlanBaseEntity expected = command.getExpectedPlan();
        String before = facts(expected);
        String fingerprint = hash(before + "|" + reason);
        mapper.lockKey("REQUEST:" + requestId);
        WmsOmsDeliveryWithdrawalEntity old = mapper.selectOne(
                new LambdaQueryWrapper<WmsOmsDeliveryWithdrawalEntity>()
                        .eq(WmsOmsDeliveryWithdrawalEntity::getRequestId, requestId));
        if (old != null) {
            require(Objects.equals(old.getRequestFingerprint(), fingerprint)
                            && Objects.equals(old.getBeforeSnapshot(), before),
                    "OMS-WMS撤回请求号已被不同计划事实或原因使用");
            requireFactory(old.getFactoryCode());
            require(planService.getById(old.getPlanId()) == null,
                    "WMS撤回回执对应的发货计划仍为活动状态，禁止将旧回执用于OMS完结");
            return receipt(old);
        }
        requireFactory(expected.getFactoryCode());
        WmsDeliverGoodsPlanBaseEntity current = planService.getOne(
                new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                        .eq(WmsDeliverGoodsPlanBaseEntity::getId, expected.getId())
                        .last("FOR UPDATE"), false);
        require(current != null && Objects.equals(facts(current), before),
                "WMS发货计划已变更或不存在，禁止按旧OMS证据撤回");
        require(Boolean.TRUE.equals(planService.removeFromOms(new java.util.ArrayList<>(
                java.util.List.of(current.getId())))), "WMS发货计划撤回失败");
        WmsOmsDeliveryWithdrawalEntity saved = new WmsOmsDeliveryWithdrawalEntity()
                .setRequestId(requestId).setRequestFingerprint(fingerprint)
                .setPlanId(current.getId()).setOrderCode(current.getOrderCode())
                .setOrderDetailId(current.getOrderCodeDetails()).setPlanCode(current.getDeliverPlanCode())
                .setBeforeSnapshot(before).setReason(reason).setStatus("SUCCEEDED")
                .setCompletedTime(LocalDateTime.now()).setFactoryCode(current.getFactoryCode());
        require(mapper.insert(saved) == 1, "WMS撤回永久回执保存失败");
        return receipt(saved);
    }

    private String facts(WmsDeliverGoodsPlanBaseEntity plan) {
        return JSONUtil.toJsonStr(Arrays.asList(plan.getId(), plan.getOrderCode(),
                plan.getOrderCodeDetails(), plan.getFactoryCode(), plan.getDeliverPlanCode(),
                plan.getCustomerCode(), plan.getCustomerName(), plan.getProductCode(),
                plan.getProductName(), plan.getProductSpec(), plan.getPlanDeliverTime(),
                quantity(plan.getDeliverNum()), quantity(plan.getDeliveredNum()),
                quantity(plan.getReturnNum()), quantity(plan.getReissueNum()),
                plan.getDeliverState(), plan.getCustomerWarehouse(), plan.getCustomerAddress(),
                plan.getCustomerContact(), plan.getCustomerPhone()));
    }

    private String quantity(BigDecimal number) {
        return number == null ? null : number.stripTrailingZeros().toPlainString();
    }

    private WmsOmsDeliveryWithdrawalCommand.Receipt receipt(WmsOmsDeliveryWithdrawalEntity entity) {
        return new WmsOmsDeliveryWithdrawalCommand.Receipt()
                .setRequestId(entity.getRequestId()).setPlanId(entity.getPlanId())
                .setOrderCode(entity.getOrderCode()).setOrderDetailId(entity.getOrderDetailId())
                .setStatus(entity.getStatus()).setRequestFingerprint(entity.getRequestFingerprint())
                .setCompletedTime(entity.getCompletedTime());
    }

    private String required(String value, int max, String label) {
        String trimmed = value == null ? null : value.trim();
        require(StrUtil.isNotBlank(trimmed) && trimmed.length() <= max, label + "不能为空且不能超过" + max + "字");
        return trimmed;
    }

    private void requireFactory(String factoryCode) {
        String selected = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factoryCode)
                        && (StrUtil.isBlank(selected) || Objects.equals(selected, factoryCode)),
                "当前工厂无权撤回该WMS发货计划");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("WMS撤回事实指纹生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
