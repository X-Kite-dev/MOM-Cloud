package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 末序有效报工的人工来源核定版本。原报工和旧核定均不覆盖，纠正后以新版本重新核清。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("mes_report_source_assignment")
@Schema(description = "不可变报工来源核定")
public class MesReportSourceAssignmentEntity extends BaseEntity<MesReportSourceAssignmentEntity> {
    @Schema(description = "永久核定请求号，100位以内")
    private String requestId;
    @Schema(description = "原核定命令SHA-256")
    private String requestFingerprint;
    @Schema(description = "MES物理工单主键")
    private Long taskId;
    @Schema(description = "MES物理工单编码")
    private String taskCode;
    @Schema(description = "唯一末序的原报工主键")
    private Long reportId;
    @Schema(description = "同一报工的核定版本，从1递增")
    private Integer assignmentVersion;
    @Schema(description = "核定时当前有效量，不等于质量放行量或入库量")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal effectiveQuantity;
    @Schema(description = "原报工当前身份、执行种类、状态及有效数量指纹")
    private String reportFingerprint;
    @Schema(description = "精确APS来源身份和分配量的稳定指纹，不含可变完工/入库字段")
    private String sourceFingerprint;
    @Schema(description = "完整来源核定数组，allocationId和quantity为十进制文本")
    private String allocationSnapshot;
    @Schema(description = "提交时全量报工/来源/历史核定的证据快照")
    private String evidenceSnapshot;
    @Schema(description = "实际批次、扫码或作业单等核定凭证引用，必须由人填写，500位以内")
    private String evidenceReference;
    @Schema(description = "本次核定/重新核清原因，500位以内")
    private String reason;
    @Schema(description = "认证链路实际工厂")
    private String factoryCode;
}
