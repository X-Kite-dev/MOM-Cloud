package com.kitex.mom.mes.controller;


import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesWarehouseUnqualifiedLockAddLockDTO;
import com.kitex.mom.mes.entity.MesWarehouseUnqualifiedLockEntity;
import com.kitex.mom.mes.service.MesWarehouseUnqualifiedLockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 不合格锁定记录
 *
 * @author kitex
 * @date 2025-02-21 10:44:33
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWarehouseUnqualifiedLock")
@Tag(description = "mesWarehouseUnqualifiedLock", name = "不合格锁定记录管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesWarehouseUnqualifiedLockController {

    private final MesWarehouseUnqualifiedLockService mesWarehouseUnqualifiedLockService;

    /**
     * 分页查询
     *
     * @param page                        分页对象
     * @param mesWarehouseUnqualifiedLock 不合格锁定记录
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseUnqualifiedLock_view')")
    public R getMesWarehouseUnqualifiedLockPage(@ParameterObject SearchPage page, @ParameterObject MesWarehouseUnqualifiedLockEntity mesWarehouseUnqualifiedLock) {
        QueryWrapper<MesWarehouseUnqualifiedLockEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehouseUnqualifiedLock.getMatCode()), MesWarehouseUnqualifiedLockEntity::getMatCode, mesWarehouseUnqualifiedLock.getMatCode());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehouseUnqualifiedLock.getMatName()), MesWarehouseUnqualifiedLockEntity::getMatName, mesWarehouseUnqualifiedLock.getMatName());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehouseUnqualifiedLock.getMatSpec()), MesWarehouseUnqualifiedLockEntity::getMatSpec, mesWarehouseUnqualifiedLock.getMatSpec());
        wrapper.lambda().eq(StrUtil.isNotBlank(mesWarehouseUnqualifiedLock.getSiteCode()), MesWarehouseUnqualifiedLockEntity::getSiteCode, mesWarehouseUnqualifiedLock.getSiteCode());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehouseUnqualifiedLock.getMatBatch()), MesWarehouseUnqualifiedLockEntity::getMatBatch, mesWarehouseUnqualifiedLock.getMatBatch());
        wrapper.lambda().isNotNull(StrUtil.isNotBlank(mesWarehouseUnqualifiedLock.getStatus()) && "1".equals(mesWarehouseUnqualifiedLock.getStatus()), MesWarehouseUnqualifiedLockEntity::getUnlockTime);
        wrapper.lambda().isNull(StrUtil.isNotBlank(mesWarehouseUnqualifiedLock.getStatus()) && "0".equals(mesWarehouseUnqualifiedLock.getStatus()), MesWarehouseUnqualifiedLockEntity::getUnlockTime);
        wrapper.lambda().orderByDesc(MesWarehouseUnqualifiedLockEntity::getLockTime);
        return R.ok(mesWarehouseUnqualifiedLockService.page(page.toMpPage(), wrapper));
    }


    @Operation(summary = "查询各推送日志结果的数量", description = "查询各推送日志结果的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject MesWarehouseUnqualifiedLockEntity mesWarehouseUnqualifiedLock) {
        QueryWrapper<MesWarehouseUnqualifiedLockEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehouseUnqualifiedLock.getMatCode()), MesWarehouseUnqualifiedLockEntity::getMatCode, mesWarehouseUnqualifiedLock.getMatCode());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehouseUnqualifiedLock.getMatName()), MesWarehouseUnqualifiedLockEntity::getMatName, mesWarehouseUnqualifiedLock.getMatName());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehouseUnqualifiedLock.getMatSpec()), MesWarehouseUnqualifiedLockEntity::getMatSpec, mesWarehouseUnqualifiedLock.getMatSpec());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehouseUnqualifiedLock.getMatBatch()), MesWarehouseUnqualifiedLockEntity::getMatBatch, mesWarehouseUnqualifiedLock.getMatBatch());
        wrapper.lambda().eq(StrUtil.isNotBlank(mesWarehouseUnqualifiedLock.getSiteCode()), MesWarehouseUnqualifiedLockEntity::getSiteCode, mesWarehouseUnqualifiedLock.getSiteCode());
        wrapper.lambda().orderByDesc(MesWarehouseUnqualifiedLockEntity::getLockTime);
        return R.ok(mesWarehouseUnqualifiedLockService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(entity -> entity.getUnlockTime() != null ? "1" : "0", Collectors.counting())));
    }


    /**
     * 通过条件查询不合格锁定记录
     *
     * @param mesWarehouseUnqualifiedLock 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseUnqualifiedLock_view')")
    public R getDetails(@ParameterObject MesWarehouseUnqualifiedLockEntity mesWarehouseUnqualifiedLock) {
        return R.ok(mesWarehouseUnqualifiedLockService.listDeep(Wrappers.query(mesWarehouseUnqualifiedLock)));
    }

    /**
     * 新增不合格锁定记录
     *
     * @param mesWarehouseUnqualifiedLock 不合格锁定记录
     * @return R
     */
    @Operation(summary = "新增不合格锁定记录", description = "新增不合格锁定记录")
    @SysLog("新增不合格锁定记录")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseUnqualifiedLock_add')")
    public R save(@Validated @RequestBody MesWarehouseUnqualifiedLockEntity mesWarehouseUnqualifiedLock) {
        return R.ok(mesWarehouseUnqualifiedLockService.saveDeep(mesWarehouseUnqualifiedLock));
    }

    /**
     * 添加锁定
     *
     * @param dto 锁定条件
     * @return R
     */
    @Operation(summary = "添加锁定", description = "添加锁定")
    @SysLog("添加锁定")
    @PostMapping("/addLock")
    public R addLock(@RequestBody MesWarehouseUnqualifiedLockAddLockDTO dto) {
        return R.ok(mesWarehouseUnqualifiedLockService.addLock(dto));
    }

    /**
     * 解锁
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "解锁", description = "解锁")
    @SysLog("解锁")
    @GetMapping("/unlock/{id}")
    public R unlock(@PathVariable Long id) {
        return R.ok(mesWarehouseUnqualifiedLockService.unlock(id));
    }

    /**
     * 修改不合格锁定记录
     *
     * @param mesWarehouseUnqualifiedLock 不合格锁定记录
     * @return R
     */
    @Operation(summary = "修改不合格锁定记录", description = "修改不合格锁定记录")
    @SysLog("修改不合格锁定记录")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseUnqualifiedLock_edit')")
    public R updateById(@RequestBody MesWarehouseUnqualifiedLockEntity mesWarehouseUnqualifiedLock) {
        return R.ok(mesWarehouseUnqualifiedLockService.updateDeep(mesWarehouseUnqualifiedLock));
    }

    /**
     * 通过id删除不合格锁定记录
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除不合格锁定记录", description = "通过id删除不合格锁定记录")
    @SysLog("通过id删除不合格锁定记录")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseUnqualifiedLock_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(mesWarehouseUnqualifiedLockService.removeDeep(ids));
    }

    /**
     * 通过id删除不合格锁定记录子表数据
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除不合格锁定记录子表数据", description = "通过id删除不合格锁定记录子表数据")
    @SysLog("通过id删除不合格锁定记录子表数据")
    @DeleteMapping("/child")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseUnqualifiedLock_del')")
    public R removeChild(@RequestBody Long[] ids) {
        return R.ok(mesWarehouseUnqualifiedLockService.removeChild(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param mesWarehouseUnqualifiedLock 查询条件
     * @param ids                         导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseUnqualifiedLock_export')")
    public List<MesWarehouseUnqualifiedLockEntity> exportExcel(MesWarehouseUnqualifiedLockEntity mesWarehouseUnqualifiedLock, Long[] ids) {
        return mesWarehouseUnqualifiedLockService.list(Wrappers.lambdaQuery(mesWarehouseUnqualifiedLock).in(ArrayUtil.isNotEmpty(ids), MesWarehouseUnqualifiedLockEntity::getId, ids));
    }

    /**
     * 导入excel 表
     *
     * @param mesWarehouseUnqualifiedLockList 对象实体列表
     * @param bindingResult                   错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseUnqualifiedLock_export')")
    public R importExcel(@RequestExcel List<MesWarehouseUnqualifiedLockEntity> mesWarehouseUnqualifiedLockList, BindingResult bindingResult) {
        return R.ok(mesWarehouseUnqualifiedLockService.saveBatch(mesWarehouseUnqualifiedLockList));
    }
}