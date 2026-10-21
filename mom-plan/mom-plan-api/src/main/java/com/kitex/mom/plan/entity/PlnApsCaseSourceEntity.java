package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 智能 APS 案件业务来源引用。
 *
 * <p>只保存来源身份、版本和指纹，真实名称、数量、状态与工厂必须在领域服务中重新取证。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_aps_case_source")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "智能 APS 案件业务来源引用")
public class PlnApsCaseSourceEntity extends BaseEntity<PlnApsCaseSourceEntity> {

    @Schema(description = "所属 APS 案件主键")
    private Long caseId;

    @Schema(description = "来源对象类型，如ORDER、MPS、SHORTAGE、DEVICE、QUALITY_CASE")
    private String sourceType;

    @Schema(description = "来源对象主键")
    private Long sourceId;

    @Schema(description = "服务端查询得到的来源业务编码")
    private String sourceCode;

    @Schema(description = "来源对象业务版本")
    private String sourceVersion;

    @Schema(description = "来源对象冻结内容指纹")
    private String sourceFingerprint;

    @Schema(description = "来源在案件中的角色：PRIMARY、AFFECTED 或 RELATED")
    private String sourceRole;

    @Schema(description = "来源关系状态：ACTIVE、STALE 或 SUPERSEDED")
    private String relationshipStatus;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "工厂编码")
    private String factoryCode;
}
