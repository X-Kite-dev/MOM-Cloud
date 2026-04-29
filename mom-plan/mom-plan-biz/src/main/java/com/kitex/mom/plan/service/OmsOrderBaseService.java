package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.oms.dto.HistoryDeliverOrderDto;
import com.kitex.mom.oms.dto.OmsOrderBasePageDto;
import com.kitex.mom.oms.entity.OmsOrderBaseEntity;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import com.kitex.mom.oms.vo.OmsOrderBaseExportVo;
import com.kitex.mom.oms.vo.OmsOrderBaseImportVo;
import org.springframework.validation.BindingResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface OmsOrderBaseService extends IService<OmsOrderBaseEntity> {

    Page<OmsOrderBasePageDto> getOmsOrderBasePage(Page<OmsOrderBasePageDto> page, OmsOrderBasePageDto omsOrderBase);

    /**
     * 通过id查询订单输入
     */
    OmsOrderBaseEntity getOrderById(Long id);

    /**
     * 根据产品编码查询历史未交信息
     */
    List<HistoryDeliverOrderDto> getHistoryDeliverOrderDtoList(String orderCode, String productCode);

    /**
     * 订单输入新增
     */
    Boolean saveOrder(OmsOrderBaseEntity omsOrderBase);

    /**
     * 订单输入编辑
     */
    Boolean updateOrderById(OmsOrderBaseEntity omsOrderBase);

    /**
     * 通过id删除订单输入
     *
     * @param idList id列表
     */
    Boolean removeOrderByIds(ArrayList<Long> idList);

    /**
     * 导入excel 表格
     *
     * @param omsOrderBaseImportVoList 订单导入列表
     * @param bindingResult            错误信息列表
     */
    R importOrder(List<OmsOrderBaseImportVo> omsOrderBaseImportVoList, BindingResult bindingResult, String factoryCode);

    /**
     * 导出excel 表格
     *
     * @param omsOrderBase 查询条件
     * @param ids          导出指定ID
     * @return excel 文件流
     */
    List<OmsOrderBaseExportVo> export(OmsOrderBaseEntity omsOrderBase, Long[] ids);

    /**
     * 订单输入批量提交评审
     */
    Boolean submitReview(ArrayList<Long> idList);

    /**
     * 订单输入批量直接通过
     */
    Boolean pass(ArrayList<Long> idList);

    /**
     * 校验订单信息
     */
    Boolean checkOrderBaseMsg(String orderCode, String productCode, String customerCode);

    /**
     * 查询订单明细
     */
    OmsOrderDetailsEntity getOrderDetails(String orderCode, String productCode, LocalDate planDeliverTime);

    /**
     * 查询各订单输入状态的数量
     *
     * @return
     */
    Map<String, Long> getCount(OmsOrderBasePageDto omsOrderBase);
}