package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 实际物料/路线/工序的转换工程版本；发布正文含单位依据和完整工程位置布局。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mdm_physical_transform_standard")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "实物转换工程标准版本")
public class MdmPhysicalTransformStandardEntity extends BaseEntity<MdmPhysicalTransformStandardEntity> {
    /** 标准家族编码。 */
    @Schema(description = "标准编码")
    private String standardNo;
    /** 标准中文名称。 */
    @Schema(description = "标准名称")
    private String standardName;
    /** 服务端分配的正式版本号。 */
    @Schema(description = "版本号")
    private Integer versionNo;
    /** 真实物料编码。 */
    @Schema(description = "物料编码")
    private String materialCode;
    /** 真实路线主键。 */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "工艺路线ID")
    private Long processRouteId;
    /** 真实路线编码，不冒充不存在的路线数字版号。 */
    @Schema(description = "工艺路线编码")
    private String processRouteCode;
    /** 路线具体工序。 */
    @Schema(description = "工序编码")
    private String processCode;
    /** 实物转换动作。 */
    @Schema(description = "转换动作")
    private String operationType;
    /** 跨单位原换算行；发布后原行修改不改本版本。 */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "原单位换算行ID")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long unitConversionId;
    /** DEPANEL布局编码；其发布版本等于本标准版本。 */
    @Schema(description = "工程位置布局编码")
    private String positionLayoutCode;
    /** 严格规范化九字段政策，不接受NONE。 */
    @Schema(description = "转换政策正文")
    private String policyJson;
    /** 当前版本完整工程原正文。 */
    @Schema(description = "工程正文")
    private String contentSnapshot;
    /** MES_PHYSICAL_ENGINEERING_V1版本域SHA。 */
    @Schema(description = "工程内容指纹")
    private String contentFingerprint;
    /** 当前必要写对应的精确永久履历ID，提交时数据库必须查到同一事实。 */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "最后操作履历ID")
    private Long lastOperationId;
    /** DRAFT、IN_REVIEW、RELEASED、RETIRED。 */
    @Schema(description = "版本状态")
    private String status;
    /** 真实工厂。 */
    @Schema(description = "工厂编码")
    private String factoryCode;
    /** 首次提交复核时刻，驳回/撤回后清空；永久履历保留。 */
    @Schema(description = "提交时间")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime submittedTime;
    /** 实际独立发布人的用户ID。 */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "发布人ID")
    private Long releasedById;
    /** 发布实名账号。 */
    @Schema(description = "发布人账号")
    private String releasedBy;
    /** 首次发布时间。 */
    @Schema(description = "发布时间")
    private LocalDateTime releasedTime;
    /** 实际退役人的ID。 */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "退役人ID")
    private Long retiredById;
    /** 退役实名账号。 */
    @Schema(description = "退役人账号")
    private String retiredBy;
    /** 一次性退役时刻。 */
    @Schema(description = "退役时间")
    private LocalDateTime retiredTime;
    /** 明确退役原因。 */
    @Schema(description = "退役原因")
    private String retireReason;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getTenantId() {
        return super.getTenantId();
    }
}
