package com.kitex.mom.qms.dto.inspection;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 创建生产质检任务参数
 *
 * @author kitex
 * @date 2022/12/2
 */
@Data
@Accessors(chain = true)
@Schema(description = "创建生产质检任务参数")
public class InspectionProcedureCreateParam {

    @Schema(description = "id")
    private Long id;

    @Schema(description = "工序任务编码")
    private String taskCode;

    @Schema(description = "标签类型")
    private String labelType;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "关联物料编码")
    private String matCode;

    @Schema(description = "关联物料名称")
    private String matName;

    @Schema(description = "规格型号")
    private String matSpec;

    @Schema(description = "工序编码")
    private String processCode;

    @Schema(description = "工序名称")
    private String processName;

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

    @Schema(description = "车间编号")
    private String deptCode;

    @Schema(description = "工单编码")
    private String orderNo;

    @Schema(description = "工单id")
    private String orderId;

    /**
     * 物料条码
     */
    @Schema(description = "物料条码")
    private String matBarcode;
}
