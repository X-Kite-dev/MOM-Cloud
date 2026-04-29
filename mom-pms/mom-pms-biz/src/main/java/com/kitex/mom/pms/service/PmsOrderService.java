package com.kitex.mom.pms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.common.flow.entity.FlowInstanceBizRecordEntity;
import com.kitex.mom.pms.dto.PmsInNoticeOrderDTO;
import com.kitex.mom.pms.dto.PmsOrderAddDTO;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.PmsOrderEntity;
import com.kitex.mom.pms.vo.PmsOrderDetailVO;
import com.kitex.mom.pms.vo.PmsOrderExcelVO;
import com.kitex.mom.pms.vo.PmsOrderSupplierVO;
import com.kitex.mom.pms.vo.PmsOrderVO;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.validation.BindingResult;

import java.util.List;

public interface PmsOrderService extends MPJDeepService<PmsOrderEntity> {
    /**
     * 新增采购订单表
     *
     * @param pmsOrder 采购订单新增DTO
     * @return R
     */
    Boolean saveDeep(PmsOrderAddDTO pmsOrder);

    /**
     * 编辑采购订单表
     *
     * @param pmsOrder 采购订单编辑DTO
     * @return R
     */
    Boolean updateDeep(PmsOrderAddDTO pmsOrder);

    Boolean removeDeep(Long[] ids);

    /**
     * 通过id构造采购通知单VO
     *
     * @param id id
     * @return R
     */
    PmsOrderVO getPurchaseInNotice(Long id);

    /**
     * 修改状态
     *
     * @param pmsOrderEntity 采购订单
     * @return R
     */
    Boolean statusChange(PmsOrderEntity pmsOrderEntity);

    /**
     * 处理到货数量
     *
     * @param pmsInNoticeOrderDTO 采购通知单接收DTO
     * @return R
     */
    Boolean inNoticeSave(PmsInNoticeOrderDTO pmsInNoticeOrderDTO);

    /**
     * 导出excel 表格
     *
     * @param pmsOrder 查询条件
     * @param ids      导出指定ID
     * @return excel 文件流
     */
    List<PmsOrderExcelVO> export(PmsOrderEntity pmsOrder, Long[] ids);

    /**
     * 分页查询订单
     *
     * @param page     分页对象
     * @param pmsOrder 采购订单表
     * @return
     */
    Page<PmsOrderDetailVO> getPmsOrderPageDetail(Page page, PmsOrderEntity pmsOrder);

    /**
     * 采购订单打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    String print(PrintReq printReq);

    /**
     * 通过采购订单id查询明细
     *
     * @param id 采购订单id
     * @return R  对象列表
     */
    List<PmsOrderDetailEntity> getDetailsById(Long id);

    /**
     * 采购订单导出
     *
     * @param id id
     */
    void download(Long id, HttpServletResponse response);

    /**
     * 审核
     *
     * @param ids 采购订单表ids
     * @return R
     */
    Boolean check(Long[] ids);

    /**
     * 审核-流程结束修改状态
     */
    Boolean processBackChangeState(FlowInstanceBizRecordEntity backDTO);

    /**
     * 通过采购订单id查询明细和供应商信息
     *
     * @param id 采购订单id
     * @return R  对象列表
     */
    PmsOrderSupplierVO getOrderDataByContract(Long id);

    /**
     * 导入excel 表格
     *
     * @param excelVOList   采购订单列表
     * @param bindingResult 错误信息列表
     * @param factoryCode   所属工厂编码
     * @return R
     */
    R importPmsOrder(List<PmsOrderExcelVO> excelVOList, BindingResult bindingResult, String factoryCode);
}