package com.kitex.mom.qms.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 创建入库通话单
 *
 * @author kyer
 * @date 2023/11/23 10:09
 **/
@Data
public class AddInBillNoticeReq {

    /**
     * id
     */
    @Schema(description = "id", name = "id")
    private String id;

    /**
     * 备注
     */
    @Schema(description = "备注", name = "remark")
    private String remark;

    /**
     * 仓库编码
     */
    @Schema(description = "仓库编码", name = "warehouseCode")
    private String warehouseCode;

    /**
     * 部门编码
     */
    @Schema(description = "部门编码", name = "deptCode")
    private String deptCode;

    /**
     * 详情
     */
    private List<AddInBillNoticeDetailReq> details;

}
