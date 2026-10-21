package com.kitex.mom.plan.service;

import com.kitex.mom.oms.dto.OmsFulfillmentSnapshotCommand;
import com.kitex.mom.oms.entity.OmsFulfillmentSnapshotEntity;

import java.util.List;

public interface OmsFulfillmentSnapshotService {
    OmsFulfillmentSnapshotEntity capture(Long orderDetailId, OmsFulfillmentSnapshotCommand.Capture command);

    List<OmsFulfillmentSnapshotEntity> list(Long orderDetailId);

    OmsFulfillmentSnapshotEntity detail(Long snapshotId);
}
