package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/** 正式SOP版本，已批准内容不可原地编辑。 */
@Data
@TenantTable
@TableName("mdm_sop_version")
@EqualsAndHashCode(callSuper = true)
public class MdmSopVersionEntity extends BaseEntity<MdmSopVersionEntity> {

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() { return super.getId(); }

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getTenantId() { return super.getTenantId(); }

    /** 同一工厂内SOP稳定编号。 */
    @Schema(description = "同一工厂内SOP稳定编号")
    private String sopNo;

    /** 作业指导书名称。 */
    @Schema(description = "作业指导书名称")
    private String name;

    /** 业务版本号，从1递增。 */
    @Schema(description = "业务版本号，从1递增")
    private Integer versionNo;

    /** DRAFT/IN_REVIEW/RELEASED/REJECTED/RETIRED/WITHDRAWN。 */
    @Schema(description = "DRAFT/IN_REVIEW/RELEASED/REJECTED/RETIRED/WITHDRAWN")
    private String status;

    /** 修订来源精确版本。 */
    @Schema(description = "修订来源精确版本")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentVersionId;

    /** 经过服务端字节校验的原件ID。 */
    @Schema(description = "经过服务端字节校验的原件ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long originalId;

    /** 原件SHA256。 */
    @Schema(description = "原件SHA256")
    private String originalHash;

    /** 原件字节数。 */
    @Schema(description = "原件字节数")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long originalSize;

    /** 原件媒体类型。 */
    @Schema(description = "原件媒体类型")
    private String mediaType;

    /** 正文说明，不冒充原件解析正文。 */
    @Schema(description = "正文说明，不冒充原件解析正文")
    private String description;

    /** 适用范围及限制。 */
    @Schema(description = "适用范围及限制")
    private String applicability;

    /** 稳定版本内容SHA256。 */
    @Schema(description = "稳定版本内容SHA256")
    private String contentHash;

    /** 包含当前状态的并发修订指纹。 */
    @Schema(description = "包含当前状态的并发修订指纹")
    private String revisionHash;

    /** 所有编制和编辑人员ID集合。 */
    @Schema(description = "所有编制和编辑人员ID集合")
    private String contributorsJson;

    /** 提交人实名ID。 */
    @Schema(description = "提交人实名ID")
    private String submittedBy;

    /** 提交时刻。 */
    @Schema(description = "提交时刻")
    private LocalDateTime submittedTime;

    /** 独立审核人实名ID。 */
    @Schema(description = "独立审核人实名ID")
    private String reviewedBy;

    /** 审核时刻。 */
    @Schema(description = "审核时刻")
    private LocalDateTime reviewedTime;

    /** 审核意见。 */
    @Schema(description = "审核意见")
    private String reviewReason;

    /** 立即生效时刻。 */
    @Schema(description = "立即生效时刻")
    private LocalDateTime effectiveFrom;

    /** 退役或安全撤回原因。 */
    @Schema(description = "退役或安全撤回原因")
    private String retireReason;

    /** 退役或撤回实名人员ID。 */
    @Schema(description = "退役或撤回实名人员ID")
    private String retiredBy;

    /** 退役或撤回时刻。 */
    @Schema(description = "退役或撤回时刻")
    private LocalDateTime retiredTime;

    /** 所属工厂编码。 */
    @Schema(description = "所属工厂编码")
    private String factoryCode;
}

