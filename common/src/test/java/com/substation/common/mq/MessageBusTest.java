package com.substation.common.mq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MessageBusTest {

    private MessageBus bus;

    @BeforeEach
    void setUp() throws Exception {
        bus = new MessageBus("localhost", 5672, "guest", "guest");
        bus.connect();
    }

    @AfterEach
    void tearDown() {
        if (bus != null && bus.isConnected()) {
            bus.close();
        }
    }

    @Test
    void connect() {
        assertTrue(bus.isConnected());
    }

    @Test
    void publishAndSubscribe() throws Exception {
        bus.declareControllerQueue();

        AtomicReference<String> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        bus.subscribe(QueueNames.CONTROLLER_CMD, msg -> {
            received.set(msg);
            latch.countDown();
        });

        String testMsg = MessageBuilder.build("TEST", 0);
        bus.publish(QueueNames.CONTROLLER_CMD, testMsg);

        boolean completed = latch.await(3, TimeUnit.SECONDS);
        assertTrue(completed, "消息应在3秒内收到");
        assertNotNull(received.get());
    }

    @Test
    void fanout() throws Exception {
        bus.declareFanoutExchange();
        String queue1 = bus.bindFanoutQueue();
        String queue2 = bus.bindFanoutQueue();

        AtomicReference<String> received1 = new AtomicReference<>();
        AtomicReference<String> received2 = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(2);

        bus.subscribe(queue1, msg -> { received1.set(msg); latch.countDown(); });
        bus.subscribe(queue2, msg -> { received2.set(msg); latch.countDown(); });

        bus.publishFanout(QueueNames.UPDATE_VIEW_EXCHANGE, MessageBuilder.build("REFRESH_ALL", 1));

        boolean completed = latch.await(3, TimeUnit.SECONDS);
        assertTrue(completed, "两个订阅者都应在3秒内收到消息");
        assertNotNull(received1.get());
        assertNotNull(received2.get());
    }
}
