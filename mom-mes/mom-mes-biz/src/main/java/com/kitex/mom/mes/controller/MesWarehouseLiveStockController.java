package com.kitex.mom.mes.controller;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesLiveStockQueryDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedDTO;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.vo.MesWarehouseLiveStockByMaterialExcelVO;
import com.kitex.mom.mes.vo.MesWarehouseLiveStockDetailExcelVO;
import com.kitex.mom.mes.vo.MesWarehouseLiveStockVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 线边库实时库存
 *
 * @author kitex
 * @date 2025-02-20 13:54:13
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWarehouseLiveStock")
@Tag(description = "mesWarehouseLiveStock", name = "线边库实时库存管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesWarehouseLiveStockController {

    private final MesWarehouseLiveStockService mesWarehouseLiveStockService;

    /**
     * 分页查询
     *
     * @param page                  分页对象
     * @param mesWarehouseLiveStock 线边库实时库存
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseLiveStock_view')")
    public R getMesWarehouseLiveStockPage(@ParameterObject SearchPage page, @ParameterObject MesLiveStockQueryDTO mesWarehouseLiveStock) {
        return R.ok(mesWarehouseLiveStockService.page(page.toMpPage(), getWrapperDetail(mesWarehouseLiveStock)));
    }

    public LambdaQueryWrapper<MesWarehouseLiveStockEntity> getWrapperDetail(MesLiveStockQueryDTO liveStock) {
        LambdaQueryWrapper<MesWarehouseLiveStockEntity> wrapper = Wrappers.lambdaQuery();
        if (CollUtil.isNotEmpty(liveStock.getInTime())) {
            wrapper.ge(MesWarehouseLiveStockEntity::getInTime, liveStock.getInTime().get(0));
            if (liveStock.getInTime().size() > 1) {
                wrapper.le(MesWarehouseLiveStockEntity::getInTime, liveStock.getInTime().get(1));
            }
        }
        wrapper.like(StrUtil.isNotBlank(liveStock.getMatCode()), MesWarehouseLiveStockEntity::getMatCode, liveStock.getMatCode())
                .like(StrUtil.isNotBlank(liveStock.getMatName()), MesWarehouseLiveStockEntity::getMatName, liveStock.getMatName())
                .eq(StrUtil.isNotBlank(liveStock.getInspectionStatus()), MesWarehouseLiveStockEntity::getInspectionStatus, liveStock.getInspectionStatus())
                .in(CollUtil.isNotEmpty(liveStock.getSiteCode()), MesWarehouseLiveStockEntity::getSiteCode, liveStock.getSiteCode());
        return wrapper;
    }

    /**
     * 分页查询-物料级
     *
     * @param page         分页对象
     * @param mesLiveStock 实时库存
     * @return
     */
    @Operation(summary = "分页查询-物料级", description = "分页查询-物料级")
    @GetMapping("/pageByMat")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseLiveStock_view')")
    public R getWmsLiveStockPageByMat(@ParameterObject SearchPage page, @ParameterObject MesLiveStockQueryDTO mesLiveStock) {
        QueryWrapper<MesWarehouseLiveStockEntity> wrapper = getQueryWrapperByMat(mesLiveStock);
        return R.ok(mesWarehouseLiveStockService.page(page.toMpPage(), wrapper));
    }

    public QueryWrapper<MesWarehouseLiveStockEntity> getQueryWrapperByMat(MesLiveStockQueryDTO mesLiveStock) {
        QueryWrapper<MesWarehouseLiveStockEntity> wrapper = new QueryWrapper<>();
        wrapper.select("mat_code", "mat_name", "mat_spec", "mat_unit", "sum(num) as num");
        wrapper.lambda().like(StrUtil.isNotBlank(mesLiveStock.getMatCode()), MesWarehouseLiveStockEntity::getMatCode, mesLiveStock.getMatCode())
                .in(CollUtil.isNotEmpty(mesLiveStock.getSiteCode()), MesWarehouseLiveStockEntity::getSiteCode, mesLiveStock.getSiteCode())
                .like(StrUtil.isNotBlank(mesLiveStock.getMatName()), MesWarehouseLiveStockEntity::getMatName, mesLiveStock.getMatName())
                .eq(StrUtil.isNotBlank(mesLiveStock.getWarehouseCode()), MesWarehouseLiveStockEntity::getWarehouseCode, mesLiveStock.getWarehouseCode())
                .groupBy(MesWarehouseLiveStockEntity::getMatCode, MesWarehouseLiveStockEntity::getMatName, MesWarehouseLiveStockEntity::getMatSpec, MesWarehouseLiveStockEntity::getMatUnit)
                .orderByAsc(MesWarehouseLiveStockEntity::getMatCode);
        return wrapper;
    }

    /**
     * 通过id查询线边库实时库存
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseLiveStock_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(mesWarehouseLiveStockService.getById(id));
    }

    /**
     * 分页查询-线边库类型（原材料、在制品、半成品、成品）
     *
     * @param page         分页对象
     * @param mesLiveStock 实时库存
     * @return
     */

    @Operation(summary = "分页查询-线边库类型（原材料、在制品、半成品、成品）", description = "分页查询-线边库类型（原材料、在制品、半成品、成品）")
    @GetMapping("/pageByMatType")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseLiveStock_view')")
    public R<Page<MesWarehouseLiveStockVO>> getWmsLiveStockPageByMatType(@ParameterObject SearchPage page, @ParameterObject MesLiveStockQueryDTO mesLiveStock) {
        return R.ok(mesWarehouseLiveStockService.getWmsLiveStockPageByMatType(page.toMpPage(), mesLiveStock));
    }

    /**
     * 新增线边库实时库存
     *
     * @param mesWarehouseLiveStock 线边库实时库存
     * @return R
     */
    @Operation(summary = "新增线边库实时库存", description = "新增线边库实时库存")
    @SysLog("新增线边库实时库存")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseLiveStock_add')")
    public R save(@RequestBody MesWarehouseLiveStockEntity mesWarehouseLiveStock) {
        if (mesWarehouseLiveStock.getQualityCaseId() != null || mesWarehouseLiveStock.getQualityOriginCaseId() != null || mesWarehouseLiveStock.getQualityDispositionReceiptId() != null || mesWarehouseLiveStock.getQualityReleaseReceiptId() != null)
            throw new com.kitex.common.core.exception.CheckedException("质量案件库存及处置谱系只能由专用命令生成");
        if (mesWarehouseLiveStock.getPhysicalLocationCode() != null || mesWarehouseLiveStock.getPhysicalLocationName() != null || mesWarehouseLiveStock.getPhysicalConfirmedTime() != null)
            throw new com.kitex.common.core.exception.CheckedException("现场位置确认须由实际转运交接生成，不得手工伪造");
        if (mesWarehouseLiveStock.getMaterialReceiptId() != null || mesWarehouseLiveStock.getSourceReportId() != null)
            throw new com.kitex.common.core.exception.CheckedException("不得通过手工库存新增伪造报工或签收来源");
        return R.ok(mesWarehouseLiveStockService.save(mesWarehouseLiveStock));
    }

    /**
     * 线边库实时库存入库
     */
    @Operation(summary = "线边库实时库存入库", description = "线边库实时库存入库")
    @SysLog("线边库实时库存入库")
    @PostMapping("/inStock")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseLiveStock_add')")
    public R inStock(@Validated @RequestBody MesWarehouseLiveStockInoutDTO mesWarehouseLiveStock) {
        if (mesWarehouseLiveStock.getMaterialReceiptId() != null || mesWarehouseLiveStock.getSourceReportId() != null
                || com.kitex.mom.mes.enums.WarehouseInBizTypeEnum.RECEPTION.getCode().equals(mesWarehouseLiveStock.getBizType()))
            throw new com.kitex.common.core.exception.CheckedException("生产签收和报工库存须由对应业务命令生成");
        return R.ok(mesWarehouseLiveStockService.inStock(mesWarehouseLiveStock));
    }

    /**
     * 线边库实时库存出库
     */
    @Operation(summary = "线边库实时库存出库", description = "线边库实时库存出库")
    @SysLog("线边库实时库存出库")
    @PostMapping("/outStock")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseLiveStock_edit')")
    public R outStock(@Validated @RequestBody MesWarehouseLiveStockInoutDTO mesWarehouseLiveStock) {
        throw new com.kitex.common.core.exception.CheckedException("通用HTTP出库入口已关闭：请使用报工、退料或冲销等受控业务命令，不能直接提交跳过质量/任务约束的库存参数");
    }

    /**
     * 修改线边库实时库存
     *
     * @param mesWarehouseLiveStock 线边库实时库存
     * @return R
     */
    @Operation(summary = "修改线边库实时库存", description = "修改线边库实时库存")
    @SysLog("修改线边库实时库存")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseLiveStock_edit')")
    public R updateById(@RequestBody MesWarehouseLiveStockEntity mesWarehouseLiveStock) {
        return R.ok(mesWarehouseLiveStockService.updateManual(mesWarehouseLiveStock));
    }

    /**
     * 通过id删除线边库实时库存
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除线边库实时库存", description = "通过id删除线边库实时库存")
    @SysLog("通过id删除线边库实时库存")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseLiveStock_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(mesWarehouseLiveStockService.removeManual(ids));
    }

    /**
     * 导出 明细 表格
     *
     * @param liveStock 查询条件
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseLiveStock_export')")
    public List<MesWarehouseLiveStockDetailExcelVO> export(MesLiveStockQueryDTO liveStock) {
        return mesWarehouseLiveStockService.listDetailExcel(getWrapperDetail(liveStock));
    }

    /**
     * 导出 物料级 表格
     *
     * @param liveStock 查询条件
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/exportByMat")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseLiveStock_export')")
    public List<MesWarehouseLiveStockByMaterialExcelVO> exportByMat(MesLiveStockQueryDTO liveStock) {
        return mesWarehouseLiveStockService.listByMatExcel(getQueryWrapperByMat(liveStock));
    }


    /**
     * 生产退料分页
     *
     * @param pageParam 分页对象
     * @param query     实时库存
     * @return
     */

    @Operation(summary = "分页查询-物料级", description = "分页查询-物料级")
    @GetMapping("/pageFeedback")
    @PreAuthorize("@pms.hasPermission('mes_feedback:show') or @pms.hasPermission('stockFeedJudge:submit')")
    public R<IPage<MesWarehouseFeedDTO>> getPageFeedBack(Page<MesWarehouseFeedDTO> pageParam, MesWarehouseFeedDTO query) {
        return R.ok(mesWarehouseLiveStockService.getPageFeedBack(pageParam, query));
    }
}
