package com.kitex.mom.wms.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.service.inBill.WmsInBillFactory;
import com.kitex.mom.wms.service.inBill.WmsInBillHandler;
import com.kitex.mom.wms.service.inNotice.WmsInNoticeFactory;
import com.kitex.mom.wms.service.inNotice.WmsInNoticeHandler;
import com.kitex.mom.wms.vo.WmsInBillDetailExcelVO;
import com.kitex.mom.wms.vo.WmsInBillExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 入库单
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/inBill")
@Tag(description = "WmsInBillController", name = "入库单")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsInBillController {

    /**
     * 分页查询
     *
     * @param page      分页对象
     * @param wmsInBill 入库单
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    public R getWmsInBillPage(@ParameterObject SearchPage page, @ParameterObject WmsInBillDetailQueryDTO wmsInBill) {
        QueryWrapper<WmsInBillEntity> wrapper = getMainQueryWrapper(wmsInBill);
        WmsInBillHandler handler = WmsInBillFactory.getHandler(wmsInBill.getBillType());
        return R.ok(handler.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询统计
     */
    @Operation(summary = "分页查询统计", description = "分页查询统计")
    @GetMapping("/getCount")
    public R getCount(@ParameterObject WmsInBillDetailQueryDTO wmsInBill) {
        QueryWrapper<WmsInBillEntity> wrapper = getCountQueryWrapper(wmsInBill);
        WmsInBillHandler handler = WmsInBillFactory.getHandler(wmsInBill.getBillType());
        return R.ok(handler.getCount(wrapper));
    }

    /**
     * 分页查询明细
     *
     * @param page   分页对象
     * @param params 入库单
     * @return
     */
    @Operation(summary = "分页查询明细", description = "分页查询明细")
    @GetMapping("/pageDetails")
    public R getWmsInBillPageDetails(@ParameterObject SearchPage page, @ParameterObject WmsInBillDetailQueryDTO params) {
        params.setBillType(params.getBillType());
        WmsInBillHandler handler = WmsInBillFactory.getHandler(params.getBillType());
        return R.ok(handler.pageDetails(page.toMpPage(), params));
    }


    /**
     * 通过id查询入库单
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    public R getById(@PathVariable("id") Long id) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(null);
        return R.ok(handler.selectById(id));
    }


    /**
     * 查询入库通知单详情
     *
     * @return R  对象
     */
    @Operation(summary = "查询入库通知单详情", description = "查询入库通知单详情")
    @GetMapping("/getNoticeDetailsById")
    public R getNoticeDetailsById(@RequestParam Long id) {
        WmsInNoticeHandler handler = WmsInNoticeFactory.getHandler(null);
        return R.ok(handler.selectById(id));
    }


    /**
     * 新增入库单
     *
     * @param wmsInBill 入库单
     * @return R
     */
    @Operation(summary = "新增入库单", description = "新增入库单")
    @SysLog("新增入库单")
    @PostMapping
    public R save(@RequestBody WmsInBillEntity wmsInBill) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(wmsInBill.getBillType());
        return R.ok(handler.saveDeep(wmsInBill));
    }

    /**
     * 新增入库单并直接审核通过
     *
     * @param wmsInBill 入库单
     * @return R
     */
    @Operation(summary = "新增入库单并直接审核通过", description = "新增入库单并直接审核通过")
    @SysLog("新增入库单并直接审核通过")
    @PostMapping("/addAndPass")
    public R addAndPass(@RequestBody WmsInBillEntity wmsInBill) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(wmsInBill.getBillType());
        return R.ok(handler.addAndPass(wmsInBill));
    }

    /**
     * 修改入库单
     *
     * @param wmsInBill 入库单
     * @return R
     */
    @Operation(summary = "修改入库单", description = "修改入库单")
    @SysLog("修改入库单")
    @PutMapping
    public R updateById(@RequestBody WmsInBillEntity wmsInBill) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(wmsInBill.getBillType());
        return R.ok(handler.updateDeep(wmsInBill));
    }

    /**
     * 通过id删除入库单
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除入库单", description = "通过id删除入库单")
    @SysLog("通过id删除入库单")
    @DeleteMapping
    public R removeById(@RequestBody String[] ids) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(null);
        return R.ok(handler.removeDeep(ids));
    }

    /**
     * 提交入库单
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "提交入库单", description = "提交入库单")
    @GetMapping("/commitBill")
    public R commitBill(Long id) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(null);
        return R.ok(handler.commitBill(id));
    }

    /**
     * 审核通过入库单
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "审核通过入库单", description = "审核通过入库单")
    @GetMapping("/passBill")
    public R passBill(Long id) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(null);
        return R.ok(handler.passBill(id));
    }

    /**
     * 审核驳回入库单
     *
     * @param params 入库单id、原因
     * @return R
     */
    @Operation(summary = "审核驳回入库单", description = "审核驳回入库单")
    @PostMapping("/rejectBill")
    public R rejectBill(@RequestBody WmsInBillEntity params) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(params.getBillType());
        return R.ok(handler.rejectBill(params));
    }

    /**
     * 导出主表 excel 表格
     *
     * @param wmsInNotice 查询条件
     * @param ids         导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/exportMain")
    public List<WmsInBillExcelVO> exportMain(WmsInBillDetailQueryDTO wmsInNotice, Long[] ids) {
        QueryWrapper<WmsInBillEntity> wrapper = new QueryWrapper<>();
        if (ArrayUtil.isNotEmpty(ids)) {
            wrapper.lambda().in(WmsInBillEntity::getId, ids);
        } else {
            wrapper = getMainQueryWrapper(wmsInNotice);
        }
        WmsInBillHandler handler = WmsInBillFactory.getHandler(wmsInNotice.getBillType());
        return handler.list(wrapper);
    }

    /**
     * 导出明细 excel 表格
     *
     * @param params 查询条件
     * @param ids    导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/exportDetails")
    public List<WmsInBillDetailExcelVO> exportDetails(WmsInBillDetailQueryDTO params, Long[] ids) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(params.getBillType());
        return handler.listDetails(params, ids);
    }

	/**
	 * 按完整原通知身份构造列表和主表导出条件；现有业务类型与组织权限由原调用入口约束。
	 *
	 * @param wmsInBill 查询参数，空通知号不筛选，非法通知号拒绝查询
	 * @return 主表查询条件，通知号以Long等值绑定
	 */
	public QueryWrapper<WmsInBillEntity> getMainQueryWrapper(WmsInBillDetailQueryDTO wmsInBill) {
		Long noticeId = wmsInBill.noticeIdValue();
		QueryWrapper<WmsInBillEntity> wrapper = new QueryWrapper<>();
		if (CollUtil.isNotEmpty(wmsInBill.getCreateTime())) {
			wrapper.lambda().ge(BaseCore::getCreateTime, wmsInBill.getCreateTime().get(0));
			if (wmsInBill.getCreateTime().size() > 1) {
				wrapper.lambda().lt(BaseCore::getCreateTime, wmsInBill.getCreateTime().get(1));
			}
		}
		wrapper.lambda().like(StrUtil.isNotBlank(wmsInBill.getBillNo()), WmsInBillEntity::getBillNo,
						wmsInBill.getBillNo())
				.eq(noticeId != null, WmsInBillEntity::getNoticeId, noticeId)
				.eq(StrUtil.isNotBlank(wmsInBill.getState()), WmsInBillEntity::getState, wmsInBill.getState())
				.eq(StrUtil.isNotBlank(wmsInBill.getWarehouseCode()), WmsInBillEntity::getWarehouseCode,
						wmsInBill.getWarehouseCode())
				.like(StrUtil.isNotBlank(wmsInBill.getCreateBy()), WmsInBillEntity::getCreateBy,
						wmsInBill.getCreateBy())
				.eq(StrUtil.isNotBlank(wmsInBill.getBillType()), WmsInBillEntity::getBillType,
						wmsInBill.getBillType())
				.orderByAsc(WmsInBillEntity::getState)
				.orderByDesc(BaseCore::getCreateTime);
		return wrapper;
	}

	/**
	 * 按完整原通知身份构造统计条件；现有业务类型与组织权限由原调用入口约束。
	 *
	 * @param wmsInBill 查询参数，空通知号不筛选，非法通知号拒绝查询
	 * @return 主表查询条件，通知号以Long等值绑定
	 */
	public QueryWrapper<WmsInBillEntity> getCountQueryWrapper(WmsInBillDetailQueryDTO wmsInBill) {
		Long noticeId = wmsInBill.noticeIdValue();
		QueryWrapper<WmsInBillEntity> wrapper = new QueryWrapper<>();
		if (CollUtil.isNotEmpty(wmsInBill.getCreateTime())) {
			wrapper.lambda().ge(BaseCore::getCreateTime, wmsInBill.getCreateTime().get(0));
			if (wmsInBill.getCreateTime().size() > 1) {
				wrapper.lambda().lt(BaseCore::getCreateTime, wmsInBill.getCreateTime().get(1));
			}
		}
		wrapper.lambda().like(StrUtil.isNotBlank(wmsInBill.getBillNo()), WmsInBillEntity::getBillNo,
						wmsInBill.getBillNo())
				.eq(noticeId != null, WmsInBillEntity::getNoticeId, noticeId)
				.eq(StrUtil.isNotBlank(wmsInBill.getWarehouseCode()), WmsInBillEntity::getWarehouseCode,
						wmsInBill.getWarehouseCode())
				.like(StrUtil.isNotBlank(wmsInBill.getCreateBy()), WmsInBillEntity::getCreateBy,
						wmsInBill.getCreateBy())
				.eq(StrUtil.isNotBlank(wmsInBill.getBillType()), WmsInBillEntity::getBillType,
						wmsInBill.getBillType());
		return wrapper;
	}
}
