package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesAutoReportEventDTO;
import com.kitex.mom.mes.dto.MesAutoReportResultDTO;
import com.kitex.mom.mes.dto.MesAutoReportRetryDTO;
import com.kitex.mom.mes.entity.MesAutoReportEventEntity;
import com.kitex.mom.mes.enums.AutoReportEventStatusEnum;
import com.kitex.mom.mes.mapper.MesAutoReportEventMapper;
import com.kitex.mom.mes.service.MesAutoReportCaptureService;
import com.kitex.mom.mes.service.MesAutoReportEventService;
import com.kitex.mom.mes.service.MesAutoReportProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MesAutoReportEventServiceImpl extends ServiceImpl<MesAutoReportEventMapper, MesAutoReportEventEntity>
        implements MesAutoReportEventService {

    private final MesAutoReportProcessor processor;
    private final MesActualFactoryAccessService access;
    private final MesAutoReportCaptureService captures;

    @Override
    public MesAutoReportResultDTO receipt(MesAutoReportEventDTO command) {
        validate(command);
        var existing = findBySourceEventId(command.getSourceEventId().trim());
        if (existing == null) return null;
        ensureSameEvent(existing, command);
        return result(existing);
    }

    @Override
    public IPage<MesAutoReportEventEntity> queryPage(Page<MesAutoReportEventEntity> page,
                                                     MesAutoReportEventEntity query) {
        String factory = access.require("read");
        LambdaQueryWrapper<MesAutoReportEventEntity> wrapper = new LambdaQueryWrapper<MesAutoReportEventEntity>()
                .eq(MesAutoReportEventEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesAutoReportEventEntity::getFactoryCode, factory);
        if (query != null) {
            wrapper.eq(StrUtil.isNotBlank(query.getSourceEventId()), MesAutoReportEventEntity::getSourceEventId,
                            query.getSourceEventId())
                    .eq(StrUtil.isNotBlank(query.getDeviceCode()), MesAutoReportEventEntity::getDeviceCode,
                            query.getDeviceCode())
                    .eq(StrUtil.isNotBlank(query.getEventIdentifier()), MesAutoReportEventEntity::getEventIdentifier,
                            query.getEventIdentifier())
                    .eq(StrUtil.isNotBlank(query.getArrangeCode()), MesAutoReportEventEntity::getArrangeCode,
                            query.getArrangeCode())
                    .eq(StrUtil.isNotBlank(query.getStatus()), MesAutoReportEventEntity::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(MesAutoReportEventEntity::getSourceEventTime)
                .orderByDesc(MesAutoReportEventEntity::getId);
        return page(page, wrapper);
    }

    @Override
    public MesAutoReportResultDTO accept(MesAutoReportEventDTO command) {
        validate(command);
        String sourceEventId = command.getSourceEventId().trim();
        MesAutoReportEventEntity existing = findBySourceEventId(sourceEventId);
        if (existing != null) {
            ensureSameEvent(existing, command);
            if (AutoReportEventStatusEnum.FAILED.name().equals(existing.getStatus())) {
                prepareRetry(existing, "来源系统重复投递", existing.getCaptureVersion() == null
                        ? "SYSTEM" : existing.getCaptureActorCode());
                return process(existing.getId());
            }
            // 接收落库后进程退出，后续相同事实可恢复；处理器持有事件锁，不重复报工。
            if (AutoReportEventStatusEnum.RECEIVED.name().equals(existing.getStatus()))
                return process(existing.getId());
            return result(existing);
        }

        MesAutoReportEventEntity event = new MesAutoReportEventEntity()
                .setSourceEventId(sourceEventId)
                .setDeviceCode(command.getDeviceCode().trim())
                .setEventIdentifier(command.getEventIdentifier().trim())
                .setSourceEventTime(command.getEventTime())
                .setRawValue(command.getValue())
                .setRequestedArrangeCode(StrUtil.blankToDefault(StrUtil.trim(command.getArrangeCode()), null))
                .setTransferCode(StrUtil.blankToDefault(StrUtil.trim(command.getTransferCode()), null))
                .setStatus(AutoReportEventStatusEnum.RECEIVED.name())
                .setProcessMessage("事件已接收，等待处理")
                .setRetryCount(0)
                .setLastProcessTime(LocalDateTime.now())
                .setRawPayload(command.getRawPayload())
                .setFactoryCode(command.getFactoryCode());
        if (command.getCaptureVersion() != null) captures.freeze(event, command);
        try {
            if (!save(event)) throw new CheckedException("原设备事件入账失败，不能形成受理回执");
        } catch (DuplicateKeyException exception) {
            MesAutoReportEventEntity concurrent = findBySourceEventId(sourceEventId);
            if (concurrent == null) {
                throw exception;
            }
            ensureSameEvent(concurrent, command);
            return result(concurrent);
        }
        return process(event.getId());
    }

    @Override
    public MesAutoReportResultDTO retry(MesAutoReportRetryDTO command) {
        String factory = access.require("write");
        if (command == null || command.getEventId() == null) {
            throw new CheckedException("自动报工事件ID不能为空");
        }
        if (StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("重试原因不能为空");
        }
        if (command.getReason().trim().length() > 500) {
            throw new CheckedException("重试原因长度不能超过500位");
        }
        MesAutoReportEventEntity event = getById(command.getEventId());
        if (event == null || !Objects.equals(factory, event.getFactoryCode())) {
            throw new CheckedException("自动报工事件不存在");
        }
        if (event.getCaptureVersion() != null) captures.authorizeProcessing(event);
        if (AutoReportEventStatusEnum.SUCCESS.name().equals(event.getStatus())) {
            return result(event);
        }
        if (!AutoReportEventStatusEnum.FAILED.name().equals(event.getStatus())
                && !AutoReportEventStatusEnum.IGNORED.name().equals(event.getStatus())) {
            throw new CheckedException("只有失败或忽略的自动报工事件可以重试");
        }
        MomUser user = SecurityUtils.getUser();
        prepareRetry(event, command.getReason().trim(), user == null ? "SYSTEM" : user.getUsername());
        return process(event.getId());
    }

    private MesAutoReportResultDTO process(Long eventId) {
        try {
            processor.process(eventId);
        } catch (Exception exception) {
            MesAutoReportEventEntity failed = getById(eventId);
            if (failed != null) {
                // 条件更新会在并发处理事务提交后重新判定，不能用旧快照把SUCCESS/IGNORED降为FAILED。
                update(new LambdaUpdateWrapper<MesAutoReportEventEntity>().eq(MesAutoReportEventEntity::getId, eventId)
                        .eq(MesAutoReportEventEntity::getStatus, AutoReportEventStatusEnum.RECEIVED.name())
                        .set(MesAutoReportEventEntity::getStatus, AutoReportEventStatusEnum.FAILED.name())
                        .set(MesAutoReportEventEntity::getProcessMessage, abbreviate(rootMessage(exception), 1000))
                        .set(MesAutoReportEventEntity::getLastProcessTime, LocalDateTime.now()));
            }
        }
        return result(getById(eventId));
    }

    private void prepareRetry(MesAutoReportEventEntity event, String reason, String operator) {
        update(new LambdaUpdateWrapper<MesAutoReportEventEntity>().eq(MesAutoReportEventEntity::getId, event.getId())
                .eq(MesAutoReportEventEntity::getStatus, event.getStatus())
                .in(MesAutoReportEventEntity::getStatus, AutoReportEventStatusEnum.FAILED.name(), AutoReportEventStatusEnum.IGNORED.name())
                .set(MesAutoReportEventEntity::getStatus, AutoReportEventStatusEnum.RECEIVED.name())
                .set(MesAutoReportEventEntity::getProcessMessage, "事件已进入补偿重试")
                .setSql("retry_count=COALESCE(retry_count,0)+1")
                .set(MesAutoReportEventEntity::getRetryReason, reason).set(MesAutoReportEventEntity::getRetryBy, operator)
                .set(MesAutoReportEventEntity::getLastProcessTime, LocalDateTime.now()));
    }

    private MesAutoReportEventEntity findBySourceEventId(String sourceEventId) {
        return getOne(new LambdaQueryWrapper<MesAutoReportEventEntity>()
                .eq(MesAutoReportEventEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesAutoReportEventEntity::getSourceEventId, sourceEventId), false);
    }

    private void validate(MesAutoReportEventDTO command) {
        if (command == null || StrUtil.isBlank(command.getSourceEventId())) {
            throw new CheckedException("来源事件号不能为空");
        }
        String factory = FactoryContextHolder.getFactoryCode();
        if (TenantContextHolder.getTenantId() == null || TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) || StrUtil.isBlank(factory)
                || (StrUtil.isNotBlank(command.getFactoryCode()) && !Objects.equals(factory, command.getFactoryCode())))
            throw new CheckedException("设备报工须绑定明确租户和匹配的实际工厂上下文");
        command.setFactoryCode(factory);
        if (command.getCaptureVersion() == null) {
            if (command.getExternalIdentifier() != null || command.getExpectedBindingId() != null
                    || command.getExpectedBindingVersion() != null)
                throw new CheckedException("旧设备协议不能附加动态实例字段，请使用正式V2采集，不换号猜报");
        } else if (!MesAutoReportCaptureEvidence.VERSION.equals(command.getCaptureVersion())) {
            throw new CheckedException("设备采集协议版本未知，禁止按旧字段回落处理");
        }
        if (command.getSourceEventId().trim().length() > 100) {
            throw new CheckedException("来源事件号长度不能超过100位");
        }
        if (StrUtil.isBlank(command.getDeviceCode()) || StrUtil.isBlank(command.getEventIdentifier())) {
            throw new CheckedException("设备编码和事件标识不能为空");
        }
        if (command.getEventTime() == null) {
            throw new CheckedException("设备事件时间不能为空");
        }
        if (command.getCaptureVersion() != null && command.getEventTime().getNano() != 0)
            throw new CheckedException("V2设备原事件须为秒精度时间，禁止落库截断后改写原正文");
        if (command.getDeviceCode().trim().length() > 100 || command.getEventIdentifier().trim().length() > 100
                || (command.getArrangeCode() != null && command.getArrangeCode().trim().length() > 100)
                || (command.getTransferCode() != null && command.getTransferCode().trim().length() > 200))
            throw new CheckedException("设备、事件、派工或扫描值超出字段容量，禁止截断原事实");
        if (command.getValue() != null) {
            var value = command.getValue().stripTrailingZeros();
            if (value.scale() > 8 || value.precision() - value.scale() > 22)
                throw new CheckedException("设备原始计数超出30位/8位小数容量，禁止数据库静默舍入后作为真实计数");
        }
        if (command.getRawPayload() != null && command.getRawPayload().length() > 20000) {
            throw new CheckedException("设备原始报文长度不能超过20000位");
        }
    }

    private void ensureSameEvent(MesAutoReportEventEntity existing, MesAutoReportEventDTO command) {
        if (!Objects.equals(existing.getCaptureVersion(), command.getCaptureVersion()))
            throw new CheckedException("来源事件原协议版本不能升级或降级；旧事件不补造动态采集依据");
        if (existing.getCaptureVersion() != null) {
            captures.authorizeDelivery(existing);
            if (!Objects.equals(MesAutoReportCaptureEvidence.inputSnapshot(MesAutoReportCaptureEvidence.originalInput(existing)),
                    MesAutoReportCaptureEvidence.inputSnapshot(command)))
                throw new CheckedException("原V2设备事件的外码、实例版本或输入已变化，拒绝覆盖后重投");
        }
        if (!Objects.equals(existing.getDeviceCode(), command.getDeviceCode().trim())
                || !Objects.equals(existing.getEventIdentifier(), command.getEventIdentifier().trim())
                || !Objects.equals(existing.getSourceEventTime(), command.getEventTime())
                || !decimalEquals(existing.getRawValue(), command.getValue())
                || !Objects.equals(existing.getFactoryCode(), command.getFactoryCode())
                || !Objects.equals(existing.getRequestedArrangeCode(), StrUtil.blankToDefault(StrUtil.trim(command.getArrangeCode()), null))
                || !Objects.equals(existing.getTransferCode(), StrUtil.blankToDefault(StrUtil.trim(command.getTransferCode()), null))
                || !Objects.equals(existing.getRawPayload(), command.getRawPayload())) {
            throw new CheckedException("来源事件号已被不同设备事件使用，拒绝覆盖原始事实");
        }
    }

    private boolean decimalEquals(java.math.BigDecimal left, java.math.BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private MesAutoReportResultDTO result(MesAutoReportEventEntity event) {
        if (event == null) {
            throw new CheckedException("自动报工事件处理结果不存在");
        }
        return new MesAutoReportResultDTO()
                .setEventId(event.getId())
                .setSourceEventId(event.getSourceEventId())
                .setStatus(event.getStatus())
                .setMessage(event.getProcessMessage())
                .setReportId(event.getReportId())
                .setArrangeCode(event.getArrangeCode());
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return StrUtil.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
    }

    private String abbreviate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
