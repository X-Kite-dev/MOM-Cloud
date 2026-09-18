package com.kitex.mom.iot.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.iot.entity.IotDeviceEventEntity;
import com.kitex.mom.iot.mapper.IotDeviceEventMapper;
import com.kitex.mom.iot.service.IotDeviceEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * IoT设备事件数据服务实现
 *
 * @author system
 * @date 2025-03-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceEventServiceImpl extends ServiceImpl<IotDeviceEventMapper, IotDeviceEventEntity> implements IotDeviceEventService {

    private static final String PRODUCTION_OUTPUT_EVENT = "PRODUCTION_OUTPUT";

    private final IotMesOutboxService outbox;
    private final IotDeviceDataAccess dataAccess;

    @Override
    public IPage<IotDeviceEventEntity> getDeviceEventPage(Page<IotDeviceEventEntity> page, IotDeviceEventEntity event) {
        com.kitex.mom.iot.service.impl.IotAlarmService.require(event!=null,"请选择设备查询事件");
        var query=scoped(event.getDeviceId(),event.getDeviceCode(),"read")
                .eq(StrUtil.isNotBlank(event.getIdentifier()),IotDeviceEventEntity::getIdentifier,event.getIdentifier())
                .eq(StrUtil.isNotBlank(event.getEventType()),IotDeviceEventEntity::getEventType,event.getEventType())
                .eq(event.getProductId()!=null,IotDeviceEventEntity::getProductId,event.getProductId())
                .eq(StrUtil.isNotBlank(event.getProductCode()),IotDeviceEventEntity::getProductCode,event.getProductCode())
                .eq(StrUtil.isNotBlank(event.getName()),IotDeviceEventEntity::getName,event.getName())
                .ge(event.getEventTime()!=null,IotDeviceEventEntity::getEventTime,event.getEventTime())
                .lt(event.getEventTime()!=null,IotDeviceEventEntity::getEventTime,event.getEventTime()==null?null:event.getEventTime().plusDays(1))
                .orderByDesc(IotDeviceEventEntity::getEventTime,IotDeviceEventEntity::getId);
        return this.page(new Page<>(Math.max(1,page.getCurrent()),Math.max(1,Math.min(500,page.getSize()))),query);
    }
    @Override
    public LambdaQueryWrapper<IotDeviceEventEntity> scoped(Long id,String code,String permission) {
        var device=dataAccess.device(id,code,permission);
        return new LambdaQueryWrapper<IotDeviceEventEntity>().eq(IotDeviceEventEntity::getTenantId,dataAccess.tenant())
                .eq(IotDeviceEventEntity::getScopeVerified,true).eq(IotDeviceEventEntity::getFactoryCode,device.getFactoryCode())
                .eq(IotDeviceEventEntity::getDeviceId,device.getId());
    }
    @Override
    public IotDeviceEventEntity authorizedEvent(Long id,String permission) {
        dataAccess.tenant();
        var event=id==null?null:this.getById(id);
        com.kitex.mom.iot.service.impl.IotAlarmService.require(event!=null&&Boolean.TRUE.equals(event.getScopeVerified()),"事件不存在或来源归属尚未核验");
        var device=dataAccess.device(event.getDeviceId(),null,permission);
        com.kitex.mom.iot.service.impl.IotAlarmService.require(java.util.Objects.equals(device.getFactoryCode(),event.getFactoryCode()),"历史事件归属与当前设备不同，不得随设备迁移放行");
        return event;
    }
    private void prepareEvent(IotDeviceEventEntity event) {
        com.kitex.mom.iot.service.impl.IotAlarmService.require(event!=null&&event.getId()==null,"新增事件不能传入或覆盖历史ID");
        var device=dataAccess.device(event.getDeviceId(),event.getDeviceCode(),"write");
        com.kitex.mom.iot.service.impl.IotAlarmService.require((event.getTenantId()==null||java.util.Objects.equals(event.getTenantId(),device.getTenantId()))
                && (StrUtil.isBlank(event.getFactoryCode())||java.util.Objects.equals(event.getFactoryCode(),device.getFactoryCode()))
                && (event.getProductId()==null||java.util.Objects.equals(event.getProductId(),device.getProductId()))
                && (StrUtil.isBlank(event.getProductCode())||java.util.Objects.equals(event.getProductCode(),device.getProductCode())),"事件租户、工厂或产品来源不一致");
        com.kitex.mom.iot.service.impl.IotAlarmService.require(event.getMesReportId()==null&&event.getMesAutoReportEventId()==null&&event.getMesForwardTime()==null
                && event.getMesForwardStatus()==null&&event.getMesForwardMessage()==null,"不能提交伪造MES转发结果");
        com.kitex.mom.iot.service.impl.IotAlarmService.require(StrUtil.isNotBlank(event.getIdentifier())&&event.getIdentifier().length()<=100
                && StrUtil.isNotBlank(event.getEventType())&&(event.getEventData()==null||event.getEventData().length()<=1000000),"事件标识、类型或载荷不合法");
        if(StrUtil.isNotBlank(event.getEventData()))JSONUtil.parseObj(event.getEventData());
        event.setTenantId(device.getTenantId());event.setFactoryCode(device.getFactoryCode());event.setScopeVerified(true);
        event.setDeviceId(device.getId());event.setDeviceCode(device.getDeviceCode());event.setProductId(device.getProductId());event.setProductCode(device.getProductCode());
        // PostgreSQL timestamp 精度为微秒；先规范化再冻结指纹，避免入库舍入导致假漂移。
        event.setEventTime((event.getEventTime()==null?LocalDateTime.now():event.getEventTime()).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeviceEvent(IotDeviceEventEntity event) {
        prepareEvent(event);
        com.kitex.mom.iot.service.impl.IotAlarmService.require(this.save(event),"设备事件保存失败");
        if(shouldForward(event))outbox.enqueue(event);
        return true;
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean batchSaveDeviceEvent(List<IotDeviceEventEntity> events) {
        com.kitex.mom.iot.service.impl.IotAlarmService.require(events!=null&&!events.isEmpty()&&events.size()<=500,"单批设备事件须为1至500条");
        events.forEach(this::prepareEvent);
        com.kitex.mom.iot.service.impl.IotAlarmService.require(this.saveBatch(events),"设备事件批量保存失败");
        events.stream().filter(this::shouldForward).forEach(outbox::enqueue);
        return true;
    }

    @Override
    public List<IotDeviceEventEntity> getRecentEventsByDeviceId(Long deviceId,Integer limit) {
        return this.list(scoped(deviceId,null,"read").orderByDesc(IotDeviceEventEntity::getEventTime,IotDeviceEventEntity::getId)
                .last("LIMIT "+Math.max(1,Math.min(500,limit==null?50:limit))));
    }
    @Override
    public List<IotDeviceEventEntity> getRecentEventsByDeviceCode(String code,Integer limit) {
        return this.list(scoped(null,code,"read").orderByDesc(IotDeviceEventEntity::getEventTime,IotDeviceEventEntity::getId)
                .last("LIMIT "+Math.max(1,Math.min(500,limit==null?50:limit))));
    }
    @Override
    public List<IotDeviceEventEntity> getByDeviceId(Long deviceId) {return getRecentEventsByDeviceId(deviceId,500);}
    @Override
    public List<IotDeviceEventEntity> getByDeviceCode(String code) {return getRecentEventsByDeviceCode(code,500);}

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveEvent(Long deviceId, String deviceCode, Long productId, String productCode,
                             String identifier, String name, String eventType, Map<String, Object> eventData, String factoryCode) {
        IotDeviceEventEntity event = new IotDeviceEventEntity();
        event.setDeviceId(deviceId);
        event.setDeviceCode(deviceCode);
        event.setProductId(productId);
        event.setProductCode(productCode);
        event.setIdentifier(identifier);
        event.setName(name);
        event.setEventType(eventType);
        event.setEventData(JSONUtil.toJsonStr(eventData));
        event.setEventTime(LocalDateTime.now());
        event.setFactoryCode(factoryCode);
        prepareEvent(event);

        boolean saved = this.save(event);
        com.kitex.mom.iot.service.impl.IotAlarmService.require(saved,"设备事件保存失败");
        if (saved && shouldForward(event)) {
            outbox.enqueue(event);
        }
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDeviceEventEntity retryMesReportForward(Long eventId,com.kitex.mom.iot.dto.IotMesRetryCommand command) {
        return outbox.retry(eventId,command);
    }

    private boolean shouldForward(IotDeviceEventEntity event) {
        return event != null && PRODUCTION_OUTPUT_EVENT.equalsIgnoreCase(event.getEventType())
                && StrUtil.isNotBlank(event.getDeviceCode()) && StrUtil.isNotBlank(event.getIdentifier());
    }

}
