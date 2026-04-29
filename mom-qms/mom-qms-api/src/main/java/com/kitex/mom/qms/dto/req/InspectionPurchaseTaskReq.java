package com.kitex.mom.qms.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 采购质检任务分页查询
 *
 * @author kyer
 * @date 2023/04/24 13:12
 **/
@Data
public class InspectionPurchaseTaskReq {

    /**
     * 关联物料编码
     */
    @Schema(description = "关联物料编码")
    private String matCode;

    /**
     * 关联物料名称
     */
    @Schema(description = "关联物料名称")
    private String matName;

    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 批次
     */
    @Schema(description = "批次")
    private String batchNo;

    /**
     * 处理状态
     */
    @Schema(description = "处理状态")
    private String status;

    /**
     * 质检状态
     */
    @Schema(description = "质检状态")
    private String result;

}
