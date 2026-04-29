package com.kitex.mom.mes.controller;


import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.entity.MesWrapEntity;
import com.kitex.mom.mes.service.MesWrapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 箱码管理
 *
 * @author kitex
 * @since 2025-03-25
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWrap")
@Tag(description = "mesWrap", name = "箱码管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesWrapController {

    private final MesWrapService service;

    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesWrap:show')")
    public R<IPage<MesWrapEntity>> page(Page<MesWrapEntity> pageParam, MesWrapEntity query) {
        return R.ok(service.queryPage(pageParam, query));
    }

    /**
     * 通过id查询箱码管理
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mesWrap:show')")
    public R<MesWrapEntity> getById(@PathVariable("id") Long id) {
        return R.ok(service.getById(id));
    }

    /**
     * 新增箱码管理
     *
     * @param mesWrap 箱码管理
     * @return R
     */
    @Operation(summary = "新增箱码管理", description = "新增箱码管理")
    @SysLog("新增箱码管理")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mesWrap:add')")
    public R<String> save(@RequestBody MesWrapEntity mesWrap) {
        return R.ok(service.addData(mesWrap));
    }

    /**
     * 修改箱码管理
     *
     * @param mesWrap 箱码管理
     * @return R
     */
    @Operation(summary = "修改箱码管理", description = "修改箱码管理")
    @SysLog("修改箱码管理")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mesWrap:edit')")
    public R<Void> updateById(@RequestBody MesWrapEntity mesWrap) {
        service.updateById(mesWrap);
        return R.ok();
    }

    /**
     * 通过id删除箱码管理
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除箱码管理", description = "通过id删除箱码管理")
    @SysLog("通过id删除箱码管理")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mesWrap:delete')")
    public R<Void> removeById(@RequestBody Long[] ids) {
        service.removeBatchByIds(CollUtil.toList(ids));
        return R.ok();
    }
} 