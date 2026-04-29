package com.kitex.mom.qms.service;


import com.kitex.mom.qms.dto.QmsHistorySpcDTO;
import com.kitex.mom.qms.entity.QmsQualityStandardItemEntity;

import java.util.List;
import java.util.Map;

/**
 * spc控制图
 *
 * @author kitex
 * @since 2025-04-28 09:18:17
 */
public interface SpcService {

    /**
     * 根据产品编码查询产品质检项（只获取范围质检项）
     *
     * @param matCode 产品编码
     */
    List<QmsQualityStandardItemEntity> qualityItem(String matCode);

    /**
     * 根据质检项查询质检记录
     */
    Map<String, Object> qualityHistory(Long id);

    /**
     * x-bar控制图
     */
    Map<String, Object> chart(QmsHistorySpcDTO qmsHistorySpcDTO);

    /**
     * 数据分布直方图
     */
    Map<String, Object> hist(QmsHistorySpcDTO qmsHistorySpcDTO);

    /**
     * 统计信息
     */
    Map<String, Object> stat(QmsHistorySpcDTO qmsHistorySpcDTO);
}
