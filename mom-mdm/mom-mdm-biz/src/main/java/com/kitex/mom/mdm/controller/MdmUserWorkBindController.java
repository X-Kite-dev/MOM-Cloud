package com.kitex.mom.mdm.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmUserFactoryModelBindEntity;
import com.kitex.mom.mdm.service.MdmUserFactoryModelBindService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户-车间-工作中心绑定（已废弃，建议使用工厂建模权限关联）
 *
 * @author claude
 * @date 2025-07-30
 * @deprecated 请使用 MdmUserFactoryModelBindController 替代
 */
@Deprecated
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmUserWorkBind")
@Tag(description = "mdmUserWorkBind", name = "用户-车间-工作中心绑定（已废弃）")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmUserWorkBindController {

    private final MdmUserFactoryModelBindService factoryModelBindService;

    /**
     * 获取当前用户绑定信息（已废弃）
     *
     * @deprecated 请使用 /mdmUserFactoryModelBind/currentBindings 替代
     */
    @Operation(summary = "当前用户绑定信息", description = "当前用户绑定信息（已废弃，建议使用工厂建模权限接口）")
    @GetMapping("/currentBindings")
    @Deprecated
    public R<String> currentBindings() {
        return R.failed("此接口已废弃，请使用 /mdmUserFactoryModelBind/currentBindings 替代");
    }

    /**
     * 绑定用户权限（已废弃）
     *
     * @deprecated 请使用 /mdmUserFactoryModelBind/* 相关接口替代
     */
    @Operation(summary = "当前用户绑定信息", description = "当前用户绑定信息（已废弃，建议使用工厂建模权限接口）")
    @PostMapping("/bind")
    @Deprecated
    public R<String> bind(@RequestBody Object bindData) {
        return R.failed("此接口已废弃，请使用 /mdmUserFactoryModelBind/* 相关接口替代");
    }

    /**
     * 获取当前用户的工厂建模权限绑定（推荐使用）
     */
    @Operation(summary = "当前用户工厂建模权限", description = "获取当前用户的工厂建模权限绑定（推荐使用）")
    @GetMapping("/currentFactoryModelBindings")
    public R<List<MdmUserFactoryModelBindEntity>> currentFactoryModelBindings() {
        MomUser currentUser = SecurityUtils.getUser();
        List<MdmUserFactoryModelBindEntity> bindings = factoryModelBindService.getByUserId(currentUser.getId());
        return R.ok(bindings);
    }
}
