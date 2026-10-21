package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesDispatchReturnDTO;
import com.kitex.mom.mes.entity.MesDispatchReturnRequestEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;

import java.util.List;

/**
 * 已接收未开工派工的受控退派服务。
 */
public interface MesDispatchReturnService extends IService<MesDispatchReturnRequestEntity> {

    /**
     * 按当前租户和实际工厂查询退派申请工作台。
     */
    IPage<MesDispatchReturnRequestEntity> pageRequests(Page<MesDispatchReturnRequestEntity> page,
                                                       MesDispatchReturnRequestEntity query);

    /**
     * 重新采集派工现场事实，返回申请资格、阻断项和证据指纹。
     */
    MesDispatchReturnDTO.Evidence preview(Long arrangeId);

    /**
     * 查询申请、当前事实和不可变操作履历。
     */
    MesDispatchReturnDTO.Detail detail(Long requestId);

    /**
     * 冻结当前事实并提交退派申请；同一永久请求号只允许相同命令重放。
     */
    MesDispatchReturnRequestEntity submit(MesDispatchReturnDTO.SubmitCommand command);

    /**
     * 由非申请人复核；批准时重新取证并原子完成资源释放和状态回退。
     */
    MesDispatchReturnRequestEntity review(MesDispatchReturnDTO.ReviewCommand command);

    /**
     * 由原申请人在待审核阶段撤回申请。
     */
    MesDispatchReturnRequestEntity withdraw(MesDispatchReturnDTO.WithdrawCommand command);

    /**
     * 开工门禁：存在待审核退派申请时禁止形成新的生产事实。
     */
    void assertExecutionAllowed(MesTaskArrangeEntity arrange);

    /**
     * 上游PLAN撤回已批准时，终止同一批派工的待审现场申请。
     */
    void supersedeByPlan(List<MesTaskArrangeEntity> arranges, String planRequestId);
}
