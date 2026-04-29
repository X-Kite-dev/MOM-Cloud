package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.dto.TpmSparePartsInOrOutBillDto;
import com.kitex.mom.tpm.entity.TpmSparePartsInOrOutBillEntity;

import java.util.List;

public interface TpmSparePartsInOrOutBillService extends IService<TpmSparePartsInOrOutBillEntity> {

    Boolean saveBill(TpmSparePartsInOrOutBillDto tpmSparePartsInOrOutBillDto);

    Boolean handle(List<Long> ids, String type);
}