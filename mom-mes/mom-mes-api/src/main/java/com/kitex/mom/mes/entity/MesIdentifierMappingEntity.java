package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 现场扫描标识到MES规范生产标识的受控映射。
 *
 * <p>映射只改变输入标识的解释，不改变工单、物料、工序、数量或质量结论。已发布映射不可
 * 原地修改；临时码必须同时受有效期和最大使用次数约束。</p>
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_identifier_mapping")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "生产标识转换映射")
public class MesIdentifierMappingEntity extends BaseEntity<MesIdentifierMappingEntity> {

    @Schema(description = "映射编码；通过版本号演进")
    private String mappingCode;

    @Schema(description = "映射名称")
    private String mappingName;

    @Schema(description = "版本号")
    private Integer versionNo;

    @Schema(description = "状态：DRAFT/PUBLISHED/RETIRED")
    private String status;

    @Schema(description = "优先级；数值越大越优先")
    private Integer priority;

    @Schema(description = "输入别名、临时码或外部码")
    private String aliasCode;

    @Schema(description = "MES内部统一使用的规范标识")
    private String canonicalCode;

    @Schema(description = "标识类型：TEMPORARY/ALTERNATE/EXTERNAL/CUSTOMER/SUPPLIER/LEGACY")
    private String identifierType;

    @Schema(description = "对象类型：UNIT/TRAVELER/PACKAGE/ANY")
    private String objectType;

    @Schema(description = "限定物料编码；为空表示不限")
    private String materialCode;

    @Schema(description = "限定生产工单；为空表示不限")
    private String taskCode;

    @Schema(description = "限定工序；为空表示不限")
    private String routeCode;

    @Schema(description = "限定工位；为空表示不限")
    private String worksiteCode;

    @Schema(description = "最大成功使用次数；为空表示不限制")
    private Integer maxUseCount;

    @Schema(description = "累计成功使用次数")
    private Integer usedCount;

    @Schema(description = "生效时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime effectiveFrom;

    @Schema(description = "失效时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime effectiveTo;

    @Schema(description = "发布人")
    private String publishedBy;

    @Schema(description = "发布时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime publishedTime;

    @Schema(description = "业务依据和说明")
    private String remark;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
