package org.mifos.connector.notification.sms.delivery;


import io.camunda.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mifos.connector.notification.camel.config.CamelProperties.*;
import static org.mifos.connector.notification.zeebe.ZeebeVariables.CALLBACK_MESSAGE;
import static org.mifos.connector.notification.zeebe.ZeebeVariables.MESSAGE_DELIVERY_STATUS;

@Component
public class DeliveryCallbackRoute extends RouteBuilder{

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${zeebe.client.ttl}")
    private int timeToLive;

    @Autowired
    private ZeebeClient zeebeClient;

    @Value("${hostconfig.protocol}")
    private String protocol;

    @Value("${hostconfig.host}")
    private String address;

    @Value("${hostconfig.port}")
    private int port;

    @Value("${fineractconfig.tenantid}")
    private String tenantId;

    @Value("${fineractconfig.tenantidvalue}")
    private String tenantIdValue;

    @Value("${fineractconfig.tenantappkey}")
    private String tenantAppKey;

    @Value("${fineractconfig.tenantappvalue}")
    private String tenantAppKeyValue;



    @Override
    public void configure() throws Exception {
            from("direct:delivery-notifications")
                    .id("delivery-notifications")
                    .log(LoggingLevel.INFO, "Calling delivery status API")
                    .setHeader(tenantId, constant(tenantIdValue))
                    .setHeader(tenantAppKey, constant(tenantAppKeyValue))
                    .setBody(exchange -> {
                        JSONArray request = new JSONArray();
                        Long internalId = Long.parseLong(exchange.getProperty(INTERNAL_ID).toString());
                        request.put(internalId);
                        return  request.toString();
                    })
                    .log("${body}")
                    .setHeader(Exchange.HTTP_METHOD, simple("POST"))
                    .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                    .to(String.format("%s://%s:%d/sms/report/?bridgeEndpoint=true", protocol, address, port))
                    .log(LoggingLevel.INFO, "Delivery Status Endpoint Received")
                    .process(exchange -> {
                        String id = exchange.getProperty(CORRELATION_ID, String.class);
                        Map<String, Object> variables = new HashMap<>();
                        String callback = exchange.getIn().getBody(String.class);
                        if(callback.contains("200")){
                            logger.info("Still Pending");
                            exchange.setProperty(MESSAGE_DELIVERY_STATUS,false);
                        }
                       else{
                           logger.info("Passed");
                            exchange.setProperty(MESSAGE_DELIVERY_STATUS,true);

                        }
                        Map<String, Object> newVariables = new HashMap<>();
                        newVariables.put(MESSAGE_DELIVERY_STATUS, exchange.getProperty(MESSAGE_DELIVERY_STATUS));
                        zeebeClient.newSetVariablesCommand(Long.parseLong(exchange.getProperty(INTERNAL_ID).toString()))
                                .variables(newVariables)
                                .send()
                                .join();
                        logger.info("Publishing created messages to variables: " + newVariables);
                        zeebeClient.newPublishMessageCommand()
                                .messageName(CALLBACK_MESSAGE)
                                .correlationKey(id)
                                .timeToLive(Duration.ofMillis(timeToLive))
                                .variables(newVariables)
                                .send()
                                .join();
                     })

            ;


        }
    }




