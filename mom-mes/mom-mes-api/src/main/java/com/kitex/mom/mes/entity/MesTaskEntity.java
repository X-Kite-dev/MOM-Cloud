package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.kitex.mom.mes.dto.RouteProcessDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 生产工单 实体类
 *
 * @author kitex
 * @since 2025-05-05
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "生产工单")
@TableName("mes_task")
public class MesTaskEntity extends BaseEntity<MesTaskEntity> {
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long reworkMaterialPlanId;

    /**
     * 工单编码
     */
    @Schema(description = "工单编码")
    private String code;

    /**
     * 工单类型 成品计划 零件计划
     */
    @Schema(description = "工单类型 成品计划 零件计划")
    private String type;

    /**
     * 生产计划单号
     */
    @Schema(description = "生产计划单号")
    private String planNo;

    @Schema(description = "上游业务生成的MES工单接收永久幂等请求号")
    private String sourceRequestId;

    @Schema(description = "MES工单接收完整载荷SHA-256指纹")
    private String sourceRequestFingerprint;

    @Schema(description = "正式计划合同代表行ID")
    private Long formalPlanContractId;

    @Schema(description = "正式计划合同编号")
    private String formalPlanContractNo;

    @Schema(description = "正式计划合同版本")
    private Integer formalPlanContractVersion;

    @Schema(description = "正式计划合同内容SHA-256")
    private String formalPlanContractFingerprint;

    @Schema(description = "MES接收时逐正式合同明细的数量分配快照JSON；历史为空，不回填")
    private String formalPlanAllocationSnapshot;

    @Schema(description = "合同分配模型版本；1为逐行快照，历史为空")
    private Integer formalPlanAllocationVersion;

    @Schema(description = "正式计划来源系统")
    private String sourceSystem;

    @Schema(description = "正式计划来源计划号")
    private String sourcePlanNo;

    @Schema(description = "正式计划来源版本")
    private String sourcePlanVersion;

    @Schema(description = "工厂计划权威策略ID")
    private Long planningPolicyId;

    @Schema(description = "工厂计划权威策略版本")
    private Integer planningPolicyVersion;

    @Schema(description = "工厂计划权威策略SHA-256")
    private String planningPolicyFingerprint;

    @Schema(description = "接单时冻结的正式MRP权威：ERP_MRP或MOM_MRP")
    private String mrpAuthority;

    @Schema(description = "生产订单同步模式")
    private String productionOrderSyncMode;

    @Schema(description = "ERP正式生产订单号")
    private String erpProductionOrderNo;

    /**
     * 生产订单号
     */
    @Schema(description = "生产订单号")
    private String productionOrder;

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
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 计划开始时间
     */
    @Schema(description = "计划开始时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planStartDate;

    /**
     * 计划结束时间
     */
    @Schema(description = "计划结束时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planEndDate;

    /**
     * 总需求数量
     */
    @Schema(description = "总需求数量")
    private BigDecimal noticeNum;

    /**
     * 日需求数量
     */
    @Schema(description = "日需求数量")
    private BigDecimal dayNeedNum;

    /**
     * 已排产数量
     */
    @Schema(description = "已排产数量")
    private BigDecimal plannedNum;

    /**
     * 完成数量
     */
    @Schema(description = "完成数量")
    private BigDecimal completeNum;

    /**
     * 成品入库量
     */
    @Schema(description = "成品入库量")
    private BigDecimal inStockNum;

    /**
     * 成品待入库量
     */
    @Schema(description = "成品待入库量")
    private BigDecimal unInStockNum;

    /**
     * 不合格品数量
     */
    @Schema(description = "不合格品数量")
    private BigDecimal unPassNum;

    /**
     * 已下达数量
     */
    @Schema(description = "已下达数量")
    private BigDecimal alreadyNoticeNum;

    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    /**
     * 完成时间
     */
    @Schema(description = "完成时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishTime;

    /**
     * 完工状态版本；报工冲销使已完成工单重新打开时递增。
     */
    @Schema(description = "完工状态版本，用于PLAN完成/重新打开事件排序")
    private Integer completionVersion;

    /**
     * 状态 新建、下达、已派工、生产中、完成
     */
    @Schema(description = "状态 新建、下达、已派工、生产中、完成")
    private String status;

    /**
     * BOM ID
     */
    @Schema(description = "bomId")
    private Long bomId;

    /**
     * BOM快照
     */
    @Schema(description = "bom快照")
    private String bomSnapshot;

    /**
     * BOM版本
     */
    @Schema(description = "bom版本")
    private String bomVersion;

    /**
     * 工艺路线ID
     */
    @Schema(description = "工艺路线id")
    private Long processRouteId;

    /**
     * 工艺路线快照
     */
    @Schema(description = "工艺路线快照")
    private String processRouteSnapshot;

    /**
     * 工艺路线版本
     */
    @Schema(description = "工艺路线版本")
    private String processRouteCode;

    /**
     * 本工单在接收时精确核验的MDM工程版本。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(description = "精确工程版本ID")
    private Long engineeringReleaseId;

    @Schema(description = "工程版本原文SHA256")
    private String engineeringContentHash;

    @Schema(description = "路线内工程显示版本号")
    private Integer engineeringVersionNo;

    /**
     * MDM经当前来源精确复核后返回的原始冻结UTF-8正文。
     */
    @Schema(description = "MDM工程联合包原始冻结正文")
    private String engineeringBundleSnapshot;

    @Schema(description = "工单下达时冻结的工艺参数标准编码")
    private String processParameterStandardNo;

    @Schema(description = "工单下达时冻结的工艺参数标准版本")
    private Integer processParameterStandardVersion;

    @Schema(description = "工单下达时冻结的完整工艺参数标准JSON；无已发布标准时也冻结明确的空目录")
    private String processParameterStandardSnapshot;

    @Schema(description = "工艺参数标准冻结JSON的SHA-256指纹")
    private String processParameterSnapshotFingerprint;

    /**
     * 来源类型
     */
    @Schema(description = "来源类型")
    private String resourceType;

    /**
     * 来源ID
     */
    @Schema(description = "来源id")
    private String resourceId;

    @Schema(description = "返工永久幂等请求号")
    private String reworkRequestId;

    @Schema(description = "原生产工单编码")
    private String sourceTaskCode;

    @Schema(description = "原实际生产工序任务编码；用于证明返工来源节点")
    private String sourceProduceTaskCode;

    @Schema(description = "来源质检类型")
    private String sourceInspectionType;

    @Schema(description = "来源质检任务ID")
    private Long sourceInspectionTaskId;

    @Schema(description = "来源质量处置记录ID")
    private Long sourceHandleRecordId;

    @Schema(description = "返工执行工序")
    private String reworkProcessCode;

    @Schema(description = "返工执行的原路线稳定节点；升级前历史返工允许为空")
    private String reworkRouteNodeCode;

    /**
     * 返工执行方案批准的跨工单时间口径；新节点返工必须显式批准NONE或WINDOW。
     */
    private String reworkTimingPolicySnapshot;

    /**
     * INHERIT_SOURCE或APPROVED_OVERRIDE，由首轮返工执行方案异人批准。
     */
    @Schema(description = "返工SOP/参数工程口径")
    private String reworkEngineeringMode;

    /**
     * MDM已批准单节点返工覆盖版本；继承原节点时为空。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(description = "返工工程覆盖版本ID")
    private Long reworkEngineeringOverrideId;

    @Schema(description = "返工工程覆盖版本原文SHA256")
    private String reworkEngineeringOverrideHash;

    @Schema(description = "返工工程选择及MDM覆盖包不可变原文")
    private String reworkEngineeringScopeSnapshot;

    @Schema(description = "返工工程选择原文SHA256")
    private String reworkEngineeringScopeFingerprint;

    /**
     * 返工执行方案批准的原动态外码实例全集。返工不取得原实例所有权，
     * 只按原冻结版本解析现场码并另记返工使用账。
     */
    @Schema(description = "返工批准的原动态实例范围JSON")
    private String reworkIdentifierScopeSnapshot;

    @Schema(description = "返工原动态实例范围SHA-256")
    private String reworkIdentifierScopeFingerprint;

    @Schema(description = "返工原因/处置要求")
    private String reworkReason;

    @Schema(description = "MES完工回传QMS状态 PENDING/SUCCEEDED/FAILED")
    private String reworkCallbackStatus;

    @Schema(description = "MES完工回传QMS说明")
    private String reworkCallbackMessage;

    @Schema(description = "MES完工回传QMS次数")
    private Integer reworkCallbackCount;

    @Schema(description = "返工复检任务ID")
    private Long reinspectionTaskId;

    @Schema(description = "返工复检同步状态 WAIT/PARTIAL/RELEASED/REJECTED/FAILED")
    private String reinspectionStatus;

    @Schema(description = "返工复检同步说明")
    private String reinspectionMessage;

    @Schema(description = "返工复检累计放行数量")
    private BigDecimal reworkQualifiedNum;

    @Schema(description = "返工复检累计拒绝数量")
    private BigDecimal reworkRejectedNum;

    @Schema(description = "计划批准的WMS物料预留关系快照JSON")
    private String materialReservationSnapshot;

    @Schema(description = "APS锁定后的工序资源、设备、人员与时间快照JSON")
    private String apsOperationSnapshot;

    @Schema(description = "APS正式任务ID")
    private Long apsTaskId;

    @Schema(description = "APS执行批次ID")
    private Long apsBatchId;

    @Schema(description = "APS执行批次号，MES接收永久幂等键")
    private String apsBatchNo;

    @Schema(description = "APS批次类型 SINGLE/SPLIT/MERGED")
    private String apsBatchType;

    @Schema(description = "APS批次来源与数量指纹")
    private String apsBatchFingerprint;

    /**
     * 车间编码
     */
    @Schema(description = "车间编码")
    private String workshopCode;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshopName;

    /**
     * 包装编码
     */
    @Schema(description = "包装编码")
    private String wrapCode;

    /**
     * 包装容量
     */
    @Schema(description = "包装容量")
    private String wrapCapacity;

    /**
     * 批次号生成规则
     */
    @Schema(description = "批次号生成规则")
    private String batchRuleCode;

    /**
     * 流转卡绑定
     */
    @Schema(description = "流转卡绑定")
    private String transferCode;

    /**
     * 工序进度列表
     */
    @Schema(description = "工序进度列表")
    @TableField(exist = false)
    private List<RouteProcessDTO> processList;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
