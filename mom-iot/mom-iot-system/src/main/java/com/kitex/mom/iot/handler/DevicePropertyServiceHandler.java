package com.kitex.mom.iot.handler;

import com.kitex.mom.iot.core.event.DeviceMessageEvent;
import com.kitex.mom.iot.core.message.DeviceMessage;
import com.kitex.mom.iot.service.IotDevicePropertyService;
import com.kitex.mom.iot.mapper.IotGatewayMapper;
import com.kitex.mom.iot.entity.IotGatewayEntity;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.hutool.core.util.StrUtil;
import static com.kitex.mom.iot.service.impl.IotAlarmService.require;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 设备属性处理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevicePropertyServiceHandler {

    private final IotDevicePropertyService devicePropertyService;
    private final IotGatewayMapper gatewayMapper;


    @EventListener
    public void handleCustomEvent(DeviceMessageEvent event) {
        DeviceMessage message = event.getMessage();
        require(message!=null && StrUtil.isNotBlank(event.getGatewayId()),"设备事件必须来自明确网关，旧协议适配器须补充网关来源");
        Long gatewayId;
        try { gatewayId=Long.valueOf(event.getGatewayId()); } catch (NumberFormatException e) { throw new IllegalArgumentException("网关ID无效"); }
        Long previousTenant=TenantContextHolder.getTenantId(); boolean tenantSkip=TenantContextHolder.getTenantSkip();
        String previousFactory=FactoryContextHolder.getFactoryCode(); boolean factorySkip=Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
        try {
            // 只按本进程实例持有的全局主键查来源；不按设备载荷猜租户，也不继承网络线程残留上下文。
            TenantContextHolder.clear(); FactoryContextHolder.clear();
            IotGatewayEntity source=gatewayMapper.selectOne(new LambdaQueryWrapper<IotGatewayEntity>()
                    .select(IotGatewayEntity::getId,IotGatewayEntity::getTenantId,IotGatewayEntity::getFactoryCode)
                    .eq(IotGatewayEntity::getId,gatewayId));
            require(source!=null && source.getTenantId()!=null && StrUtil.isNotBlank(source.getFactoryCode()),"网关租户/工厂来源不存在，禁止跨域采集");
            TenantContextHolder.setTenantId(source.getTenantId()); FactoryContextHolder.setFactoryCode(source.getFactoryCode());
            devicePropertyService.updateDeviceProperties(message.getDeviceId(),message.getProperties(),message.getPropertyStates());
        } finally {
            TenantContextHolder.clear(); FactoryContextHolder.clear();
            if(previousTenant!=null)TenantContextHolder.setTenantId(previousTenant);
            if(tenantSkip)TenantContextHolder.setTenantSkip();
            if(previousFactory!=null)FactoryContextHolder.setFactoryCode(previousFactory);
            if(factorySkip)FactoryContextHolder.setFactorySkip();
        }
    }


    public boolean handlePropertyReport(String deviceId, Map<String, Object> properties) {
        log.debug("处理设备属性上报: deviceId={}", deviceId);

        if (properties == null || properties.isEmpty()) {
            log.warn("属性数据为空，无需处理");
            return false;
        }

        // 调用设备属性服务更新属性
        return devicePropertyService.updateDeviceProperties(deviceId, properties);
    }
}
