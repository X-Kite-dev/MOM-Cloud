package com.kitex.mom.pms.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.pms.dto.PmsOrderPurchaseReceiptInspectionDTO;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptInNumDTO;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptRejectDTO;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptSetQualifiedNumDTO;
import com.kitex.mom.pms.dto.app.WmsPurchaseReceiptParamsDTO;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptEntity;
import com.kitex.mom.pms.service.WmsPurchaseReceiptService;
import com.kitex.mom.pms.vo.WmsPurchaseReceiptExcelVO;
import com.kitex.mom.qms.dto.inspection.InspectionPurchaseCreateParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 采购收料单
 *
 * @author kitex
 * @date 2025-01-22 11:34:35
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsPurchaseReceipt")
@Tag(description = "wmsPurchaseReceipt", name = "采购收料单管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsPurchaseReceiptController {

    private final WmsPurchaseReceiptService wmsPurchaseReceiptService;

    /**
     * 分页查询
     *
     * @param page               分页对象
     * @param wmsPurchaseReceipt 采购收料单
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    public R getWmsPurchaseReceiptPage(@ParameterObject SearchPage page, @ParameterObject WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        QueryWrapper<WmsPurchaseReceiptEntity> wrapper = createWrapper(wmsPurchaseReceipt);
        wrapper.lambda().eq(StrUtil.isNotBlank(wmsPurchaseReceipt.getState()), WmsPurchaseReceiptEntity::getState, wmsPurchaseReceipt.getState());
        return R.ok(wmsPurchaseReceiptService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各采购收料状态的数量
     *
     * @return
     */

    @Operation(summary = "查询各采购收料状态的数量", description = "查询各采购收料状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        return R.ok(wmsPurchaseReceiptService.list(createWrapper(wmsPurchaseReceipt))
                .stream()
                .collect(Collectors.groupingBy(WmsPurchaseReceiptEntity::getState, Collectors.counting())));
    }

    public QueryWrapper<WmsPurchaseReceiptEntity> createWrapper(WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        QueryWrapper<WmsPurchaseReceiptEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(wmsPurchaseReceipt.getBillNo()), WmsPurchaseReceiptEntity::getBillNo, wmsPurchaseReceipt.getBillNo())
                .like(StrUtil.isNotBlank(wmsPurchaseReceipt.getPmsOrderNo()), WmsPurchaseReceiptEntity::getPmsOrderNo, wmsPurchaseReceipt.getPmsOrderNo())
                .like(StrUtil.isNotBlank(wmsPurchaseReceipt.getSupplierCode()), WmsPurchaseReceiptEntity::getSupplierCode, wmsPurchaseReceipt.getSupplierCode())
                .eq(StrUtil.isNotBlank(wmsPurchaseReceipt.getWarehouseCode()), WmsPurchaseReceiptEntity::getWarehouseCode, wmsPurchaseReceipt.getWarehouseCode())
                .orderByAsc(WmsPurchaseReceiptEntity::getState).orderByDesc(BaseCore::getCreateTime);
        return wrapper;
    }

    /**
     * 通过条件查询采购收料单
     *
     * @param wmsPurchaseReceipt 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    public R getDetails(@ParameterObject WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        return R.ok(wmsPurchaseReceiptService.listDeep(Wrappers.query(wmsPurchaseReceipt)));
    }

    /**
     * 根据id查询采购收料单
     *
     * @param id id
     * @return R  对象
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/genQmsDetails/{id}")
    public R genQmsDetails(@PathVariable("id") Long id) {
        return R.ok(wmsPurchaseReceiptService.genQmsDetails(id));
    }

    /**
     * 新增采购收料单
     *
     * @param wmsPurchaseReceipt 采购收料单
     * @return R
     */
    @Operation(summary = "新增采购收料单", description = "新增采购收料单")
    @SysLog("新增采购收料单")
    @PostMapping("/add")
    public R save(@RequestBody WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        return R.ok(wmsPurchaseReceiptService.saveDeep(wmsPurchaseReceipt));
    }

    /**
     * 入库时更新实际入库数
     *
     * @param wmsPurchaseReceiptInNumDTO 采购收料入库数量DTO
     */
    @Operation(summary = "入库时更新实际入库数", description = "入库时更新实际入库数")
    @SysLog("入库时更新实际入库数")
    @PutMapping("/addMatNum")
    public R<Boolean> addMatNum(@RequestBody WmsPurchaseReceiptInNumDTO wmsPurchaseReceiptInNumDTO) {
        return R.ok(wmsPurchaseReceiptService.addMatNum(wmsPurchaseReceiptInNumDTO.getDocumentId(), wmsPurchaseReceiptInNumDTO.getMatCode(),
                wmsPurchaseReceiptInNumDTO.getMatBatch(), wmsPurchaseReceiptInNumDTO.getMatNum()));
    }

    /**
     * 采购入库通知单作废后回调
     *
     * @param wmsPurchaseReceiptRejectDTO 采购入库通知单作废DTO
     */
    @Operation(summary = "采购入库通知单作废后回调", description = "采购入库通知单作废后回调")
    @SysLog("采购入库通知单作废后回调")
    @PutMapping("/voidObjsBack")
    public R<Boolean> voidObjsBack(@RequestBody WmsPurchaseReceiptRejectDTO wmsPurchaseReceiptRejectDTO) {
        return R.ok(wmsPurchaseReceiptService.voidObjsBack(wmsPurchaseReceiptRejectDTO.getDocumentId(), wmsPurchaseReceiptRejectDTO.getGenType()
                , wmsPurchaseReceiptRejectDTO.getResult()));
    }

    /**
     * 根据单号获取信息
     */
    @Operation(summary = "根据单号获取信息", description = "根据单号获取信息")
    @SysLog("根据单号获取信息")
    @PostMapping("/getByBillNo")
    public R getByBillNo(@RequestBody WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        return R.ok(wmsPurchaseReceiptService.getByBillNo(wmsPurchaseReceipt));
    }

    /**
     * app收料
     *
     * @param wmsPurchaseReceipt 采购收料单
     * @return R
     */
    @Operation(summary = "app收料", description = "app收料")
    @SysLog("app收料")
    @PostMapping("/appReceipt")
    public R appReceipt(@RequestBody WmsPurchaseReceiptParamsDTO wmsPurchaseReceipt) {
        return R.ok(wmsPurchaseReceiptService.appReceipt(wmsPurchaseReceipt));
    }

    /**
     * 生成质检任务
     */
    @Operation(summary = "生成质检任务", description = "生成质检任务")
    @SysLog("生成质检任务")
    @PostMapping("/genQualityTask")
    public R genQualityTask(@RequestBody List<InspectionPurchaseCreateParam> body) {
        return R.ok(wmsPurchaseReceiptService.genQualityTask(body, null));
    }

//	/**
//	 * 绑定质检任务id
//	 */
//	@Operation(summary = "绑定质检任务id", description = "绑定质检任务id")
//	@SysLog("绑定质检任务id")
//	@PostMapping("/setQualityId")
//	public R setQualityId(@RequestBody WmsPurchaseReceiptEntity body) {
//		return R.ok(wmsPurchaseReceiptService.setQualityId(body));
//	}

    /**
     * 质检回馈合格数量
     */
    @Operation(summary = "质检回馈合格数量", description = "质检回馈合格数量")
    @SysLog("质检回馈合格数量")
    @PostMapping("/setQualifiedNum")
    public R setQualifiedNum(@RequestBody WmsPurchaseReceiptSetQualifiedNumDTO body) {
        return R.ok(wmsPurchaseReceiptService.setQualifiedNum(body));
    }

    /**
     * 生成采购入库通知单
     *
     * @param wmsPurchaseReceipt 采购收料单
     * @return R
     */
    @Operation(summary = "生成采购入库通知单", description = "生成采购入库通知单")
    @SysLog("生成采购入库通知单")
    @PostMapping("/genInNotice")
    public R genInNotice(@RequestBody WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        return R.ok(wmsPurchaseReceiptService.genInNotice(wmsPurchaseReceipt));
    }

    /**
     * 免检生成采购入库通知单
     *
     * @param wmsPurchaseReceipt 采购收料单
     * @return R
     */
    @Operation(summary = "免检生成采购入库通知单", description = "免检生成采购入库通知单")
    @SysLog("免检生成采购入库通知单")
    @PostMapping("/qmsGenInNotice")
    public R qmsGenInNotice(@RequestBody WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        return R.ok(wmsPurchaseReceiptService.qmsGenInNotice(wmsPurchaseReceipt));
    }

    /**
     * pc收料
     *
     * @param pmsOrder 采购订单新增DTO
     * @return R
     */
    @Operation(summary = "收料", description = "/收料")
    @SysLog("/收料")
    @PostMapping("/receive")
    public R<Boolean> receive(@RequestBody PmsOrderPurchaseReceiptInspectionDTO pmsOrder) {
        return R.ok(wmsPurchaseReceiptService.receive(pmsOrder));
    }

    /**
     * 修改采购收料单
     *
     * @param wmsPurchaseReceipt 采购收料单
     * @return R
     */
    @Operation(summary = "修改采购收料单", description = "修改采购收料单")
    @SysLog("修改采购收料单")
    @PutMapping("/edit")
    public R updateById(@RequestBody WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        return R.ok(wmsPurchaseReceiptService.updateDeep(wmsPurchaseReceipt));
    }

    /**
     * 通过id删除采购收料单
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除采购收料单", description = "通过id删除采购收料单")
    @SysLog("通过id删除采购收料单")
    @DeleteMapping
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(wmsPurchaseReceiptService.removeDeep(ids));
    }

    /**
     * 通过id删除采购收料单子表数据
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除采购收料单子表数据", description = "通过id删除采购收料单子表数据")
    @SysLog("通过id删除采购收料单子表数据")
    @DeleteMapping("/child")
    public R removeChild(@RequestBody Long[] ids) {
        return R.ok(wmsPurchaseReceiptService.removeChild(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param wmsPurchaseReceipt 查询条件
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    public List<WmsPurchaseReceiptExcelVO> exportExcel(WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        QueryWrapper<WmsPurchaseReceiptEntity> wrapper = createWrapper(wmsPurchaseReceipt);
        wrapper.lambda().eq(StrUtil.isNotBlank(wmsPurchaseReceipt.getState()), WmsPurchaseReceiptEntity::getState, wmsPurchaseReceipt.getState());
        return wmsPurchaseReceiptService.listExcel(wrapper);
    }

}