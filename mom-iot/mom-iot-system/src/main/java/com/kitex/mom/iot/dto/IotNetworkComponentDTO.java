package com.kitex.mom.iot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * IoT网络组件DTO
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@Schema(description = "IoT网络组件DTO")
public class IotNetworkComponentDTO {

    /**
     * 主键ID
     */
    @Schema(description = "主键ID")
    private Long id;

    /**
     * 组件名称
     */
    @Schema(description = "组件名称")
    private String name;

    /**
     * 组件类型（MQTT服务/HTTP/CoAP等）
     */
    @Schema(description = "组件类型")
    private String type;

    /**
     * 集群模式（共享配置/独立配置）
     */
    @Schema(description = "集群模式")
    private String clusterMode;

    /**
     * 本地地址
     */
    @Schema(description = "本地地址")
    private String localAddress;

    /**
     * 本地端口
     */
    @Schema(description = "本地端口")
    private Integer localPort;

    /**
     * 公网地址
     */
    @Schema(description = "公网地址")
    private String publicAddress;

    /**
     * 公网端口
     */
    @Schema(description = "公网端口")
    private Integer publicPort;

    /**
     * 最大消息长度
     */
    @Schema(description = "最大消息长度")
    private Integer maxMessageSize;

    /**
     * 是否启用TLS
     */
    @Schema(description = "是否启用TLS")
    private Boolean tlsEnabled;

    /**
     * 证书ID
     */
    @Schema(description = "证书ID")
    private String certificateId;

    /**
     * 私钥别名
     */
    @Schema(description = "私钥别名")
    private String keyAlias;

    /**
     * 说明
     */
    @Schema(description = "说明")
    private String description;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;
} 