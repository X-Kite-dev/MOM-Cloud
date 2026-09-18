package com.kitex.mom.iot.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.iot.dto.DeviceMetadataQueryDTO;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.entity.IotDevicePropertyEntity;
import com.kitex.mom.iot.entity.IotProductModelEntity;
import com.kitex.mom.iot.mapper.IotDeviceMapper;
import com.kitex.mom.iot.mapper.IotDevicePropertyMapper;
import com.kitex.mom.iot.mapper.IotProductModelMapper;
import com.kitex.mom.iot.service.DeviceMetadataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 设备元数据服务实现
 *
 * @author system
 */
@Service
@RequiredArgsConstructor
public class DeviceMetadataServiceImpl implements DeviceMetadataService {

    private final IotDevicePropertyMapper devicePropertyMapper;
    private final IotProductModelMapper productModelMapper;
    private final IotDeviceMapper deviceMapper;

    private final IotDeviceDataAccess dataAccess;
    private java.time.LocalDateTime parseTime(String value,java.time.LocalDateTime fallback) {
        if(cn.hutool.core.util.StrUtil.isBlank(value))return fallback;
        try{return java.time.LocalDateTime.parse(value.replace(' ','T'));}
        catch(RuntimeException e){throw new com.kitex.common.core.exception.CheckedException("时间须为yyyy-MM-dd HH:mm:ss");}
    }
    @Override
    public IPage<IotDevicePropertyEntity> queryMetadata(Page<IotDevicePropertyEntity> page,DeviceMetadataQueryDTO queryDTO) {
        com.kitex.mom.iot.service.impl.IotAlarmService.require(queryDTO!=null&&page!=null,"请提供元数据查询条件");
        var product=dataAccess.product(queryDTO.getProductId());
        com.kitex.mom.iot.service.impl.IotAlarmService.require(cn.hutool.core.util.StrUtil.isNotBlank(queryDTO.getModelIdentifier()),"物模型标识不能为空");
        List<Long> ids=queryDTO.getDeviceIds();
        if(ids!=null&&!ids.isEmpty()){
            com.kitex.mom.iot.service.impl.IotAlarmService.require(ids.size()<=500&&!ids.contains(null)&&new java.util.HashSet<>(ids).size()==ids.size(),"设备选择最多500个且不得为空或重复");
            var found=deviceMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotDeviceEntity>()
                    .select(IotDeviceEntity::getId).in(IotDeviceEntity::getId,ids).eq(IotDeviceEntity::getProductId,product.getId())
                    .eq(IotDeviceEntity::getFactoryCode,product.getFactoryCode()));
            com.kitex.mom.iot.service.impl.IotAlarmService.require(found.size()==ids.size(),"所选设备含跨产品、跨厂、跨租户或不存在的设备");
        }
        var end=parseTime(queryDTO.getEndTime(),java.time.LocalDateTime.now());
        var start=parseTime(queryDTO.getStartTime(),end.minusDays(31));
        com.kitex.mom.iot.service.impl.IotAlarmService.require(start.isBefore(end)&&!end.isAfter(start.plusDays(31)),"元数据时间范围最多31天");
        var safePage=new Page<IotDevicePropertyEntity>(Math.max(1,page.getCurrent()),Math.max(1,Math.min(500,page.getSize())));
        return devicePropertyMapper.selectMetadataPage(safePage,ids,queryDTO.getModelIdentifier(),start.toString(),end.toString(),
                product.getId(),dataAccess.tenant(),product.getFactoryCode());
    }
    @Override
    public List<IotProductModelEntity> getProductModels(Long productId) {
        var product=dataAccess.product(productId);
        return productModelMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotProductModelEntity>()
                .eq(IotProductModelEntity::getProductId,productId).eq(IotProductModelEntity::getFactoryCode,product.getFactoryCode()));
    }
    @Override
    public List<IotDeviceEntity> getProductDevices(Long productId) {
        var product=dataAccess.product(productId);
        var found=deviceMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotDeviceEntity>()
                .select(IotDeviceEntity::getId,IotDeviceEntity::getDeviceCode,IotDeviceEntity::getDeviceName,IotDeviceEntity::getProductId,IotDeviceEntity::getFactoryCode)
                .eq(IotDeviceEntity::getProductId,productId).eq(IotDeviceEntity::getFactoryCode,product.getFactoryCode()).orderByAsc(IotDeviceEntity::getId).last("LIMIT 501"));
        com.kitex.mom.iot.service.impl.IotAlarmService.require(found.size()<=500,"产品设备超过500个，请使用设备管理分页检索后按ID选择");
        return found;
    }
}
