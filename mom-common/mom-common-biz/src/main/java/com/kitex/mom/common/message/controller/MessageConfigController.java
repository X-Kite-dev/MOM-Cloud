package com.kitex.mom.common.message.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.common.message.entity.MessageConfigEntity;
import com.kitex.mom.common.message.service.MessageConfigService;
import com.kitex.mom.common.message.vo.MessageConfigExcelVO;
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
 * 消息推送配置表
 *
 * @author kitex
 * @date 2025-01-14 08:44:24
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/messageConfig")
@Tag(description = "messageConfig", name = "消息推送配置表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MessageConfigController {

    private final MessageConfigService messageConfigService;

    /**
     * 分页查询
     *
     * @param page          分页对象
     * @param messageConfig 消息推送配置表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('message_messageConfig_view')")
    public R<Page<MessageConfigEntity>> getMessageConfigPage(@ParameterObject SearchPage page, @ParameterObject MessageConfigEntity messageConfig) {
        LambdaQueryWrapper<MessageConfigEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(messageConfig.getName()), MessageConfigEntity::getName, messageConfig.getName());
        wrapper.like(StrUtil.isNotBlank(messageConfig.getCode()), MessageConfigEntity::getCode, messageConfig.getCode());
        wrapper.eq(StrUtil.isNotBlank(messageConfig.getType()), MessageConfigEntity::getType, messageConfig.getType());
        return R.ok(messageConfigService.page(page.toMpPage(), wrapper));
    }

    /**
     * 通过条件查询消息推送配置列表
     *
     * @param messageConfig 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/list")
    public R<List<MessageConfigEntity>> getList(@ParameterObject MessageConfigEntity messageConfig) {
        return R.ok(messageConfigService.list(Wrappers.query(messageConfig)));
    }

    /**
     * 通过条件查询消息推送配置表
     *
     * @param messageConfig 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('message_messageConfig_view')")
    public R<List<MessageConfigEntity>> getDetails(@ParameterObject MessageConfigEntity messageConfig) {
        return R.ok(messageConfigService.listDeep(Wrappers.query(messageConfig)));
    }

    /**
     * 通过业务编码查询消息推送配置
     *
     * @param code 业务编码
     * @return R 对象
     */
    @Operation(summary = "通过业务编码查询消息推送配置", description = "通过业务编码查询消息推送配置")
    @GetMapping("/getConfigByCode")
    public R<MessageConfigEntity> getConfigByCode(@RequestParam("code") String code) {
        return R.ok(messageConfigService.getConfigByCode(code));
    }

    /**
     * 新增消息推送配置表
     *
     * @param messageConfig 消息推送配置表
     * @return R
     */
    @Operation(summary = "新增消息推送配置表", description = "新增消息推送配置表")
    @SysLog("新增消息推送配置表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('message_messageConfig_add')")
    public R<Boolean> save(@RequestBody MessageConfigEntity messageConfig) {
        return R.ok(messageConfigService.saveDeep(messageConfig));
    }

    /**
     * 修改消息推送配置表
     *
     * @param messageConfig 消息推送配置表
     * @return R
     */
    @Operation(summary = "修改消息推送配置表", description = "修改消息推送配置表")
    @SysLog("修改消息推送配置表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('message_messageConfig_edit')")
    public R<Boolean> updateById(@RequestBody MessageConfigEntity messageConfig) {
        return R.ok(messageConfigService.updateDeep(messageConfig));
    }

    /**
     * 通过id删除消息推送配置表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除消息推送配置表", description = "通过id删除消息推送配置表")
    @SysLog("通过id删除消息推送配置表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('message_messageConfig_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(messageConfigService.removeDeep(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param messageConfig 查询条件
     * @param ids           导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('message_messageConfig_export')")
    public List<MessageConfigExcelVO> exportExcel(MessageConfigEntity messageConfig, Long[] ids) {
        return messageConfigService.exportExcel(messageConfig, ids);
    }
}