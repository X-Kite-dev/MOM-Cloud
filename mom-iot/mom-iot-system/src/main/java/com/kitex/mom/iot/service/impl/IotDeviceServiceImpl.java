package com.kitex.mom.iot.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.iot.constant.DeviceStatusEnum;
import com.kitex.mom.iot.dto.IotDeviceDTO;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.entity.IotProductEntity;
import com.kitex.mom.iot.enums.DeviceTypeEnum;
import com.kitex.mom.iot.mapper.IotDeviceMapper;
import com.kitex.mom.iot.service.IotDeviceService;
import com.kitex.mom.iot.service.IotProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * IoT设备服务实现
 *
 * @author system
 * @date 2025-03-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceServiceImpl extends ServiceImpl<IotDeviceMapper, IotDeviceEntity> implements IotDeviceService {

    private final IotProductService iotProductService;

    @Override
    public IPage<IotDeviceEntity> getDevicePage(Page<IotDeviceEntity> page, IotDeviceEntity device) {
        LambdaQueryWrapper<IotDeviceEntity> queryWrapper = new LambdaQueryWrapper<>();

        // 设置查询条件
        if (device != null) {
            queryWrapper.like(StrUtil.isNotBlank(device.getDeviceName()), IotDeviceEntity::getDeviceName, device.getDeviceName())
                    .eq(StrUtil.isNotBlank(device.getDeviceCode()), IotDeviceEntity::getDeviceCode, device.getDeviceCode())
                    .eq(device.getProductId() != null, IotDeviceEntity::getProductId, device.getProductId())
                    .eq(StrUtil.isNotBlank(device.getProductCode()), IotDeviceEntity::getProductCode, device.getProductCode())
                    .eq(device.getStatus() != null, IotDeviceEntity::getStatus, device.getStatus());
        }

        // 排序
        queryWrapper.orderByDesc(IotDeviceEntity::getCreateTime);

        return this.page(page, queryWrapper);
    }

    @Override
    public List<IotDeviceEntity> getDevicesByProductId(Long productId) {
        return baseMapper.selectByProductId(productId);
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createDevice(IotDeviceDTO deviceDTO) {
        // 检查设备编码是否已存在
        IotDeviceEntity existingDevice = this.getDeviceByCode(deviceDTO.getDeviceCode());
        if (existingDevice != null) {
            log.error("设备编码已存在: {}", deviceDTO.getDeviceCode());
            return false;
        }

        // 获取产品信息
        IotProductEntity product = iotProductService.getById(deviceDTO.getProductId());
        if (product == null) {
            log.error("产品不存在: {}", deviceDTO.getProductId());
            return false;
        }

        // 验证设备类型
        if (DeviceTypeEnum.SUB_DEVICE.getValue().equals(deviceDTO.getDeviceType())) {
            // 如果是子设备，必须指定网关设备
            if (deviceDTO.getGatewayDeviceId() == null) {
                log.error("子设备必须指定网关设备");
                return false;
            }

            // 验证网关设备是否存在且类型正确
            IotDeviceEntity gatewayDevice = this.getById(deviceDTO.getGatewayDeviceId());
            if (gatewayDevice == null || !DeviceTypeEnum.GATEWAY_DEVICE.getValue().equals(gatewayDevice.getDeviceType())) {
                log.error("指定的网关设备不存在或类型错误");
                return false;
            }
        }

        IotDeviceEntity device = new IotDeviceEntity();
        BeanUtil.copyProperties(deviceDTO, device);

        // 设置产品信息
        device.setProductCode(product.getProductCode());
        device.setProductName(product.getProductName());

        // 设置初始状态为未激活
        device.setStatus(DeviceStatusEnum.INACTIVE.getCode());

        // 生成设备秘钥
        device.setDeviceSecret(generateDeviceSecret());

        return this.save(device);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean batchCreateDevice(List<IotDeviceDTO> deviceDTOList) {
        if (CollUtil.isEmpty(deviceDTOList)) {
            return false;
        }

        List<IotDeviceEntity> deviceList = new ArrayList<>(deviceDTOList.size());
        for (IotDeviceDTO deviceDTO : deviceDTOList) {
            // 检查设备编码是否已存在
            IotDeviceEntity existingDevice = this.getDeviceByCode(deviceDTO.getDeviceCode());
            if (existingDevice != null) {
                log.error("设备编码已存在: {}", deviceDTO.getDeviceCode());
                continue;
            }

            // 获取产品信息
            IotProductEntity product = iotProductService.getById(deviceDTO.getProductId());
            if (product == null) {
                log.error("产品不存在: {}", deviceDTO.getProductId());
                continue;
            }

            IotDeviceEntity device = new IotDeviceEntity();
            BeanUtil.copyProperties(deviceDTO, device);

            // 设置产品信息
            device.setProductCode(product.getProductCode());
            device.setProductName(product.getProductName());

            // 设置初始状态为未激活
            device.setStatus(DeviceStatusEnum.INACTIVE.getCode());

            // 生成设备秘钥
            device.setDeviceSecret(generateDeviceSecret());

            deviceList.add(device);
        }

        return this.saveBatch(deviceList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateDevice(IotDeviceDTO deviceDTO) {
        IotDeviceEntity device = this.getById(deviceDTO.getId());
        if (device == null) {
            return false;
        }

        // 检查设备编码是否已被其他设备使用
        if (!device.getDeviceCode().equals(deviceDTO.getDeviceCode())) {
            IotDeviceEntity existingDevice = this.getDeviceByCode(deviceDTO.getDeviceCode());
            if (existingDevice != null && !existingDevice.getId().equals(deviceDTO.getId())) {
                log.error("设备编码已存在: {}", deviceDTO.getDeviceCode());
                return false;
            }
        }

        // 如果产品ID发生变化，需要更新产品相关信息
        if (!device.getProductId().equals(deviceDTO.getProductId())) {
            IotProductEntity product = iotProductService.getById(deviceDTO.getProductId());
            if (product == null) {
                log.error("产品不存在: {}", deviceDTO.getProductId());
                return false;
            }
            device.setProductId(product.getId());
            device.setProductCode(product.getProductCode());
            device.setProductName(product.getProductName());
        }

        // 验证设备类型
        if (DeviceTypeEnum.SUB_DEVICE.getValue().equals(deviceDTO.getDeviceType())) {
            // 如果是子设备，必须指定网关设备
            if (deviceDTO.getGatewayDeviceId() == null) {
                log.error("子设备必须指定网关设备");
                return false;
            }

            // 验证网关设备是否存在且类型正确
            IotDeviceEntity gatewayDevice = this.getById(deviceDTO.getGatewayDeviceId());
            if (gatewayDevice == null || !DeviceTypeEnum.GATEWAY_DEVICE.getValue().equals(gatewayDevice.getDeviceType())) {
                log.error("指定的网关设备不存在或类型错误");
                return false;
            }
        } else {
            // 如果不是子设备，清除网关设备ID
            deviceDTO.setGatewayDeviceId(null);
        }

        BeanUtil.copyProperties(deviceDTO, device, "status", "deviceSecret", "activeTime", "lastOnlineTime");
        return this.updateById(device);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeDevice(Long id) {
        // 这里可以添加删除设备相关的其他数据的逻辑，如设备属性数据等
        return this.removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateStatus(Long deviceId, Integer status) {
        return baseMapper.updateStatus(deviceId, status) > 0;
    }

    @Override
    public String generateDeviceSecret() {
        // 生成随机设备秘钥
        String randomStr = IdUtil.fastSimpleUUID() + RandomUtil.randomString(16);
        return DigestUtil.md5Hex(randomStr);
    }

    @Override
    public IotDeviceEntity getDeviceByCode(String deviceCode) {
        if (StrUtil.isBlank(deviceCode)) {
            return null;
        }

        LambdaQueryWrapper<IotDeviceEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotDeviceEntity::getDeviceCode, deviceCode);

        return this.getOne(queryWrapper);
    }
} 