package com.kitex.mom.wms.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.wms.dto.WmsInoutFlowQueryDTO;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.service.inOutFlow.DefaultWmsInoutFlowService;
import com.kitex.mom.wms.vo.WmsInoutFlowExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 出入库流水
 *
 * @author kitex
 * @date 2024-12-18 18:48:20
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/material/wmsInoutFlow")
@Tag(description = "wmsInoutFlow", name = "出入库流水")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsMaterialInoutFlowController {

    private final DefaultWmsInoutFlowService wmsInoutFlowService;

    /**
     * 分页查询
     *
     * @param page         分页对象
     * @param wmsInoutFlow 出入库流水
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
//	@PreAuthorize("@pms.hasPermission('wms_wmsInoutFlow_view')")
    public R getWmsInoutFlowPage(@ParameterObject SearchPage page, @ParameterObject WmsInoutFlowQueryDTO wmsInoutFlow) {
        LambdaQueryWrapper<WmsInoutFlowEntity> wrapper = Wrappers.lambdaQuery();
        createWrapper(wrapper, wmsInoutFlow);
        return R.ok(wmsInoutFlowService.page(page.toMpPage(), wrapper));
    }

    public void createWrapper(LambdaQueryWrapper<WmsInoutFlowEntity> wrapper, WmsInoutFlowQueryDTO wmsInoutFlow) {
        if (CollUtil.isNotEmpty(wmsInoutFlow.getCreateTime())) {
            wrapper.ge(BaseCore::getCreateTime, wmsInoutFlow.getCreateTime().get(0));
            if (wmsInoutFlow.getCreateTime().size() > 1) {
                wrapper.le(BaseCore::getCreateTime, wmsInoutFlow.getCreateTime().get(1));
            }
        }
        wrapper.in(CollUtil.isNotEmpty(wmsInoutFlow.getWarehouseCode()), WmsInoutFlowEntity::getWarehouseCode, wmsInoutFlow.getWarehouseCode())
                .like(StrUtil.isNotBlank(wmsInoutFlow.getBillNo()), WmsInoutFlowEntity::getBillNo, wmsInoutFlow.getBillNo())
                .like(StrUtil.isNotBlank(wmsInoutFlow.getMatCode()), WmsInoutFlowEntity::getMatCode, wmsInoutFlow.getMatCode())
                .like(StrUtil.isNotBlank(wmsInoutFlow.getMatName()), WmsInoutFlowEntity::getMatName, wmsInoutFlow.getMatName())
                .like(StrUtil.isNotBlank(wmsInoutFlow.getSiteCode()), WmsInoutFlowEntity::getSiteCode, wmsInoutFlow.getSiteCode())
                .eq(StrUtil.isNotBlank(wmsInoutFlow.getOperateType()), WmsInoutFlowEntity::getOperateType, wmsInoutFlow.getOperateType())
                .eq(StrUtil.isNotBlank(wmsInoutFlow.getBillType()), WmsInoutFlowEntity::getBillType, wmsInoutFlow.getBillType())
                .orderByDesc(BaseCore::getCreateTime);
    }

    /**
     * 导出excel 表格
     *
     * @param wmsInoutFlow 查询条件
     * @param ids          导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
//	@PreAuthorize("@pms.hasPermission('wms_wmsInoutFlow_export')")
    public List<WmsInoutFlowExcelVO> exportExcel(WmsInoutFlowQueryDTO wmsInoutFlow, Long[] ids) {
        LambdaQueryWrapper<WmsInoutFlowEntity> wrapper = Wrappers.lambdaQuery();
        if (ArrayUtil.isNotEmpty(ids)) {
            wrapper.in(WmsInoutFlowEntity::getId, ids);
        } else {
            createWrapper(wrapper, wmsInoutFlow);
        }
        return wmsInoutFlowService.listExcel(wrapper);
    }
}