package com.kitex.mom.wms.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库单明细查询参数
 *
 * @author kitex
 * @date 2024-12-17 11:21:00
 */
@Data
public class WmsInBillDetailQueryDTO {

    /**
     * 入库单号
     */
    private String billNo;

    /**
     * 通知单号
     */
    private String noticeId;

    /**
     * 状态
     */
    private String state;

    /**
     * 状态列表
     */
    private List<String> stateList;

    /**
     * 物料编码
     */
    private String matCode;

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

    /**
     * 单据类型
     */
    private List<String> billTypes;

}