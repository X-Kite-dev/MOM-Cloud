package com.kitex.mom.qms.entity;

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
import java.time.LocalDate;
import java.util.Date;

/**
 * 采购质检任务
 *
 * @author kitex
 * @date 2022/12/1
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "采购质检任务")
@TableName("qms_inspection_purchase_task")
public class QmsInspectionPurchaseTaskEntity extends BaseEntity<QmsInspectionPurchaseTaskEntity> {

    @Schema(description = "采购订单明细ID；用于来料质检结果回写和库存来源追溯")
    private Long orderDetailId;

    @Schema(description = "任务编码")
    private String taskCode;

    @Deprecated
    @Schema(description = "任务名称")
    private String name;

    @Schema(description = "关联物料编码")
    private String matCode;

    @Schema(description = "关联物料名称")
    private String matName;

    @Schema(description = "规格型号")
    private String matSpec;

    @Schema(description = "供应商编码")
    private String supplierCode;

    @Schema(description = "供应商名称")
    private String supplierName;

    @Schema(description = "关联质量标准")
    private Long standardId;

    @Schema(description = "关联质量标准名称")
    private String standardName;

    @Schema(description = "批次")
    private String batchNo;

    @Schema(description = "数量")
    private Integer num = 0;

    @Schema(description = "取样数")
    private Integer inspectionNum = 0;

    /**
     * 未质检，质检中，质检完成
     */
    @Schema(description = "处理状态")
    private String status;

    /**
     * 合格，不合格，紧急放行，免检，让步接收
     */
    @Schema(description = "处理结果")
    private String result;

    @Schema(description = "可用数量")
    private Integer usableNum;

    @Schema(description = "处理数量")
    private Integer handleNum;

    @Schema(description = "处理结果")
    private String handleResult;

    @Schema(description = "最后的质检(合格判定)时间")
    private Date latestInspectionTime;

    @Schema(description = "最后的更新任务(追加报检)时间")
    private Date latestAppendTime;

    @Deprecated
    @Schema(description = "是否完成当前所有的质检项")
    private Boolean currentFinish;

    @Schema(description = "备注")
    private String remark;

    /**
     * 生产日期
     */
    @Schema(description = "生产日期", name = "matProduceDate")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date matProduceDate;

    /**
     * 通知入库数量
     */
    @Schema(description = "通知入库数量")
    private BigDecimal noticeInBillNum;

    /**
     * 车间编号
     */
    @Schema(description = "车间编号", name = "deptCode")
    private String deptCode;

    /**
     * 物料条码
     */
    @Schema(description = "物料条码")
    private String matBarcode;

    @Schema(description = "附件")
    private String annex;

    @Schema(description = "供应商物料认证台账ID")
    private Long materialAuthorizationId;

    @Schema(description = "供应商物料认证编号")
    private String materialAuthorizationNo;

    @Schema(description = "供应商物料认证有效期")
    private LocalDate materialAuthorizationValidEnd;

    @Schema(description = "来料检验等级：NORMAL、TIGHTENED")
    private String inspectionLevel;

    @Schema(description = "质量标准原始检验方式")
    private String originalInspectionMethod;

    @Schema(description = "认证与加严检验决策JSON快照")
    private String complianceSnapshot;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
