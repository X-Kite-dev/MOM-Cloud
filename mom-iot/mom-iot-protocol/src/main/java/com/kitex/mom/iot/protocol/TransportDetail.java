package com.kitex.mom.iot.protocol;

import com.kitex.mom.iot.core.protocol.ProtocolSupport;
import com.kitex.mom.iot.core.protocol.TransportType;
import com.kitex.mom.iot.core.route.Route;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;


@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TransportDetail {
    @Schema(description = "ID")
    private String id;

    @Schema(description = "名称")
    private String name;


    @Schema(description = "路由信息")
    private List<Route> routes;

    @Schema(description = "文档信息")
    private String document;

    @Schema(description = "默认物模型")
    private String metadata;

    public static TransportDetail of(ProtocolSupport support, TransportType transport) {
        TransportDetail transportDetail = new TransportDetail();

        List<Route> routes1 = support.getRoutes(transport);
        transportDetail.setRoutes(routes1);
        transportDetail.setId(support.getId());
        transportDetail.setName(support.getName());

        String document1 = support.getDocument(transport);
        transportDetail.setDocument(document1);

        return transportDetail;

    }
}