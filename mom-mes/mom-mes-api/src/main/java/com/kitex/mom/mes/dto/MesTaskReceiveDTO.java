package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 生产计划接收生成生产工单
 */
@Data
public class MesTaskReceiveDTO {

    /**
     * PLAN普通生产计划下达的永久幂等请求号。
     * APS批次下达仍以apsBatchNo作为业务幂等键，但也会携带本字段形成统一回执。
     */
    @Schema(description = "上游业务生成的MES工单接收永久幂等请求号")
    private String sourceRequestId;

    /**
     * 下达载荷SHA-256指纹；同一请求号只能对应同一份生产计划快照。
     */
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

    @Schema(description = "ERP正式生产订单号；MOM自建订单模式允许为空")
    private String erpProductionOrderNo;

    @Schema(description = "生产订单总量；拆批工单仍携带完整订单量")
    private BigDecimal productionOrderQuantity;

    /**
     * 生产订单号
     */
    private String productionOrder;

    /**
     * 计划单号
     */
    private String planNo;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String matCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String matName;

    /**
     * 产品规格
     */
    @Schema(description = "产品规格")
    private String matSpec;

    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date planStartDate;

    /**
     * 需求日期
     */
    @Schema(description = "需求日期")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date planEndDate;

    /**
     * 需求数量
     */
    @Schema(description = "需求数量")
    private BigDecimal noticeNum;

    /**
     * 日需求量
     */
    @Schema(description = "日需求量")
    private BigDecimal dayNeedNum;

    /**
     * 计划类型
     */
    @Schema(description = "计划类型")
    private String type;

    @Schema(description = "bomId")
    private Long bomId;

    @Schema(description = "bom版本")
    private String bomVersion;

    @Schema(description = "来源类型")
    private String resourceType;

    @Schema(description = "来源id")
    private Long resourceId;

    @Schema(description = "返工永久幂等请求号")
    private String reworkRequestId;

    @Schema(description = "原生产工单编码")
    private String sourceTaskCode;

    /**
     * 质量任务实际来自哪一道MES工序任务。新返工必须携带，用它解析稳定节点，
     * 不能只凭可能重复的主工序编码选择返工位置。
     */
    @Schema(description = "原实际生产工序任务编码")
    private String sourceProduceTaskCode;

    @Schema(description = "来源质检类型 PROCEDURE/PRODUCT")
    private String sourceInspectionType;

    @Schema(description = "来源质检任务ID")
    private Long sourceInspectionTaskId;

    @Schema(description = "来源质量处置记录ID")
    private Long sourceHandleRecordId;

    @Schema(description = "返工执行工序；为空时取原路线最终工序")
    private String reworkProcessCode;

    @Schema(description = "返工原因/处置要求")
    private String reworkReason;

    @Schema(description = "计划批准的WMS物料预留关系快照JSON")
    private String materialReservationSnapshot;

    @Schema(description = "APS锁定后的工序资源、设备、人员与时间快照JSON")
    private String apsOperationSnapshot;

    @Schema(description = "APS正式任务ID")
    private Long apsTaskId;

    @Schema(description = "APS执行批次ID")
    private Long apsBatchId;

    @Schema(description = "APS执行批次号，作为MES接收永久幂等键")
    private String apsBatchNo;

    @Schema(description = "APS批次类型 SINGLE/SPLIT/MERGED")
    private String apsBatchType;

    @Schema(description = "APS批次来源与数量指纹")
    private String apsBatchFingerprint;

    @Schema(description = "APS批次包含的原计划数量分配")
    private List<MesApsBatchAllocationDTO> apsBatchAllocations;

    /**
     * PLAN/APS已选择且经MDM核验的工程版本ID。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(description = "精确工程版本ID")
    private Long engineeringReleaseId;

    /**
     * 与releaseId共同组成不可替换的生产版本对。
     */
    @Schema(description = "工程版本原文SHA256")
    private String engineeringContentHash;

    @Schema(description = "路线内工程显示版本号")
    private Integer engineeringVersionNo;

    /**
     * 工艺路线编码
     */
    @Schema(description = "工艺路线编码")
    private String processRouteCode;

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
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;
}
