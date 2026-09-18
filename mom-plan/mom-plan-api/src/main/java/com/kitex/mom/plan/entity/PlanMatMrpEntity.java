package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * MRP运算记录表
 *
 * @author kitex
 * @date 2025-04-15 17:37:58
 */
@Data
@TenantTable
@TableName("plan_mat_mrp")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MRP运算记录表")
public class PlanMatMrpEntity extends BaseEntity<PlanMatMrpEntity> {

    /**
     * 客户订单号
     */
    @Schema(description = "客户订单号")
    private String planDemandNo;

    @Schema(description = "MRP运算采用的已发布MPS版本ID；旧直达需求为空")
    private Long mpsVersionId;

    @Schema(description = "MRP运算采用的已发布MPS需求行ID")
    private Long mpsLineId;

    /**
     * 销售单号
     */
    @Schema(description = "销售单号")
    private String saleOrderCode;

    /**
     * bom ID
     */
    @Schema(description = "bom ID")
    private Long bomId;

    /**
     * 子级bom ID
     */
    @Schema(description = "子级bom ID")
    private Long subitemBomId;

    /**
     * 客户编码
     */
    @Schema(description = "客户编码")
    private String customerCode;

    /**
     * 客户名称
     */
    @Schema(description = "客户名称")
    private String customerName;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;

    /**
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;

    @Schema(description = "MRP运算时冻结的需求领料仓库编码")
    private String demandWarehouseCode;

    @Schema(description = "MRP运算时冻结的需求领料仓库名称")
    private String demandWarehouseName;

    /**
     * 提前期
     */
    @Schema(description = "提前期")
    private Integer leadTime;

    /**
     * 安全库存
     */
    @Schema(description = "安全库存")
    private Integer safetyStock;

    /**
     * 最小订购量
     */
    @Schema(description = "最小订购量")
    private Integer minOrderQuantity;

    /**
     * 固定批量
     */
    @Schema(description = "固定批量")
    private Integer lotSize;

    /**
     * 时间
     */
    @Schema(description = "时间")
    private LocalDate orderDate;

    @Schema(description = "原始需求日期")
    private LocalDate demandDate;

    @Schema(description = "本次MRP冻结的BOM逐层需求路径JSON；同一物料多路径分别保留")
    private String bomPathSnapshot;

    @Schema(description = "BOM逐层需求路径SHA-256指纹")
    private String bomPathFingerprint;

    @Schema(description = "本物料在本次需求中出现的最大BOM层级，根产品为0")
    private Integer maxBomLevel;

    /**
     * 需求数量
     */
    @Schema(description = "需求数量")
    private BigDecimal num;

    @Schema(description = "BOM毛需求数量")
    private BigDecimal grossDemandNum;

    @Schema(description = "本次MRP分配的主料可用库存")
    private BigDecimal stockAllocatedNum;

    @Schema(description = "MRP运算批次号；同批需求共享一个库存时间桶求解上下文")
    private String mrpRunNo;

    @Schema(description = "当前需求在同一需求日期时间桶内的库存分配优先级；数值越大越优先")
    private Integer inventoryAllocationPriority;

    @Schema(description = "跨需求库存分配顺序；按需求日、优先级、需求号稳定生成")
    private Integer inventoryAllocationSequence;

    @Schema(description = "已形成库存来源Pegging的主料数量")
    private BigDecimal inventoryPeggedNum;

    @Schema(description = "库存Pegging状态：NONE、PLANNED、RESERVED、CONSUMED、RELEASED、STALE")
    private String inventoryPeggingStatus;

    @Schema(description = "库存来源、批次、数量、时间桶和需求路径的SHA-256证据指纹")
    private String inventoryPeggingFingerprint;

    @TableField(exist = false)
    @Schema(description = "本次运算暂存的库存Pegging明细；MRP保存后写入独立台账")
    private List<PlanMrpInventoryPeggingEntity> inventoryPeggingDetails;

    @Schema(description = "替代决策前净缺料数量")
    private BigDecimal netDemandNum;

    @Schema(description = "替代决策后仍需供应的需求数量")
    private BigDecimal remainingDemandNum;

    @Schema(description = "齐套状态：READY、SHORTAGE、SUBSTITUTE_PENDING、SUBSTITUTE_APPROVED、PARTIAL_SHORTAGE")
    private String readinessStatus;

    @Schema(description = "替代决策状态：NONE、PENDING、APPROVED、PARTIAL_APPROVED、REJECTED、CANCELLED、POLICY_CONFLICT、MULTILEVEL_RECALC_REQUIRED")
    private String substituteStatus;

    @Schema(description = "MRP运算时冻结的替代策略JSON")
    private String substitutePolicySnapshot;

    @Schema(description = "是否允许主料与多个替代料混用")
    private Boolean substituteMixAllowed;

    @Schema(description = "替代选用顺序")
    private String substituteStrategy;

    @Schema(description = "系统生成的替代建议JSON")
    private String substituteSuggestion;

    @Schema(description = "已批准的替代分配JSON")
    private String substituteAllocation;

    @Schema(description = "替代决策请求唯一号")
    private String substituteDecisionRequestId;

    @Schema(description = "替代决策说明")
    private String substituteDecisionReason;

    @Schema(description = "替代决策人编码")
    private String substituteDecisionBy;

    @Schema(description = "替代决策人名称")
    private String substituteDecisionByName;

    @Schema(description = "替代决策时间")
    private java.time.LocalDateTime substituteDecisionTime;

    @Schema(description = "替代决策撤销请求唯一号")
    private String substituteCancelRequestId;

    @Schema(description = "替代决策撤销原因")
    private String substituteCancelReason;

    @Schema(description = "替代决策撤销人编码")
    private String substituteCancelBy;

    @Schema(description = "替代决策撤销人名称")
    private String substituteCancelByName;

    @Schema(description = "替代决策撤销时间")
    private java.time.LocalDateTime substituteCancelTime;

    @Schema(description = "WMS正式物料预留单号")
    private String materialReservationNo;

    @Schema(description = "WMS物料预留状态：ACTIVE、RELEASED、CONSUMED")
    private String materialReservationStatus;

    @Schema(description = "WMS预留结果快照JSON")
    private String materialReservationSnapshot;

    @Schema(description = "WMS物料预留时间")
    private java.time.LocalDateTime materialReservationTime;

    @Schema(description = "WMS主料正式预留单号")
    private String mainMaterialReservationNo;

    @Schema(description = "WMS主料预留状态：ACTIVE、RELEASED、CONSUMED")
    private String mainMaterialReservationStatus;

    @Schema(description = "WMS主料预留结果快照JSON")
    private String mainMaterialReservationSnapshot;

    @Schema(description = "WMS主料预留时间")
    private java.time.LocalDateTime mainMaterialReservationTime;

    @TableField(exist = false)
    @Schema(description = "WMS主料预留当前是否通过完整性复核")
    private Boolean mainMaterialReservationHealthy;

    @TableField(exist = false)
    @Schema(description = "WMS主料预留当前风险说明")
    private String mainMaterialReservationRisk;

    @TableField(exist = false)
    @Schema(description = "WMS预留当前是否通过完整性与合规复核")
    private Boolean materialReservationHealthy;

    @TableField(exist = false)
    @Schema(description = "WMS预留当前风险说明")
    private String materialReservationRisk;

    /**
     * 完成数量
     */
    @Schema(description = "完成数量")
    private BigDecimal completeNum;

    /**
     * 类型，采购、自制、委外
     */
    @Schema(description = "类型，采购、自制、委外")
    private String type;

    /**
     * 状态，1已下发、0未下发
     */
    @Schema(description = "状态，1已下发、0未下发")
    private Boolean status;

    @Schema(description = "MRP下达实际生成的供应来源类型：SELF_ISSUE、PURCHASE_ISSUE_MONTH、OUTSOURCE_ISSUE_MONTH")
    private String supplySourceType;

    @Schema(description = "MRP下达实际生成的下层供应单据ID")
    private Long supplySourceId;

    @Schema(description = "MRP下达实际生成的下层供应单据号")
    private String supplySourceNo;

    @Schema(description = "MRP下达实际生成的下层供应数量")
    private BigDecimal supplySourceQuantity;

    @Schema(description = "下层供应单据状态快照：CREATED、WITHDRAWN")
    private String supplySourceStatus;

    @Schema(description = "下层供应受控撤回永久幂等请求号")
    private String supplyWithdrawalRequestId;

    @Schema(description = "下层供应受控撤回命令SHA-256指纹")
    private String supplyWithdrawalFingerprint;

    @Schema(description = "下层供应受控撤回原因")
    private String supplyWithdrawalReason;

    @Schema(description = "下层供应受控撤回时间")
    private java.time.LocalDateTime supplyWithdrawalTime;

    @Schema(description = "最近一次Agent采购执行永久幂等号")
    private String purchaseRequestId;

    @Schema(description = "最近一次Agent采购订单ID")
    private Long purchaseOrderId;

    @Schema(description = "最近一次Agent采购订单号")
    private String purchaseOrderNo;

    @Schema(description = "最近一次Agent采购订单行ID")
    private Long purchaseOrderDetailId;

    @Schema(description = "最近一次Agent采购数量")
    private BigDecimal purchaseOrderNum;

    @Schema(description = "最近一次Agent采购回执状态")
    private String purchaseOrderStatus;

    @Schema(description = "最近一次Agent采购执行回执JSON")
    private String purchaseOrderReceipt;

    @Schema(description = "最近一次Agent采购执行时间")
    private java.time.LocalDateTime purchaseOrderTime;

    @Schema(description = "最近一次已应用的PMS采购状态事件号")
    private String purchaseStatusEventId;

    @Schema(description = "最近一次PMS采购订单驳回原因")
    private String purchaseRejectReason;

    @Schema(description = "最近一次PMS采购订单驳回时间")
    private java.time.LocalDateTime purchaseRejectedTime;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
