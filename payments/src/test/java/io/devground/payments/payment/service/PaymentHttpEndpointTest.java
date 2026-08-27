package io.devground.payments.payment.service;

import io.devground.core.commands.deposit.ChargeDeposit;
import io.devground.core.commands.deposit.CreateDeposit;
import io.devground.core.model.vo.DepositHistoryType;
import io.devground.payments.support.PaymentIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * 진짜 HTTP 호출로 결제 응답 시간 측정.
 *
 * 임베디드 서버(실제 포트) + MySQL + Kafka 컨테이너 위에서
 * POST /api/payments/process 를 RestTemplate 으로 호출하여
 * 전체 HTTP 스택(Spring MVC → Controller → Service → DB + Kafka)을 통과하는 시간을 측정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentHttpEndpointTest extends PaymentIntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(PaymentHttpEndpointTest.class);
    private static final RestTemplate rest = new RestTemplate();

    @LocalServerPort
    private int port;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    private static final int ITERATIONS = 10;
    private static final long AMOUNT = 5000L;
    private static final String USER = "http-perf-user";

    private boolean userSetupDone = false;

    @BeforeEach
    void setUpUser() throws Exception {
        if (!userSetupDone) {
            kafkaTemplate.send("deposits-join-commands", USER, new CreateDeposit(USER));
            Thread.sleep(2000);
            userSetupDone = true;
        }
    }

    @Test
    @DisplayName("HTTP POST /api/payments/process 응답 시간 (MySQL+Kafka 실제 컨테이너)")
    void measureHttpPaymentResponseTime() throws Exception {
        List<Long> times = new ArrayList<>();

        // Warmup
        topUp();
        var headers = headers();
        rest.postForEntity(url(), new HttpEntity<>(body("warmup"), headers), String.class);
        Thread.sleep(500);

        log.info("--- HTTP 결제 응답 시간 측정 ({}회) ---", ITERATIONS);

        for (int i = 0; i < ITERATIONS; i++) {
            topUp();
            String orderCode = "http-perf-" + i;

            long start = System.nanoTime();
            var resp = rest.postForEntity(url(),
                    new HttpEntity<>(body(orderCode), headers), String.class);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            times.add(elapsed);
            log.info("  #{}  {}ms  status={}  body={}",
                    i, elapsed, resp.getStatusCode().value(),
                    resp.getBody() != null ? resp.getBody().substring(0, Math.min(80, resp.getBody().length())) : "null");
        }

        double avg = times.stream().mapToLong(Long::longValue).average().orElse(0);
        long min = times.stream().mapToLong(Long::longValue).min().orElse(0);
        long max = times.stream().mapToLong(Long::longValue).max().orElse(0);

        log.info("==============================================");
        log.info("  HTTP 결제 응답 시간 ({}회)", ITERATIONS);
        log.info("  avg={}ms  min={}ms  max={}ms", String.format("%.1f", avg), min, max);
        log.info("  (전체 HTTP 스택: MVC→Controller→Service→DB+Kafka)");
        log.info("==============================================");

        assertThat(avg).isGreaterThan(0);
    }

    private void topUp() throws Exception {
        kafkaTemplate.send("deposits-commands", USER,
                new ChargeDeposit(USER, "topup-" + System.nanoTime(), AMOUNT * 2, DepositHistoryType.CHARGE_TRANSFER));
        Thread.sleep(300);
    }

    private String url() {
        return "http://localhost:" + port + "/api/payments/process";
    }

    private HttpHeaders headers() {
        var h = new HttpHeaders();
        h.set("X-CODE", USER);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String body(String orderCode) {
        return String.format(
            "{\"orderCode\":\"%s\",\"useDeposit\":true,\"amount\":%d,\"productCodes\":[\"p1\"]}",
            orderCode, AMOUNT);
    }
}
