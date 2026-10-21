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
 * 逐标识退回上序申请及生效路线版本。
 *
 * <p>一条批准记录就是一个不可覆盖的执行版本。它只用于在制品复核重过站，
 * 不改变原始工艺路线，不重复增加工单完工量；末工序产出回退或需要补料时必须走返工工单。</p>
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_identifier_route_return")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "逐标识退回上序申请")
public class MesIdentifierRouteReturnEntity extends BaseEntity<MesIdentifierRouteReturnEntity> {

    @Schema(description = "申请单号")
    private String returnNo;

    @Schema(description = "生产工单号")
    private String taskCode;

    @Schema(description = "规范生产标识")
    private String identifier;

    @Schema(description = "标识路线版本号")
    private Integer versionNo;

    @Schema(description = "退回内容协议：NODE_ROUTE_RETURN_V2；历史NULL保持原合同")
    private String returnSchemaVersion;

    @Schema(description = "申请依据的完整原始JSON；历史记录不补造")
    private String sourceSnapshot;

    @Schema(description = "申请时唯一已过前沿的实际工序任务")
    private String sourceProduceTaskCode;

    @Schema(description = "申请时唯一已过前沿的稳定节点")
    private String sourceRouteNodeCode;

    @Schema(description = "批准退回目标的实际工序任务")
    private String targetProduceTaskCode;

    @Schema(description = "批准退回目标的稳定节点")
    private String targetRouteNodeCode;

    @Schema(description = "全部批准节点完成后恢复的实际工序任务")
    private String resumeProduceTaskCode;

    @Schema(description = "恢复节点；不能用同名主工序替代")
    private String resumeRouteNodeCode;

    @Schema(description = "申请时最近已过工序编码")
    private String sourceRouteCode;

    @Schema(description = "申请时最近已过工序名称")
    private String sourceRouteName;

    @Schema(description = "申请时最近已过工序顺序")
    private Integer sourceRouteOrder;

    @Schema(description = "退回目标工序编码")
    private String targetRouteCode;

    @Schema(description = "退回目标工序名称")
    private String targetRouteName;

    @Schema(description = "退回目标工序顺序")
    private Integer targetRouteOrder;

    @Schema(description = "本次循环恢复工序编码")
    private String resumeRouteCode;

    @Schema(description = "本次循环恢复工序名称")
    private String resumeRouteName;

    @Schema(description = "本次循环恢复工序顺序")
    private Integer resumeRouteOrder;

    @Schema(description = "目标至恢复工序的冻结路线JSON")
    private String routeSnapshot;

    @Schema(description = "申请依据SHA-256指纹")
    private String sourceFingerprint;

    @Schema(description = "状态：PENDING/ACTIVE/REJECTED/WITHDRAWN/COMPLETED")
    private String status;

    @Schema(description = "申请原因")
    private String reason;

    private Long applicantId;
    private String applicantCode;
    private String applicantName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime appliedTime;

    private Long reviewerId;
    private String reviewerCode;
    private String reviewerName;
    private String reviewReason;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reviewedTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime activatedTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime completedTime;

    private Long completedReportId;

    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
