package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesExecutionTemplateEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.MesReportChannelEnum;

import java.util.List;

/**
 * 生产执行模板生命周期、匹配、冻结和运行期校验服务。
 */
public interface MesExecutionTemplateService extends IService<MesExecutionTemplateEntity> {

    IPage<MesExecutionTemplateEntity> queryPage(Page<MesExecutionTemplateEntity> page,
                                                MesExecutionTemplateEntity query);

    /**
     * 当前真实租户工厂的范围匹配试算，不写派工或替代现场门。
     */
    MesExecutionTemplatePreviewDTO preview(MesExecutionTemplatePreviewQueryDTO query);

    MesExecutionTemplateEntity saveDraft(MesExecutionTemplateEntity draft);

    MesExecutionTemplateEntity copyVersion(Long sourceId);

    /**
     * 旧调用不再允许省略所见修订，保留签名仅用于明确拒绝。
     */
    @Deprecated
    MesExecutionTemplateEntity publish(Long id);

    MesExecutionTemplateEntity publish(Long id, Long expectedRevisionNo);

    @Deprecated
    MesExecutionTemplateEntity retire(Long id);

    MesExecutionTemplateEntity retire(Long id, Long expectedRevisionNo);

    /**
     * 核对同编码/范围的当前有效旧版与待切换草稿，不改变状态。
     */
    MesExecutionTemplateReplacementDTO replacementPreview(Long draftId, Long expectedRevisionNo);

    /**
     * 在单个独立配置事务内退役旧版并发布新版，失败时两行一起回滚。
     */
    MesExecutionTemplateEntity replacePublished(Long sourceId, Long sourceRevisionNo, Long draftId,
                                                Long draftRevisionNo);

    void freezeForDispatch(MesTaskEntity task, MesTaskArrangeEntity arrange);

    /**
     * 同一动态区段须在实际同批派工中具备全部角色；失败整批回滚。
     */
    void validateDispatchBundle(List<MesTaskArrangeEntity> arrangements);

    /**
     * 精确模板或派工的只读前置诊断，不更新冻结契约。
     */
    MesIdentifierNodeReadinessDTO nodeReadiness(Long templateId, String arrangeCode, MesReportChannelEnum channel);

    MesExecutionContractDTO contract(String arrangeCode);

    MesExecutionContractDTO contract(MesTaskArrangeEntity arrange);

    void validateReport(MesTaskArrangeEntity arrange, MesTaskReportVO command,
                        MesReportChannelEnum channel);
}
