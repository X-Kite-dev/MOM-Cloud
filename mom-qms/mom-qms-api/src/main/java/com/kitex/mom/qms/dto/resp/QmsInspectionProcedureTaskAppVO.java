package com.kitex.mom.qms.dto.resp;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import lombok.Data;

import java.util.List;

/**
 * app显示 需要统计
 *
 * @author kitex
 * @since 2025-05-10
 */
@Data
public class QmsInspectionProcedureTaskAppVO {

    private List<QmsStatisticsDTO> classify;

    private Page<QmsInspectionProcedureTaskEntity> page;
}
