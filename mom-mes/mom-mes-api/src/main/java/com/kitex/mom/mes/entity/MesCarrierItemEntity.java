package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 载具内逐件成员谱系；卸载后保留历史行，不覆盖原装载关系。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_carrier_item")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "生产载具逐件成员")
public class MesCarrierItemEntity extends BaseEntity<MesCarrierItemEntity> {
    private Long carrierId;
    private String carrierCode;
    private String identifier;
    private String taskCode;
    private String matCode;
    private String matName;
    @Schema(description = "成员状态：LOADED/UNLOADED")
    private String status;
    private String loadRequestId;
    private LocalDateTime loadedTime;
    private String unloadRequestId;
    private LocalDateTime unloadedTime;
    private Long lastReportId;
    private String lastArrangeCode;
    private String lastProduceTaskCode;
    private String lastRouteCode;
    private String lastRouteName;
    private LocalDateTime lastReportTime;
    private Integer reportCount;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
