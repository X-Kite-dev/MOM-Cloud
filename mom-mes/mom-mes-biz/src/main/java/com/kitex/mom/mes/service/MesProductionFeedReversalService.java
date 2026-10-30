package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesProductionFeedReversalEntity;

import java.util.List;

/**
 * 原产出全部耗料份额的内部受控纠正。
 * 不撤生产数量、不退实物、不放行质量；必须由本地真实业务事务调用。
 */
public interface MesProductionFeedReversalService extends IService<MesProductionFeedReversalEntity> {
    /**
     * 只恢复显式选定产出的全部原份额，每产出终身一次；零需求仍登记。
     *
     * @param requestId           稳定纠正原号，租户内永久唯一
     * @param batchId             已提交的原总耗用主账
     * @param expectedFingerprint 原主账完整证据SHA
     * @param outputIds           同一原主账中的真实产出主键全集
     * @param reason              完整纠正原因
     * @return 本事务完整原回执；不会另起事务或重算当前BOM
     */
    MesProductionFeedReversalEntity restoreOutputs(String requestId, Long batchId, String expectedFingerprint,
                                                   List<Long> outputIds, String reason);

    /**
     * 当前事务内本人原号完整回读；不是独立HTTP/UNKNOWN恢复协议。
     */
    MesProductionFeedReversalEntity original(String requestId);
}

