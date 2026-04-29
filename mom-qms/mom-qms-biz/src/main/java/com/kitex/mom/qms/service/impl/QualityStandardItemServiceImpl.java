package com.kitex.mom.qms.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.qms.dto.inspection.InspectionItemResultRecord;
import com.kitex.mom.qms.entity.QmsQualityStandardItemEntity;
import com.kitex.mom.qms.enums.CheckTypeEnum;
import com.kitex.mom.qms.mapper.QualityStandardItemMapper;
import com.kitex.mom.qms.service.QualityStandardItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 质检标准项目
 *
 * @author kitex
 * @date 2022/11/29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityStandardItemServiceImpl extends ServiceImpl<QualityStandardItemMapper, QmsQualityStandardItemEntity> implements QualityStandardItemService {

    /**
     * 分页
     */
    public Page<QmsQualityStandardItemEntity> page(Page<QmsQualityStandardItemEntity> page, QmsQualityStandardItemEntity query) {
        return this.lambdaQuery().page(page);
    }

    /**
     * 根据质检项内容判断是否合格, 没结果就是 null
     */
    public Boolean judgementCheckResult(InspectionItemResultRecord record, QmsQualityStandardItemEntity standardItem) {
        String checkType = standardItem.getCheckType();
        CheckTypeEnum checkTypeEnum = CheckTypeEnum.findByName(checkType);
        if (checkTypeEnum != CheckTypeEnum.BOOL && Objects.isNull(record.getDoubleValue())) {
            return null;
        }
        switch (checkTypeEnum) {
            case BOOL: {
                return record.getQualified();
            }
            case RANG: {
                return record.getDoubleValue() >= standardItem.getLowerLimit()
                        && record.getDoubleValue() <= standardItem.getUpperLimit();
            }
            case UPPER_LIMIT: {
                return record.getDoubleValue() <= standardItem.getUpperLimit();
            }
            case LOWER_LIMIT: {
                return record.getDoubleValue() >= standardItem.getLowerLimit();
            }
            default: {
                return null;
            }
        }
    }

}
