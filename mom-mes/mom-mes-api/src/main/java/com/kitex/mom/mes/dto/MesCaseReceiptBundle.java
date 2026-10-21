package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesCaseDispositionReceiptEntity;
import com.kitex.mom.mes.entity.MesInventoryQualityCaseEntity;
import lombok.Data;

import java.util.List;

@Data
public class MesCaseReceiptBundle {
    private MesInventoryQualityCaseEntity source;
    private List<MesCaseDispositionReceiptEntity> receipts;
}
