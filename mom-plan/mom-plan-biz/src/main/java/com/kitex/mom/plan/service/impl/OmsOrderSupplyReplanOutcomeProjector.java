package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.oms.entity.OmsOrderChangeVersionEntity;
import com.kitex.mom.oms.entity.OmsOrderSupplyReplanEntity;
import com.kitex.mom.oms.entity.OmsOrderSupplyReplanOperationEntity;
import com.kitex.mom.plan.mapper.OmsOrderSupplyReplanMapper;
import com.kitex.mom.plan.mapper.OmsOrderSupplyReplanOperationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * 把OMS订单变更的驳回、作废和取代终态同步回供应级联主账，避免遗留可误执行申请。
 */
@Service
@RequiredArgsConstructor
public class OmsOrderSupplyReplanOutcomeProjector {
    private static final Set<String> ACTIVE = Set.of(
            "SUBMITTED", "APPROVED", "EXECUTING", "FAILED", "READY_FOR_FINAL_REVIEW", "READY_FOR_REISSUE");

    private final OmsOrderSupplyReplanMapper replanMapper;
    private final OmsOrderSupplyReplanOperationMapper operationMapper;

    public void project(OmsOrderChangeVersionEntity version) {
        if (version == null || !Set.of("REJECTED", "CANCELLED", "SUPERSEDED").contains(version.getStatus())) return;
        OmsOrderSupplyReplanEntity replan = replanMapper.selectOne(
                new LambdaQueryWrapper<OmsOrderSupplyReplanEntity>()
                        .eq(OmsOrderSupplyReplanEntity::getOrderChangeVersionId, version.getId())
                        .in(OmsOrderSupplyReplanEntity::getStatus, ACTIVE)
                        .last("FOR UPDATE"));
        if (replan == null) return;
        String target = "SUPERSEDED".equals(version.getStatus()) ? "STALE" : "CANCELLED";
        String requestId = "OSR-SYS-OUTCOME-" + replan.getId() + '-' + version.getStatus();
        String fingerprint = hash(version.getId() + "|" + version.getStatus() + "|" + target);
        OmsOrderSupplyReplanOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<OmsOrderSupplyReplanOperationEntity>()
                        .eq(OmsOrderSupplyReplanOperationEntity::getRequestId, requestId));
        if (replay != null) {
            require(Objects.equals(replay.getReplanId(), replan.getId())
                            && Objects.equals(replay.getRequestFingerprint(), fingerprint),
                    "订单变更终态回投请求号冲突");
            return;
        }
        String before = replan.getStatus();
        replan.setStatus(target).setLastError("订单变更版本已进入" + version.getStatus() + "终态，级联申请停止执行");
        require(replanMapper.updateById(replan) == 1, "订单变更终态回投供应级联主账失败");
        String operatorCode = StrUtil.blankToDefault(version.getReviewerCode(), version.getApplicantCode());
        String operatorName = StrUtil.blankToDefault(version.getReviewerName(), version.getApplicantName());
        OmsOrderSupplyReplanOperationEntity operation = new OmsOrderSupplyReplanOperationEntity()
                .setReplanId(replan.getId()).setRequestId(requestId).setRequestFingerprint(fingerprint)
                .setOperationType("ORDER_CHANGE_" + version.getStatus()).setBeforeStatus(before).setAfterStatus(target)
                .setContent(replan.getLastError()).setOperatorCode(operatorCode).setOperatorName(operatorName)
                .setOperationTime(LocalDateTime.now()).setFactoryCode(replan.getFactoryCode());
        operation.setTenantId(replan.getTenantId());
        require(operationMapper.insert(operation) == 1, "订单变更终态回投供应级联履历失败");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("供应级联终态回投指纹生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
