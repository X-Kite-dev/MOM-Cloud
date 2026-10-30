package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesIdentifierNodePolicyDTO;
import com.kitex.mom.mes.entity.MesIdentifierBindingGroupEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;

import java.util.List;

/**
 * 永久换码区段领域服务，沿标准IService持久层规范，但只开放有实际组织校验的领域入口。
 * 通用CRUD和Mapper暴露均禁止，数据库另行保护不可变事实。
 */
public interface MesIdentifierBindingGroupService extends IService<MesIdentifierBindingGroupEntity> {

    /**
     * 派工事务在查重前取得工单业务锁，不在这里反向锁历史派工行。
     */
    void lockDispatchTask(String taskCode);

    /**
     * 首次创建或核对原永久组；须处于派工事务中，失败与整个派工批次一起回滚。
     */
    MesIdentifierNodePolicyDTO freezeForDispatch(MesTaskEntity task, MesTaskArrangeEntity arrange,
                                                 MesIdentifierNodePolicyDTO policy);

    /**
     * 查询当前实际租户工厂的工单永久区段，不能输入其他工厂或租户。
     */
    List<MesIdentifierBindingGroupEntity> taskGroups(Long taskId);

    /**
     * 查询精确永久区段原始依据，不按当前模板补造历史。
     */
    MesIdentifierBindingGroupEntity evidence(Long groupId);
}
