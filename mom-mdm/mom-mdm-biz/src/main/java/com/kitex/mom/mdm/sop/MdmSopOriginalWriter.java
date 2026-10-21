package com.kitex.mom.mdm.sop;

import com.kitex.mom.mdm.entity.MdmSopOriginalEntity;
import com.kitex.mom.mdm.mapper.MdmSopOriginalMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.kitex.mom.mdm.sop.MdmSopSupport.require;

/** 两个编制入口共用不可变原件写入；只清理由当前事务明确回滚的新对象。 */
@Component
@RequiredArgsConstructor
public class MdmSopOriginalWriter {
    private final MdmSopOriginalStorage storage;
    private final MdmSopOriginalMapper originals;
    private final MdmSopSupport support;

    public MdmSopOriginalEntity save(MdmSopOriginalStorage.Upload upload) {
        require(TransactionSynchronizationManager.isActualTransactionActive(), "原件归档必须处于SOP业务事务");
        var original = new MdmSopOriginalEntity();
        original.setTenantId(support.tenant());
        original.setFactoryCode(support.access(true));
        original.setFileName(upload.name());
        original.setMediaType(upload.mediaType());
        original.setByteSize((long) upload.bytes().length);
        original.setSha256(upload.hash());
        original.setUploadedBy(support.actor());
        original.setStorageKey(storage.store(upload));
        String key = original.getStorageKey();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) storage.discardUncommitted(key);
            }
        });
        require(originals.insert(original) == 1, "SOP原件元数据保存失败");
        return original;
    }
}
