package com.kitex.mom.iot.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.iot.entity.IotGatewayEntity;
import com.kitex.mom.iot.entity.IotNetworkComponentEntity;
import com.kitex.mom.iot.gateway.DeviceGateway;
import com.kitex.mom.iot.gateway.DeviceGatewayManager;
import com.kitex.mom.iot.gateway.DeviceGatewayProvider;
import com.kitex.mom.iot.gateway.GatewayState;
import com.kitex.mom.iot.mapper.IotGatewayMapper;
import com.kitex.mom.iot.service.IotGatewayService;
import com.kitex.mom.iot.service.IotNetworkComponentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IotGatewayServiceImpl extends ServiceImpl<IotGatewayMapper, IotGatewayEntity> implements IotGatewayService {

    private final IotNetworkComponentService networkComponentService;
    private final DeviceGatewayManager gatewayManager;


    @Override
    public IPage<IotGatewayEntity> queryPage(Page<IotGatewayEntity> page, IotGatewayEntity gateway) {
        LambdaQueryWrapper<IotGatewayEntity> queryWrapper = new LambdaQueryWrapper<>();

        if (gateway != null) {
            queryWrapper.like(StrUtil.isNotBlank(gateway.getGatewayName()), IotGatewayEntity::getGatewayName, gateway.getGatewayName()).eq(StrUtil.isNotBlank(gateway.getGatewayCode()), IotGatewayEntity::getGatewayCode, gateway.getGatewayCode()).eq(StrUtil.isNotBlank(gateway.getProtocol()), IotGatewayEntity::getProtocol, gateway.getProtocol()).eq(gateway.getNetworkComponentId() != null, IotGatewayEntity::getNetworkComponentId, gateway.getNetworkComponentId()).eq(gateway.getStatus() != null, IotGatewayEntity::getStatus, gateway.getStatus());
        }

        queryWrapper.orderByDesc(IotGatewayEntity::getCreateTime);

        IPage<IotGatewayEntity> pageResult = this.page(page, queryWrapper);

        // 填充关联信息
        pageResult.getRecords().forEach(this::fillRelatedInfo);

        return pageResult;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createGateway(IotGatewayEntity gateway) {
        // 验证网关编码是否已存在
        if (this.checkGatewayCodeExists(gateway.getGatewayCode())) {
            log.error("网关编码已存在: {}", gateway.getGatewayCode());
            return false;
        }

        // 验证网络组件是否存在
        IotNetworkComponentEntity networkComponent = networkComponentService.getById(gateway.getNetworkComponentId());
        if (networkComponent == null) {
            log.error("网络组件不存在: {}", gateway.getNetworkComponentId());
            return false;
        }
        // 拼接上接入方式
        gateway.setProvider(networkComponent.getType() + "-gateway");


        Optional<DeviceGatewayProvider> provider = gatewayManager.getProvider(gateway.getProvider());
        DeviceGatewayProvider deviceGatewayProvider = provider.orElseThrow(() -> new RuntimeException("网关提供者不存在"));

        gateway.setTransport(deviceGatewayProvider.getTransport().getName());
        // 直接复制配置
        gateway.setConfiguration(networkComponent.getConfiguration());

        return this.save(gateway);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateGateway(IotGatewayEntity gateway) {
        // 验证网关是否存在
        IotGatewayEntity existingGateway = this.getById(gateway.getId());
        if (existingGateway == null) {
            log.error("网关不存在: {}", gateway.getId());
            return false;
        }

        // 如果网关编码发生变化，验证新编码是否可用
        if (!existingGateway.getGatewayCode().equals(gateway.getGatewayCode())) {
            if (this.checkGatewayCodeExists(gateway.getGatewayCode())) {
                log.error("网关编码已存在: {}", gateway.getGatewayCode());
                return false;
            }
        }

        // 验证网络组件是否存在
        IotNetworkComponentEntity networkComponent = networkComponentService.getById(gateway.getNetworkComponentId());
        if (networkComponent == null) {
            log.error("网络组件不存在: {}", gateway.getNetworkComponentId());
            return false;
        }
        // 拼接上接入方式
        gateway.setProvider(networkComponent.getType() + "-gateway");
        Optional<DeviceGatewayProvider> provider = gatewayManager.getProvider(gateway.getProvider());
        DeviceGatewayProvider deviceGatewayProvider = provider.orElseThrow(() -> new RuntimeException("网关提供者不存在"));

        gateway.setTransport(deviceGatewayProvider.getTransport().getName());
        // 直接复制配置
        gateway.setConfiguration(networkComponent.getConfiguration());

        return this.updateById(gateway);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeGateway(List<String> ids) {
        return this.removeBatchByIds(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateStatus(Long id, String status) {
        IotGatewayEntity gateway = new IotGatewayEntity();
        gateway.setId(id);
        gateway.setStatus(status);
        return this.updateById(gateway);
    }


    @Override
    public List<IotGatewayEntity> getByNetworkComponentId(Long networkComponentId) {
        LambdaQueryWrapper<IotGatewayEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotGatewayEntity::getNetworkComponentId, networkComponentId);
        List<IotGatewayEntity> gateways = this.list(queryWrapper);
        gateways.forEach(this::fillRelatedInfo);
        return gateways;
    }

    @Override
    public void startGateway(Long id) {
        // 验证网关是否存在
        IotGatewayEntity gateway = this.getById(id);
        if (gateway == null) {
            throw new RuntimeException("网关不存在");
        }

        // 调用适配器启动网关
        gatewayManager.start(String.valueOf(id));
        gateway.setStatus(GatewayState.started.name());

    }

    @Override
    public void stopGateway(Long id) {
        // 验证网关是否存在
        IotGatewayEntity gateway = this.getById(id);
        if (gateway == null) {
            throw new RuntimeException("网关不存在");
        }

//        // 调用适配器停止网关
        gatewayManager.shutdown(String.valueOf(id));

    }

    @Override
    public boolean isGatewayRunning(Long id) {
        // 验证网关是否存在
        IotGatewayEntity gateway = this.getById(id);
        if (gateway == null) {
            log.error("网关不存在: {}", id);
            return false;
        }

        DeviceGateway gateway1 = gatewayManager.getGateway(String.valueOf(id));
        GatewayState state = gateway1.getState();
        if (state == GatewayState.shutdown) {
            return false;
        }
        if (state == GatewayState.starting) {
            return true;
        }
        return false;
    }


    @Override
    public IotNetworkComponentEntity getNetworkComponentById(Long networkComponentId) {
        if (networkComponentId == null) {
            return null;
        }
        return networkComponentService.getById(networkComponentId);
    }

    /**
     * 获取所有的网关状态
     *
     * @return
     */
    @Override
    public Map<String, Boolean> getAllGatewayStatus() {
        HashMap<String, Boolean> objectObjectHashMap = new HashMap<>();
        gatewayManager.getGateways().forEach(deviceGateway -> {
            objectObjectHashMap.put(deviceGateway.getId(), deviceGateway.isAlive());
        });
        return objectObjectHashMap;
    }

    @Override
    public List<IotGatewayEntity> queryList(IotGatewayEntity gateway) {
        LambdaQueryWrapper<IotGatewayEntity> queryWrapper = new LambdaQueryWrapper<>();

        if (gateway != null) {
            queryWrapper.like(StrUtil.isNotBlank(gateway.getGatewayName()), IotGatewayEntity::getGatewayName, gateway.getGatewayName()).eq(StrUtil.isNotBlank(gateway.getGatewayCode()), IotGatewayEntity::getGatewayCode, gateway.getGatewayCode()).eq(StrUtil.isNotBlank(gateway.getProtocol()), IotGatewayEntity::getProtocol, gateway.getProtocol()).eq(gateway.getNetworkComponentId() != null, IotGatewayEntity::getNetworkComponentId, gateway.getNetworkComponentId()).eq(gateway.getStatus() != null, IotGatewayEntity::getStatus, gateway.getStatus());
        }

        queryWrapper.orderByDesc(IotGatewayEntity::getCreateTime);

        List<IotGatewayEntity> list = this.list(queryWrapper);

        // 填充关联信息
        list.forEach(this::fillRelatedInfo);

        return list;
    }

    /**
     * 检查网关编码是否已存在
     */
    private boolean checkGatewayCodeExists(String gatewayCode) {
        LambdaQueryWrapper<IotGatewayEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotGatewayEntity::getGatewayCode, gatewayCode);
        return this.count(queryWrapper) > 0;
    }

    /**
     * 填充关联信息
     */
    private void fillRelatedInfo(IotGatewayEntity gateway) {
        if (gateway != null) {
            if (gateway.getNetworkComponentId() != null) {
                gateway.setNetworkComponent(networkComponentService.getById(gateway.getNetworkComponentId()));
            }
        }
    }
} 