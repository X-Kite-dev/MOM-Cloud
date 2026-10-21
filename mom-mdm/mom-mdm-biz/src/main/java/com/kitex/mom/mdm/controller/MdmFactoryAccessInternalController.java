package com.kitex.mom.mdm.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.entity.MdmUserFactoryModelBindEntity;
import com.kitex.mom.mdm.enums.FactoryModelPermissionEnum;
import com.kitex.mom.mdm.enums.FactoryModelTypeEnum;
import com.kitex.mom.mdm.mapper.MdmFactoryModelMapper;
import com.kitex.mom.mdm.mapper.MdmUserFactoryModelBindMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * 只接受内部服务调用；明确绑定到唯一启用工厂节点，未配置时故障关闭。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmFactoryAccess/internal")
public class MdmFactoryAccessInternalController {
    private final MdmFactoryModelMapper factoryMapper;
    private final MdmUserFactoryModelBindMapper bindMapper;

    @Inner
    @PostMapping("/check")
    public R<Boolean> check(@RequestBody MdmFactoryAccessCheck request) {
        if (request == null || request.getUserId() == null
                || StrUtil.isBlank(request.getFactoryCode())
                || StrUtil.isBlank(request.getRequiredPermission())) return R.ok(false);
        FactoryModelPermissionEnum required = FactoryModelPermissionEnum.getByCode(
                request.getRequiredPermission().trim().toLowerCase(Locale.ROOT));
        if (required == null) return R.ok(false);
        List<MdmFactoryModelEntity> factories = factoryMapper.selectList(
                new LambdaQueryWrapper<MdmFactoryModelEntity>()
                        .eq(MdmFactoryModelEntity::getCode, request.getFactoryCode().trim())
                        .eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.FACTORY.getCode())
                        .eq(MdmFactoryModelEntity::getStatus, true));
        if (factories.size() != 1) return R.ok(false);
        List<MdmUserFactoryModelBindEntity> bindings = bindMapper.selectList(
                new LambdaQueryWrapper<MdmUserFactoryModelBindEntity>()
                        .eq(MdmUserFactoryModelBindEntity::getUserId, request.getUserId())
                        .eq(MdmUserFactoryModelBindEntity::getFactoryModelId, factories.get(0).getId())
                        .eq(MdmUserFactoryModelBindEntity::getStatus, true));
        return R.ok(bindings.stream().anyMatch(binding -> {
            FactoryModelPermissionEnum granted = FactoryModelPermissionEnum.getByCode(
                    StrUtil.blankToDefault(binding.getPermissionType(), "").toLowerCase(Locale.ROOT));
            return granted != null && granted.hasPermission(required);
        }));
    }
}
