package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Set;

@Data
@Accessors(chain = true)
public class WmsMaterialReservationLedgerQueryDTO {
    private Set<String> reservationNos;
}
