package org.mifos.connector.notification.zeebe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.ZeebeClient;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.mifos.connector.common.channel.dto.TransactionChannelCollectionRequestDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.notification.camel.config.CamelProperties.*;
import static org.mifos.connector.notification.zeebe.ZeebeVariables.*;
import static org.mifos.connector.notification.zeebe.ZeebeVariables.TRANSACTION_ID;

@Component
public class ZeebeWorkers {
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    private CamelContext camelContext;

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${zeebe.client.evenly-allocated-max-jobs}")
    private int workerMaxJobs;

    @PostConstruct
    public void setupWorkers() {
        zeebeClient.newWorker()
                .jobType("transaction-failure")
                .handler((client, job) -> {
                    logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                    Map<String, Object> variables = job.getVariablesAsMap();
                    String internalId = String.valueOf(job.getProcessInstanceKey());
                    String transactionId = (String) variables.get(TRANSACTION_ID);
                    Exchange exchange = new DefaultExchange(camelContext);
                    exchange.setProperty(CORRELATION_ID, transactionId);
                    exchange.setProperty(INTERNAL_ID,internalId);
                   producerTemplate.send("direct:create-messages", exchange);
                    client.newCompleteCommand(job.getKey())
                            .send()
                            .join()
                    ;
                })
                .name("transaction-failure")
                .maxJobsActive(workerMaxJobs)
                .open();


//        zeebeClient.newWorker()
//                .jobType("transaction-success")
//                .handler((client, job) -> {
//                    logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
//                    client.newCompleteCommand(job.getKey())
//                            .send()
//                    ;
//                })
//                .name("transaction-success")
//                .maxJobsActive(workerMaxJobs)
//                .open();

        zeebeClient.newWorker()
                .jobType("notification-service")
                .handler((client, job) -> {
                    logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());

                    Map<String, Object> variables = job.getVariablesAsMap();
                    TransactionChannelCollectionRequestDTO channelRequest = objectMapper.readValue(
                            (String) variables.get("channelRequest"), TransactionChannelCollectionRequestDTO .class);

                    Exchange exchange = new DefaultExchange(camelContext);
                    exchange.setProperty(MOBILE_NUMBER,variables.get(PHONE_NUMBER));
                    exchange.setProperty(CORRELATION_ID, variables.get(TRANSACTION_ID));
                    exchange.setProperty(INTERNAL_ID,variables.get(MESSAGE_INTERNAL_ID));
                    exchange.setProperty(DELIVERY_MESSAGE,variables.get(MESSAGE_TO_SEND));

                    producerTemplate.send("direct:send-notifications", exchange);


                    client.newCompleteCommand(job.getKey())
                            .send()
                            .join()
                    ;
                })
                .name("notification-service")
                .maxJobsActive(workerMaxJobs)
                .open();

        zeebeClient.newWorker()
                .jobType("get-notification-status")
                .handler((client, job) -> {
                    logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                    Map<String, Object> variables = job.getVariablesAsMap();
                    String transactionId = (String) variables.get(TRANSACTION_ID);
                    Exchange exchange = new DefaultExchange(camelContext);
                    exchange.setProperty(INTERNAL_ID,variables.get(MESSAGE_INTERNAL_ID));
                    exchange.setProperty(CORRELATION_ID, variables.get(TRANSACTION_ID));
                    producerTemplate.send("direct:delivery-notifications", exchange);
                   client.newCompleteCommand(job.getKey())
                           .send()
                           .join()
                   ;
                })
                .name("get-notification-status")
                .maxJobsActive(workerMaxJobs)
                .open();

    }
}