package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesChangePointHandleEntity;
import com.kitex.mom.mes.entity.MesChangePointOperationEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;

import java.time.LocalDateTime;
import java.util.List;


/**
 * 变化点处理
 *
 * @author kitex
 * @since 2025-05-26
 */
public interface MesChangePointHandleService extends IService<MesChangePointHandleEntity> {

    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */
    IPage<MesChangePointHandleEntity> queryPage(Page<MesChangePointHandleEntity> pageParam, MesChangePointHandleEntity query);

    /**
     * 读取当前实际工厂内的变化点详情。
     */
    MesChangePointHandleEntity getDetail(Long id);

    /**
     * 通知
     *
     * @param mesChangePointHandle
     */
    MesChangePointHandleEntity trigger(ChangePointTriggerCommand command);

    /**
     * 处理
     *
     * @param mesChangePointHandle
     */
    void accept(ChangePointAcceptCommand command);

    void handle(ChangePointHandleCommand command);

    void verify(ChangePointVerifyCommand command);

    /**
     * 对已验证通过且仍处于生产阻断状态的变化点执行独立恢复生产审批。
     */
    void release(ChangePointProductionReleaseCommand command);

    void cancel(ChangePointCancelCommand command);

    void retryInspection(Long id);

    List<MesChangePointOperationEntity> operations(Long id);

    /**
     * 当前实际工厂指定时间窗内的变化点暂停与在制损失聚合。
     */
    MesChangePointLossAnalysisDTO lossAnalysis(LocalDateTime startTime, LocalDateTime endTime);

    void assertStartAllowed(MesTaskArrangeEntity arrange);

    void assertReportAllowed(MesTaskArrangeEntity arrange);

    void scanSla();

    /**
     * 扫描复产后加严巡检监控，生成或同步QMS中检并推进监控状态。
     */
    void scanIntensifiedInspections();
}
