package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesExecutionContractDTO;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.dto.MesIdentifierNodeReadinessDTO;
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

    MesExecutionTemplateEntity saveDraft(MesExecutionTemplateEntity draft);

    MesExecutionTemplateEntity copyVersion(Long sourceId);

    MesExecutionTemplateEntity publish(Long id);

    MesExecutionTemplateEntity retire(Long id);

    void freezeForDispatch(MesTaskEntity task, MesTaskArrangeEntity arrange);

    /** 同一动态区段须在实际同批派工中具备全部角色；失败整批回滚。 */
    void validateDispatchBundle(List<MesTaskArrangeEntity> arrangements);

    /** 精确模板或派工的只读前置诊断，不更新冻结契约。 */
    MesIdentifierNodeReadinessDTO nodeReadiness(Long templateId, String arrangeCode, MesReportChannelEnum channel);

    MesExecutionContractDTO contract(String arrangeCode);

    MesExecutionContractDTO contract(MesTaskArrangeEntity arrange);

    void validateReport(MesTaskArrangeEntity arrange, MesTaskReportVO command,
                        MesReportChannelEnum channel);
}
