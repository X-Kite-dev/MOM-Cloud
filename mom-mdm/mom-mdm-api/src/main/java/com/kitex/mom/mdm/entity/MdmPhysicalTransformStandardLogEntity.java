package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 转换工程标准的不可改删履历，完整保存版本前后事实及所有编制参与人。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mdm_physical_transform_standard_log")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "实物转换工程标准履历")
public class MdmPhysicalTransformStandardLogEntity extends BaseEntity<MdmPhysicalTransformStandardLogEntity> {
    /**
     * 精确原标准版本ID。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "标准ID")
    private Long standardId;
    /**
     * 真实工厂。
     */
    @Schema(description = "工厂编码")
    private String factoryCode;
    /**
     * CREATE、UPDATE、SUBMIT、WITHDRAW、REJECT、RELEASE、RETIRE或COPY。
     */
    @Schema(description = "动作")
    private String action;
    /**
     * 操作前状态，首次为空。
     */
    @Schema(description = "原状态")
    private String fromStatus;
    /**
     * 操作后状态。
     */
    @Schema(description = "结果状态")
    private String toStatus;
    /**
     * 完整未裁剪业务原因。
     */
    @Schema(description = "原因")
    private String reason;
    /**
     * 实际操作者ID。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "操作者ID")
    private Long actorId;
    /**
     * 实际实名账号。
     */
    @Schema(description = "操作者账号")
    private String actorCode;
    /**
     * 实际操作时刻。
     */
    @Schema(description = "操作时间")
    private LocalDateTime operatedTime;
    /**
     * 原版本完整记录JSON，首建为空。
     */
    @Schema(description = "操作前完整事实")
    private String beforeSnapshot;
    /**
     * 操作后完整记录JSON，不以状态标签替代事实。
     */
    @Schema(description = "操作后完整事实")
    private String afterSnapshot;
}
