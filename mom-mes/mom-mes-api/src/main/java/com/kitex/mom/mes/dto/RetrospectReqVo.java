package com.kitex.mom.mes.dto;

import lombok.Data;

/**
 * 追溯
 *
 * @author kitex
 * @since 2025-04-24
 */
@Data
public class RetrospectReqVo {

    /**
     * 反向追溯传：物料条码
     * 正向追溯传：箱码
     * 半成品正向追溯传：箱码
     * 半成品反向追溯传：物料条码
     */
    private String code;

    private String startDate;

    private String endDate;
}
