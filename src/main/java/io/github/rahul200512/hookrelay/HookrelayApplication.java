package io.github.rahul200512.hookrelay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import io.github.rahul200512.hookrelay.config.NativeHints;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@ImportRuntimeHints(NativeHints.class)
public class HookrelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(HookrelayApplication.class, args);
    }
}
