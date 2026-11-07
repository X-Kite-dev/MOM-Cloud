package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 未接收或已接收且尚未开工派工的受控退派申请。
 *
 * <p>证据版本决定整根未执行或普通配额目标范围。批准后关闭原目标、释放未用工装；
 * 配额目标只释放自身额度并保持父在制状态，只有原未执行父满足条件才恢复待派工。
 * 原申请、派工及审批证据永久保留，不覆盖已执行事实。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_dispatch_return_request")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MES已接收未开工受控退派申请")
public class MesDispatchReturnRequestEntity extends BaseEntity<MesDispatchReturnRequestEntity> {

    @Schema(description = "退派申请单号")
    private String requestNo;

    @Schema(description = "提交永久幂等请求号")
    private String submitRequestId;

    @Schema(description = "提交命令指纹")
    private String submitFingerprint;

    @Schema(description = "目标派工主键")
    private Long arrangeId;

    @Schema(description = "目标派工单号")
    private String arrangeCode;

    @Schema(description = "生产工单号")
    private String taskCode;

    @Schema(description = "工序任务单号")
    private String produceTaskCode;

    /** 原申请时目标状态；只允许UN_RECEIVE或RECEIVE，不以后来的关闭状态覆盖。 */
    @Schema(description = "申请时目标派工状态：UN_RECEIVE/RECEIVE")
    private String originalStatus;

    @Schema(description = "SUBMITTED/APPROVED/REJECTED/WITHDRAWN/STALE/SUPERSEDED")
    private String status;

    @Schema(description = "申请时完整事实指纹")
    private String evidenceFingerprint;

    @Schema(description = "申请时完整事实快照JSON")
    private String evidenceSnapshot;

    @Schema(description = "退派原因")
    private String reason;

    @Schema(description = "申请人主键")
    private Long applicantId;
    @Schema(description = "申请人工号")
    private String applicantCode;
    @Schema(description = "申请人姓名")
    private String applicantName;
    @Schema(description = "申请提交时间")
    private LocalDateTime submittedTime;

    @Schema(description = "审批永久幂等请求号")
    private String reviewRequestId;

    @Schema(description = "审批命令指纹")
    private String reviewFingerprint;

    @Schema(description = "审批结论；true批准，false驳回或事实失效")
    private Boolean approved;
    @Schema(description = "审核依据")
    private String reviewReason;
    @Schema(description = "审核人主键")
    private Long reviewerId;
    @Schema(description = "审核人工号")
    private String reviewerCode;
    @Schema(description = "审核人姓名")
    private String reviewerName;
    @Schema(description = "审核时间")
    private LocalDateTime reviewedTime;

    @Schema(description = "批准执行或终止时间")
    private LocalDateTime handledTime;

    @Schema(description = "释放资源和状态迁移结果JSON")
    private String resultSnapshot;

    @Schema(description = "处理结果说明")
    private String resultMessage;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "实际工厂编码")
    private String factoryCode;
}
