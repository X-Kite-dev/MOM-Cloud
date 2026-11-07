package com.kitex.mom.mes.dto;

/** 创建时冻结的已生效目标；不替代原发出证明，不表示实物/产能预留。 */
public final class MesWipRedirectedDeliveryDTO {
    private MesWipRedirectedDeliveryDTO() {}

    public static final String SCHEMA = "MES_WIP_REDIRECTED_DELIVERY@1";

    public record Target(
            String schemaVersion,
            MesWipRedirectActivationDTO.Effect activation,
            String activationHash,
            MesWipRedirectTargetDTO.Check check) {}
}
