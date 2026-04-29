package com.kitex.mom.iot.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.entity.IotDevicePropertyEntity;
import com.kitex.mom.iot.entity.IotProductEntity;
import com.kitex.mom.iot.entity.IotProductModelEntity;
import com.kitex.mom.iot.mapper.IotDeviceMapper;
import com.kitex.mom.iot.mapper.IotDevicePropertyMapper;
import com.kitex.mom.iot.mapper.IotProductMapper;
import com.kitex.mom.iot.mapper.IotProductModelMapper;
import com.kitex.mom.iot.service.IotDevicePropertyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * IoT设备属性数据服务实现
 *
 * @author system
 * @date 2025-03-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDevicePropertyServiceImpl extends ServiceImpl<IotDevicePropertyMapper, IotDevicePropertyEntity> implements IotDevicePropertyService {


    private final IotDeviceMapper iotDeviceMapper;
    private final IotProductMapper iotProductMapper;

    private final IotProductModelMapper iotProductModelMapper;

    @Override
    public IPage<IotDevicePropertyEntity> getDevicePropertyPage(Page<IotDevicePropertyEntity> page, IotDevicePropertyEntity property) {
        return baseMapper.selectPage(page, new LambdaQueryWrapper<IotDevicePropertyEntity>()
                .eq(property.getDeviceId() != null, IotDevicePropertyEntity::getDeviceId, property.getDeviceId())
                .eq(StrUtil.isNotBlank(property.getIdentifier()), IotDevicePropertyEntity::getIdentifier, property.getIdentifier())
                .orderByDesc(IotDevicePropertyEntity::getCreateTime));
    }

    @Override
    public List<IotDevicePropertyEntity> getLatestPropertiesByDeviceId(Long deviceId) {
        // 获取该设备的所有不同标识符
        List<String> identifiers = baseMapper.selectList(
                        new LambdaQueryWrapper<IotDevicePropertyEntity>()
                                .eq(IotDevicePropertyEntity::getDeviceId, deviceId)
                                .groupBy(IotDevicePropertyEntity::getIdentifier)
                                .select(IotDevicePropertyEntity::getIdentifier))
                .stream()
                .map(IotDevicePropertyEntity::getIdentifier)
                .collect(Collectors.toList());

        // 对每个标识符获取最新的一条记录
        List<IotDevicePropertyEntity> result = new ArrayList<>();
        for (String identifier : identifiers) {
            IotDevicePropertyEntity latestProperty = getLatestPropertyByDeviceIdAndIdentifier(deviceId, identifier);
            if (latestProperty != null) {
                result.add(latestProperty);
            }
        }

        return result;
    }

    @Override
    public IotDevicePropertyEntity getLatestPropertyByDeviceIdAndIdentifier(Long deviceId, String identifier) {
        return baseMapper.selectOne(
                new LambdaQueryWrapper<IotDevicePropertyEntity>()
                        .eq(IotDevicePropertyEntity::getDeviceId, deviceId)
                        .eq(IotDevicePropertyEntity::getIdentifier, identifier)
                        .orderByDesc(IotDevicePropertyEntity::getCreateTime)
                        .last("LIMIT 1"));
    }

    @Override
    public List<IotDevicePropertyEntity> getLatestPropertiesByDeviceCode(String deviceCode) {
        // 查询设备的属性记录
        List<IotDevicePropertyEntity> properties = baseMapper.selectList(
                new LambdaQueryWrapper<IotDevicePropertyEntity>()
                        .eq(IotDevicePropertyEntity::getDeviceCode, deviceCode)
                        .orderByDesc(IotDevicePropertyEntity::getCreateTime));

        // 按标识符分组，只保留每个标识符的最新记录
        Map<String, IotDevicePropertyEntity> latestProperties = new HashMap<>();
        for (IotDevicePropertyEntity property : properties) {
            String identifier = property.getIdentifier();
            if (!latestProperties.containsKey(identifier)) {
                latestProperties.put(identifier, property);
            }
        }

        return new ArrayList<>(latestProperties.values());
    }

    /**
     * 获取属性历史记录
     *
     * @param deviceId
     * @param identifier
     * @return
     */
    @Override
    public List<IotDevicePropertyEntity> getPropertyHistory(Long deviceId, String identifier) {
        try {
            // 获取属性历史记录
            return baseMapper.selectList(
                    new LambdaQueryWrapper<IotDevicePropertyEntity>()
                            .eq(IotDevicePropertyEntity::getDeviceId, deviceId)
                            .eq(IotDevicePropertyEntity::getIdentifier, identifier)
                            .orderByDesc(IotDevicePropertyEntity::getCreateTime));
        } catch (Exception e) {
            log.error("获取属性历史记录失败: {}", e.getMessage(), e);
        }
        return Collections.emptyList();
    }

    /**
     * 获取设备属性一个月内的历史数据用于图表展示
     *
     * @param deviceId   设备ID
     * @param identifier 属性标识符
     * @return 图表数据，包含时间和值
     */
    @Override
    public Map<String, Object> getPropertyChartData(Long deviceId, String identifier) {
        // 获取一个月前的时间
        LocalDateTime oneMonthAgo = LocalDateTime.now().minusMonths(1);
        LocalDateTime now = LocalDateTime.now();

        return getPropertyChartData(deviceId, identifier, oneMonthAgo, now);
    }

    /**
     * 获取设备属性历史数据用于图表展示，根据指定时间范围
     *
     * @param deviceId   设备ID
     * @param identifier 属性标识符
     * @param startTime  开始时间
     * @param endTime    结束时间
     * @return 图表数据，包含时间和值
     */
    @Override
    public Map<String, Object> getPropertyChartData(Long deviceId, String identifier, LocalDateTime startTime, LocalDateTime endTime) {
        try {
            // 查询指定时间范围内的属性数据
            List<IotDevicePropertyEntity> properties = baseMapper.selectList(
                    new LambdaQueryWrapper<IotDevicePropertyEntity>()
                            .eq(IotDevicePropertyEntity::getDeviceId, deviceId)
                            .eq(IotDevicePropertyEntity::getIdentifier, identifier)
                            .ge(startTime != null, IotDevicePropertyEntity::getCollectTime, startTime)
                            .le(endTime != null, IotDevicePropertyEntity::getCollectTime, endTime)
                            .orderByAsc(IotDevicePropertyEntity::getCollectTime));

            if (CollUtil.isEmpty(properties)) {
                return Collections.emptyMap();
            }

            // 提取属性名称和单位
            String propertyName = properties.get(0).getName();
            String unit = properties.get(0).getUnit();

            // 准备图表数据
            List<LocalDateTime> timestamps = new ArrayList<>();
            List<String> values = new ArrayList<>();

            for (IotDevicePropertyEntity property : properties) {
                timestamps.add(property.getCollectTime());
                values.add(property.getValue());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("name", propertyName);
            result.put("unit", unit);
            result.put("timestamps", timestamps);
            result.put("values", values);

            return result;
        } catch (Exception e) {
            log.error("获取属性图表数据失败: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * 更新设备属性
     *
     * @param deviceCode 设备ID
     * @param properties 属性值映射
     * @return
     */
    @Override
    public boolean updateDeviceProperties(String deviceCode, Map<String, Object> properties) {
        log.info("更新设备属性: deviceId={}, properties={}", deviceCode, properties);

        if (properties == null || properties.isEmpty()) {
            log.warn("属性数据为空，无需更新");
            return false;
        }

        IotDeviceEntity deviceEntity = iotDeviceMapper.selectOne(Wrappers.<IotDeviceEntity>lambdaQuery().eq(IotDeviceEntity::getDeviceCode, deviceCode));

        IotProductEntity iotProductEntity = iotProductMapper.selectById(deviceEntity.getProductId());
        List<IotProductModelEntity> iotProductModelEntities = iotProductModelMapper.selectByProductId(deviceEntity.getProductId());

        // 转成 k - v 的映射
        Map<String, IotProductModelEntity> modelMap = iotProductModelEntities.stream()
                .collect(Collectors.toMap(IotProductModelEntity::getIdentifier, Function.identity()));


        // 批量保存属性数据
        List<IotDevicePropertyEntity> propertyEntities = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String identifier = entry.getKey();
            Object value = entry.getValue();

            if (value == null) {
                continue;
            }
            IotProductModelEntity iotProductModelEntity = modelMap.get(identifier);

            // 创建新的属性记录
            IotDevicePropertyEntity propertyEntity = new IotDevicePropertyEntity();
            propertyEntity.setDeviceId(deviceEntity.getId());
            propertyEntity.setDeviceCode(deviceCode);
            propertyEntity.setProductId(iotProductEntity.getId());
            propertyEntity.setProductCode(iotProductEntity.getProductCode());
            propertyEntity.setIdentifier(identifier);
            propertyEntity.setValue(value.toString());
            propertyEntity.setCollectTime(now);

            if (iotProductModelEntity != null) {
                propertyEntity.setName(iotProductModelEntity.getName());
                propertyEntity.setDataType(iotProductModelEntity.getDataType());
                propertyEntity.setUnit("");
            } else {
                // 属性为空
                propertyEntity.setName("");
                propertyEntity.setDataType("");
                propertyEntity.setUnit("");
            }
            propertyEntities.add(propertyEntity);
        }

        // 批量保存属性数据
        boolean saved = false;
        if (!propertyEntities.isEmpty()) {
            saved = saveBatch(propertyEntities);
        }

        return saved;
    }

    @Override
    public Map<String, Object> getDeviceProperties(String productId, String deviceId) {
        log.info("获取设备属性: productId={}, deviceId={}", productId, deviceId);

        // 查询设备的属性记录
        List<IotDevicePropertyEntity> properties = baseMapper.selectList(
                new LambdaQueryWrapper<IotDevicePropertyEntity>()
                        .eq(IotDevicePropertyEntity::getProductCode, productId)
                        .eq(IotDevicePropertyEntity::getDeviceCode, deviceId)
                        .orderByDesc(IotDevicePropertyEntity::getCreateTime));

        if (CollUtil.isEmpty(properties)) {
            return Collections.emptyMap();
        }

        // 按标识符分组，只保留每个标识符的最新记录
        Map<String, Object> result = new HashMap<>();
        Set<String> processedIdentifiers = new HashSet<>();

        for (IotDevicePropertyEntity property : properties) {
            String identifier = property.getIdentifier();
            if (!processedIdentifiers.contains(identifier)) {
                result.put(identifier, property.getValue());
                processedIdentifiers.add(identifier);
            }
        }

        return result;
    }
}