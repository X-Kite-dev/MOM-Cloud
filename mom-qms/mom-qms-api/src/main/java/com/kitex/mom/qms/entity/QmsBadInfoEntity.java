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

import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "不合格品管理")
@TableName("qms_bad_info")
public class QmsBadInfoEntity extends BaseEntity<QmsBadInfoEntity> {

    @Schema(description = "NCR永久编号")
    private String ncrNo;

    @Schema(description = "来源幂等请求号")
    private String requestId;

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

    @Schema(description = "未判定数量")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal unJudgeNum;

    @Schema(description = "已判定数量")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal judgedNum;

    @Schema(description = "已作出处置决策数量")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal disposedNum;

    @Schema(description = "已验证关闭数量")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal closedNum;

    @Schema(description = "不合格类型")
    private String badType;

    @Schema(description = "容器码")
    private String containerCode;

    @Schema(description = "库位编码")
    private String locationCode;

    @Schema(description = "库位名称")
    private String locationName;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "WMS质量隔离单号")
    private String holdNo;

    @Schema(description = "WMS隔离事务ID")
    private Long holdOperationId;

    @Schema(description = "WMS隔离同步状态")
    private String holdSyncStatus;

    @Schema(description = "WMS隔离同步说明")
    private String holdSyncMessage;

    private String detectedBy;
    private String detectedByName;
    private LocalDateTime detectedTime;

    @Schema(description = "当前关联CAPA主键")
    private Long capaId;

    @Schema(description = "当前关联CAPA状态")
    private String capaStatus;

    @Schema(description = "要求发起CAPA的原因")
    private String capaRequiredReason;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
