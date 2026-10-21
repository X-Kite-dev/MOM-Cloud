package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.mapper.*;
import com.kitex.mom.mdm.service.MdmPlanResourceEventOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 将上线前已存在的MDM未来不可用事实逐批补入Outbox。
 * 正常编辑仍由档案保存事务实时入箱，本服务只处理缺失事件并可安全重复执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MdmPlanResourceEventSourceReconciler {
    private final MdmToolingUnavailableWindowMapper toolingWindowMapper;
    private final MdmEmployeeCalendarExceptionMapper employeeCalendarMapper;
    private final MdmToolingMapper toolingMapper;
    private final MdmEmployeeMapper employeeMapper;
    private final MdmEmployeeSkillMapper employeeSkillMapper;
    private final MdmPlanResourceEventOutboxService outboxService;

    public int reconcileMissing(int limit) {
        int batchSize = Math.max(1, Math.min(limit, 500));
        int reconciled = reconcileToolings(toolingWindowMapper.selectMissingPlanEvents(batchSize));
        reconciled += reconcileToolingMasters(toolingMapper.selectMissingMasterStatusEvents(batchSize));
        reconciled += reconcileEmployees(employeeCalendarMapper.selectMissingPlanEvents(batchSize));
        return reconciled + reconcileQualifications(employeeSkillMapper.selectMissingQualificationEvents(batchSize));
    }

    private int reconcileToolingMasters(List<MdmToolingEntity> candidates) {
        int count = 0;
        for (MdmToolingEntity candidate : candidates) {
            try {
                Runnable action = () -> {
                    MdmToolingEntity current = toolingMapper.selectById(candidate.getId());
                    if (current != null) outboxService.synchronizeToolingMasterStatus(current);
                };
                if (candidate.getTenantId() == null) action.run();
                else TenantBroker.applyAs(candidate.getTenantId(), ignored -> {
                    action.run();
                    return true;
                });
                count++;
            } catch (RuntimeException exception) {
                log.error("MDM历史工装主状态补入Outbox失败，tenantId={}，toolingCode={}",
                        candidate.getTenantId(), candidate.getToolingCode(), exception);
            }
        }
        return count;
    }

    private int reconcileToolings(List<MdmToolingUnavailableWindowEntity> candidates) {
        Set<ResourceKey> resources = new LinkedHashSet<>();
        for (MdmToolingUnavailableWindowEntity source : candidates) {
            resources.add(new ResourceKey(source.getTenantId(), source.getFactoryCode(), source.getToolingCode()));
        }
        int count = 0;
        for (ResourceKey key : resources) {
            try {
                boolean changed = key.tenantId() == null ? reconcileTooling(key)
                        : TenantBroker.applyAs(key.tenantId(), ignored -> reconcileTooling(key));
                if (changed) count++;
            } catch (RuntimeException exception) {
                log.error("MDM历史工装不可用事实补入Outbox失败，tenantId={}，factoryCode={}，toolingCode={}",
                        key.tenantId(), key.factoryCode(), key.resourceCode(), exception);
            }
        }
        return count;
    }

    private boolean reconcileTooling(ResourceKey key) {
        MdmToolingEntity tooling = toolingMapper.selectOne(new LambdaQueryWrapper<MdmToolingEntity>()
                .eq(MdmToolingEntity::getToolingCode, key.resourceCode())
                .eq(StrUtil.isNotBlank(key.factoryCode()), MdmToolingEntity::getFactoryCode, key.factoryCode())
                .eq(MdmToolingEntity::getStatus, true));
        if (tooling == null) return false;
        List<MdmToolingUnavailableWindowEntity> windows = toolingWindowMapper.selectList(
                new LambdaQueryWrapper<MdmToolingUnavailableWindowEntity>()
                        .eq(MdmToolingUnavailableWindowEntity::getToolingCode, key.resourceCode())
                        .eq(StrUtil.isNotBlank(key.factoryCode()), MdmToolingUnavailableWindowEntity::getFactoryCode,
                                key.factoryCode()));
        outboxService.synchronizeToolingWindows(tooling, windows);
        return true;
    }

    private int reconcileEmployees(List<MdmEmployeeCalendarExceptionEntity> candidates) {
        Set<ResourceKey> resources = new LinkedHashSet<>();
        for (MdmEmployeeCalendarExceptionEntity source : candidates) {
            resources.add(new ResourceKey(source.getTenantId(), source.getFactoryCode(), source.getEmployeeCode()));
        }
        int count = 0;
        for (ResourceKey key : resources) {
            try {
                boolean changed = key.tenantId() == null ? reconcileEmployee(key)
                        : TenantBroker.applyAs(key.tenantId(), ignored -> reconcileEmployee(key));
                if (changed) count++;
            } catch (RuntimeException exception) {
                log.error("MDM历史人员日历不可用事实补入Outbox失败，tenantId={}，factoryCode={}，employeeCode={}",
                        key.tenantId(), key.factoryCode(), key.resourceCode(), exception);
            }
        }
        return count;
    }

    private boolean reconcileEmployee(ResourceKey key) {
        MdmEmployeeEntity employee = employeeMapper.selectOne(new LambdaQueryWrapper<MdmEmployeeEntity>()
                .eq(MdmEmployeeEntity::getEmployeeCode, key.resourceCode())
                .eq(StrUtil.isNotBlank(key.factoryCode()), MdmEmployeeEntity::getFactoryCode, key.factoryCode())
                .eq(MdmEmployeeEntity::getStatus, "1"));
        if (employee == null) return false;
        List<MdmEmployeeCalendarExceptionEntity> exceptions = employeeCalendarMapper.selectList(
                new LambdaQueryWrapper<MdmEmployeeCalendarExceptionEntity>()
                        .eq(MdmEmployeeCalendarExceptionEntity::getEmployeeCode, key.resourceCode())
                        .eq(StrUtil.isNotBlank(key.factoryCode()), MdmEmployeeCalendarExceptionEntity::getFactoryCode,
                                key.factoryCode()));
        outboxService.synchronizeEmployeeCalendar(employee, exceptions);
        return true;
    }

    private int reconcileQualifications(List<MdmEmployeeSkillEntity> candidates) {
        Set<ResourceKey> resources = new LinkedHashSet<>();
        for (MdmEmployeeSkillEntity source : candidates) {
            resources.add(new ResourceKey(source.getTenantId(), source.getFactoryCode(), source.getEmployeeCode()));
        }
        int count = 0;
        for (ResourceKey key : resources) {
            try {
                boolean changed = key.tenantId() == null ? reconcileQualification(key)
                        : TenantBroker.applyAs(key.tenantId(), ignored -> reconcileQualification(key));
                if (changed) count++;
            } catch (RuntimeException exception) {
                log.error("MDM历史人员资质失效事实补入Outbox失败，tenantId={}，factoryCode={}，employeeCode={}",
                        key.tenantId(), key.factoryCode(), key.resourceCode(), exception);
            }
        }
        return count;
    }

    private boolean reconcileQualification(ResourceKey key) {
        MdmEmployeeEntity employee = employeeMapper.selectOne(new LambdaQueryWrapper<MdmEmployeeEntity>()
                .eq(MdmEmployeeEntity::getEmployeeCode, key.resourceCode())
                .eq(StrUtil.isNotBlank(key.factoryCode()), MdmEmployeeEntity::getFactoryCode, key.factoryCode())
                .eq(MdmEmployeeEntity::getStatus, "1"));
        if (employee == null) return false;
        List<MdmEmployeeSkillEntity> skills = employeeSkillMapper.selectList(
                new LambdaQueryWrapper<MdmEmployeeSkillEntity>()
                        .eq(MdmEmployeeSkillEntity::getEmployeeCode, key.resourceCode())
                        .eq(StrUtil.isNotBlank(key.factoryCode()), MdmEmployeeSkillEntity::getFactoryCode,
                                key.factoryCode()));
        outboxService.synchronizeEmployeeQualifications(employee, skills);
        return true;
    }

    private record ResourceKey(Long tenantId, String factoryCode, String resourceCode) {
        private ResourceKey {
            resourceCode = Objects.requireNonNull(resourceCode, "resourceCode");
        }
    }
}
