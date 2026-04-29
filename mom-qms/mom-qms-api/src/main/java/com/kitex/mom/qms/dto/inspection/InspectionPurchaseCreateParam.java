package com.kitex.mom.qms.dto.inspection;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 创建来料质检任务参数
 *
 * @author kitex
 * @date 2022/12/2
 */
@Data
@Accessors(chain = true)
@Schema(description = "创建来料质检任务参数")
public class InspectionPurchaseCreateParam {

    @Schema(description = "id")
    private Long id;

    @Schema(description = "通知单id")
    private String noticeId;

    @Schema(description = "任务编码")
    private String taskCode;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "规格型号")
    private String matSpec;

    @Schema(description = "供应商编码")
    private String supplierCode;

    @Schema(description = "供应商名称")
    private String supplierName;

    @Schema(description = "质检标准")
    private Long standardId;

    @Schema(description = "批次")
    private String batchNo;

    @Schema(description = "数量")
    private Integer num;

    @Schema(description = "取样数")
    private Integer inspectionNum;

    @Schema(description = "是否新建")
    private Boolean isCreate;

    /**
     * 生产日期
     */
    @Schema(description = "生产日期", name = "matProduceDate")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date matProduceDate;

    @Schema(description = "车间编码")
    private String deptCode;

    /**
     * 物料条码
     */
    @Schema(description = "物料条码")
    private String matBarcode;

    /**
     * 组织id
     */
    @Schema(description = "组织id")
    private String factoryCode;
}
