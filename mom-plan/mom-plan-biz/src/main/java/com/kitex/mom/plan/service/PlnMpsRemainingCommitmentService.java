package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.plan.dto.PlnMpsRemainingCommitmentDTO;
import com.kitex.mom.plan.entity.PlnMpsRemainingCommitmentEntity;
import com.kitex.mom.plan.entity.PlnMpsRemainingCommitmentOperationEntity;

/**
 * 剩余生产责任领域命令；无任意CRUD、删除或已发布回退入口。
 */
public interface PlnMpsRemainingCommitmentService extends IService<PlnMpsRemainingCommitmentEntity> {
    /**
     * 原确认基线与完整新日桶/政策/产能预览，不占正式责任。
     */
    PlnMpsRemainingCommitmentDTO.Preview preview(PlnMpsRemainingCommitmentDTO.PreviewCommand command);

    /**
     * 冻结不可变待审版本及全部日桶，同号仅原实名/原载荷重放。
     */
    PlnMpsRemainingCommitmentDTO.Detail submit(PlnMpsRemainingCommitmentDTO.Submit command);

    /**
     * 当前实际组织不可变版本及当前头指针。
     */
    PlnMpsRemainingCommitmentDTO.Detail detail(Long id);

    /**
     * 按精确原桶返回完整版本履历，不包含大型正文。
     */
    Page<PlnMpsRemainingCommitmentEntity> history(Long lineId, long current, long size);

    /**
     * 各类永久命令原回执；未观察到不是取消或未落账证明。
     */
    PlnMpsRemainingCommitmentOperationEntity byRequest(String requestId);

    /**
     * 编制人取消待审版保留原载荷；已发布版不能取消。
     */
    PlnMpsRemainingCommitmentDTO.Detail cancel(PlnMpsRemainingCommitmentDTO.Cancel command);

    /**
     * 异人审核、最新能力重核及精确前版CAS切换；不修改物理生产事实。
     */
    PlnMpsRemainingCommitmentDTO.Detail review(PlnMpsRemainingCommitmentDTO.Review command);
}
