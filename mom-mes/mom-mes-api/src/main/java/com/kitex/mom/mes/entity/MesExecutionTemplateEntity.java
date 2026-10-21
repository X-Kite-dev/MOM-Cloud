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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 生产执行模板。
 *
 * <p>模板把报工对象、触发来源和现场控制从工艺工序类型中解耦。发布版本不可原地修改，
 * 派工时会把命中的版本和完整策略冻结到派工单，保证在制任务不受后续配置变化影响。</p>
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_execution_template")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "生产执行与报工策略模板")
public class MesExecutionTemplateEntity extends BaseEntity<MesExecutionTemplateEntity> {

    @Schema(description = "模板编码；同一编码通过版本号演进")
    private String templateCode;

    @Schema(description = "模板名称")
    private String templateName;

    @Schema(description = "版本号，从1递增")
    private Integer versionNo;

    @Schema(description = "生命周期状态：DRAFT/PUBLISHED/RETIRED")
    private String status;

    @Schema(description = "匹配优先级；数值越大越优先")
    private Integer priority;

    @Schema(description = "适用物料编码；为空表示不限")
    private String materialCode;

    /** 完整草稿保存允许清空主工序，关闭精确节点范围时不能残留旧匹配条件。 */
    @Schema(description = "适用工序编码；为空表示不限")
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String routeCode;

    /** 精确节点适用范围；与routeNodeCode一起设置，旧模板二者均为空。 */
    @Schema(description = "适用工艺路线编码，须与节点编码成对配置")
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String processRouteCode;

    /** 区分同一主工序在路线中的不同执行节点，不用序号作为身份。 */
    @Schema(description = "适用稳定节点编码，最长64字符")
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String routeNodeCode;

    @Schema(description = "适用车间编码；为空表示不限")
    private String workshopCode;

    @Schema(description = "适用工位编码；为空表示不限")
    private String worksiteCode;

    @Schema(description = "报工模式：QUANTITY/ONE_CODE/TRANSFER_CARD/PACKAGE_ONE_CODE/PACKAGE_QUANTITY")
    private String reportMode;

    @Schema(description = "允许触发来源，逗号分隔：MANUAL/AUTO_EVENT/API")
    private String allowedTriggers;

    @Schema(description = "标识解析方式：CANONICAL_ONLY/ALLOW_MAPPING/MAPPING_REQUIRED")
    private String identifierResolveMode;

    @Schema(description = "允许的映射标识类型，逗号分隔")
    private String allowedIdentifierTypes;

    /**
     * 节点式外码策略的公开配置JSON，不包含服务端工序图或指纹。
     * NONE不产生动态关系；非NONE草稿在完整执行链就绪前禁止发布。
     */
    @Schema(description = "节点式外码策略配置JSON；旧版本及默认策略为NONE，不等同静态标识映射")
    private String identifierNodePolicy;

    /**
     * 实物转换公开配置JSON；不是数学预检或服务端冻结资格，完整执行链前仅保存草稿。
     */
    @Schema(description = "实物转换公开配置JSON；旧NULL为NONE，发布/派工冻结后不可原改")
    private String physicalTransformPolicy;

    /**
     * 草稿选择的精确MDM正式转换工程版本ID；未来仅在新派工边界冻结，不改旧任务。
     */
    @Schema(description = "正式转换工程标准ID")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private Long physicalTransformStandardId;

    @Schema(description = "是否允许分批报工")
    private Boolean partialReportAllowed;

    @Schema(description = "是否允许超出派工数量报工")
    private Boolean overReportAllowed;

    @Schema(description = "允许超报比例，例如0.05表示5%")
    private BigDecimal overReportRate;

    @Schema(description = "单次报工数量上限；为空表示不额外限制")
    private BigDecimal maxSingleQuantity;

    @Schema(description = "报工前是否必须校验前置工序")
    private Boolean predecessorRequired;

    @Schema(description = "报工前是否必须校验生产投料齐套")
    private Boolean materialReadyRequired;

    @Schema(description = "报工前是否必须通过质量门")
    private Boolean qualityGateRequired;

    @Schema(description = "是否必须绑定实际工位")
    private Boolean worksiteRequired;

    @Schema(description = "是否必须绑定可运行设备")
    private Boolean deviceRequired;

    @Schema(description = "是否必须完成工装校验")
    private Boolean toolingRequired;

    @Schema(description = "是否允许移动端执行")
    private Boolean mobileAllowed;

    @Schema(description = "是否允许工站端执行")
    private Boolean stationAllowed;

    @Schema(description = "生效时间；为空表示发布后立即生效")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime effectiveFrom;

    @Schema(description = "失效时间；为空表示长期有效")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime effectiveTo;

    @Schema(description = "发布人账号")
    private String publishedBy;

    @Schema(description = "发布时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime publishedTime;

    @Schema(description = "业务说明")
    private String remark;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
