package com.kitex.mom.qms.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建入库通话单
 *
 * @author kyer
 * @date 2023/11/23 10:09
 **/
@Data
public class AddInBillNoticeDetailReq {

    /**
     * 物料编码
     */
    @Schema(description = "物料编码", name = "matCode")
    private String matCode;

    /**
     * 物料名称
     */
    @Schema(description = "备注", name = "matName")
    private String matName;

    /**
     * 批次号
     */
    @Schema(description = "批次号", name = "matBatch")
    private String matBatch;

    /**
     * 单位
     */
    @Schema(description = "单位", name = "unit")
    private String unit;

    /**
     * 类型
     */
    @Schema(description = "类型", name = "matType")
    private String matType;

    /**
     * 规格型号
     */
    @Schema(description = "通知入库数量", name = "specification")
    private String specification;

    /**
     * 生产日期
     */
    @Schema(description = "生产日期", name = "matProduceDate")
    private String matProduceDate;

    /**
     * 通知入库数量
     */
    @Schema(description = "通知入库数量", name = "num")
    private BigDecimal noticeInNum;

}
