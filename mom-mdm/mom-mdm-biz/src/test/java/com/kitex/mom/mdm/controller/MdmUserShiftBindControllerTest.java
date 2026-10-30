package com.kitex.mom.mdm.controller;

import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.entity.MdmUserShiftBindEntity;
import com.kitex.mom.mdm.service.MdmClassesService;
import com.kitex.mom.mdm.service.MdmUserShiftBindService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class MdmUserShiftBindControllerTest {
    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void existingBindingReturnsShiftCodeInData() {
        var service = mock(MdmUserShiftBindService.class);
        var classes = mock(MdmClassesService.class);
        var binding = new MdmUserShiftBindEntity();
        binding.setShiftCode("261024A");
        when(service.getById(21L)).thenReturn(binding);
        login();
        var result = new MdmUserShiftBindController(service, classes).currentBindings();
        assertEquals(0, result.getCode());
        assertEquals("261024A", result.getData());
        verify(classes, never()).getClassesRule(any(LocalDateTime.class));
    }

    @Test
    void firstBindingReturnsTheSameActualShiftInData() {
        var service = mock(MdmUserShiftBindService.class);
        var classes = mock(MdmClassesService.class);
        when(classes.getClassesRule(any(LocalDateTime.class))).thenReturn("261024A");
        login();
        var result = new MdmUserShiftBindController(service, classes).currentBindings();
        assertEquals("261024A", result.getData());
        verify(service).saveOrUpdate(argThat(row -> row.getUserId().equals(21L) && row.getShiftCode().equals("261024A")));
    }

    private void login() {
        var user = mock(MomUser.class);
        when(user.getId()).thenReturn(21L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
    }
}
