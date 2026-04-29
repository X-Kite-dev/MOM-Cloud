package com.kitex.mom.qms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.qms.dto.inspection.InspectionItemResultRecord;
import com.kitex.mom.qms.entity.QmsQualityStandardItemEntity;

/**
 * 质检标准项目
 *
 * @author kitex
 * @date 2022/11/29
 */
public interface QualityStandardItemService extends IService<QmsQualityStandardItemEntity> {


    /**
     * 分页
     */
    Page<QmsQualityStandardItemEntity> page(Page<QmsQualityStandardItemEntity> page, QmsQualityStandardItemEntity query);


    /**
     * 根据质检项内容判断是否合格, 没结果就是 null
     */
    Boolean judgementCheckResult(InspectionItemResultRecord record, QmsQualityStandardItemEntity standardItem);

}
