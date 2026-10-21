package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.oms.entity.OmsDeliveryPromiseEntity;
import com.kitex.mom.oms.entity.OmsDeliveryPromiseOperationEntity;
import com.kitex.mom.oms.entity.OmsOrderChangeVersionEntity;
import com.kitex.mom.plan.mapper.OmsDeliveryPromiseMapper;
import com.kitex.mom.plan.mapper.OmsDeliveryPromiseOperationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 将OMS受控订单变更终态可靠投影回交期答复单。
 *
 * <p>该组件只依赖Mapper，避免订单评审、订单跟踪和交期答复Service之间形成循环引用。</p>
 */
@Service
@RequiredArgsConstructor
public class OmsDeliveryPromiseOutcomeProjector {
    private final OmsDeliveryPromiseMapper promiseMapper;
    private final OmsDeliveryPromiseOperationMapper operationMapper;

    public void project(OmsOrderChangeVersionEntity version) {
        if (version == null || version.getId() == null || StrUtil.isBlank(version.getStatus())) {
            return;
        }
        OmsDeliveryPromiseEntity promise = promiseMapper.selectOne(
                new LambdaQueryWrapper<OmsDeliveryPromiseEntity>()
                        .eq(OmsDeliveryPromiseEntity::getChangeVersionId, version.getId())
                        .last("FOR UPDATE"));
        if (promise == null || Objects.equals(promise.getOrderChangeStatus(), version.getStatus())) {
            return;
        }
        String requestId = "DP-SYS-OUTCOME-" + version.getId() + "-" + version.getStatus();
        if (operationMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryPromiseOperationEntity>()
                .eq(OmsDeliveryPromiseOperationEntity::getRequestId, requestId)) > 0) {
            return;
        }
        String before = promise.getStatus();
        String after = switch (version.getStatus()) {
            case "APPROVED" -> "READY_TO_REPLY";
            case "REJECTED", "CANCELLED" -> "ORDER_CHANGE_REJECTED";
            case "SUPERSEDED" -> "STALE";
            default -> before;
        };
        promise.setOrderChangeStatus(version.getStatus()).setStatus(after);
        if ("READY_TO_REPLY".equals(after)) {
            promise.setReadyTime(LocalDateTime.now());
        }
        require(promiseMapper.updateById(promise) == 1, "订单变更结果回投交期答复失败");

        String operatorCode = StrUtil.blankToDefault(version.getReviewerCode(), version.getApplicantCode());
        String operatorName = StrUtil.blankToDefault(version.getReviewerName(), version.getApplicantName());
        OmsDeliveryPromiseOperationEntity operation = new OmsDeliveryPromiseOperationEntity()
                .setRequestId(requestId)
                .setRequestFingerprint(hash(version.getId() + "|" + version.getStatus()))
                .setPromiseId(promise.getId())
                .setOperationType("ORDER_CHANGE_" + version.getStatus())
                .setBeforeStatus(before)
                .setAfterStatus(after)
                .setContent(StrUtil.blankToDefault(version.getReviewRemark(), "订单变更状态同步"))
                .setOperatorCode(StrUtil.blankToDefault(operatorCode, "SYSTEM"))
                .setOperatorName(StrUtil.blankToDefault(operatorName, "系统"))
                .setOperationTime(LocalDateTime.now())
                .setFactoryCode(promise.getFactoryCode());
        operation.setTenantId(promise.getTenantId());
        require(operationMapper.insert(operation) == 1, "订单变更结果回投履历保存失败");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("交期答复结果指纹生成失败", exception);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
