package com.kitex.mom.mdm.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.UserFactoryModelBindDTO;
import com.kitex.mom.mdm.entity.MdmUserFactoryModelBindEntity;
import com.kitex.mom.mdm.service.MdmUserFactoryModelBindService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户-工厂建模权限关联 Controller
 *
 * @author claude
 * @date 2025-07-30
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmUserFactoryModelBind")
@Tag(description = "mdmUserFactoryModelBind", name = "用户-工厂建模权限关联管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmUserFactoryModelBindController {

    private final MdmUserFactoryModelBindService userFactoryModelBindService;

    /**
     * 分页查询用户权限绑定
     *
     * @param page 分页对象
     * @param bind 查询条件
     * @return 分页结果
     */
    @Operation(summary = "分页查询", description = "分页查询用户权限绑定")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_userFactoryModelBind_view')")
    public R<Page<MdmUserFactoryModelBindEntity>> page(@ParameterObject SearchPage page,
                                                       @ParameterObject MdmUserFactoryModelBindEntity bind) {
        LambdaQueryWrapper<MdmUserFactoryModelBindEntity> wrapper = new LambdaQueryWrapper<>();
        if (bind.getUserId() != null) {
            wrapper.eq(MdmUserFactoryModelBindEntity::getUserId, bind.getUserId());
        }
        if (bind.getUserName() != null) {
            wrapper.like(MdmUserFactoryModelBindEntity::getUserName, bind.getUserName());
        }
        if (bind.getName() != null) {
            wrapper.like(MdmUserFactoryModelBindEntity::getName, bind.getName());
        }
        if (bind.getFactoryModelId() != null) {
            wrapper.eq(MdmUserFactoryModelBindEntity::getFactoryModelId, bind.getFactoryModelId());
        }
        if (bind.getNodeType() != null) {
            wrapper.eq(MdmUserFactoryModelBindEntity::getNodeType, bind.getNodeType());
        }
        if (bind.getNodeLevel() != null) {
            wrapper.eq(MdmUserFactoryModelBindEntity::getNodeLevel, bind.getNodeLevel());
        }
        if (bind.getPermissionType() != null) {
            wrapper.eq(MdmUserFactoryModelBindEntity::getPermissionType, bind.getPermissionType());
        }
        if (bind.getStatus() != null) {
            wrapper.eq(MdmUserFactoryModelBindEntity::getStatus, bind.getStatus());
        }
        wrapper.orderByDesc(MdmUserFactoryModelBindEntity::getCreateTime);
        return R.ok(userFactoryModelBindService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各绑定工厂权限的用户的状态数量
     *
     * @return 数量统计响应数据
     */
    @Operation(summary = "查询各绑定工厂权限的用户的状态数量", description = "查询各绑定工厂权限的用户的状态数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MdmUserFactoryModelBindEntity bind) {
        LambdaQueryWrapper<MdmUserFactoryModelBindEntity> wrapper = new LambdaQueryWrapper<>();
        if (bind.getUserId() != null) {
            wrapper.eq(MdmUserFactoryModelBindEntity::getUserId, bind.getUserId());
        }
        if (bind.getUserName() != null) {
            wrapper.like(MdmUserFactoryModelBindEntity::getUserName, bind.getUserName());
        }
        if (bind.getName() != null) {
            wrapper.like(MdmUserFactoryModelBindEntity::getName, bind.getName());
        }
        if (bind.getFactoryModelId() != null) {
            wrapper.eq(MdmUserFactoryModelBindEntity::getFactoryModelId, bind.getFactoryModelId());
        }
        if (bind.getNodeType() != null) {
            wrapper.eq(MdmUserFactoryModelBindEntity::getNodeType, bind.getNodeType());
        }
        if (bind.getNodeLevel() != null) {
            wrapper.eq(MdmUserFactoryModelBindEntity::getNodeLevel, bind.getNodeLevel());
        }
        if (bind.getPermissionType() != null) {
            wrapper.eq(MdmUserFactoryModelBindEntity::getPermissionType, bind.getPermissionType());
        }
        return R.ok(userFactoryModelBindService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmUserFactoryModelBindEntity::getStatus, Collectors.counting())));
    }

    /**
     * 根据id获取详情
     *
     * @return 详情
     */
    @Operation(summary = "根据id获取详情", description = "根据id获取详情")
    @GetMapping("/getById/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_userFactoryModelBind_view')")
    public R<MdmUserFactoryModelBindEntity> getById(@PathVariable Long id) {
        return R.ok(userFactoryModelBindService.getById(id));
    }


    /**
     * 获取当前用户的权限绑定列表
     *
     * @return 权限绑定列表
     */
    @Operation(summary = "当前用户权限绑定", description = "获取当前用户的权限绑定列表")
    @GetMapping("/currentBindings")
    public R<List<MdmUserFactoryModelBindEntity>> currentBindings() {
        MomUser currentUser = SecurityUtils.getUser();
        List<MdmUserFactoryModelBindEntity> bindings = userFactoryModelBindService.getByUserId(currentUser.getId());
        return R.ok(bindings);
    }

    /**
     * 根据节点类型获取当前用户的权限绑定
     *
     * @param nodeType 节点类型
     * @return 权限绑定列表
     */
    @Operation(summary = "按类型获取当前用户权限", description = "根据节点类型获取当前用户的权限绑定")
    @GetMapping("/currentBindings/{nodeType}")
    public R<List<MdmUserFactoryModelBindEntity>> currentBindingsByType(@PathVariable String nodeType) {
        MomUser currentUser = SecurityUtils.getUser();
        List<MdmUserFactoryModelBindEntity> bindings = userFactoryModelBindService.getByUserIdAndNodeType(currentUser.getId(), nodeType);
        return R.ok(bindings);
    }

    /**
     * 根据用户ID获取权限绑定列表
     *
     * @param userId 用户ID
     * @return 权限绑定列表
     */
    @Operation(summary = "获取用户权限绑定", description = "根据用户ID获取权限绑定列表")
    @GetMapping("/user/{userId}")
    @PreAuthorize("@pms.hasPermission('mdm_userFactoryModelBind_view')")
    public R<List<MdmUserFactoryModelBindEntity>> getUserBindings(@PathVariable Long userId) {
        List<MdmUserFactoryModelBindEntity> bindings = userFactoryModelBindService.getByUserId(userId);
        return R.ok(bindings);
    }

    /**
     * 批量绑定用户权限
     *
     * @param userFactoryModelBindDTO 用户工厂建模权限绑定DTO
     * @return 操作结果
     */
    @Operation(summary = "批量绑定权限", description = "批量绑定用户权限")
    @PostMapping("/batch")
    @PreAuthorize("@pms.hasPermission('mdm_userFactoryModelBind_add')")
    public R<Boolean> batchBind(@RequestBody UserFactoryModelBindDTO userFactoryModelBindDTO) {
        return R.ok(userFactoryModelBindService.batchBindUser(userFactoryModelBindDTO));
    }

    /**
     * 修改权限绑定
     *
     * @param bind 权限绑定
     * @return 操作结果
     */
    @Operation(summary = "修改权限绑定", description = "修改权限绑定")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_userFactoryModelBind_edit')")
    public R<Boolean> update(@RequestBody MdmUserFactoryModelBindEntity bind) {
        return R.ok(userFactoryModelBindService.updateById(bind));
    }

    /**
     * 解绑权限
     *
     * @param ids 主键ids
     * @return 操作结果
     */
    @Operation(summary = "解绑权限", description = "解绑权限")
    @DeleteMapping("/unbind")
    @PreAuthorize("@pms.hasPermission('mdm_userFactoryModelBind_del')")
    public R<Boolean> unbind(@RequestBody Long[] ids) {
        return R.ok(userFactoryModelBindService.unbindUser(ids));
    }

    /**
     * 检查用户权限
     *
     * @param userId         用户ID
     * @param factoryModelId 工厂建模节点ID
     * @param permissionType 权限类型
     * @return 是否有权限
     */
    @Operation(summary = "检查用户权限", description = "检查用户是否有指定权限")
    @GetMapping("/hasPermission/{userId}/{factoryModelId}/{permissionType}")
    public R<Boolean> hasPermission(@PathVariable Long userId,
                                    @PathVariable Long factoryModelId,
                                    @PathVariable String permissionType) {
        boolean hasPermission = userFactoryModelBindService.hasPermission(userId, factoryModelId, permissionType);
        return R.ok(hasPermission);
    }
}