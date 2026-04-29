package com.kitex.mom.qms.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 工序质检任务分页查询
 *
 * @author kyer
 * @date 2023/04/24 11:06
 **/
@Data
public class InspectionProcedureTaskReq {

    /**
     * 批次
     */
    @Schema(description = "批次")
    private String batchNo;

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
     * 工序编码
     */
    @Schema(description = "工序编码")
    private String processCode;

    /**
     * 工序名称
     */
    @Schema(description = "工序名称")
    private String processName;

    /**
     * 处理状态
     */
    @Schema(description = "处理状态，逗号分隔字符串")
    private String status;

    /**
     * 质检状态
     */
    @Schema(description = "质检状态")
    private String result;

}
