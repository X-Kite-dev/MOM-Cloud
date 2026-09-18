package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.Date;

@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "不合格品判定记录")
@TableName("qms_bad_info_detail")
public class QmsBadInfoDetailEntity extends BaseEntity<QmsBadInfoDetailEntity> {

    @Schema(description = "不合格明细永久编号")
    private String detailNo;
    /** 实际发布的多岗位会签提案；历史决策不补造。 */
    private Long mrbProposalId;

    @Schema(description = "判定幂等请求号")
    private String requestId;

    @Schema(description = "不合格品管理id")
    private Long badId;

    @Schema(description = "来源任务id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long resourceTaskId;

    @Schema(description = "来源类型")
    private String resourceType;

    @Schema(description = "物料编码")
    private String matCode;
    private String quantityUnit;

    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "规格型号")
    private String matModel;

    @Schema(description = "供应商编码")
    private String supplierCode;

    @Schema(description = "供应商名称")
    private String supplierName;

    @Schema(description = "批次")
    private String batchNo;

    @Schema(description = "数量")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal num;

    @Schema(description = "判定数量")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal judgeNum;

    @Schema(description = "不合格类型")
    private String badType;

    @Schema(description = "不合格类型")
    private String badTypeName;

    @Schema(description = "不合格原因")
    private String badReason;

    @Schema(description = "容器码")
    private String containerCode;

    @Schema(description = "库位编码")
    private String locationCode;

    @Schema(description = "库位名称")
    private String locationName;

    @Schema(description = "处置结果")
    private String handleResult;

    @Schema(description = "处置备注")
    private String handleRemark;

    @Schema(description = "处置文件")
    private String handleFile;

    @Schema(description = "预计处置完成时间")
    private java.time.LocalDateTime expectedCompletionTime;

    private Long wmsOperationId;
    private String wmsSyncStatus;
    private String wmsSyncMessage;
    private String executionRef;
    private String mesDecisionKey;
    @JsonSerialize(using=ToStringSerializer.class) private Long mesDeciderUserId;
    private Boolean mesConcessionUnrestricted;
    @TableField(updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    @JsonSerialize(using=ToStringSerializer.class) private Long mesReceiptId;
    @TableField(updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS) private String mesExecutorCode;
    @TableField(updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS) private String mesExecutionEvidence;

    private String verificationResult;
    private String verificationRemark;
    private String verifierCode;
    private String verifierName;
    private java.time.LocalDateTime verificationTime;

    @Schema(description = "处置状态")
    private String status;

    @Schema(description = "处置人id")
    private String handlePersonId;

    @Schema(description = "处置人姓名")
    private String handlePersonName;

    @Schema(description = "处置时间")
    private Date handleTime;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
