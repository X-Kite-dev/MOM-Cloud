package com.kitex.mom.iot.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.kitex.common.data.handler.JsonTypeHandler;
import com.kitex.mom.iot.core.network.NetworkProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * IoT网络组件实体
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@TenantTable
@TableName("iot_network_component")
@Schema(description = "IoT网络组件实体")
public class IotNetworkComponentEntity extends BaseEntity<IotNetworkComponentEntity> {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    /**
     * 组件名称
     */
    @Schema(description = "组件名称")
    private String name;

    /**
     * 组件类型（mqtt-server,http-server ）
     */
    @Schema(description = "组件类型")
    private String type;

    /**
     * 集群模式（共享配置/独立配置）
     */
    @Schema(description = "集群模式")
    private String clusterMode;


    /**
     * 配置信息 最终使用json进行存储 String
     */
    @Schema(description = "配置信息")
    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> configuration;
    /**
     * 说明
     */
    @Schema(description = "说明")
    private String description;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    public NetworkProperties toNetworkProperties() {
        return NetworkProperties.builder().id(String.valueOf(this.getId())).name(this.name).description(this.description).enabled(true)
                // 复制又有数据到prop 中
                .properties(configuration).build();
    }


}