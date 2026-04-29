package com.kitex.mom.iot.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.kitex.common.data.handler.JsonTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * IoT网关管理
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@TenantTable
@TableName("iot_gateway")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "IoT网关管理")
public class IotGatewayEntity extends BaseEntity<IotGatewayEntity> {

    /**
     * 网关名称
     */
    @Schema(description = "网关名称")
    private String gatewayName;

    /**
     * 网关编码
     */
    @Schema(description = "网关编码")
    private String gatewayCode;

    /**
     * 解析协议
     */
    @Schema(description = "解析协议")
    private String protocol;

    /**
     * 传输协议
     */
    @Schema(description = "传输协议,如TCP,MQTT,UDP")
    private String transport;

    /**
     * 网络组件ID
     */
    @Schema(description = "网络组件ID")
    private Long networkComponentId;


    /**
     * 配置信息(根据类型不同而不同)
     */
    @Schema(description = "配置信息")
    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> configuration;

    /**
     * 接入方式 mqtt-client-gateway
     */
    @Schema(description = "接入方式")
    private String provider;


    /**
     * 状态（0-禁用，1-启用, 2-暂停）
     */
    @Schema(description = "状态")
    private String status;

    /**
     * 描述
     */
    @Schema(description = "描述")
    private String description;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 网络组件信息（非数据库字段）
     */
    @TableField(exist = false)
    @Schema(description = "网络组件信息")
    private IotNetworkComponentEntity networkComponent;

} 