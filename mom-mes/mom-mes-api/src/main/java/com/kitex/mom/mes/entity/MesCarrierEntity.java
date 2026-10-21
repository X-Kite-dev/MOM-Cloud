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
 * 可复用生产载具主档及当前占用状态。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_carrier")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "可复用生产载具")
public class MesCarrierEntity extends BaseEntity<MesCarrierEntity> {

    @Schema(description = "载具唯一编码")
    private String carrierCode;

    @Schema(description = "载具类型：TRAY/RACK/TOTE/CART/OTHER")
    private String carrierType;

    @Schema(description = "逐件容量")
    private Integer capacity;

    @Schema(description = "状态：EMPTY/LOADING/LOCKED/MAINTENANCE/RETIRED")
    private String status;

    @Schema(description = "当前在载生产标识数量")
    private Integer currentQuantity;

    @Schema(description = "并发控制及操作快照版本")
    private Integer versionNo;

    @Schema(description = "当前成员共同生产工单；空载时为空")
    private String taskCode;

    @Schema(description = "当前成员共同物料编码")
    private String matCode;

    @Schema(description = "当前成员共同物料名称")
    private String matName;

    @Schema(description = "当前成员共同的唯一下一派工单")
    private String readyArrangeCode;

    @Schema(description = "当前成员共同的唯一下一工序编码")
    private String readyRouteCode;

    @Schema(description = "当前成员共同的唯一下一工序名称")
    private String readyRouteName;

    @Schema(description = "最近整载具展开报工时间")
    private LocalDateTime lastReportTime;

    @Schema(description = "锁定人账号")
    private String lockedBy;

    @Schema(description = "锁定时间")
    private LocalDateTime lockedTime;

    @Schema(description = "最近操作原因")
    private String lastReason;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
