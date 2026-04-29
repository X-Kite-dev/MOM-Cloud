package com.kitex.mom.wms.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.dto.WmsInNoticeQueryDTO;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.enums.OtherOutBillTypeEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.service.outNotice.WmsOutNoticeFactory;
import com.kitex.mom.wms.service.outNotice.WmsOutNoticeHandler;
import com.kitex.mom.wms.vo.WmsOutNoticeDetailExcelVO;
import com.kitex.mom.wms.vo.WmsOutNoticeExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 其他出库通知单
 *
 * @author kitex
 * @date 2024-12-31 17:57:44
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/other/outNotice")
@Tag(description = "WmsOtherOutNotice", name = "其他出库通知单")
public class WmsOtherOutNoticeController {

    // 其他出库通用类型
    private final String billType = OutBillTypeEnum.OTHER_OUT.getCode();

    // 其他出库所有类型，查询列表时的条件
    private final List<String> otherBillTypes = Arrays.stream(OtherOutBillTypeEnum.values()).map(OtherOutBillTypeEnum::getCode).toList();

    /**
     * 分页查询其他出库通知单
     */
    @Operation(summary = "分页查询其他出库通知单", description = "分页查询其他出库通知单")
    @GetMapping("/page")
    public R<Page<WmsOutNoticeEntity>> page(SearchPage page, WmsInNoticeQueryDTO queryDTO) {
        QueryWrapper<WmsOutNoticeEntity> wrapper = getMainQueryWrapper(queryDTO);
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        Page result = handler.page(page.toMpPage(), wrapper);
        return R.ok(result);
    }

    @GetMapping("/getCount")
    @Operation(summary = "分页查询生产领料出库通知单", description = "分页查询生产领料出库通知单")
    public R<Map<String, Long>> getCount(WmsInNoticeQueryDTO queryDTO) {
        QueryWrapper<WmsOutNoticeEntity> wrapper = getCountQueryWrapper(queryDTO);
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        return R.ok(handler.getCount(wrapper));
    }

    /**
     * 分页查询明细
     *
     * @param page   分页对象
     * @param params 查询条件
     * @return
     */
    @Operation(summary = "分页查询明细", description = "分页查询明细")
    @GetMapping("/pageDetails")
    public R getWmsInNoticeDetailPage(SearchPage page, WmsInNoticeDetailQueryDTO params) {
        params.setBillTypes(otherBillTypes);
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        return R.ok(handler.pageDetails(page.toMpPage(), params));
    }

    /**
     * 新增其他出库通知单
     */
    @PostMapping("/add")
    @Operation(summary = "新增其他出库通知单", description = "新增其他出库通知单")
    public R add(@RequestBody WmsOutNoticeEntity wmsOutNoticeEntity) {
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(wmsOutNoticeEntity.getBillType());
        Boolean result = handler.saveDeep(wmsOutNoticeEntity);
        return result ? R.ok() : R.failed();
    }

    /**
     * 修改其他出库通知单
     */
    @PutMapping("/edit")
    @Operation(summary = "修改其他出库通知单", description = "修改其他出库通知单")
    public R edit(@RequestBody WmsOutNoticeEntity wmsOutNoticeEntity) {
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(wmsOutNoticeEntity.getBillType());
        Boolean result = handler.updateDeep(wmsOutNoticeEntity);
        return result ? R.ok() : R.failed();
    }

    /**
     * 查询其他出库通知单详情
     */
    @GetMapping("/detailsById")
    @Operation(summary = "查询其他出库通知单详情", description = "查询其他出库通知单详情")
    public R details(Long id) {
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        WmsOutNoticeEntity wmsOutNoticeEntity = handler.selectById(id);
        return R.ok(wmsOutNoticeEntity);
    }

    /**
     * 根据通知单id查询实时库存
     */
    @GetMapping("/getLiveStockByNoticeId")
    @Operation(summary = "根据通知单id查询实时库存", description = "根据通知单id查询实时库存")
    public R getLiveStockByNoticeId(Long id) {
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        WmsOutBillEntity wmsOutNoticeEntity = handler.getLiveStockByNoticeId(id);
        return R.ok(wmsOutNoticeEntity);
    }

    /**
     * 作废
     */
    @GetMapping("/voidObj")
    @Operation(summary = "作废", description = "作废")
    public R voidObj(@RequestParam Long id) {
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        handler.voidObjs(id);
        return R.ok();
    }

    /**
     * 删除其他出库通知单
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除其他出库通知单", description = "删除其他出库通知单")
    public R delete(@RequestBody String[] ids) {
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        Boolean result = handler.removeDeep(ids);
        return result ? R.ok() : R.failed();
    }

    /**
     * 导出主表 excel 表格
     *
     * @param wmsOutNotice 查询条件
     * @param ids          导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/exportMain")
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutNotice_export')")
    public List<WmsOutNoticeExcelVO> exportMain(WmsInNoticeQueryDTO wmsOutNotice, Long[] ids) {
        QueryWrapper<WmsOutNoticeEntity> wrapper = new QueryWrapper<>();
        if (ArrayUtil.isNotEmpty(ids)) {
            wrapper.lambda().in(WmsOutNoticeEntity::getId, ids);
        } else {
            wrapper = getMainQueryWrapper(wmsOutNotice);
        }
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutNotice_export')")
    public List<WmsOutNoticeDetailExcelVO> exportDetails(WmsInNoticeDetailQueryDTO params, Long[] ids) {
        params.setBillTypes(otherBillTypes);
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        return handler.listDetails(params, ids);
    }

    public QueryWrapper<WmsOutNoticeEntity> getMainQueryWrapper(WmsInNoticeQueryDTO queryDTO) {
        QueryWrapper<WmsOutNoticeEntity> wrapper = new QueryWrapper<>();
        if (CollUtil.isNotEmpty(queryDTO.getCreateTime())) {
            wrapper.lambda().ge(BaseCore::getCreateTime, queryDTO.getCreateTime().get(0));
            if (queryDTO.getCreateTime().size() > 1) {
                wrapper.lambda().lt(BaseCore::getCreateTime, queryDTO.getCreateTime().get(1));
            }
        }
        wrapper.lambda().like(StrUtil.isNotBlank(queryDTO.getId()), WmsOutNoticeEntity::getId, queryDTO.getId())
                .eq(StrUtil.isNotBlank(queryDTO.getState()), WmsOutNoticeEntity::getState, queryDTO.getState())
                .eq(StrUtil.isNotBlank(queryDTO.getWarehouseCode()), WmsOutNoticeEntity::getWarehouseCode, queryDTO.getWarehouseCode())
                .like(StrUtil.isNotBlank(queryDTO.getCreateBy()), BaseCore::getCreateBy, queryDTO.getCreateBy())
                .in(WmsOutNoticeEntity::getBillType, otherBillTypes)
                .orderByAsc(WmsOutNoticeEntity::getState)
                .orderByDesc(BaseCore::getCreateTime);
        return wrapper;
    }


    public QueryWrapper<WmsOutNoticeEntity> getCountQueryWrapper(WmsInNoticeQueryDTO queryDTO) {
        QueryWrapper<WmsOutNoticeEntity> wrapper = new QueryWrapper<>();
        if (CollUtil.isNotEmpty(queryDTO.getCreateTime())) {
            wrapper.lambda().ge(BaseCore::getCreateTime, queryDTO.getCreateTime().get(0));
            if (queryDTO.getCreateTime().size() > 1) {
                wrapper.lambda().lt(BaseCore::getCreateTime, queryDTO.getCreateTime().get(1));
            }
        }
        wrapper.lambda().like(StrUtil.isNotBlank(queryDTO.getId()), WmsOutNoticeEntity::getId, queryDTO.getId())
                .eq(StrUtil.isNotBlank(queryDTO.getWarehouseCode()), WmsOutNoticeEntity::getWarehouseCode, queryDTO.getWarehouseCode())
                .like(StrUtil.isNotBlank(queryDTO.getCreateBy()), BaseCore::getCreateBy, queryDTO.getCreateBy())
                .in(WmsOutNoticeEntity::getBillType, otherBillTypes);
        return wrapper;
    }
}
