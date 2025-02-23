package com.netflix.conductor.contribs.queue.nats.config;

import com.netflix.conductor.core.config.ConductorProperties;
import com.netflix.conductor.core.events.EventQueueProvider;
import com.netflix.conductor.core.events.queue.ObservableQueue;
import com.netflix.conductor.model.TaskModel;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import rx.Scheduler;

import java.util.EnumMap;
import java.util.Map;

/**
 * @author andrey.stelmashenko@gmail.com
 */
@Configuration
@EnableConfigurationProperties(JetStreamProperties.class)
@ConditionalOnProperty(name = "conductor.event-queues.jsm.enabled", havingValue = "true")
public class JetStreamConfiguration {
    @Bean
    public EventQueueProvider jsmEventQueueProvider(JetStreamProperties properties, Scheduler scheduler) {
        return new JetStreamEventQueueProvider(properties, scheduler);
    }

    @ConditionalOnProperty(name = "conductor.default-event-queue.type", havingValue = "jsm")
    @Bean
    public Map<TaskModel.Status, ObservableQueue> getQueues(
            JetStreamEventQueueProvider provider,
            ConductorProperties conductorProperties,
            JetStreamProperties properties) {
        String stack = "";
        if (conductorProperties.getStack() != null && conductorProperties.getStack().length() > 0) {
            stack = conductorProperties.getStack() + "_";
        }
        TaskModel.Status[] statuses = new TaskModel.Status[]{TaskModel.Status.COMPLETED, TaskModel.Status.FAILED};
        Map<TaskModel.Status, ObservableQueue> queues = new EnumMap<>(TaskModel.Status.class);
        for (TaskModel.Status status : statuses) {
            String queuePrefix = StringUtils.isBlank(properties.getListenerQueuePrefix())
                    ? conductorProperties.getAppId() + "_jsm_notify_" + stack
                    : properties.getListenerQueuePrefix();

            String queueName = queuePrefix + status.name();

            ObservableQueue queue = provider.getQueue(queueName);
            queues.put(status, queue);
        }

        return queues;
    }
}
