package com.kitex.mom.wms.service.noticeAcceptance;

import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.wms.api.feign.RemoteProducePickingNoticeService;
import com.kitex.mom.wms.controller.WmsProductionPickingOutNoticeController;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.mapper.WmsOutNoticeMapper;
import com.kitex.mom.wms.service.outNotice.DefaultWmsOutNoticeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WmsProductionNoticeApiContractTest {
    @Test
    void legacyFeignSourceActionsDeclareInternalCallWithoutChangingBooleanContract() throws Exception {
        assertTrue(
                RemoteProducePickingNoticeService.class.getMethod("add", WmsOutNoticeEntity.class).isAnnotationPresent(
                        InternalCall.class));
        assertTrue(RemoteProducePickingNoticeService.class.getMethod("voidObjByDocumentId", Long.class,
                String.class).isAnnotationPresent(InternalCall.class));
        for (String name : new String[]{"accept", "acceptBatch", "receiptBySource", "receiptByRequest", "cancelSource"})
            assertTrue(Arrays.stream(RemoteProducePickingNoticeService.class.getMethods()).anyMatch(
                    m -> m.getName().equals(name) && m.isAnnotationPresent(InternalCall.class)));
    }

    @Test
    void existingFeignDetailsUriAndExistingPcUriBothResolve() throws Exception {
        var mapping = WmsProductionPickingOutNoticeController.class.getMethod("details", Long.class).getAnnotation(
                GetMapping.class);
        assertTrue(Arrays.asList(mapping.value()).contains("/details"));
        assertTrue(Arrays.asList(mapping.value()).contains("/detailsById"));
    }

    @Test
    void genericNoticeHandlerCannotBypassMesSourceAcceptanceOrSourceCancellation() {
        var service = mock(WmsProductionNoticeAcceptanceService.class);
        when(service.acceptLegacy(any())).thenReturn(true);
        when(service.cancelLegacy(88L, "mes_picking")).thenReturn(true);
        ObjectProvider<WmsProductionNoticeAcceptanceService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(service);
        var handler = new DefaultWmsOutNoticeService();
        var notices = mock(WmsOutNoticeMapper.class);
        ReflectionTestUtils.setField(handler, "wmsOutNoticeMapper", notices);
        ReflectionTestUtils.setField(handler, "productionAcceptance", provider);
        var notice = WmsProductionNoticeCanonicalTest.command().getNotice();
        assertTrue(handler.saveDeep(notice));
        assertTrue(handler.voidObjByDocumentId(88L, "mes_picking"));
        verify(service).acceptLegacy(notice);
        verify(service).cancelLegacy(88L, "mes_picking");
        verifyZeroInteractions(notices);
    }
}
