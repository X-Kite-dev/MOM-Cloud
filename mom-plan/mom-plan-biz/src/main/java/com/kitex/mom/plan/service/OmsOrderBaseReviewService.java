package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.oms.dto.OmsOrderBaseReviewPageDto;
import com.kitex.mom.oms.dto.OmsOrderChangeReviewCommand;
import com.kitex.mom.oms.entity.OmsOrderBaseReviewEntity;
import com.kitex.mom.oms.vo.OmsOrderBaseReviewExportVo;

import java.util.List;
import java.util.Map;

public interface OmsOrderBaseReviewService extends IService<OmsOrderBaseReviewEntity> {

    /**
     * 订单评审分页查询
     */
    Page<OmsOrderBaseReviewPageDto> getOmsOrderBaseReviewPage(Page<OmsOrderBaseReviewPageDto> page, OmsOrderBaseReviewPageDto omsOrderBaseReview);

    /**
     * 通过id查询订单评审表
     *
     * @param id id
     */
    OmsOrderBaseReviewEntity getOrderReviewById(Long id);

    /**
     * 导出excel 表格
     *
     * @param omsOrderBaseReview 查询条件
     * @param ids                导出指定ID
     * @return excel 文件流
     */
    List<OmsOrderBaseReviewExportVo> export(OmsOrderBaseReviewEntity omsOrderBaseReview, Long[] ids);

    /**
     * 订单评审
     *
     * @param ids        主表ids
     * @param reviewType 评审结果 0 通过 1驳回 2作废
     * @param remark     评审意见
     */
    Boolean review(Long[] ids, String reviewType, String remark);

    Boolean reviewChange(OmsOrderChangeReviewCommand command);

    /**
     * 查询各订单评审状态的数量
     *
     * @return
     */
    Map<String, Long> getCount(OmsOrderBaseReviewPageDto omsOrderBaseReview);
}
