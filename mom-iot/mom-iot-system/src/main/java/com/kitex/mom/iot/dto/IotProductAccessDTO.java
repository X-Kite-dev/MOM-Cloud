package com.kitex.mom.iot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * IoT产品接入信息DTO
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@Schema(description = "IoT产品接入信息DTO")
public class IotProductAccessDTO {

    /**
     * ID
     */
    @Schema(description = "ID")
    private Long id;

    /**
     * 产品ID
     */
    @Schema(description = "产品ID")
    private Long productId;

    /**
     * 连接类型(mqtt/http/coap等)
     */
    @Schema(description = "连接类型")
    private String connectType;

    /**
     * 服务器地址
     */
    @Schema(description = "服务器地址")
    private String serverAddress;

    /**
     * 服务器端口
     */
    @Schema(description = "服务器端口")
    private Integer serverPort;

    /**
     * 用户名
     */
    @Schema(description = "用户名")
    private String username;

    /**
     * 密码
     */
    @Schema(description = "密码")
    private String password;

    /**
     * 客户端ID前缀
     */
    @Schema(description = "客户端ID前缀")
    private String clientIdPrefix;

    /**
     * 订阅主题
     */
    @Schema(description = "订阅主题")
    private String subscribeTopic;

    /**
     * 发布主题
     */
    @Schema(description = "发布主题")
    private String publishTopic;

    /**
     * 是否使用TLS/SSL
     */
    @Schema(description = "是否使用TLS/SSL")
    private Boolean useTls;

    /**
     * 证书路径
     */
    @Schema(description = "证书路径")
    private String certPath;

    /**
     * 连接超时时间(秒)
     */
    @Schema(description = "连接超时时间(秒)")
    private Integer connectTimeout;

    /**
     * 心跳间隔(秒)
     */
    @Schema(description = "心跳间隔(秒)")
    private Integer keepAlive;

    /**
     * 消息QoS(0/1/2)
     */
    @Schema(description = "消息QoS")
    private Integer qos;

    /**
     * 是否清除会话
     */
    @Schema(description = "是否清除会话")
    private Boolean cleanSession;

    /**
     * 是否自动重连
     */
    @Schema(description = "是否自动重连")
    private Boolean autoReconnect;

    /**
     * 最大重连次数
     */
    @Schema(description = "最大重连次数")
    private Integer maxReconnect;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

    /**
     * 网关ID
     */
    @Schema(description = "网关ID")
    private Long gatewayId;

    /**
     * 网关名称
     */
    @Schema(description = "网关名称")
    private String gatewayName;

    /**
     * 是否使用网关接入
     */
    @Schema(description = "是否使用网关接入")
    private Boolean useGateway;
} 