package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesAutoReportEventDTO;
import com.kitex.mom.mes.entity.MesAutoReportEventEntity;

/**
 * 可信设备采集领域服务；不提供无权限JSON转换或独立报工入口。
 */
public interface MesAutoReportCaptureService extends IService<MesAutoReportEventEntity> {
    /**
     * 首次接收前核定原实名、精确节点、实例与单件规则，填充不可变采集证据。
     */
    void freeze(MesAutoReportEventEntity event, MesAutoReportEventDTO input);

    /**
     * 同原事件续投的实名及事件权限检查，不能以内部调用标志绕过。
     */
    void authorizeDelivery(MesAutoReportEventEntity event);

    /**
     * 原事件处理/人工补偿仍须原采集账号及对应事件或重试权限。
     */
    void authorizeProcessing(MesAutoReportEventEntity event);
}
