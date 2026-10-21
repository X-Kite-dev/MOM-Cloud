package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.wms.dto.WmsOmsDeliveryIssueCommand;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.entity.WmsOmsDeliveryIssueEntity;
import com.kitex.mom.wms.mapper.WmsOmsDeliveryIssueMapper;
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
import java.util.List;
import java.util.Objects;

/**
 * 发货计划创建/同步与永久回执共用WMS事务；OMS可用原号重放不猜远端结果。
 */
@Service
@RequiredArgsConstructor
public class WmsOmsDeliveryIssueService {
    private final WmsOmsDeliveryIssueMapper mapper;
    private final WmsDeliverGoodsPlanBaseService planService;

    @Transactional(rollbackFor = Exception.class)
    public WmsOmsDeliveryIssueCommand.Receipt issue(WmsOmsDeliveryIssueCommand.Command command) {
        require(command != null && command.getDesiredPlan() != null, "OMS下发WMS必须提供目标计划");
        String requestId = required(command.getRequestId(), 64, "OMS-WMS下发请求号");
        String action = required(command.getAction(), 10, "OMS-WMS下发动作");
        require("CREATE".equals(action) || "UPDATE".equals(action), "OMS-WMS仅支持创建或同步发货计划");
        WmsDeliverGoodsPlanBaseEntity desired = command.getDesiredPlan();
        WmsDeliverGoodsPlanBaseEntity expected = command.getExpectedPlan();
        require(StrUtil.isNotBlank(desired.getOrderCode()) && desired.getOrderCodeDetails() != null
                && StrUtil.isNotBlank(desired.getFactoryCode())
                && StrUtil.isNotBlank(desired.getDeliverPlanCode()), "OMS-WMS下发缺少订单行、工厂或计划号");
        requireFactory(desired.getFactoryCode());
        require("CREATE".equals(action) ? expected == null && desired.getId() == null
                        : expected != null && expected.getId() != null
                        && Objects.equals(desired.getId(), expected.getId())
                        && Objects.equals(desired.getOrderCode(), expected.getOrderCode())
                        && Objects.equals(desired.getOrderCodeDetails(), expected.getOrderCodeDetails())
                        && Objects.equals(desired.getFactoryCode(), expected.getFactoryCode())
                        && Objects.equals(desired.getDeliverPlanCode(), expected.getDeliverPlanCode()),
                "OMS-WMS创建/同步命令的原计划来源不一致");
        String before = expected == null ? null : facts(expected);
        String desiredFacts = facts(desired);
        String fingerprint = hash(action + "|" + before + "|" + desiredFacts);
        mapper.lockKey("REQUEST:" + requestId);
        WmsOmsDeliveryIssueEntity old = mapper.selectOne(new LambdaQueryWrapper<WmsOmsDeliveryIssueEntity>()
                .eq(WmsOmsDeliveryIssueEntity::getRequestId, requestId));
        if (old != null) {
            require(Objects.equals(old.getRequestFingerprint(), fingerprint)
                            && Objects.equals(old.getBeforeSnapshot(), before)
                            && Objects.equals(old.getDesiredSnapshot(), desiredFacts),
                    "OMS-WMS下发请求号已被不同计划事实使用");
            requireFactory(old.getFactoryCode());
            return receipt(old);
        }
        if ("CREATE".equals(action)) {
            require(Boolean.TRUE.equals(planService.saveFromOms(List.of(desired))), "WMS发货计划创建失败");
        } else {
            WmsDeliverGoodsPlanBaseEntity current = planService.getOne(
                    new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                            .eq(WmsDeliverGoodsPlanBaseEntity::getId, expected.getId()).last("FOR UPDATE"), false);
            require(current != null && Objects.equals(facts(current), before),
                    "WMS来源计划已变化，禁止按旧OMS快照同步");
            require(Boolean.TRUE.equals(planService.updateFromOms(List.of(desired))), "WMS发货计划同步失败");
        }
        List<WmsDeliverGoodsPlanBaseEntity> rows = planService.list(
                new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                        .eq(WmsDeliverGoodsPlanBaseEntity::getOrderCode, desired.getOrderCode())
                        .eq(WmsDeliverGoodsPlanBaseEntity::getOrderCodeDetails, desired.getOrderCodeDetails())
                        .last("FOR UPDATE"));
        require(rows.size() == 1 && Objects.equals(rows.get(0).getFactoryCode(), desired.getFactoryCode())
                        && Objects.equals(rows.get(0).getDeliverPlanCode(), desired.getDeliverPlanCode()),
                "OMS-WMS下发后来源计划回读不唯一或计划号已变化");
        WmsDeliverGoodsPlanBaseEntity after = rows.get(0);
        WmsOmsDeliveryIssueEntity saved = new WmsOmsDeliveryIssueEntity()
                .setRequestId(requestId).setRequestFingerprint(fingerprint).setAction(action)
                .setBeforeSnapshot(before).setDesiredSnapshot(desiredFacts)
                .setAfterSnapshot(facts(after)).setPlanId(after.getId())
                .setOrderCode(after.getOrderCode()).setOrderDetailId(after.getOrderCodeDetails())
                .setPlanCode(after.getDeliverPlanCode()).setStatus("SUCCEEDED")
                .setCompletedTime(LocalDateTime.now()).setFactoryCode(after.getFactoryCode());
        require(mapper.insert(saved) == 1, "WMS下发永久回执保存失败");
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

    private WmsOmsDeliveryIssueCommand.Receipt receipt(WmsOmsDeliveryIssueEntity entity) {
        return new WmsOmsDeliveryIssueCommand.Receipt().setRequestId(entity.getRequestId())
                .setAction(entity.getAction()).setPlanId(entity.getPlanId())
                .setOrderCode(entity.getOrderCode()).setOrderDetailId(entity.getOrderDetailId())
                .setPlanCode(entity.getPlanCode()).setRequestFingerprint(entity.getRequestFingerprint())
                .setStatus(entity.getStatus()).setCompletedTime(entity.getCompletedTime());
    }

    private String required(String value, int max, String label) {
        String trimmed = value == null ? null : value.trim();
        require(StrUtil.isNotBlank(trimmed) && trimmed.length() <= max,
                label + "不能为空且不能超过" + max + "字");
        return trimmed;
    }

    private void requireFactory(String factoryCode) {
        String selected = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factoryCode)
                        && (StrUtil.isBlank(selected) || Objects.equals(selected, factoryCode)),
                "当前工厂无权下发该WMS发货计划");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("WMS下发事实指纹生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
