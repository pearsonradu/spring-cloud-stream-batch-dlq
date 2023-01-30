package springcloudkafkastreambatchdlq.springcloudkafkastreambatchdlq;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.config.ListenerContainerCustomizer;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@SpringBootApplication
@RequiredArgsConstructor
public class SpringCloudKafkaStreamBatchDlqApplication implements CommandLineRunner {

    private static final String DLQ_BINDING_NAME = "dlq-out-0";
    private final KafkaTemplate<Sample, Sample> kafkaTemplate;
    private final StreamBridge streamBridge;

    public static void main(String[] args) {
        SpringApplication.run(SpringCloudKafkaStreamBatchDlqApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        // Generate data
        for (var i = 0; ; i++) {
            kafkaTemplate.send("incoming", new Sample("hello world", i));
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

    @Bean
    public Function<List<Sample>, List<Sample>> toUpperCase() {
        return strings -> {
            System.out.println("\n\nReceived " + strings.size() + " strings");
            var upperCaseSamples = new ArrayList<Sample>();

            for (int i = 0; i < strings.size(); i++) {
                var sample = strings.get(i);
                var num = sample.value();

                if (num % 100 == 0) {
                    System.out.println("I don't know how to handle multiples of 100. Throwing exception for " + num + ".");
                    throw new RuntimeException();
                } else if (num % 5 == 0) {
                    System.out.println("I don't know how to handle multiples of 5. Manually sending " + num + " to the DLQ.");
                    streamBridge.send(DLQ_BINDING_NAME, sample);
                    continue;
                }

                upperCaseSamples.add(new Sample(sample.text().toUpperCase(), sample.value()));
            }

            System.out.println("Converted " + upperCaseSamples.size() + "/" + strings.size() + " items to upper case");
            return upperCaseSamples;
        };
    }

    @Bean
    ListenerContainerCustomizer<AbstractMessageListenerContainer<?, ?>> customizer(KafkaTemplate<?, ?> template,
                                                                                   BindingServiceProperties bindingServiceProperties) {
        return (container, destinationName, group) -> {

            var dlqDestination = bindingServiceProperties.getBindingProperties(DLQ_BINDING_NAME).getDestination();

            var recoverer = new DeadLetterPublishingRecoverer(template,
                    (consumerRecord, e) -> new TopicPartition(dlqDestination, consumerRecord.partition())
            );

            var commonErrorHandler = new DefaultErrorHandler(recoverer);

            container.setCommonErrorHandler(commonErrorHandler);
        };
    }

    record Sample(String text, int value) {
    }
}
