package com.kitex.mom.tpm.delayedConfig;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component(value = "tpmRunner")
public class ApplicationRunnerImpl implements ApplicationRunner {

    @Autowired
    private TpmDelayedConfig delayedConfig;

    @Override
    public void run(ApplicationArguments args) {
        delayedConfig.connect();
    }
}
