package com.kitex.mom.iot.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.iot.entity.IotSceneHistoryEntity;
import com.kitex.mom.iot.service.IotSceneHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 场景联动历史记录控制器
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/scene/history")
@Tag(name = "场景联动历史管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class IotSceneHistoryController {

    private final IotSceneHistoryService historyService;

    /**
     * 分页查询场景执行历史
     *
     * @param page    分页对象
     * @param history 查询参数
     * @return 分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询场景执行历史")
    @PreAuthorize("@pms.hasPermission('iot_scene_view')")
    public R<IPage<IotSceneHistoryEntity>> getHistoryPage(Page<IotSceneHistoryEntity> page, IotSceneHistoryEntity history) {
        return R.ok(historyService.getHistoryPage(page, history));
    }
} 