package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.WipTimeoutCancelCommand;
import com.kitex.mom.mes.dto.WipTimeoutHandleCommand;
import com.kitex.mom.mes.dto.WipTimeoutNotifyResult;
import com.kitex.mom.mes.dto.WipTimeoutVerifyCommand;
import com.kitex.mom.mes.entity.MesWipTimeoutConfigEntity;
import com.kitex.mom.mes.entity.MesWipTimeoutHandleEntity;
import com.kitex.mom.mes.entity.MesWipTimeoutOperationEntity;
import com.kitex.mom.mes.mapper.MesWipTimeoutHandleMapper;
import com.kitex.mom.mes.service.MesWipTimeoutHandleScanService;
import com.kitex.mom.mes.service.MesWipTimeoutHandleService;
import com.kitex.mom.mes.service.MesWipTimeoutConfigService;
import com.kitex.mom.mes.service.MesWipTimeoutNotifyService;
import com.kitex.mom.mes.service.MesWipTimeoutOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MesWipTimeoutHandleServiceImpl extends ServiceImpl<MesWipTimeoutHandleMapper, MesWipTimeoutHandleEntity>
        implements MesWipTimeoutHandleService {

    private static final String OPEN = "0";
    private static final String WAIT_VERIFY = "1";
    private static final String CLOSED = "2";
    private static final String CANCELLED = "9";
    private static final String PASS = "PASS";
    private static final String REJECT = "REJECT";
    private static final Set<String> DISPOSITIONS = Set.of(
            "EXPEDITE", "TRANSFER", "QUALITY_HOLD", "ENGINEERING_HOLD");

    private final MesWipTimeoutHandleScanService scanService;
    private final MesWipTimeoutOperationService operationService;
    private final MesWipTimeoutConfigService configService;
    private final MesWipTimeoutNotifyService notifyService;
    private final MesActualFactoryAccessService factoryAccess;

    @Override
    public IPage<MesWipTimeoutHandleEntity> queryPage(Page<MesWipTimeoutHandleEntity> page,
                                                       MesWipTimeoutHandleEntity query) {
        query.setFactoryCode(factoryAccess.require("read"));
        return baseMapper.queryPage(page, query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(WipTimeoutHandleCommand command) {
        require(command != null && command.getId() != null, "超时事件不能为空");
        require(StrUtil.isNotBlank(command.getRootCause()), "根本原因不能为空");
        require(StrUtil.isNotBlank(command.getCorrectiveAction()), "纠正或协调措施不能为空");
        require(DISPOSITIONS.contains(command.getDisposition()), "处置类型无效");
        require(command.getExpectedResolveTime() != null
                        && command.getExpectedResolveTime().isAfter(LocalDateTime.now()),
                "承诺解除时间必须晚于当前时间");
        require(StrUtil.isNotBlank(command.getHandleDesc()), "处置说明不能为空");

        MesWipTimeoutHandleEntity record = lock(command.getId());
        String requestId = StrUtil.blankToDefault(command.getRequestId(), IdUtil.fastSimpleUUID());
        if (hasOperation(record.getId(), requestId)) {
            return;
        }
        require(OPEN.equals(record.getStatus()), "仅待处理事件可以提交处置");
        MomUser user = SecurityUtils.getUser();
        require(StrUtil.isBlank(record.getAssignedUserCodes())
                        || csvContains(record.getAssignedUserCodes(), String.valueOf(user.getId())),
                "当前用户不在本事件已通知的处置人员中");

        String before = record.getStatus();
        record.setRootCause(command.getRootCause());
        record.setCorrectiveAction(command.getCorrectiveAction());
        record.setDisposition(command.getDisposition());
        record.setExpectedResolveTime(command.getExpectedResolveTime());
        record.setHandleDesc(command.getHandleDesc());
        record.setHandlePersonCode(user.getUsername());
        record.setHandlePersonName(user.getName());
        record.setHandleTime(LocalDateTime.now());
        record.setVerificationResult(null);
        record.setVerificationRemark(null);
        record.setStatus(WAIT_VERIFY);
        updateById(record);
        saveOperation(record, requestId, "HANDLE", before, WAIT_VERIFY,
                "根因：" + command.getRootCause() + "；措施：" + command.getCorrectiveAction()
                        + "；处置：" + command.getDisposition() + "；承诺时间：" + command.getExpectedResolveTime()
                        + "；说明：" + command.getHandleDesc());
        MesWipTimeoutConfigEntity config = configService.getOne(
                new LambdaQueryWrapper<MesWipTimeoutConfigEntity>()
                        .eq(MesWipTimeoutConfigEntity::getCode, record.getConfigCode()).last("limit 1"));
        if (config != null) {
            WipTimeoutNotifyResult result = notifyService.notifyVerification(config, record);
            record.setVerifierUserCodes(result.getRecipientCodes());
            record.setVerifierUserNames(result.getRecipientNames());
            updateById(record);
            saveOperation(record, IdUtil.fastSimpleUUID(), "NOTIFY_VERIFY", WAIT_VERIFY, WAIT_VERIFY,
                    "通道=" + StrUtil.nullToEmpty(result.getChannels()) + "；结果="
                            + StrUtil.nullToEmpty(result.getResult()) + "；说明="
                            + StrUtil.nullToEmpty(result.getDetail()));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void verify(WipTimeoutVerifyCommand command) {
        require(command != null && command.getId() != null, "超时事件不能为空");
        require(PASS.equals(command.getVerificationResult()) || REJECT.equals(command.getVerificationResult()),
                "验证结果必须为PASS或REJECT");
        require(StrUtil.isNotBlank(command.getVerificationRemark()), "验证说明不能为空");
        MesWipTimeoutHandleEntity record = lock(command.getId());
        String requestId = StrUtil.blankToDefault(command.getRequestId(), IdUtil.fastSimpleUUID());
        if (hasOperation(record.getId(), requestId)) {
            return;
        }
        require(WAIT_VERIFY.equals(record.getStatus()), "仅待验证事件可以验证");
        require(!PASS.equals(command.getVerificationResult())
                        || (StrUtil.isNotBlank(record.getRootCause())
                        && StrUtil.isNotBlank(record.getCorrectiveAction())
                        && record.getExpectedResolveTime() != null),
                "历史事件缺少根因、措施或承诺解除时间，请先驳回并重新处置");
        MomUser user = SecurityUtils.getUser();
        require(StrUtil.isBlank(record.getVerifierUserCodes())
                        || csvContains(record.getVerifierUserCodes(), String.valueOf(user.getId())),
                "当前用户不在本事件验证人员中");
        String before = record.getStatus();
        LocalDateTime now = LocalDateTime.now();
        record.setVerificationResult(command.getVerificationResult());
        record.setVerificationRemark(command.getVerificationRemark());
        record.setVerifierCode(user.getUsername());
        record.setVerifierName(user.getName());
        record.setVerificationTime(now);
        if (PASS.equals(command.getVerificationResult())) {
            record.setStatus(CLOSED);
            record.setCloseTime(now);
            record.setCloseReason("处置验证通过；承诺时间前抑制重复报警，逾期仍在制将自动重开");
        } else {
            record.setStatus(OPEN);
            record.setHandlePersonCode(null);
            record.setHandlePersonName(null);
            record.setHandleTime(null);
            record.setHandleDesc(null);
            record.setRootCause(null);
            record.setCorrectiveAction(null);
            record.setDisposition(null);
            record.setExpectedResolveTime(null);
            record.setCloseTime(null);
            record.setCloseReason(null);
        }
        updateById(record);
        saveOperation(record, requestId, PASS.equals(command.getVerificationResult()) ? "VERIFY_PASS" : "VERIFY_REJECT",
                before, record.getStatus(), command.getVerificationRemark());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(WipTimeoutCancelCommand command) {
        require(command != null && command.getId() != null, "超时事件不能为空");
        require(StrUtil.isNotBlank(command.getReason()), "取消原因不能为空");
        MesWipTimeoutHandleEntity record = lock(command.getId());
        String requestId = StrUtil.blankToDefault(command.getRequestId(), IdUtil.fastSimpleUUID());
        if (hasOperation(record.getId(), requestId)) {
            return;
        }
        require(OPEN.equals(record.getStatus()) || WAIT_VERIFY.equals(record.getStatus()),
                "仅活动事件可以取消");
        String before = record.getStatus();
        record.setStatus(CANCELLED);
        record.setCloseTime(LocalDateTime.now());
        record.setCloseReason(command.getReason());
        updateById(record);
        saveOperation(record, requestId, "CANCEL", before, CANCELLED, command.getReason());
    }

    @Override
    public List<MesWipTimeoutOperationEntity> operations(Long id) {
        require(id != null, "超时事件不能为空");
        String factory = factoryAccess.require("read");
        require(getOne(new LambdaQueryWrapper<MesWipTimeoutHandleEntity>().eq(MesWipTimeoutHandleEntity::getId,id)
                .eq(MesWipTimeoutHandleEntity::getFactoryCode,factory)) != null,"当前工厂超时事件不存在");
        return operationService.list(new LambdaQueryWrapper<MesWipTimeoutOperationEntity>()
                .eq(MesWipTimeoutOperationEntity::getHandleId, id)
                .eq(MesWipTimeoutOperationEntity::getFactoryCode, factory)
                .orderByAsc(MesWipTimeoutOperationEntity::getOperationTime)
                .orderByAsc(MesWipTimeoutOperationEntity::getId));
    }

    @Override
    public void scanNow() {
        factoryAccess.require("write");
        scanService.scanAndEscalate();
    }

    private MesWipTimeoutHandleEntity lock(Long id) {
        String factory = factoryAccess.require("write");
        MesWipTimeoutHandleEntity record = getOne(new LambdaQueryWrapper<MesWipTimeoutHandleEntity>()
                .eq(MesWipTimeoutHandleEntity::getId, id).eq(MesWipTimeoutHandleEntity::getFactoryCode, factory).last("FOR UPDATE"));
        if (record == null) {
            throw new CheckedException("在制超时事件不存在");
        }
        return record;
    }

    private boolean hasOperation(Long id, String requestId) {
        return operationService.count(new LambdaQueryWrapper<MesWipTimeoutOperationEntity>()
                .eq(MesWipTimeoutOperationEntity::getHandleId, id)
                .eq(MesWipTimeoutOperationEntity::getRequestId, requestId)) > 0;
    }

    private void saveOperation(MesWipTimeoutHandleEntity record, String requestId, String operationType,
                               String before, String after, String content) {
        MomUser user = SecurityUtils.getUser();
        var operation = new MesWipTimeoutOperationEntity()
                .setRequestId(requestId)
                .setHandleId(record.getId())
                .setIncidentKey(record.getIncidentKey())
                .setOperationType(operationType)
                .setBeforeStatus(before)
                .setAfterStatus(after)
                .setContent(StrUtil.subPre(StrUtil.nullToEmpty(content), 2000))
                .setOperatorCode(user.getUsername())
                .setOperatorName(user.getName())
                .setOperationTime(LocalDateTime.now()).setFactoryCode(record.getFactoryCode());
        operation.setTenantId(record.getTenantId());
        operationService.save(operation);
    }

    private boolean csvContains(String csv, String value) {
        return Arrays.stream(csv.split(",")).map(String::trim).anyMatch(item -> Objects.equals(item, value));
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
