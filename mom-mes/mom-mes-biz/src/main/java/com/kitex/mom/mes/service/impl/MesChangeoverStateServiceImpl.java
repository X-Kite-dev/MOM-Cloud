package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesChangeoverStateDTO;
import com.kitex.mom.mes.entity.MesChangeoverExecutionEntity;
import com.kitex.mom.mes.entity.MesWorksiteMoldEntity;
import com.kitex.mom.mes.mapper.MesChangeoverExecutionMapper;
import com.kitex.mom.mes.service.MesChangeoverStateService;
import com.kitex.mom.mes.service.MesWorksiteMoldService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

import static com.kitex.mom.mes.service.impl.MesChangeoverAttributionFactsReader.*;

/**
 * 同一可重复读事务观察MES原计时账及绑定，查询不调用会留门禁记录的设备检查。
 * 设备多活动账、绑定歧义或精确来源冲突均拒绝，不以limit 1掩盖数据异常。
 */
@Service
@RequiredArgsConstructor
public class MesChangeoverStateServiceImpl extends ServiceImpl<MesChangeoverExecutionMapper, MesChangeoverExecutionEntity>
        implements MesChangeoverStateService {
    private final MesActualFactoryAccessService access;
    private final MesWorksiteMoldService bindingService;
    private final PermissionService permissions;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @SuppressWarnings("unchecked")
    public MesChangeoverStateDTO observe(String deviceCode, String worksiteCode, String executionId, String requestId) {
        String factory = access.require("read");
        require(!StrUtil.hasBlank(deviceCode, worksiteCode) && deviceCode.length() <= 100 && worksiteCode.length() <= 100,
                "请明确设备和工位，编码不得超过100字");
        deviceCode = deviceCode.trim();
        worksiteCode = worksiteCode.trim();
        var user = SecurityUtils.getUser();
        var active = list(scope(factory).eq(MesChangeoverExecutionEntity::getDeviceCode, deviceCode)
                .eq(MesChangeoverExecutionEntity::getStatus, "STARTED").last("LIMIT 2"));
        require(active.size() <= 1, "设备存在多条进行中换型，请先治理原账，不自动选一条");
        MesChangeoverExecutionEntity requested = null;
        if (StrUtil.isNotBlank(executionId)) {
            requested = getOne(scope(factory).eq(MesChangeoverExecutionEntity::getId, id(executionId)));
            require(requested != null, "当前组织未找到指定原账，不能据此证明未提交");
        }
        if (StrUtil.isNotBlank(requestId)) {
            require(requestId.length() <= 100 && requestId.equals(requestId.trim()), "开始原号须为不超过100字的规范文本");
            var byRequest = list(scope(factory).eq(MesChangeoverExecutionEntity::getRequestId, requestId).last("LIMIT 2"));
            require(byRequest.size() <= 1, "开始原号存在多份原账，禁止自动恢复");
            var found = byRequest.isEmpty() ? null : byRequest.get(0);
            require(requested == null || (found != null && Objects.equals(requested.getId(), found.getId())),
                    "执行ID与开始原号不指向同一账");
            requested = found;
        }
        require(requested == null || (Objects.equals(requested.getDeviceCode(), deviceCode)
                && Objects.equals(requested.getWorksiteCode(), worksiteCode)), "指定原账不属于当前设备和工位");
        var bindings = bindingService.list(new LambdaQueryWrapper<MesWorksiteMoldEntity>()
                .eq(MesWorksiteMoldEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesWorksiteMoldEntity::getFactoryCode, factory)
                .eq(MesWorksiteMoldEntity::getDeviceCode, deviceCode)
                .eq(MesWorksiteMoldEntity::getWorksiteCode, worksiteCode).last("LIMIT 2"));
        require(bindings.size() <= 1, "工位设备绑定不唯一，禁止自动恢复");
        require(bytes(json(requested)) + bytes(json(active)) + bytes(json(bindings)) <= 1024 * 1024,
                "原换型事实超过1MiB，须治理查看范围，不截断证明");
        return new MesChangeoverStateDTO().setTenantId(TenantContextHolder.getTenantId().toString())
                .setFactoryCode(factory).setActorUserId(user.getId().toString()).setAccount(user.getUsername())
                .setDeviceCode(deviceCode).setWorksiteCode(worksiteCode)
                .setActiveExecution(active.isEmpty() ? null : (Map<String, Object>) exact(active.get(0)))
                .setRequestedExecution(requested == null ? null : (Map<String, Object>) exact(requested))
                .setCurrentBinding(bindings.isEmpty() ? null : (Map<String, Object>) exact(bindings.get(0)))
                .setCanStart(permissions.hasPermission("mesChangeoverExecution:start"))
                .setCanCancel(permissions.hasPermission("mesChangeoverExecution:cancel"))
                .setCanComplete(permissions.hasPermission("mesChangeoverExecution:complete"));
    }

    private LambdaQueryWrapper<MesChangeoverExecutionEntity> scope(String factory) {
        return new LambdaQueryWrapper<MesChangeoverExecutionEntity>()
                .eq(MesChangeoverExecutionEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesChangeoverExecutionEntity::getFactoryCode, factory);
    }
}
