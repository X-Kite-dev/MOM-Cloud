package com.kitex.mom.tpm.dto;

import com.kitex.mom.tpm.entity.TpmRepairVerification;
import com.kitex.mom.tpm.entity.TpmRepairVerificationOperation;
import lombok.Data;

import java.util.List;

/**
 * MDM恢复设备资格前核对TPM已提交的异岗维修验证及当前开放阻断。
 */
@Data
public class TpmRepairRecoveryEvidence {
    private TpmRepairVerification version;
    private TpmRepairVerificationOperation verificationOperation;
    private List<String> blockers;
}
