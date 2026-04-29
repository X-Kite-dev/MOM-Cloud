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
import com.kitex.mom.common.message.dto.query.MessageLogQueryDTO;
import com.kitex.mom.common.message.entity.MessageLogEntity;
import com.kitex.mom.common.message.service.MessageLogService;
import com.kitex.mom.common.message.vo.MessageLogExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 消息推送记录
 *
 * @author kitex
 * @date 2025-01-13 16:29:19
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/messageLog")
@Tag(description = "messageLog", name = "消息推送记录管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MessageLogController {

    private final MessageLogService messageLogService;

    /**
     * 分页查询
     *
     * @param page       分页对象
     * @param messageLog 消息推送记录
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
//    @PreAuthorize("@pms.hasPermission('message_messageLog_view')")
    public R<Page<MessageLogEntity>> getMessageLogPage(@ParameterObject SearchPage page, @ParameterObject MessageLogQueryDTO messageLog) {
        LambdaQueryWrapper<MessageLogEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(StrUtil.isNotBlank(messageLog.getModuleCode()), MessageLogEntity::getModuleCode, messageLog.getModuleCode());
        wrapper.eq(StrUtil.isNotBlank(messageLog.getBusinessType()), MessageLogEntity::getBusinessType, messageLog.getBusinessType());
        wrapper.eq(StrUtil.isNotBlank(messageLog.getType()), MessageLogEntity::getType, messageLog.getType());
        wrapper.like(StrUtil.isNotBlank(messageLog.getUser()), MessageLogEntity::getUser, messageLog.getUser());
        wrapper.eq(messageLog.getResult() != null, MessageLogEntity::getResult, messageLog.getResult());
        if (ArrayUtil.isNotEmpty(messageLog.getTime())) {
            wrapper.between(MessageLogEntity::getTime, messageLog.getTime()[0], messageLog.getTime()[1]);
        }
        wrapper.orderByDesc(MessageLogEntity::getTime);
        return R.ok(messageLogService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各推送日志结果的数量
     */
    @Operation(summary = "查询各推送日志结果的数量", description = "查询各推送日志结果的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MessageLogQueryDTO messageLog) {
        LambdaQueryWrapper<MessageLogEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.select(MessageLogEntity::getResult)
                .eq(StrUtil.isNotBlank(messageLog.getBusinessType()), MessageLogEntity::getBusinessType, messageLog.getBusinessType())
                .eq(StrUtil.isNotBlank(messageLog.getType()), MessageLogEntity::getType, messageLog.getType());
        if (ArrayUtil.isNotEmpty(messageLog.getTime())) {
            wrapper.between(MessageLogEntity::getTime, messageLog.getTime()[0], messageLog.getTime()[1]);
        }
        return R.ok(messageLogService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MessageLogEntity::getResult, Collectors.counting())));
    }

    /**
     * 通过id查询消息推送记录
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
//    @PreAuthorize("@pms.hasPermission('message_messageLog_view')")
    public R<MessageLogEntity> getById(@PathVariable("id") Long id) {
        return R.ok(messageLogService.getById(id));
    }

    /**
     * 通过id删除消息推送记录
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除消息推送记录", description = "通过id删除消息推送记录")
    @SysLog("通过id删除消息推送记录")
    @DeleteMapping
//    @PreAuthorize("@pms.hasPermission('message_messageLog_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(messageLogService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param messageLog 查询条件
     * @param ids        导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
//    @PreAuthorize("@pms.hasPermission('message_messageLog_export')")
    public List<MessageLogExcelVO> export(MessageLogEntity messageLog, Long[] ids) {
        return messageLogService.export(messageLog, ids);
    }

    /**
     * 消息重推
     *
     * @param id id
     */
    @Operation(summary = "消息重推", description = "消息重推")
    @PostMapping("/push/{id}")
//    @PreAuthorize("@pms.hasPermission('message_messageLog_push')")
    public R<Boolean> businessPush(@PathVariable("id") Long id) {
        return R.ok(messageLogService.businessPush(id));
    }
}