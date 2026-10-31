package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesMaterialReturnWithdrawDTO;
import com.kitex.mom.mes.dto.MesMaterialReturnActionDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedJudgeDTO;
import com.kitex.mom.mes.entity.MesWarehouseStockFeedJudgeEntity;

/**
 * 线边库余料退料审批服务接口
 */
public interface MesWarehouseStockFeedJudgeService extends IService<MesWarehouseStockFeedJudgeEntity> {

    MesMaterialReturnActionDTO.Context reviewContext();
    MesMaterialReturnActionDTO.Context withdrawContext();
    MesMaterialReturnActionDTO.Receipt reviewReceipt(String requestId);
    MesMaterialReturnActionDTO.Receipt withdrawReceipt(String requestId);

    /** 本人申请的实名实际工厂上下文。 */
    MesWarehouseFeedDTO.SubmissionContext submissionContext();

    /** 只查本人原提交；无记录不构成未受理撤销证明。 */
    MesWarehouseFeedDTO.Submission submissionReceipt(String requestId);

    /**
     * 按当前租户和实际工厂查询受控退料申请。
     */
    IPage<MesWarehouseStockFeedJudgeEntity> pageRequests(Page<MesWarehouseStockFeedJudgeEntity> page,
                                                         MesWarehouseStockFeedJudgeEntity query);

    /**
     * 预览精确库存组的可退数量、阻断原因和证据指纹。
     */
    MesWarehouseFeedDTO.Evidence preview(MesWarehouseFeedDTO source);

    /**
     * 查询申请、当前库存事实和不可变履历。
     */
    MesWarehouseFeedDTO.Detail detail(Long id);

    /**
     * 提交退料审批
     *
     * @param submitDTO 退料审批提交参数
     */
    MesWarehouseStockFeedJudgeEntity submitReturnMaterial(MesWarehouseFeedDTO submitDTO);

    /**
     * 以申请时事实指纹执行异岗审批；批准前重新采集库存事实。
     *
     * @param submitDTO 审批结论、意见、永久请求号和原事实指纹
     * @return 审批后的申请；事实漂移时返回STALE终态
     */
    MesWarehouseStockFeedJudgeEntity judge(MesWarehouseFeedJudgeDTO submitDTO);

    /**
     * 原申请人在待审核阶段撤回并解除来源库存锁定。
     */
    MesWarehouseStockFeedJudgeEntity withdraw(MesMaterialReturnWithdrawDTO command);
}
