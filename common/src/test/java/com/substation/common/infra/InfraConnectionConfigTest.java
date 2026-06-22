package com.substation.common.infra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InfraConnectionConfigTest {

    @Test
    void localhostDefaultsWhenNoArgs() {
        var config = InfraConnectionConfig.fromArgs(new String[0]);
        assertEquals("localhost", config.redisHost());
        assertEquals(6379, config.redisPort());
        assertEquals("localhost", config.mqHost());
        assertEquals(5672, config.mqPort());
    }

    @Test
    void parsesHostFlagsAlongsideCarId() {
        var config = InfraConnectionConfig.fromArgs(new String[]{
                "Car001",
                "--redis-host", "100.94.124.1",
                "--mq-host", "100.94.124.1",
                "--dynamic"
        });
        assertEquals("100.94.124.1", config.redisHost());
        assertEquals("100.94.124.1", config.mqHost());
    }

    @Test
    void missingHostValueThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> InfraConnectionConfig.fromArgs(new String[]{"--redis-host"}));
    }
}
