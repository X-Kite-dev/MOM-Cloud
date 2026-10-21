package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 工艺参数标准版本表头。
 *
 * <p>一个版本覆盖一条工艺路线。已发布版本不能原地修改，生产工单下达时会冻结
 * 完整参数清单和内容指纹。</p>
 */
@Data
@TenantTable
@TableName("mdm_process_parameter_standard")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工艺参数标准版本")
public class MdmProcessParameterStandardEntity extends BaseEntity<MdmProcessParameterStandardEntity> {

    @Schema(description = "标准编码，同一标准的不同版本保持一致")
    private String standardNo;

    @Schema(description = "标准名称")
    private String standardName;

    @Schema(description = "版本号，从1开始递增")
    private Integer versionNo;

    @Schema(description = "内容协议：历史NULL为LEGACY_PROCESS_V1；新草稿NODE_V2，与业务版本号独立")
    private String contentSchemaVersion;

    @Schema(description = "工艺路线ID")
    private Long processRouteId;

    @Schema(description = "工艺路线编码")
    private String routeCode;

    @Schema(description = "工艺路线名称")
    private String routeName;

    @Schema(description = "DRAFT草稿、RELEASED已发布、RETIRED已退役")
    private String status;

    @Schema(description = "生效时间")
    private LocalDateTime effectiveTime;

    @Schema(description = "发布后的参数内容SHA-256指纹")
    private String contentFingerprint;

    @Schema(description = "参数项数量")
    private Integer itemCount;

    @Schema(description = "发布人用户ID；必须与编制人不同")
    private String releasedBy;

    @Schema(description = "发布人姓名快照")
    private String releasedByName;

    @Schema(description = "发布时间")
    private LocalDateTime releasedTime;

    @Schema(description = "退役操作人用户ID")
    private String retiredBy;

    @Schema(description = "退役操作人姓名快照")
    private String retiredByName;

    @Schema(description = "退役时间")
    private LocalDateTime retiredTime;

    @Schema(description = "退役原因")
    private String retireReason;

    @Schema(description = "版本说明")
    private String remark;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
