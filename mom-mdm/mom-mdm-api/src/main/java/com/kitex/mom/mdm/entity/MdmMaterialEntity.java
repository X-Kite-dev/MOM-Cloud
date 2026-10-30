package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 物料实体类 - 用于管理生产物料的基础信息
 * 包含物料的基本属性、规格参数、单位信息等
 *
 * @author kitex
 * @date 2024-10-17 10:30:41
 */
@Data
@TenantTable
@TableName("mdm_material")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "物料表")
public class MdmMaterialEntity extends BaseEntity<MdmMaterialEntity> {

    /**
     * 物料名称 - 物料的中文名称，用于显示和识别
     */
    @Schema(description = "物料名称")
    private String materialName;

    /**
     * 物料编码 - 物料的唯一标识编码
     */
    @Schema(description = "物料编码")
    private String materialCode;

    /**
     * 物料型态 - 物料的类型分类（如：原材料、半成品、成品等）
     */
    @Schema(description = "物料型态")
    private String materialType;

    /**
     * 规格型号 - 物料的技术规格和型号参数
     */
    @Schema(description = "规格型号")
    private String specificationModel;

    /**
     * 状态 - 物料的启用状态（true-启用，false-禁用）
     */
    @Schema(description = "状态")
    private Boolean status;

    /**
     * 物料属性 - 物料的特性属性描述
     */
    @Schema(description = "物料属性")
    private String materialAttribute;

    /**
     * 基本单位 - 物料的基本计量单位编码
     */
    @Schema(description = "基本单位")
    private String basicUnit;

    /**
     * 分组编码 - 物料所属分组的编码
     */
    @Schema(description = "分组编码")
    private String groupCode;

    /**
     * 分组名称 - 物料所属分组的名称
     */
    @Schema(description = "分组名称")
    private String groupName;

    /**
     * 采购单位
     */
    @Schema(description = "采购单位")
    private String purchaseUnit;

    /**
     * 默认供应商
     */
    @Schema(description = "默认供应商")
    private String defaultSupplier;

    /**
     * 销售计量单位
     */
    @Schema(description = "销售计量单位")
    private String salesMeasureUnit;

    /**
     * 库存计量单位
     */
    @Schema(description = "库存计量单位")
    private String inventoryMeasureUnit;

    /**
     * 生产计量单位
     */
    @Schema(description = "生产计量单位")
    private String productionMeasureUnit;

    /**
     * 安全库存
     */
    @Schema(description = "最小安全库存")
    private BigDecimal minStock;

    /**
     * 安全库存
     */
    @Schema(description = "最大安全库存")
    private BigDecimal maxStock;

    /**
     * 安全库存
     */
    @Schema(description = "超限比例")
    private BigDecimal excessRatio;

    /**
     * 默认工作中心
     */
    @Schema(description = "默认工作中心")
    private String defaultWorkCenter;

    /**
     * 默认工艺路线
     */
    @Schema(description = "默认工艺路线")
    private String defaultProcessRoute;

    /**
     * 标准人员工时
     */
    @Schema(description = "标准人员工时")
    private BigDecimal standardLaborHours;

    /**
     * 标准机器工时
     */
    @Schema(description = "标准机器工时")
    private BigDecimal standardMachineHours;

    /**
     * 发料批量
     */
    @Schema(description = "发料批量")
    private BigDecimal issuanceBatch;

    /**
     * 是否计划性生产
     */
    @Schema(description = "是否计划性生产")
    private Integer plannedProduction;

    /**
     * 换线时长
     */
    @Schema(description = "换线时长")
    private BigDecimal changeoverTime;

    /**
     * APS排产产品族编码。与库存/财务物料分组解耦，用于顺序相关换型规则。
     */
    @Schema(description = "APS排产产品族编码")
    private String schedulingFamilyCode;

    @Schema(description = "APS排产产品族名称")
    private String schedulingFamilyName;

    /**
     * 模具规格
     */
    @Schema(description = "模具规格")
    private String moldSpecification;

    /**
     * 固定提前期
     */
    @Schema(description = "固定提前期")
    private BigDecimal fixedLeadTime;

    /**
     * 变动提前期
     */
    @Schema(description = "变动提前期")
    private BigDecimal variableLeadTime;

    /**
     * 检验提前期
     */
    @Schema(description = "检验提前期")
    private BigDecimal inspectionLeadTime;

    /**
     * 结构属性
     */
    @Schema(description = "结构属性")
    private String structuralAttributes;

    /**
     * 有效期状态
     */
    @Schema(description = "有效期状态")
    private Boolean expiration;

    /**
     * 有效期天数
     */
    @Schema(description = "有效期天数")
    private Integer expirationDay;

    /**
     * 呆滞物料状态
     */
    @Schema(description = "呆滞物料状态")
    private Boolean slowMaterial;

    /**
     * 呆滞物料预警天数
     */
    @Schema(description = "呆滞物料预警天数")
    private Integer slowMaterialDay;

    /**
     * 先入先出状态
     */
    @Schema(description = "先入先出状态")
    private Boolean fifo;

    /**
     * 跨仓储作业统一生效的物料批次管理要求。
     */
    @Schema(description = "是否按批次管理")
    private Boolean batchManaged;

    /**
     * 空批次自动生成时使用的编码模板；只定义结构，作业类型决定是否启用生成。
     */
    @Schema(description = "批次编码模板")
    private String batchCodeTemplate;

    /**
     * 单件物理身份编码模板，由WMS入库作业规则决定是否自动生成。
     */
    @Schema(description = "单件条码模板")
    private String serialCodeTemplate;

    /**
     * 单件身份要求由物料主数据定义，仓储作业规则只能加强。
     */
    @Schema(description = "是否按单件序列号管理")
    private Boolean serialManaged;

    /**
     * 先入先出字段
     */
    @Schema(description = "先入先出字段")
    private String fifoField;

    /**
     * 最小订购量
     */
    @Schema(description = "最小订购量")
    private Integer minOrderQuantity;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 物料图片
     */
    @Schema(description = "物料图片")
    private String filePath;

}
