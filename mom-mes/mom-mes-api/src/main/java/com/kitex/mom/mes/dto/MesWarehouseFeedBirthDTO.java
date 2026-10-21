package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesWarehouseFeedBirthEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedBirthLineEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 投料真实来源只读证据；不是稳定客户端原号或再次执行许可。
 */
@Schema(description = "投料永久出生完整来源")
public record MesWarehouseFeedBirthDTO(
        @Schema(description = "RECORDED或HISTORICAL_UNPROVEN，不把旧批次猜成已证明来源") String status,
        @Schema(description = "首次真实投料原账；历史无证据时为空") MesWarehouseFeedBirthEntity birth,
        @Schema(description = "全部逐库存OUT及OLD/NEW原证据；未截断分页") List<MesWarehouseFeedBirthLineEntity> sources) {

    public MesWarehouseFeedBirthDTO {
        sources = List.copyOf(sources);
    }
}
