package com.kitex.mom.common.flow.controller;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.flow.dto.FlowInstanceBizRecordDTO;
import com.kitex.mom.common.flow.entity.FlowInstanceBizRecordEntity;
import com.kitex.mom.common.flow.service.FlowInstanceBizRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 流程实例与业务关联记录
 *
 * @author kitex
 * @date 2025-03-28 17:08:36
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/flowInstanceBizRecord")
@Tag(description = "flowInstanceBizRecord", name = "流程实例与业务关联记录管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class FlowInstanceBizRecordController {

    private final FlowInstanceBizRecordService flowInstanceBizRecordService;

    /**
     * 分页查询
     *
     * @param page                  分页对象
     * @param flowInstanceBizRecord 流程实例与业务关联记录
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    //@PreAuthorize("@pms.hasPermission('common_flowInstanceBizRecord_view')")
    public R<Page<FlowInstanceBizRecordEntity>> getFlowInstanceBizRecordPage(@ParameterObject SearchPage page, @ParameterObject FlowInstanceBizRecordDTO flowInstanceBizRecord) {
        LambdaQueryWrapper<FlowInstanceBizRecordEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(flowInstanceBizRecord.getBillId()), FlowInstanceBizRecordEntity::getBillId, flowInstanceBizRecord.getBillId());
        wrapper.eq(StrUtil.isNotBlank(flowInstanceBizRecord.getBizService()), FlowInstanceBizRecordEntity::getBizService, flowInstanceBizRecord.getBizService());
        wrapper.eq(StrUtil.isNotBlank(flowInstanceBizRecord.getFlowType()), FlowInstanceBizRecordEntity::getFlowType, flowInstanceBizRecord.getFlowType());
        wrapper.eq(StrUtil.isNotBlank(flowInstanceBizRecord.getResult()), FlowInstanceBizRecordEntity::getResult, flowInstanceBizRecord.getResult());
        wrapper.like(StrUtil.isNotBlank(flowInstanceBizRecord.getCreateBy()), FlowInstanceBizRecordEntity::getCreateBy, flowInstanceBizRecord.getCreateBy());
        if (ArrayUtil.isNotEmpty(flowInstanceBizRecord.getCreateTime())) {
            wrapper.between(FlowInstanceBizRecordEntity::getCreateTime, flowInstanceBizRecord.getCreateTime()[0], flowInstanceBizRecord.getCreateTime()[1]);
        }
        wrapper.orderByDesc(FlowInstanceBizRecordEntity::getCreateTime);
        return R.ok(flowInstanceBizRecordService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各流程记录结果的数量
     *
     * @return
     */
    @Operation(summary = "查询各流程记录结果的数量", description = "查询各流程记录结果的数量")
    @GetMapping("/getCount")
    //@PreAuthorize("@pms.hasPermission('common_flowInstanceBizRecord_view')")
    public R<Map<String, Long>> getCount(@ParameterObject FlowInstanceBizRecordDTO flowInstanceBizRecord) {
        LambdaQueryWrapper<FlowInstanceBizRecordEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(flowInstanceBizRecord.getBillId()), FlowInstanceBizRecordEntity::getBillId, flowInstanceBizRecord.getBillId());
        wrapper.eq(StrUtil.isNotBlank(flowInstanceBizRecord.getBizService()), FlowInstanceBizRecordEntity::getBizService, flowInstanceBizRecord.getBizService());
        wrapper.eq(StrUtil.isNotBlank(flowInstanceBizRecord.getFlowType()), FlowInstanceBizRecordEntity::getFlowType, flowInstanceBizRecord.getFlowType());
        wrapper.like(StrUtil.isNotBlank(flowInstanceBizRecord.getCreateBy()), FlowInstanceBizRecordEntity::getCreateBy, flowInstanceBizRecord.getCreateBy());
        if (ArrayUtil.isNotEmpty(flowInstanceBizRecord.getCreateTime())) {
            wrapper.between(FlowInstanceBizRecordEntity::getCreateTime, flowInstanceBizRecord.getCreateTime()[0], flowInstanceBizRecord.getCreateTime()[1]);
        }
        return R.ok(flowInstanceBizRecordService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(FlowInstanceBizRecordEntity::getResult, Collectors.counting())));
    }

    /**
     * 通过id查询流程实例与业务关联记录
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    //@PreAuthorize("@pms.hasPermission('common_flowInstanceBizRecord_view')")
    public R<FlowInstanceBizRecordEntity> getById(@PathVariable("id") Long id) {
        return R.ok(flowInstanceBizRecordService.getById(id));
    }
}