package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmSparePartsLocationEntity;

import java.util.ArrayList;

public interface TpmSparePartsLocationService extends IService<TpmSparePartsLocationEntity> {

    Page<TpmSparePartsLocationEntity> pageList(Page toMpPage, TpmSparePartsLocationEntity tpmSparePartsLocation);

    TpmSparePartsLocationEntity getLocationById(Long id);

    Boolean removeLocationById(ArrayList<Long> toList);
}