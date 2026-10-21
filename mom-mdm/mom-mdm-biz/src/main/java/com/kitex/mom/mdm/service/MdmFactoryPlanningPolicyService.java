package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyCommand;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningImpactDTO;
import com.kitex.mom.mdm.entity.MdmFactoryPlanningPolicyEntity;

import java.util.List;

/** 工厂计划协同策略的版本化领域服务。 */
public interface MdmFactoryPlanningPolicyService extends IService<MdmFactoryPlanningPolicyEntity> {
    /** 读取当前租户、指定工厂唯一生效策略；缺失或冲突时故障关闭。 */
    MdmFactoryPlanningPolicyDTO requireActive(String factoryCode);

    /** 查询工厂全部非删除版本，按版本倒序返回。 */
    List<MdmFactoryPlanningPolicyDTO> history(String factoryModelId);

    /** 校验工厂和模式边界后创建下一版草稿。 */
    MdmFactoryPlanningPolicyDTO createDraft(MdmFactoryPlanningPolicyCommand.CreateDraft command);

    /** 实时采集 PLAN/MES 影响并返回本次事实对应的确认号。 */
    MdmFactoryPlanningImpactDTO inspect(String draftId);

    /** 异岗发布当前草稿；未来生效版本先进入预约状态。 */
    MdmFactoryPlanningPolicyDTO publish(MdmFactoryPlanningPolicyCommand.Publish command);
}
