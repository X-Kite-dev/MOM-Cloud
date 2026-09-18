package com.kitex.mom.mes.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.mes.entity.MesWarehouseInoutEntity;
import com.kitex.mom.mes.entity.MesQualityStockProofEntity;
import com.kitex.mom.mes.mapper.MesQualityStockProofMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.service.MesWarehouseInoutService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import com.kitex.mom.mes.vo.MesWarehouseInoutExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 线边库出入库流水
 *
 * @author kitex
 * @date 2025-02-20 17:26:14
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWarehouseInout")
@Tag(description = "mesWarehouseInout", name = "线边库出入库流水管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesWarehouseInoutController {

    private final MesWarehouseInoutService mesWarehouseInoutService;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesQualityStockProofMapper qualityStockProofMapper;

    @GetMapping("/qualityStockProof/page")
    @Operation(summary = "质量库存状态转换和拆分证据；不是物流出入库")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseInout_view')")
    public R getQualityStockProofPage(@ParameterObject SearchPage page,
                                     @ParameterObject MesQualityStockProofEntity query) {
        String factory = factoryAccess.require("read");
        LambdaQueryWrapper<MesQualityStockProofEntity> wrapper = new LambdaQueryWrapper<MesQualityStockProofEntity>()
                .eq(MesQualityStockProofEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesQualityStockProofEntity::getFactoryCode, factory)
                .eq(query.getSourceReportId() != null, MesQualityStockProofEntity::getSourceReportId, query.getSourceReportId())
                .eq(StrUtil.isNotBlank(query.getProduceTaskCode()), MesQualityStockProofEntity::getProduceTaskCode, query.getProduceTaskCode())
                .eq(StrUtil.isNotBlank(query.getMatCode()), MesQualityStockProofEntity::getMatCode, query.getMatCode())
                .eq(StrUtil.isNotBlank(query.getAfterInspectionStatus()), MesQualityStockProofEntity::getAfterInspectionStatus, query.getAfterInspectionStatus())
                .orderByDesc(MesQualityStockProofEntity::getId);
        if (query.getSourceStockId() != null) wrapper.and(w -> w
                .eq(MesQualityStockProofEntity::getSourceStockId, query.getSourceStockId())
                .or().eq(MesQualityStockProofEntity::getResultStockId, query.getSourceStockId()));
        return R.ok(qualityStockProofMapper.selectPage(new Page<>(Math.max(1, page.getCurrent()),
                Math.max(1, Math.min(100, page.getSize()))), wrapper));
    }

    /**
     * 分页查询
     *
     * @param page              分页对象
     * @param mesWarehouseInout 线边库出入库流水
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseInout_view')")
    public R getMesWarehouseInoutPage(@ParameterObject SearchPage page, @ParameterObject MesWarehouseInoutEntity mesWarehouseInout) {
        QueryWrapper<MesWarehouseInoutEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().eq(MesWarehouseInoutEntity::getFactoryCode, factoryAccess.require("read"))
                .eq(mesWarehouseInout.getSourceStockId() != null, MesWarehouseInoutEntity::getSourceStockId, mesWarehouseInout.getSourceStockId())
                .eq(mesWarehouseInout.getSourceReportId() != null, MesWarehouseInoutEntity::getSourceReportId, mesWarehouseInout.getSourceReportId());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehouseInout.getBillNo()), MesWarehouseInoutEntity::getBillNo, mesWarehouseInout.getBillNo());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehouseInout.getMatCode()), MesWarehouseInoutEntity::getMatCode, mesWarehouseInout.getMatCode());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehouseInout.getMatName()), MesWarehouseInoutEntity::getMatName, mesWarehouseInout.getMatName());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehouseInout.getMatSpec()), MesWarehouseInoutEntity::getMatSpec, mesWarehouseInout.getMatSpec());
        wrapper.lambda().eq(StrUtil.isNotBlank(mesWarehouseInout.getSiteCode()), MesWarehouseInoutEntity::getSiteCode, mesWarehouseInout.getSiteCode());
        // 操作类型
        wrapper.lambda().eq(StrUtil.isNotBlank(mesWarehouseInout.getOperateType()), MesWarehouseInoutEntity::getOperateType, mesWarehouseInout.getOperateType());
        //业务类型
        wrapper.lambda().eq(StrUtil.isNotBlank(mesWarehouseInout.getBizType()), MesWarehouseInoutEntity::getBizType, mesWarehouseInout.getBizType());

        wrapper.lambda().orderByDesc(BaseCore::getCreateTime);
        return R.ok(mesWarehouseInoutService.page(page.toMpPage(), wrapper));
    }


    /**
     * 通过id查询线边库出入库流水
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseInout_view')")
    public R getById(@PathVariable("id") Long id) {
        var record = mesWarehouseInoutService.getOne(new LambdaQueryWrapper<MesWarehouseInoutEntity>()
                .eq(MesWarehouseInoutEntity::getId, id).eq(MesWarehouseInoutEntity::getFactoryCode, factoryAccess.require("read")));
        if (record == null) throw new com.kitex.common.core.exception.CheckedException("当前工厂库存流水不存在");
        return R.ok(record);
    }

//    /**
//     * 新增线边库出入库流水
//     * @param mesWarehouseInout 线边库出入库流水
//     * @return R
//     */
//    @Operation(summary = "新增线边库出入库流水" , description = "新增线边库出入库流水" )
//    @SysLog("新增线边库出入库流水" )
//    @PostMapping
//    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseInout_add')" )
//    public R save(@RequestBody MesWarehouseInoutEntity mesWarehouseInout) {
//        return R.ok(mesWarehouseInoutService.save(mesWarehouseInout));
//    }
//
//    /**
//     * 修改线边库出入库流水
//     * @param mesWarehouseInout 线边库出入库流水
//     * @return R
//     */
//    @Operation(summary = "修改线边库出入库流水" , description = "修改线边库出入库流水" )
//    @SysLog("修改线边库出入库流水" )
//    @PutMapping
//    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseInout_edit')" )
//    public R updateById(@RequestBody MesWarehouseInoutEntity mesWarehouseInout) {
//        return R.ok(mesWarehouseInoutService.updateById(mesWarehouseInout));
//    }

//    /**
//     * 通过id删除线边库出入库流水
//     * @param ids id列表
//     * @return R
//     */
//    @Operation(summary = "通过id删除线边库出入库流水" , description = "通过id删除线边库出入库流水" )
//    @SysLog("通过id删除线边库出入库流水" )
//    @DeleteMapping
//    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseInout_del')" )
//    public R removeById(@RequestBody Long[] ids) {
//        return R.ok(mesWarehouseInoutService.removeBatchByIds(CollUtil.toList(ids)));
//    }


    /**
     * 导出excel 表格
     *
     * @param mesWarehouseInout 查询条件
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseInout_export')")
    public List<MesWarehouseInoutExcelVO> export(MesWarehouseInoutEntity mesWarehouseInout) {
        LambdaQueryWrapper<MesWarehouseInoutEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(MesWarehouseInoutEntity::getFactoryCode, factoryAccess.require("read"))
                .eq(mesWarehouseInout.getSourceStockId()!=null,MesWarehouseInoutEntity::getSourceStockId,mesWarehouseInout.getSourceStockId())
                .eq(mesWarehouseInout.getSourceReportId()!=null,MesWarehouseInoutEntity::getSourceReportId,mesWarehouseInout.getSourceReportId());
        wrapper.eq(StrUtil.isNotBlank(mesWarehouseInout.getBillNo()), MesWarehouseInoutEntity::getBillNo, mesWarehouseInout.getBillNo());
        wrapper.eq(StrUtil.isNotBlank(mesWarehouseInout.getMatCode()), MesWarehouseInoutEntity::getMatCode, mesWarehouseInout.getMatCode());
        wrapper.eq(StrUtil.isNotBlank(mesWarehouseInout.getMatName()), MesWarehouseInoutEntity::getMatName, mesWarehouseInout.getMatName());
        List<MesWarehouseInoutEntity> list = mesWarehouseInoutService.list(wrapper);
        List<MesWarehouseInoutExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            MesWarehouseInoutEntity it = list.get(i);
            MesWarehouseInoutExcelVO vo = new MesWarehouseInoutExcelVO();
            BeanUtil.copyProperties(it, vo);
            vo.setSourceStockId(it.getSourceStockId() == null ? null : it.getSourceStockId().toString());
            vo.setSourceReportId(it.getSourceReportId() == null ? null : it.getSourceReportId().toString());
            vo.setStockBeforeNum(it.getStockBeforeNum() == null ? null : it.getStockBeforeNum().toPlainString());
            vo.setStockAfterNum(it.getStockAfterNum() == null ? null : it.getStockAfterNum().toPlainString());
            vo.setRowNo(i + 1);
            result.add(vo);
        }
        return result;
    }
}
