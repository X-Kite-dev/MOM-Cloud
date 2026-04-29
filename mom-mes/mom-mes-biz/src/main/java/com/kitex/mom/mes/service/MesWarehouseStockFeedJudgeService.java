package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesWarehouseFeedDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedJudgeDTO;
import com.kitex.mom.mes.entity.MesWarehouseStockFeedJudgeEntity;

/**
 * 线边库余料退料审批服务接口
 */
public interface MesWarehouseStockFeedJudgeService extends IService<MesWarehouseStockFeedJudgeEntity> {

    /**
     * 提交退料审批
     *
     * @param submitDTO 退料审批提交参数
     */
    void submitReturnMaterial(MesWarehouseFeedDTO submitDTO);

    /**
     * 审批
     *
     * @param submitDTO
     */
    void judge(MesWarehouseFeedJudgeDTO submitDTO);
}