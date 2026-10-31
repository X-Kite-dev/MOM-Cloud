package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kitex.mom.mes.dto.WipTimeoutNotifyResult;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesWipTimeoutHandleMapper;
import com.kitex.mom.mes.service.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

class MesWipTimeoutScanScopeTest {
    @Test
    void sameDispatchCodeInTwoFactoriesUsesEachOriginalScope() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (var type :
                List.of(
                        MesWipTimeoutConfigEntity.class,
                        MesTaskArrangeEntity.class,
                        MesWarehouseLiveStockEntity.class,
                        MesWipTimeoutHandleEntity.class))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "scan"),
                    type);
        var configs = mock(MesWipTimeoutConfigService.class);
        var stocks = mock(MesWarehouseLiveStockService.class);
        var tasks = mock(MesTaskArrangeService.class);
        var handles = mock(MesWipTimeoutHandleMapper.class);
        var notify = mock(MesWipTimeoutNotifyService.class);
        var operations = mock(MesWipTimeoutOperationService.class);
        var sourceRows = new ArrayList<MesWarehouseLiveStockEntity>();
        var taskRows = new ArrayList<MesTaskArrangeEntity>();
        var rules = new ArrayList<MesWipTimeoutConfigEntity>();
        for (int i = 1; i <= 2; i++) {
            var s = new MesWarehouseLiveStockEntity();
            s.setId((long) i);
            s.setTenantId(1L);
            s.setFactoryCode("F" + i);
            s.setBillNo("SAME");
            s.setMatDetailType("WIP");
            s.setMatCode("P1");
            s.setMatBarcode("BC" + i);
            s.setNum(BigDecimal.ONE);
            s.setInTime(LocalDateTime.now().minusMinutes(5));
            sourceRows.add(s);
            var a = new MesTaskArrangeEntity();
            a.setCode("SAME");
            a.setTenantId(1L);
            a.setFactoryCode("F" + i);
            a.setRouteCode("R" + i);
            a.setRouteWorkCenter("L" + i);
            taskRows.add(a);
            var r = new MesWipTimeoutConfigEntity();
            r.setId((long) i);
            r.setTenantId(1L);
            r.setFactoryCode("F" + i);
            r.setCode("C" + i);
            r.setEnabled(true);
            r.setTimeoutSec(60);
            r.setLineCode("L" + i);
            r.setRouteCode("R" + i);
            rules.add(r);
        }
        when(configs.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(rules);
        when(stocks.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(sourceRows);
        when(tasks.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenAnswer(
                        call -> {
                            var wrapper =
                                    (com.baomidou.mybatisplus.core.conditions.query
                                                            .LambdaQueryWrapper<
                                                    ?>)
                                            call.getArguments()[0];
                            wrapper.getSqlSegment();
                            var values = wrapper.getParamNameValuePairs().values();
                            return values.contains("F1")
                                    ? List.of(taskRows.get(0))
                                    : values.contains("F2") ? List.of(taskRows.get(1)) : taskRows;
                        });
        when(handles.selectList(any())).thenReturn(List.of());
        when(operations.save(any())).thenReturn(true);
        when(notify.notifyLevel(any(), any(), anyInt()))
                .thenReturn(
                        new WipTimeoutNotifyResult().setResult("SUCCESS").setChannels("SYSTEM"));
        var created = new ArrayList<MesWipTimeoutHandleEntity>();
        when(handles.insert(any(MesWipTimeoutHandleEntity.class)))
                .thenAnswer(
                        call -> {
                            var row = (MesWipTimeoutHandleEntity) call.getArguments()[0];
                            row.setId(100L + created.size());
                            created.add(row);
                            return 1;
                        });
        new MesWipTimeoutHandleScanServiceImpl(configs, stocks, tasks, handles, notify, operations)
                .scanAndEscalate();
        assertEquals(2, created.size());
        for (int i = 0; i < 2; i++) {
            assertEquals("F" + (i + 1), created.get(i).getFactoryCode());
            assertEquals("C" + (i + 1), created.get(i).getConfigCode());
            assertEquals("L" + (i + 1), created.get(i).getLineCode());
            assertEquals("R" + (i + 1), created.get(i).getRouteCode());
        }
    }
}
