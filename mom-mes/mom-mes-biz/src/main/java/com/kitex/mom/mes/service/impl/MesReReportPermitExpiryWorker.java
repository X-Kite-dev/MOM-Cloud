package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesReportReverseOperationEntity;
import com.kitex.mom.mes.entity.MesReportReverseRequestEntity;
import com.kitex.mom.mes.mapper.MesReportReverseOperationMapper;
import com.kitex.mom.mes.mapper.MesReportReverseRequestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 在独立事务内关闭一张到期的聚合报工重报许可，并保存不可变系统履历。
 */
@Service
@RequiredArgsConstructor
public class MesReReportPermitExpiryWorker {

    private final MesReportReverseRequestMapper requestMapper;
    private final MesReportReverseOperationMapper operationMapper;

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean expireOne(Long requestId, LocalDateTime observedTime) {
        MesReportReverseRequestEntity request = requestMapper.selectOne(
                new LambdaQueryWrapper<MesReportReverseRequestEntity>()
                        .eq(MesReportReverseRequestEntity::getId, requestId)
                        .eq(MesReportReverseRequestEntity::getStatus, "APPROVED")
                        .eq(MesReportReverseRequestEntity::getReReportPermitStatus, "ACTIVE")
                        .le(MesReportReverseRequestEntity::getReReportExpireTime, observedTime)
                        .last("FOR UPDATE"));
        if (request == null) {
            return false;
        }
        request.setReReportPermitStatus("EXPIRED");
        if (requestMapper.updateById(request) != 1) {
            throw new CheckedException("受控重报许可到期状态保存失败");
        }

        String operationRequestId = "RRP-EXP-" + request.getId();
        String fingerprint = hash(request.getId() + "|" + request.getReReportPermitNo() + "|"
                + request.getReReportAllowedQuantity() + "|" + request.getReReportUsedQuantity() + "|"
                + request.getReReportExpireTime());
        MesReportReverseOperationEntity operation = new MesReportReverseOperationEntity()
                .setReverseRequestId(request.getId()).setRequestId(operationRequestId)
                .setRequestFingerprint(fingerprint).setOperationType("PERMIT_EXPIRE")
                .setBeforeStatus("ACTIVE").setAfterStatus("EXPIRED")
                .setContent("受控重报许可到期，许可号=" + request.getReReportPermitNo()
                        + "，允许数量=" + request.getReReportAllowedQuantity()
                        + "，已使用数量=" + request.getReReportUsedQuantity()
                        + "，到期时间=" + request.getReReportExpireTime())
                .setOperatorId(0L).setOperatorCode("SYSTEM").setOperatorName("系统到期任务")
                .setOperationTime(observedTime).setFactoryCode(request.getFactoryCode());
        operation.setTenantId(request.getTenantId());
        if (operationMapper.insert(operation) != 1) {
            throw new CheckedException("受控重报许可到期履历保存失败");
        }
        return true;
    }

    private String hash(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("生成受控重报许可到期指纹失败", exception);
        }
    }
}
