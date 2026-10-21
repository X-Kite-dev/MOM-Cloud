package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kitex.mom.mes.dto.MesUnqualifiedTimingRecordDTO;
import com.kitex.mom.mes.entity.MesTaskReportUnqualifiedEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.dto.MesProductionNgSourceDTO;
import com.kitex.mom.mes.enums.ReportModeEnum;

/**
 * 生产报工-不合格品上报
 *
 * @author kitex
 * @since 2025-05-06
 */
public interface MesTaskReportUnqualifiedService extends IService<MesTaskReportUnqualifiedEntity> {

    /**
     * 在真实不良上报原事务冻结计量、保存并精确回读；失败整笔回滚。
     * report同时携带服务器解析的规范码和原始输入；前序、时间和计量统一使用规范码。
     * originalInputJson保留原请求，不能为匹配前序或今日映射改写原号正文。
     */
    void saveFrozenSource(MesTaskReportUnqualifiedEntity report, MesTaskArrangeEntity arrange, ReportModeEnum mode,
                          String originalInputJson);

    /** 回放核原证据，不再次读取当前MDM单位或创建库存。 */
    void verifySource(MesTaskReportUnqualifiedEntity report);

    /** 按实际租户/工厂查询原不良请求结果；不触发重新上报。 */
    MesProductionNgSourceDTO source(String requestId);

    /** 按实际租户/工厂分页查看原NG时间证据；不以GOOD报告或操作流水ID代替。 */
    IPage<MesUnqualifiedTimingRecordDTO> timingPage(long current, long size, String taskCode,
                                                  String arrangeCode, String identifier);
}
