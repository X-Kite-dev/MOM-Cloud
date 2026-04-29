package com.kitex.mom.mes.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * <p>
 * 用料清单查询 VO类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Data
@Schema(description = "用料清单查询VO")
public class MesMaterialListQueryVO {

    /**
     * 工单编码
     */
    @Schema(description = "工单编码")
    private String taskCode;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String productName;

    /**
     * 状态：NEW-新建，PICKING-领料中，COMPLETED-完成
     */
    @Schema(description = "状态：NEW-新建，PICKING-领料中，COMPLETED-完成")
    private String status;

    /**
     * 创建时间开始
     */
    @Schema(description = "创建时间开始")
    private String createTimeStart;

    /**
     * 创建时间结束
     */
    @Schema(description = "创建时间结束")
    private String createTimeEnd;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    private String factoryCode;
}