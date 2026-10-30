package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesIdentifierBindingPreviewDTO;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesIdentifierBindingEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;

import java.util.List;

/**
 * 活动外码领域协调器；通用CRUD关闭，不提供独立占码、解绑或直接状态更新。
 */
public interface MesIdentifierBindingService extends IService<MesIdentifierBindingEntity> {
    /**
     * 工单已锁后核定节点、活动实例和原扫描；不在报工成功前写占用。NONE返回null。
     */
    Prepared prepare(MesTaskReportVO command, MesTaskArrangeEntity arrange, MesTaskEntity task,
                     ReportModeEnum mode, MesReportCommandService.CapturedCommand captured);

    /**
     * 保存报工前冻结本次动态事实。
     */
    void stampReport(Prepared prepared, MesTaskReportEntity report);

    /**
     * 报工、库存、计数及原回执成功后，同一事务追加实例状态和不可变操作。
     */
    void complete(Prepared prepared, MesTaskReportEntity report, MesReportCommandService.CapturedCommand captured);

    /**
     * 明确派工后的双码/单码预览；只读，不生成许可或占用。
     */
    MesIdentifierBindingPreviewDTO preview(String arrangeCode, String identifier, String externalIdentifier);

    /**
     * 精确实例证据查询，包含已释放历史，不以外码猜测旧实例。
     */
    MesIdentifierBindingEntity evidence(Long bindingId);

    /**
     * 普通扫码候选入口须先阻断全部外码生命周期，不能在释放后退回静态别名/新SN。
     */
    void assertOrdinaryScanAllowed(String identifier);

    /**
     * 履历查询可读取全部历史占用的内部SN，但这些历史不构成重新执行许可。
     */
    List<String> historyCanonicalIdentifiers(String identifier);

    /**
     * 普通纠正沿旧规则；动态完整冲销必须用受控补偿协调器，不允许部分数量/裸入口旁路。
     */
    void assertLegacyCorrectionAllowed(MesTaskReportEntity report);

    /**
     * 不可变准备结果，不保留可被后续报工改写的VO引用。
     */
    record Prepared(String action, MesIdentifierBindingEntity binding, String evidenceSnapshot,
                    Integer versionBefore, Integer versionAfter) {
    }
}
