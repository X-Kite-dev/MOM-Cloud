package com.kitex.mom.common.message.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.common.message.dto.MessageBusinessConfigAddDTO;
import com.kitex.mom.common.message.entity.MessageBusinessConfigEntity;
import com.kitex.mom.common.message.service.MessageBusinessConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 业务模块配置表
 *
 * @author kitex
 * @date 2025-01-15 09:17:26
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/messageBusinessConfig")
@Tag(description = "messageBusinessConfig", name = "业务模块配置表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MessageBusinessConfigController {

    private final MessageBusinessConfigService messageBusinessConfigService;

    /**
     * 分页查询
     *
     * @param page                  分页对象
     * @param messageBusinessConfig 业务模块配置表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('message_messageBusinessConfig_view')")
    public R<Page<MessageBusinessConfigEntity>> getMessageBusinessConfigPage(@ParameterObject SearchPage page, @ParameterObject MessageBusinessConfigEntity messageBusinessConfig) {
        LambdaQueryWrapper<MessageBusinessConfigEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(StrUtil.isNotBlank(messageBusinessConfig.getModuleCode()), MessageBusinessConfigEntity::getModuleCode, messageBusinessConfig.getModuleCode());
        wrapper.like(StrUtil.isNotBlank(messageBusinessConfig.getName()), MessageBusinessConfigEntity::getName, messageBusinessConfig.getName());
        wrapper.like(StrUtil.isNotBlank(messageBusinessConfig.getCode()), MessageBusinessConfigEntity::getCode, messageBusinessConfig.getCode());
        return R.ok(messageBusinessConfigService.page(page.toMpPage(), wrapper));
    }

    /**
     * 通过id查询业务模块配置表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('message_messageBusinessConfig_view')")
    public R<MessageBusinessConfigAddDTO> getById(@PathVariable("id") Long id) {
        return R.ok(messageBusinessConfigService.getBusinessConfig(id));
    }

    /**
     * 通过条件查询业务配置
     *
     * @param messageBusinessConfig 查询条件
     * @return R 对象列表
     */
    @Operation(summary = "通过条件查询业务配置", description = "通过条件查询业务配置")
    @GetMapping("/details")
    public R<MessageBusinessConfigEntity> getDetails(@ParameterObject MessageBusinessConfigEntity messageBusinessConfig) {
        return R.ok(messageBusinessConfigService.getOne(Wrappers.query(messageBusinessConfig), false));
    }

    /**
     * 新增业务模块配置表
     *
     * @param messageBusinessConfigAddDTO 业务模块配置DTO
     * @return R
     */
    @Operation(summary = "新增业务模块配置表", description = "新增业务模块配置表")
    @SysLog("新增业务模块配置表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('message_messageBusinessConfig_add')")
    public R<Boolean> save(@RequestBody MessageBusinessConfigAddDTO messageBusinessConfigAddDTO) {
        return R.ok(messageBusinessConfigService.saveOrUpdateBusinessConfig(messageBusinessConfigAddDTO));
    }

    /**
     * 修改业务模块配置表
     *
     * @param messageBusinessConfigAddDTO 业务模块配置DTO
     * @return R
     */
    @Operation(summary = "修改业务模块配置表", description = "修改业务模块配置表")
    @SysLog("修改业务模块配置表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('message_messageBusinessConfig_edit')")
    public R<Boolean> updateById(@RequestBody MessageBusinessConfigAddDTO messageBusinessConfigAddDTO) {
        return R.ok(messageBusinessConfigService.saveOrUpdateBusinessConfig(messageBusinessConfigAddDTO));
    }

    /**
     * 通过id删除业务模块配置表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除业务模块配置表", description = "通过id删除业务模块配置表")
    @SysLog("通过id删除业务模块配置表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('message_messageBusinessConfig_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(messageBusinessConfigService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param messageBusinessConfig 查询条件
     * @param ids                   导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('message_messageBusinessConfig_export')")
    public List<MessageBusinessConfigEntity> export(MessageBusinessConfigEntity messageBusinessConfig, Long[] ids) {
        return messageBusinessConfigService.list(Wrappers.lambdaQuery(messageBusinessConfig).in(ArrayUtil.isNotEmpty(ids), MessageBusinessConfigEntity::getId, ids));
    }
}