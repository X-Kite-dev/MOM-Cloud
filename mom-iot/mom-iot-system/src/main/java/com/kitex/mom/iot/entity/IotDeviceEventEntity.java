package com.kitex.mom.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseCore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * IoT设备事件数据
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@com.kitex.common.core.util.TenantTable
@TableName("iot_device_event")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "IoT设备事件数据")
public class IotDeviceEventEntity extends BaseCore<IotDeviceEventEntity> {
    @Schema(description = "采集时绑定的租户，禁止按设备当前归属覆盖历史")
    private Long tenantId;
    @Schema(description = "原始来源归属是否已经核验；未核验历史不进入业务读视图")
    private Boolean scopeVerified;


    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键id")
    private Long id;
    /**
     * 设备ID
     */
    @Schema(description = "设备ID")
    private Long deviceId;

    /**
     * 设备编码
     */
    @Schema(description = "设备编码")
    private String deviceCode;

    /**
     * 产品ID
     */
    @Schema(description = "产品ID")
    private Long productId;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 事件标识符
     */
    @Schema(description = "事件标识符")
    private String identifier;

    /**
     * 事件名称
     */
    @Schema(description = "事件名称")
    private String name;

    /**
     * 事件类型
     */
    @Schema(description = "事件类型")
    private String eventType;

    /**
     * 事件数据(JSON格式)
     */
    @Schema(description = "事件数据")
    private String eventData;

    /**
     * 事件时间
     */
    @Schema(description = "事件时间")
    private LocalDateTime eventTime;

    @Schema(description = "所属工厂编码")
    private String factoryCode;

    @Schema(description = "投递/报工状态：PENDING、RETRY、DEAD为投递状态；SUCCESS、IGNORED、FAILED为MES业务回执")
    private String mesForwardStatus;

    @Schema(description = "MES自动报工受理消息")
    private String mesForwardMessage;

    @Schema(description = "MES自动报工事件台账ID")
    private Long mesAutoReportEventId;

    @Schema(description = "MES生成的报工记录ID")
    private Long mesReportId;

    @Schema(description = "最近一次转发MES时间")
    private LocalDateTime mesForwardTime;

}
