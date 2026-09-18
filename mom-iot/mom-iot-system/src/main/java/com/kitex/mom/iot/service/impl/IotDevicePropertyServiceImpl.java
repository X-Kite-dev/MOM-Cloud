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
import org.springframework.transaction.annotation.Transactional;
import static com.kitex.mom.iot.service.impl.IotAlarmService.require;

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
    private final IotAlarmEvaluator alarmEvaluator;
    private final IotDeviceDataAccess dataAccess;

    private LambdaQueryWrapper<IotDevicePropertyEntity> scoped(Long id, String code) {
        var device=dataAccess.device(id,code,"read");
        return new LambdaQueryWrapper<IotDevicePropertyEntity>()
                .eq(IotDevicePropertyEntity::getTenantId,dataAccess.tenant()).eq(IotDevicePropertyEntity::getScopeVerified,true)
                .eq(IotDevicePropertyEntity::getFactoryCode,device.getFactoryCode()).eq(IotDevicePropertyEntity::getDeviceId,device.getId());
    }
    @Override
    public IPage<IotDevicePropertyEntity> getDevicePropertyPage(Page<IotDevicePropertyEntity> page,IotDevicePropertyEntity property) {
        require(property!=null,"请提供设备查询条件");
        var query=scoped(property.getDeviceId(),property.getDeviceCode())
                .eq(StrUtil.isNotBlank(property.getIdentifier()),IotDevicePropertyEntity::getIdentifier,property.getIdentifier())
                .eq(property.getProductId()!=null,IotDevicePropertyEntity::getProductId,property.getProductId())
                .eq(StrUtil.isNotBlank(property.getProductCode()),IotDevicePropertyEntity::getProductCode,property.getProductCode())
                .orderByDesc(IotDevicePropertyEntity::getCollectTime,IotDevicePropertyEntity::getId);
        return baseMapper.selectPage(new Page<>(Math.max(1,page.getCurrent()),Math.max(1,Math.min(500,page.getSize()))),query);
    }
    @Override
    public List<IotDevicePropertyEntity> getLatestPropertiesByDeviceId(Long deviceId) {
        var device=dataAccess.device(deviceId,null,"read");
        return baseMapper.latestScoped(device.getId(),dataAccess.tenant(),device.getFactoryCode());
    }
    @Override
    public IotDevicePropertyEntity getLatestPropertyByDeviceIdAndIdentifier(Long deviceId,String identifier) {
        require(StrUtil.isNotBlank(identifier),"属性标识不能为空");
        return baseMapper.selectOne(scoped(deviceId,null).eq(IotDevicePropertyEntity::getIdentifier,identifier)
                .orderByDesc(IotDevicePropertyEntity::getCollectTime,IotDevicePropertyEntity::getId).last("LIMIT 1"));
    }
    @Override
    public List<IotDevicePropertyEntity> getLatestPropertiesByDeviceCode(String deviceCode) {
        var device=dataAccess.device(null,deviceCode,"read");
        return baseMapper.latestScoped(device.getId(),dataAccess.tenant(),device.getFactoryCode());
    }
    @Override
    public List<IotDevicePropertyEntity> getPropertyHistory(Long deviceId,String identifier) {
        require(StrUtil.isNotBlank(identifier),"属性标识不能为空");
        var result=baseMapper.selectList(scoped(deviceId,null).eq(IotDevicePropertyEntity::getIdentifier,identifier)
                .orderByDesc(IotDevicePropertyEntity::getCollectTime,IotDevicePropertyEntity::getId).last("LIMIT 501"));
        require(result.size()<=500,"历史记录超过500条，请使用分页历史查询，不返回截断的完整历史");
        return result;
    }
    @Override
    public Map<String,Object> getPropertyChartData(Long deviceId,String identifier) {
        LocalDateTime end=LocalDateTime.now();
        return getPropertyChartData(deviceId,identifier,end.minusDays(31),end);
    }
    @Override
    public Map<String,Object> getPropertyChartData(Long deviceId,String identifier,LocalDateTime startTime,LocalDateTime endTime) {
        require(StrUtil.isNotBlank(identifier),"属性标识不能为空");
        require(startTime!=null&&endTime!=null&&startTime.isBefore(endTime)
                && !endTime.isAfter(startTime.plusDays(31)),"趋势须指定起止时间，范围最多31天，结束时间不含本时刻");
        var records=baseMapper.selectList(scoped(deviceId,null).eq(IotDevicePropertyEntity::getIdentifier,identifier)
                .ge(IotDevicePropertyEntity::getCollectTime,startTime).lt(IotDevicePropertyEntity::getCollectTime,endTime)
                .orderByAsc(IotDevicePropertyEntity::getCollectTime,IotDevicePropertyEntity::getId).last("LIMIT 5001"));
        require(records.size()<=5000,"趋势超过5000个原始点，请缩小时间范围；不抽样隐藏尖峰");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("name",records.isEmpty()?identifier:records.get(0).getName());
        result.put("unit",records.isEmpty()?"":records.get(0).getUnit());
        result.put("timestamps",records.stream().map(IotDevicePropertyEntity::getCollectTime).toList());
        result.put("values",records.stream().map(IotDevicePropertyEntity::getValue).toList());
        result.put("qualityCodes",records.stream().map(IotDevicePropertyEntity::getQualityCode).toList());
        result.put("propertyIds",records.stream().map(p->String.valueOf(p.getId())).toList());
        result.put("complete",true); result.put("timeBasis","SERVER_COLLECT_TIME");return result;
    }
    @Override
    public List<IotDevicePropertyEntity> details(Long deviceId,String deviceCode,Long productId,String productCode,
            String identifier,String name,LocalDateTime startTime,LocalDateTime endTime) {
        require(startTime==null||endTime==null||!startTime.isAfter(endTime),"开始时间不能晚于结束时间");
        return baseMapper.selectList(scoped(deviceId,deviceCode).eq(productId!=null,IotDevicePropertyEntity::getProductId,productId)
                .eq(StrUtil.isNotBlank(productCode),IotDevicePropertyEntity::getProductCode,productCode)
                .eq(StrUtil.isNotBlank(identifier),IotDevicePropertyEntity::getIdentifier,identifier)
                .eq(StrUtil.isNotBlank(name),IotDevicePropertyEntity::getName,name)
                .ge(startTime!=null,IotDevicePropertyEntity::getCollectTime,startTime).le(endTime!=null,IotDevicePropertyEntity::getCollectTime,endTime)
                .orderByDesc(IotDevicePropertyEntity::getCollectTime,IotDevicePropertyEntity::getId).last("LIMIT 100"));
    }

    /**
     * 更新设备属性
     *
     * @param deviceCode 设备ID
     * @param properties 属性值映射
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateDeviceProperties(String deviceCode, Map<String, Object> properties) {
        return updateDeviceProperties(deviceCode, properties, Collections.emptyMap());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateDeviceProperties(String deviceCode, Map<String, Object> properties, Map<String, String> qualityCodes) {
        log.debug("更新设备属性: deviceId={}", deviceCode);

        if (properties == null || properties.isEmpty()) {
            log.warn("属性数据为空，无需更新");
            return false;
        }

        require(StrUtil.isNotBlank(deviceCode) && properties.size()<=500,"属性采集须指定设备且单次不超过500个属性");
        require(com.kitex.common.data.tenant.TenantContextHolder.getTenantId()!=null
                && !com.kitex.common.data.tenant.TenantContextHolder.getTenantSkip(),"设备采集须由可信网关绑定明确租户，不能全域查询或跳过租户");
        String sourceFactory=com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(sourceFactory),"设备采集须绑定可信网关工厂");
        List<IotDeviceEntity> devices = iotDeviceMapper.selectList(Wrappers.<IotDeviceEntity>lambdaQuery().eq(IotDeviceEntity::getDeviceCode, deviceCode).last("FOR UPDATE"));
        require(devices.size()==1,"当前租户设备编码不存在或不唯一，禁止猜测设备来源");
        IotDeviceEntity deviceEntity = devices.get(0);
        require(Objects.equals(sourceFactory,deviceEntity.getFactoryCode()) && deviceEntity.getStatus()!=null && deviceEntity.getStatus()!=4,
                "设备工厂缺失或已禁用，禁止形成告警来源");

        IotProductEntity iotProductEntity = iotProductMapper.selectById(deviceEntity.getProductId());
        require(iotProductEntity!=null,"设备所属产品不存在");
        List<IotProductModelEntity> iotProductModelEntities = iotProductModelMapper.selectByProductId(deviceEntity.getProductId());

        // 转成 k - v 的映射
        Map<String, IotProductModelEntity> modelMap = iotProductModelEntities.stream()
                .filter(model -> "property".equals(model.getFuncType()))
                .collect(Collectors.toMap(IotProductModelEntity::getIdentifier, Function.identity()));


        // 批量保存属性数据
        List<IotDevicePropertyEntity> propertyEntities = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String identifier = entry.getKey();
            Object value = entry.getValue();

            IotProductModelEntity iotProductModelEntity = modelMap.get(identifier);

            // 创建新的属性记录
            IotDevicePropertyEntity propertyEntity = new IotDevicePropertyEntity();
            propertyEntity.setDeviceId(deviceEntity.getId());
            propertyEntity.setTenantId(deviceEntity.getTenantId());
            propertyEntity.setFactoryCode(deviceEntity.getFactoryCode());
            propertyEntity.setScopeVerified(true);
            propertyEntity.setDeviceCode(deviceCode);
            propertyEntity.setProductId(iotProductEntity.getId());
            propertyEntity.setProductCode(iotProductEntity.getProductCode());
            propertyEntity.setIdentifier(identifier);
            propertyEntity.setValue(String.valueOf(value));
            propertyEntity.setCollectTime(now);
            String quality=qualityCodes==null?null:qualityCodes.get(identifier);
            propertyEntity.setQualityCode(StrUtil.isBlank(quality)?"UNSPECIFIED":quality.length()>100?"INVALID_QUALITY":quality.trim().toUpperCase(Locale.ROOT));

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
            require(saved,"原始设备属性保存失败");
            alarmEvaluator.evaluate(deviceEntity,propertyEntities);
        }

        return saved;
    }

    @Override
    public Map<String, Object> getDeviceProperties(String productId, String deviceId) {
        var device=dataAccess.device(null,deviceId,"read"); // 兼容旧契约：参数名称虽是ID，实际为编码。
        require(Objects.equals(device.getProductCode(),productId),"设备与产品编码不匹配");
        Map<String,Object> result=new LinkedHashMap<>();
        for(var property:baseMapper.latestScoped(device.getId(),dataAccess.tenant(),device.getFactoryCode()))result.put(property.getIdentifier(),property.getValue());
        return result;
    }
}
