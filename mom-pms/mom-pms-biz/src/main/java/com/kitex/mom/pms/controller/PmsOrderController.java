package com.kitex.mom.pms.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.common.flow.entity.FlowInstanceBizRecordEntity;
import com.kitex.mom.pms.dto.PmsOrderAddDTO;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.PmsOrderEntity;
import com.kitex.mom.pms.enums.PmsOrderStatusEnum;
import com.kitex.mom.pms.service.PmsOrderService;
import com.kitex.mom.pms.vo.PmsOrderDetailVO;
import com.kitex.mom.pms.vo.PmsOrderExcelVO;
import com.kitex.mom.pms.vo.PmsOrderSupplierVO;
import com.kitex.mom.pms.vo.PmsOrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 采购订单表
 *
 * @author kitex
 * @date 2025-01-07 13:36:28
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsOrder")
@Tag(description = "pmsOrder", name = "采购订单表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsOrderController {

    private final PmsOrderService pmsOrderService;

    /**
     * 分页查询采购订单
     *
     * @param page     分页对象
     * @param pmsOrder 采购订单表
     * @return
     */

    @Operation(summary = "分页查询采购订单", description = "分页查询采购订单")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('pms_pmsOrder_view')")
    public R<Page<PmsOrderEntity>> getPmsOrderPage(@ParameterObject SearchPage page, @ParameterObject PmsOrderEntity pmsOrder) {
        QueryWrapper<PmsOrderEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .eq(pmsOrder.getId() != null, PmsOrderEntity::getId, pmsOrder.getId())
                .like(StrUtil.isNotBlank(pmsOrder.getPmsOrderNo()), PmsOrderEntity::getPmsOrderNo, pmsOrder.getPmsOrderNo())
                .like(StrUtil.isNotBlank(pmsOrder.getSupplierCode()), PmsOrderEntity::getSupplierCode, pmsOrder.getSupplierCode())
                .like(StrUtil.isNotBlank(pmsOrder.getSupplierName()), PmsOrderEntity::getSupplierName, pmsOrder.getSupplierName())
                .eq(StrUtil.isNotBlank(pmsOrder.getStatus()), PmsOrderEntity::getStatus, pmsOrder.getStatus())
                .orderByAsc(PmsOrderEntity::getStatus).orderByDesc(PmsOrderEntity::getCreateTime);
        return R.ok(pmsOrderService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询到货数据大于0的采购订单
     *
     * @param page     分页对象
     * @param pmsOrder 采购订单表
     * @return
     */
    @Operation(summary = "分页查询到货数据大于0的采购订单", description = "分页查询到货数据大于0的采购订单")
    @GetMapping("/pageDetail")
    public R<Page<PmsOrderDetailVO>> getPmsOrderPageDetail(@ParameterObject SearchPage page, @ParameterObject PmsOrderEntity pmsOrder) {
        return R.ok(pmsOrderService.getPmsOrderPageDetail(page.toMpPage(), pmsOrder));
    }

    /**
     * 合同绑定采购订单分页查询
     *
     * @param page     分页对象
     * @param pmsOrder 采购订单表
     * @return
     */
    @Operation(summary = "合同绑定采购订单分页查询", description = "合同绑定采购订单分页查询")
    @GetMapping("/contractPage")
    public R<Page<PmsOrderEntity>> getContractPage(@ParameterObject SearchPage page, @ParameterObject PmsOrderEntity pmsOrder) {
        LambdaQueryWrapper<PmsOrderEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(pmsOrder.getPmsOrderNo()), PmsOrderEntity::getPmsOrderNo, pmsOrder.getPmsOrderNo());
        wrapper.like(StrUtil.isNotBlank(pmsOrder.getSupplierCode()), PmsOrderEntity::getSupplierCode, pmsOrder.getSupplierCode());
        wrapper.like(StrUtil.isNotBlank(pmsOrder.getSupplierName()), PmsOrderEntity::getSupplierName, pmsOrder.getSupplierName());
        wrapper.in(PmsOrderEntity::getStatus, PmsOrderStatusEnum.CHECK_SUCCESS.getCode(), PmsOrderStatusEnum.RECEIVING.getCode(),
                PmsOrderStatusEnum.PART_RECEIVE.getCode(), PmsOrderStatusEnum.COMPLETE.getCode());
        wrapper.eq(PmsOrderEntity::getContractStatus, Boolean.FALSE);
        wrapper.orderByAsc(PmsOrderEntity::getStatus).orderByDesc(PmsOrderEntity::getCreateTime);
        return R.ok(pmsOrderService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各采购订单状态的数量
     *
     * @return
     */

    @Operation(summary = "查询各采购订单状态的数量", description = "查询各采购订单状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject PmsOrderEntity pmsOrder) {
        QueryWrapper<PmsOrderEntity> wrapper = Wrappers.query();
        wrapper.lambda().select(PmsOrderEntity::getStatus)
                .eq(pmsOrder.getId() != null, PmsOrderEntity::getId, pmsOrder.getId())
                .like(StrUtil.isNotBlank(pmsOrder.getPmsOrderNo()), PmsOrderEntity::getPmsOrderNo, pmsOrder.getPmsOrderNo())
                .like(StrUtil.isNotBlank(pmsOrder.getSupplierCode()), PmsOrderEntity::getSupplierCode, pmsOrder.getSupplierCode())
                .like(StrUtil.isNotBlank(pmsOrder.getSupplierName()), PmsOrderEntity::getSupplierName, pmsOrder.getSupplierName());
        return R.ok(pmsOrderService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(PmsOrderEntity::getStatus, Collectors.counting())));
    }

    /**
     * 通过采购订单id查询明细
     *
     * @param id 采购订单id
     * @return R  对象列表
     */
    @Operation(summary = "通过采购订单id查询明细", description = "通过采购订单id查询明细")
    @GetMapping("/detailsById/{id}")
    public R<List<PmsOrderDetailEntity>> getDetailsById(@PathVariable("id") Long id) {
        return R.ok(pmsOrderService.getDetailsById(id));
    }

    /**
     * 通过采购订单id查询明细和供应商信息
     *
     * @param id 采购订单id
     * @return R  对象列表
     */
    @Operation(summary = "通过采购订单id查询明细和供应商信息", description = "通过采购订单id查询明细和供应商信息")
    @GetMapping("/orderDataByContract/{id}")
    public R<PmsOrderSupplierVO> getOrderDataByContract(@PathVariable("id") Long id) {
        return R.ok(pmsOrderService.getOrderDataByContract(id));
    }

    /**
     * 通过条件查询采购订单表
     *
     * @param pmsOrder 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('pms_pmsOrder_view')")
    public R<List<PmsOrderEntity>> getDetails(@ParameterObject PmsOrderEntity pmsOrder) {
        return R.ok(pmsOrderService.listDeep(Wrappers.query(pmsOrder)));
    }

    /**
     * 新增采购订单表
     *
     * @param pmsOrder 采购订单新增DTO
     * @return R
     */
    @Operation(summary = "新增采购订单表", description = "新增采购订单表")
    @SysLog("新增采购订单表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsOrder_add')")
    public R<Boolean> save(@RequestBody PmsOrderAddDTO pmsOrder) {
        return R.ok(pmsOrderService.saveDeep(pmsOrder));
    }

    /**
     * 编辑采购订单表
     *
     * @param pmsOrder 采购订单编辑DTO
     * @return R
     */
    @Operation(summary = "编辑采购订单表", description = "编辑采购订单表")
    @SysLog("编辑采购订单表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsOrder_edit')")
    public R<Boolean> update(@RequestBody PmsOrderAddDTO pmsOrder) {
        return R.ok(pmsOrderService.updateDeep(pmsOrder));
    }

    /**
     * 通过id构造采购通知单VO
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id构造采购通知单VO", description = "通过id构造采购通知单VO")
    @SysLog("通过id构造采购通知单VO")
    @GetMapping("/getPurchaseInNotice/{id}")
    public R<PmsOrderVO> getPurchaseInNotice(@PathVariable("id") Long id) {
        return R.ok(pmsOrderService.getPurchaseInNotice(id));
    }

    /**
     * 审核
     *
     * @param ids 采购订单表ids
     * @return R
     */
    @Operation(summary = "审核", description = "审核")
    @PutMapping("/check")
    public R<Boolean> check(@RequestBody Long[] ids) {
        return R.ok(pmsOrderService.check(ids));
    }

    /**
     * 审核-流程结束修改状态
     *
     * @param backDTO 参数
     */
    @Inner(value = false)
    @Operation(summary = "审核-流程结束修改状态", description = "审核-流程结束修改状态")
    @PostMapping("/processBackChangeState")
    public R<Boolean> processBackChangeState(@RequestBody FlowInstanceBizRecordEntity backDTO) {
        return R.ok(pmsOrderService.processBackChangeState(backDTO));
    }

    /**
     * 通过id删除采购订单表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除采购订单表", description = "通过id删除采购订单表")
    @SysLog("通过id删除采购订单表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsOrder_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(pmsOrderService.removeDeep(ids));
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   采购订单列表
     * @param bindingResult 错误信息列表
     * @param factoryCode   所属工厂编码
     * @return R
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('pms_pmsOrder_export')")
    public R importPmsOrder(@RequestExcel(headRowNumber = 2) List<PmsOrderExcelVO> excelVOList, BindingResult bindingResult,
                            @RequestParam("factoryCode") Long factoryCode) {
        return pmsOrderService.importPmsOrder(excelVOList, bindingResult, String.valueOf(factoryCode));
    }

    /**
     * 导出excel 表格
     *
     * @param pmsOrder 查询条件
     * @param ids      导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('pms_pmsOrder_export')")
    public List<PmsOrderExcelVO> exportExcel(PmsOrderEntity pmsOrder, Long[] ids) {
        return pmsOrderService.export(pmsOrder, ids);
    }

    /**
     * 采购订单打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    @Operation(summary = "采购订单打印", description = "采购订单打印")
    @GetMapping("/print")
    public R<String> print(@ParameterObject PrintReq printReq) {
        return R.ok(pmsOrderService.print(printReq));
    }

    /**
     * 采购订单导出
     *
     * @param id id
     */
    @Operation(summary = "采购订单导出", description = "采购订单导出")
    @GetMapping("/download")
    public void download(Long id, HttpServletResponse response) {
        pmsOrderService.download(id, response);
    }
}
