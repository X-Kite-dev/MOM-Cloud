package com.kitex.mom.wms.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * WmsInNoticeQueryDTO
 *
 * @author kitex
 * @date 2024/12/13 13:54
 */
@Data
public class WmsInNoticeQueryDTO {

    /**
     * 入库通知单号
     */
    private String id;

    /**
     * 入库通知单状态;0待处理、1处理中、2已完成、3已作废
     */
    private String state;

    /**
     * 仓库编码
     */
    private String warehouseCode;

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

}
