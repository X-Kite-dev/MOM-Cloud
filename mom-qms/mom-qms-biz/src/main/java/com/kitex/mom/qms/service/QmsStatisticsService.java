package com.kitex.mom.qms.service;

import java.util.Map;

/**
 * 质量统计分析
 *
 * @author kitex
 * @since 2025-04-02
 */
public interface QmsStatisticsService {

    /**
     * 查询统计数量
     *
     * @return
     */
    Map<String, Object> getLineNum();


    Map<String, Object> getCardNum();
}
