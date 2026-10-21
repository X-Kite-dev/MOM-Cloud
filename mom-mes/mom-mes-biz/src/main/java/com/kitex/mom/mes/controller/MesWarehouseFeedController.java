package com.kitex.mom.mes.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.dto.MesWarehouseFeedLeftDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedBirthDTO;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseFeedBirthService;
import com.kitex.mom.mes.service.impl.MesStartPreparationDrilldownService;
import com.kitex.mom.mes.vo.MesWarehouseFeedExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 线边库投料记录
 *
 * @author kitex
 * @date 2025-02-20 17:22:19
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWarehouseFeed")
@Tag(description = "mesWarehouseFeed", name = "线边库投料记录管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesWarehouseFeedController {

    private final MesWarehouseFeedService mesWarehouseFeedService;
    private final MesWarehouseFeedBirthService feedBirths;
    private final MesStartPreparationDrilldownService preparationDrilldown;

    /** 原来源只读，所有原库存行均返回；不是按批次查询当前库存冒充出生。 */
    @Operation(summary = "查询投料永久出生来源")
    @GetMapping("/{id}/birth")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseFeed_view')")
    public R<MesWarehouseFeedBirthDTO> birth(@PathVariable("id") Long id) {
        return R.ok(feedBirths.evidence(id));
    }

    /**
     * 分页查询
     *
     * @param page             分页对象
     * @param mesWarehouseFeed 线边库投料记录
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseFeed_view')")
    public R getMesWarehouseFeedPage(@ParameterObject SearchPage page, @ParameterObject MesWarehouseFeedEntity mesWarehouseFeed,
                                     @RequestParam(required = false) Long preparationArrangeId) {
        LambdaQueryWrapper<MesWarehouseFeedEntity> wrapper = Wrappers.lambdaQuery();
        if (preparationArrangeId != null) {
            var arrange = preparationDrilldown.require(preparationArrangeId, mesWarehouseFeed.getArrangeCode(), mesWarehouseFeed.getTaskCode());
            wrapper.eq(MesWarehouseFeedEntity::getTenantId, arrange.getTenantId())
                    .eq(MesWarehouseFeedEntity::getFactoryCode, arrange.getFactoryCode());
        }
        wrapper.eq(StrUtil.isNotBlank(mesWarehouseFeed.getArrangeCode()), MesWarehouseFeedEntity::getArrangeCode, mesWarehouseFeed.getArrangeCode());
        wrapper.eq(StrUtil.isNotBlank(mesWarehouseFeed.getProduceTaskCode()), MesWarehouseFeedEntity::getProduceTaskCode, mesWarehouseFeed.getProduceTaskCode());
        wrapper.eq(StrUtil.isNotBlank(mesWarehouseFeed.getTaskCode()), MesWarehouseFeedEntity::getTaskCode, mesWarehouseFeed.getTaskCode());
        wrapper.eq(StrUtil.isNotBlank(mesWarehouseFeed.getMatCode()), MesWarehouseFeedEntity::getMatCode, mesWarehouseFeed.getMatCode());
        wrapper.eq(StrUtil.isNotBlank(mesWarehouseFeed.getMatName()), MesWarehouseFeedEntity::getMatName, mesWarehouseFeed.getMatName());
        wrapper.eq(StrUtil.isNotBlank(mesWarehouseFeed.getMatSpec()), MesWarehouseFeedEntity::getMatSpec, mesWarehouseFeed.getMatSpec());
        // todo 类型 已使用 未使用 部分使用。根据使用投料数量和使用数量计算


        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(mesWarehouseFeedService.page(page.toMpPage(), wrapper));
    }

    @Operation(summary = "查询列表", description = "查询列表")
    @GetMapping("/list")
    public R<List<MesWarehouseFeedEntity>> list(MesWarehouseFeedEntity query) {
        QueryWrapper<MesWarehouseFeedEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesWarehouseFeedEntity::getCreateTime);
        return R.ok(mesWarehouseFeedService.list(autoQueryWrapper));
    }

    /**
     * 余料管理分页查询
     *
     * @param pageParam 分页对象
     * @param query     线边库投料记录
     * @return
     */

    @Operation(summary = "余料管理分页查询", description = "余料管理分页查询")
    @GetMapping("/leftPage")
    @PreAuthorize("@pms.hasPermission('mes_left:show')")
    public R<IPage<MesWarehouseFeedLeftDTO>> queryLeftPage(Page<MesWarehouseFeedLeftDTO> pageParam, MesWarehouseFeedLeftDTO query) {
        return R.ok(mesWarehouseFeedService.queryLeftPage(pageParam, query));
    }


    @Operation(summary = "查询列表", description = "查询列表")
    @GetMapping("/queryTaskFeedList")
    public R<List<MesWarehouseFeedLeftDTO>> queryTaskFeedList(MesWarehouseFeedLeftDTO query) {
        return R.ok(mesWarehouseFeedService.queryTaskFeedList(query));
    }

    /**
     * 通过id查询线边库投料记录
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseFeed_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(mesWarehouseFeedService.getById(id));
    }

    /**
     * 新增线边库投料记录（pad投料接口）
     *
     * @param mesWarehouseFeed 线边库投料记录
     * @return R
     */
    @Operation(summary = "新增线边库投料记录（pad投料接口）", description = "新增线边库投料记录（pad投料接口）")
    @SysLog("新增线边库投料记录（pad投料接口）")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseFeed_add')")
    public R save(@Validated @RequestBody MesWarehouseFeedEntity mesWarehouseFeed) {
        if (mesWarehouseFeed.getFeedCommandId() != null)
            throw new CheckedException("客户端禁止指定内部命令主键；稳定投料请使用完整原文命令接口");
        return R.ok(mesWarehouseFeedService.saveFeed(mesWarehouseFeed));
    }

//    /**
//     * 修改线边库投料记录
//     *
//     * @param mesWarehouseFeed 线边库投料记录
//     * @return R
//     */
//    @Operation(summary = "修改线边库投料记录", description = "修改线边库投料记录")
//    @SysLog("修改线边库投料记录")
//    @PutMapping
//    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseFeed_edit')")
//    public R updateById(@RequestBody MesWarehouseFeedEntity mesWarehouseFeed) {
//        return R.ok(mesWarehouseFeedService.updateById(mesWarehouseFeed));
//    }

//    /**
//     * 通过id删除线边库投料记录
//     *
//     * @param ids id列表
//     * @return R
//     */
//    @Operation(summary = "通过id删除线边库投料记录", description = "通过id删除线边库投料记录")
//    @SysLog("通过id删除线边库投料记录")
//    @DeleteMapping
//    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseFeed_del')")
//    public R removeById(@RequestBody Long[] ids) {
//        return R.ok(mesWarehouseFeedService.removeBatchByIds(CollUtil.toList(ids)));
//    }


    /**
     * 导出excel 表格
     *
     * @param mesWarehouseFeed 查询条件
     * @param ids              导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseFeed_export')")
    public List<MesWarehouseFeedExcelVO> export(MesWarehouseFeedEntity mesWarehouseFeed, Long[] ids) {
        LambdaQueryWrapper<MesWarehouseFeedEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(StrUtil.isNotBlank(mesWarehouseFeed.getArrangeCode()), MesWarehouseFeedEntity::getArrangeCode, mesWarehouseFeed.getArrangeCode());
        wrapper.eq(StrUtil.isNotBlank(mesWarehouseFeed.getProduceTaskCode()), MesWarehouseFeedEntity::getProduceTaskCode, mesWarehouseFeed.getProduceTaskCode());
        wrapper.eq(StrUtil.isNotBlank(mesWarehouseFeed.getTaskCode()), MesWarehouseFeedEntity::getTaskCode, mesWarehouseFeed.getTaskCode());
        wrapper.eq(StrUtil.isNotBlank(mesWarehouseFeed.getMatCode()), MesWarehouseFeedEntity::getMatCode, mesWarehouseFeed.getMatCode());
        wrapper.eq(StrUtil.isNotBlank(mesWarehouseFeed.getMatName()), MesWarehouseFeedEntity::getMatName, mesWarehouseFeed.getMatName());
        List<MesWarehouseFeedEntity> list = mesWarehouseFeedService.list(wrapper);
        List<MesWarehouseFeedExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            MesWarehouseFeedEntity it = list.get(i);
            MesWarehouseFeedExcelVO vo = new MesWarehouseFeedExcelVO();
            BeanUtil.copyProperties(it, vo);
            vo.setRowNo(i + 1);
            result.add(vo);
        }
        return result;
    }


    @Operation(description = "退料")
    @PostMapping("feedBack")
    @PreAuthorize("@pms.hasPermission('mes_left:feedback')")
    public R<Void> feedBack(@Validated @RequestBody MesWarehouseFeedEntity mesWarehouseFeed) {
        mesWarehouseFeedService.feedBack(mesWarehouseFeed);
        return R.ok();
    }


}
