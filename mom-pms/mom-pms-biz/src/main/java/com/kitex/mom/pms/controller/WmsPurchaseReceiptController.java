package com.kitex.mom.pms.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.pms.dto.PmsOrderPurchaseReceiptInspectionDTO;
import com.kitex.mom.pms.dto.PmsPurchaseInboundPosting;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptInNumDTO;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptRejectDTO;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptSetQualifiedNumDTO;
import com.kitex.mom.pms.dto.app.WmsPurchaseReceiptParamsDTO;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptEntity;
import com.kitex.mom.pms.exception.PmsReceiptRegistrationRejectedException;
import com.kitex.mom.pms.service.WmsPurchaseReceiptService;
import com.kitex.mom.pms.service.impl.PmsPurchaseInboundPostingService;
import com.kitex.mom.pms.vo.WmsPurchaseReceiptExcelVO;
import com.kitex.mom.qms.dto.inspection.InspectionPurchaseCreateParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
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

    private final PmsPurchaseInboundPostingService inboundPosting;

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
        return R.ok(wmsPurchaseReceiptService.addMatNum(wmsPurchaseReceiptInNumDTO.getDocumentId(),
                wmsPurchaseReceiptInNumDTO.getOrderDetailId(), wmsPurchaseReceiptInNumDTO.getMatCode(),
                wmsPurchaseReceiptInNumDTO.getMatBatch(), wmsPurchaseReceiptInNumDTO.getMatNum()));
    }

    /**
     * WMS 正式过账后按原入库单投递。内部服务请求仍须保留实际岗位身份。
     */
    @Inner
    @PostMapping("/internal/wms-inbound-posting")
    @Operation(summary = "受理正式采购入库永久回写")
    public R<PmsPurchaseInboundPosting.Receipt> acceptInboundPosting(@RequestBody PmsPurchaseInboundPosting command) {
        return R.ok(inboundPosting.accept(command));
    }

    /**
     * 按原入库单查询同组织已提交的永久回执。
     */
    @Inner
    @GetMapping("/internal/wms-inbound-posting/{wmsBillId}")
    @Operation(summary = "查询采购入库永久回执")
    public R<PmsPurchaseInboundPosting.Receipt> inboundPostingReceipt(@PathVariable Long wmsBillId) {
        return R.ok(inboundPosting.lookup(wmsBillId));
    }

    /**
     * WMS 新建采购入库前核对原采购来源和目标仓是否可走 V1。
     *
     * @param purchaseReceiptId PMS 原收料单主键
     * @param warehouseCode WMS 原采购入库通知的目标仓库编码
     * @return 是否适用 V1，目标仓冲突时返回业务失败
     */
    @Inner
    @GetMapping("/internal/wms-inbound-posting/eligibility/{purchaseReceiptId}")
    @Operation(summary = "核对采购入库永久回写适用范围")
    public R<Boolean> inboundPostingEligibility(@PathVariable Long purchaseReceiptId,
                                                 @RequestParam("warehouseCode") String warehouseCode) {
        return R.ok(inboundPosting.eligible(purchaseReceiptId, warehouseCode));
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
    @PreAuthorize("@pms.hasPermission('pms_pmsOrder_receive')")
    public R getByBillNo(@RequestBody WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        return R.ok(wmsPurchaseReceiptService.getByBillNo(wmsPurchaseReceipt));
    }

    /**
     * 按原收料单登记实收；不接受客户端改价，不承担仓库入库或质量判定。
     *
     * @param wmsPurchaseReceipt 原收料单、原行及本次实收事实
     * @return 原行登记保存结果；自身领域拒绝返回非0业务码，事务/数据访问等未知异常沿原异常通道
     */
    @Operation(summary = "原单实收登记", description = "按原收料单登记，核对原行和剩余量并保留权威价格")
    @SysLog("app收料")
    @PostMapping("/appReceipt")
    @PreAuthorize("@pms.hasPermission('pms_pmsOrder_receive')")
    public R<Boolean> appReceipt(@Valid @RequestBody WmsPurchaseReceiptParamsDTO wmsPurchaseReceipt) {
        try {
            return R.ok(wmsPurchaseReceiptService.appReceipt(wmsPurchaseReceipt));
        }
        catch (PmsReceiptRegistrationRejectedException rejection) {
            // 两层服务的事务代理已退出并回滚；只承认自身领域拒绝，不能把提交/数据库异常判为未执行。
            return R.failed(rejection.getMessage());
        }
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
