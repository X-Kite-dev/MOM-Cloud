package com.kitex.mom.mdm.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.UserFactoryModelBindDTO;
import com.kitex.mom.mdm.entity.MdmUserFactoryModelBindEntity;
import com.kitex.mom.mdm.service.impl.MdmUserFactoryModelBindServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 批量绑定必须保留操作者选择的权限；非法权限不得先删除原授权。
 */
class MdmUserFactoryModelBindPermissionTest {

    @ParameterizedTest
    @ValueSource(strings = {"read", "write", "admin"})
    void savesExactlyTheRequestedPermission(String permission) {
        var service = service();
        doAnswer(invocation -> {
            Collection<MdmUserFactoryModelBindEntity> rows = invocation.getArgument(0);
            assertEquals(1, rows.size());
            var row = rows.iterator().next();
            assertEquals(10L, row.getFactoryModelId());
            assertEquals("ZD", row.getFactoryModelCode());
            assertEquals(21L, row.getUserId());
            assertEquals("Glue", row.getUserName());
            assertEquals(permission, row.getPermissionType());
            assertEquals(Boolean.TRUE, row.getStatus());
            return true;
        }).when(service).saveBatch(anyCollection(), eq(1000));
        assertTrue(service.batchBindUser(command(permission)));
        verify(service).remove(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"WRITE", "owner", " "})
    void rejectsInvalidPermissionBeforeRemovingExistingGrants(String permission) {
        var service = service();
        assertThrows(CheckedException.class, () -> service.batchBindUser(command(permission)));
        verify(service, never()).remove(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(service, never()).saveBatch(anyCollection(), anyInt());
    }

    @Test
    void validatesEveryNodeBeforeReplacingAnyBinding() {
        var service = service();
        var command = command("write");
        command.setFactoryModels(List.of(command.getFactoryModels().get(0), command("unknown").getFactoryModels().get(0)));
        assertThrows(CheckedException.class, () -> service.batchBindUser(command));
        verify(service, never()).remove(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(service, never()).saveBatch(anyCollection(), anyInt());
    }

    private MdmUserFactoryModelBindServiceImpl service() {
        var service = spy(new MdmUserFactoryModelBindServiceImpl(mock(MdmFactoryModelService.class),
                mock(com.kitex.admin.api.feign.RemoteUserService.class)));
        doReturn(true).when(service).remove(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        doReturn(true).when(service).saveBatch(anyCollection(), anyInt());
        return service;
    }

    private UserFactoryModelBindDTO command(String permission) {
        var user = new UserFactoryModelBindDTO.UserDTO();
        user.setUserId(21L);
        user.setUsername("Glue");
        user.setName("Glue");
        var node = new UserFactoryModelBindDTO.FactoryModelDTO();
        node.setId(10L);
        node.setCode("ZD");
        node.setName("装备制造工厂");
        node.setType("FACTORY");
        node.setLevel(2);
        node.setPermissionType(permission);
        var command = new UserFactoryModelBindDTO();
        command.setUsers(List.of(user));
        command.setFactoryModels(List.of(node));
        return command;
    }
}
