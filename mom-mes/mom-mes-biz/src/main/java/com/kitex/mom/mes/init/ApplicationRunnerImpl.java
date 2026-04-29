package com.kitex.mom.mes.init;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component(value = "mesRunner")
public class ApplicationRunnerImpl implements ApplicationRunner {

    @Autowired
    private DelayedConfig delayedConfig;

    @Override
    public void run(ApplicationArguments args) {
        delayedConfig.connect();
    }
}