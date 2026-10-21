package com.kitex.mom.oms.entity;

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

/** OMS订单供应级联不可变操作履历，同时承担人工命令永久幂等账。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("oms_order_supply_replan_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "OMS订单供应级联不可变操作履历")
public class OmsOrderSupplyReplanOperationEntity extends BaseEntity<OmsOrderSupplyReplanOperationEntity> {
    @Schema(description = "供应级联主账ID")
    private Long replanId;
    @Schema(description = "永久幂等请求号")
    private String requestId;
    @Schema(description = "请求载荷指纹")
    private String requestFingerprint;
    @Schema(description = "提交、复核、续跑、失败、完成或订单变更终态回投动作")
    private String operationType;
    @Schema(description = "动作前主账状态")
    private String beforeStatus;
    @Schema(description = "动作后主账状态")
    private String afterStatus;
    @Schema(description = "操作原因或执行结果说明")
    private String content;
    @Schema(description = "操作人工号")
    private String operatorCode;
    @Schema(description = "操作人姓名")
    private String operatorName;
    @Schema(description = "操作时间")
    private LocalDateTime operationTime;
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "工厂编码")
    private String factoryCode;
}
