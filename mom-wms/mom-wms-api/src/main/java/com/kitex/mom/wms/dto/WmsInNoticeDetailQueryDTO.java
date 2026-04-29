package com.kitex.mom.wms.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库通知单明细查询参数
 *
 * @author kitex
 * @date 2024-12-10 15:11:37
 */
@Data
public class WmsInNoticeDetailQueryDTO {

    /**
     * 通知单号
     */
    private String noticeId;

    /**
     * 状态
     */
    private String state;

    /**
     * 仓库编码
     */
    private String warehouseCode;

    /**
     * 物料编码
     */
    private String matCode;

    /**
     * 创建人
     */
    private String createBy;

    /**
     * 创建时间
     */
    private List<LocalDateTime> createTime;

    /**
     * 单据类型
     */
    private String billType;

    /**
     * 单据类型(其他出入库查询时)
     */
    private List<String> billTypes;

}