package com.kitex.mom.mes.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.constant.enums.MessageTypeEnum;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.mes.entity.MesAndonHandleConfigStaffEntity;
import com.kitex.mom.mes.entity.MesAndonNoticeEntity;
import com.kitex.mom.mes.entity.MesAndonOperationEntity;
import com.kitex.mom.mes.entity.MesAndonRecordEntity;
import com.kitex.mom.mes.enums.AndonNoticeTypeEnum;
import com.kitex.mom.mes.enums.AndonStateEnum;
import com.kitex.mom.mes.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MesAndonSlaScanServiceImpl implements MesAndonSlaScanService {

    private static final int BATCH_SIZE = 100;
    private static final String MES = "MES";

    private final MesAndonRecordService recordService;
    private final MesAndonHandleConfigStaffService staffService;
    private final MesAndonNoticeService noticeService;
    private final MesAndonOperationService operationService;
    private final RemoteMessagePushService messageService;
    private final RemoteUserService remoteUserService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void scanAndEscalate() {
        LocalDateTime now = LocalDateTime.now();
        List<MesAndonRecordEntity> dueRecords = recordService.page(new Page<>(1, BATCH_SIZE),
                new LambdaQueryWrapper<MesAndonRecordEntity>()
                        .in(MesAndonRecordEntity::getState, AndonStateEnum.UN_RECEIVE.getCode(),
                                AndonStateEnum.UP_UN_RECEIVE.getCode(), AndonStateEnum.UN_HANDLE.getCode(),
                                AndonStateEnum.UP_UN_HANDLE.getCode())
                        .isNotNull(MesAndonRecordEntity::getNextEscalationTime)
                        .le(MesAndonRecordEntity::getNextEscalationTime, now)
                        .orderByAsc(MesAndonRecordEntity::getNextEscalationTime)).getRecords();

        int escalated = 0;
        for (MesAndonRecordEntity candidate : dueRecords) {
            MesAndonRecordEntity record = recordService.getOne(new LambdaQueryWrapper<MesAndonRecordEntity>()
                    .eq(MesAndonRecordEntity::getId, candidate.getId()).last("FOR UPDATE"));
            if (record == null || record.getNextEscalationTime() == null
                    || record.getNextEscalationTime().isAfter(now) || !isActive(record.getState())) {
                continue;
            }
            if (escalate(record, now)) {
                escalated++;
            }
        }
        log.info("安灯SLA扫描完成, 到期数={}, 升级数={}", dueRecords.size(), escalated);
    }

    private boolean escalate(MesAndonRecordEntity record, LocalDateTime now) {
        List<MesAndonHandleConfigStaffEntity> steps = staffService.list(
                new LambdaQueryWrapper<MesAndonHandleConfigStaffEntity>()
                        .eq(MesAndonHandleConfigStaffEntity::getConfigCode, record.getConfigCode())
                        .eq(MesAndonHandleConfigStaffEntity::getTenantId, record.getTenantId())
                        .eq(MesAndonHandleConfigStaffEntity::getLevel, record.getLevel()));
        steps.sort(Comparator.comparingInt(item -> parseStep(item.getSort())));
        int currentStep = record.getEscalationStep() == null ? 0 : record.getEscalationStep();
        MesAndonHandleConfigStaffEntity next = steps.stream()
                .filter(item -> parseStep(item.getSort()) > currentStep).findFirst().orElse(null);
        if (next == null) {
            record.setNextEscalationTime(null);
            recordService.updateById(record);
            return false;
        }

        int nextStep = parseStep(next.getSort());
        int nextCount = (record.getEscalationCount() == null ? 0 : record.getEscalationCount()) + 1;
        String requestId = "SLA-" + record.getCode() + "-" + nextStep + "-" + nextCount;
        boolean alreadyDone = operationService.count(new LambdaQueryWrapper<MesAndonOperationEntity>()
                .eq(MesAndonOperationEntity::getRecordId, record.getId())
                .eq(MesAndonOperationEntity::getRequestId, requestId)) > 0;
        if (alreadyDone) {
            record.setEscalationStep(nextStep);
            setNextTime(record, steps, nextStep, now);
            recordService.updateById(record);
            return false;
        }

        String before = record.getState();
        record.setState(isUnreceived(before) ? AndonStateEnum.UP_UN_RECEIVE.getCode()
                : AndonStateEnum.UP_UN_HANDLE.getCode());
        record.setEscalationStep(nextStep);
        record.setEscalationCount(nextCount);
        record.setHandlerCode(mergeCsv(record.getHandlerCode(), next.getStaffCode()));
        record.setHandlerName(mergeCsv(record.getHandlerName(), next.getStaffName()));
        setNextTime(record, steps, nextStep, now);
        recordService.updateById(record);
        createNotice(record, next, now);

        var operation = new MesAndonOperationEntity()
                .setRequestId(requestId)
                .setRecordId(record.getId())
                .setRecordCode(record.getCode())
                .setOperationType("ESCALATE")
                .setBeforeState(before)
                .setAfterState(record.getState())
                .setContent("SLA超时升级至第" + nextStep + "级：" + StrUtil.nullToEmpty(next.getStaffName()))
                .setOperatorCode("SYSTEM")
                .setOperatorName("安灯SLA任务")
                .setOperationTime(now).setFactoryCode(record.getFactoryCode());
        operation.setTenantId(record.getTenantId());
        operationService.save(operation);
        safeSend("安灯超时升级通知：\n流程编号：" + record.getCode() + "\n异常类型："
                + record.getTypeName() + "\n当前升级层级：" + nextStep + "\n请立即协调处理", next.getStaffCode());
        return true;
    }

    private void setNextTime(MesAndonRecordEntity record, List<MesAndonHandleConfigStaffEntity> steps,
                             int currentStep, LocalDateTime now) {
        boolean hasHigherStep = steps.stream().anyMatch(item -> parseStep(item.getSort()) > currentStep);
        MesAndonHandleConfigStaffEntity current = steps.stream()
                .filter(item -> parseStep(item.getSort()) == currentStep).findFirst().orElse(null);
        if (!hasHigherStep || current == null || current.getLimitTime() == null || current.getLimitTime() <= 0) {
            record.setNextEscalationTime(null);
            return;
        }
        record.setNextEscalationTime(now.plusMinutes(current.getLimitTime()));
    }

    private void createNotice(MesAndonRecordEntity record, MesAndonHandleConfigStaffEntity staff,
                              LocalDateTime now) {
        List<String> codes = splitCsv(staff.getStaffCode());
        List<String> names = splitCsv(staff.getStaffName());
        for (int i = 0; i < codes.size(); i++) {
            String staffCode = codes.get(i);
            if (noticeService.count(new LambdaQueryWrapper<MesAndonNoticeEntity>()
                    .eq(MesAndonNoticeEntity::getRecordCode, record.getCode())
                    .eq(MesAndonNoticeEntity::getStaffCode, staffCode)
                    .eq(MesAndonNoticeEntity::getNoticeType, AndonNoticeTypeEnum.NOTICE.getCode())) > 0) {
                continue;
            }
            MesAndonNoticeEntity notice = new MesAndonNoticeEntity();
            notice.setTenantId(record.getTenantId());
            notice.setFactoryCode(record.getFactoryCode());
            notice.setRecordCode(record.getCode());
            notice.setStaffCode(staffCode);
            notice.setStaffName(i < names.size() ? names.get(i) : staffCode);
            notice.setNoticeTime(now);
            notice.setNoticeType(AndonNoticeTypeEnum.NOTICE.getCode());
            noticeService.save(notice);
        }
    }

    private String mergeCsv(String left, String right) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        splitCsv(left).forEach(value -> values.put(value, true));
        splitCsv(right).forEach(value -> values.put(value, true));
        return String.join(",", values.keySet());
    }

    private List<String> splitCsv(String value) {
        if (StrUtil.isBlank(value)) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank).toList();
    }

    private int parseStep(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private boolean isActive(String state) {
        return isUnreceived(state) || AndonStateEnum.UN_HANDLE.getCode().equals(state)
                || AndonStateEnum.UP_UN_HANDLE.getCode().equals(state);
    }

    private boolean isUnreceived(String state) {
        return AndonStateEnum.UN_RECEIVE.getCode().equals(state)
                || AndonStateEnum.UP_UN_RECEIVE.getCode().equals(state);
    }

    private void safeSend(String message, String users) {
        try {
            List<Long> userIds = splitCsv(users).stream().map(value -> {
                try {
                    return Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }).filter(Objects::nonNull).distinct().toList();
            if (CollUtil.isEmpty(userIds)) {
                return;
            }
            R<List<SysUserEntity>> response = remoteUserService.getUserByIdInner(userIds);
            List<SysUserEntity> usersFound = response == null ? null : response.getData();
            if (CollUtil.isEmpty(usersFound)) {
                throw new IllegalStateException("未查到消息接收人");
            }
            MessageDTO dto = new MessageDTO();
            dto.setCode(MES);
            dto.setMessageType(MessageTypeEnum.TEXT.getType());
            dto.setUserList(userIds);
            dto.setUserIds(usersFound.stream().map(SysUserEntity::getWxDingUserid)
                    .filter(StrUtil::isNotBlank).collect(Collectors.joining(",")));
            dto.setMessage(message);
            messageService.pushPersonMsg(dto);
        } catch (Exception ex) {
            log.error("安灯SLA升级消息推送失败, users={}", users, ex);
        }
    }
}
