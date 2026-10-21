package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.entity.MesIdentifierBindingGroupEntity;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 永久冻结区段只读查询；不提供编辑、删除、直接解绑或自动权限授权。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesIdentifierBindingGroup")
@Tag(name = "生产换码区段", description = "工单永久工艺依据只读查询")
public class MesIdentifierBindingGroupController {

    private final MesIdentifierBindingGroupService service;

    /** 原生产模板查看权限控制，实际租户/工厂由服务端认证上下文取得。 */
    @GetMapping("/task-groups")
    @Operation(summary = "查询工单永久换码区段", description = "空集合表示未冻结区段，不代表已经存在活动绑定")
    @PreAuthorize("@pms.hasPermission('mesExecutionTemplate:show')")
    public R<List<MesIdentifierBindingGroupEntity>> taskGroups(@RequestParam Long taskId) {
        return R.ok(service.taskGroups(taskId));
    }

    /** 返回首次冻结的全图、区段、原路线和指纹，不读取当前模板替换原依据。 */
    @GetMapping("/evidence")
    @Operation(summary = "查看永久换码区段原始依据")
    @PreAuthorize("@pms.hasPermission('mesExecutionTemplate:show')")
    public R<MesIdentifierBindingGroupEntity> evidence(@RequestParam Long groupId) {
        return R.ok(service.evidence(groupId));
    }
}
