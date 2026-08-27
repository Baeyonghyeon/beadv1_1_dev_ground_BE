// k6 Kafka 기반 예치금 성능 테스트 — DAU 10만
//
// 실행:
//   docker run --rm -i --network docker_default \
//     -v $(pwd):/scripts \
//     k6-kafka:latest run /scripts/mixed-workload-kafka.js
//
// Spring Kafka JsonDeserializer 는 __TypeId__ 헤더를 보고 역직렬화할 클래스를 결정한다.
// 토픽: deposits-commands (deposits.command.topic.name)

import { Writer } from 'k6/x/kafka';
import { sleep } from 'k6';

const BROKER = __ENV.KAFKA_BROKER || 'kafka:9090';
const TOPIC = 'deposits-commands';
const USER_POOL = parseInt(__ENV.USER_POOL || '10000');

// Spring JsonDeserializer 타입 헤더
const TYPE_CHARGE = 'io.devground.core.commands.deposit.ChargeDeposit';
const TYPE_WITHDRAW = 'io.devground.core.commands.deposit.WithdrawDeposit';
const TYPE_REFUND = 'io.devground.core.commands.deposit.RefundDeposit';

const writer = new Writer({
    brokers: [BROKER],
    topic: TOPIC,
});

function randomUserCode() {
    return `USER-${Math.floor(Math.random() * USER_POOL)}`;
}

// 설계기준 시나리오: 충전 3 + 출금 1 + 환불 2 + 결제차감 3 = 총 ~9 TPS
export const options = {
    scenarios: {
        charge: {
            executor: 'constant-arrival-rate',
            rate: 3,
            timeUnit: '1s',
            duration: '1m', // 스모크 테스트: 1분 (본 테스트: 30m)
            preAllocatedVUs: 5,
            exec: 'charge',
        },
        withdraw: {
            executor: 'constant-arrival-rate',
            rate: 1,
            timeUnit: '1s',
            duration: '1m',
            preAllocatedVUs: 3,
            exec: 'withdraw',
        },
        refund: {
            executor: 'constant-arrival-rate',
            rate: 2,
            timeUnit: '1s',
            duration: '1m',
            preAllocatedVUs: 3,
            exec: 'refund',
        },
        payment: {
            executor: 'constant-arrival-rate',
            rate: 3,
            timeUnit: '1s',
            duration: '1m',
            preAllocatedVUs: 5,
            exec: 'payment',
        },
    },
};

export function charge() {
    const userCode = randomUserCode();
    const payload = JSON.stringify({
        userCode: userCode,
        paymentKey: 'k6-test',
        amount: 10000,
        type: 'CHARGE_TRANSFER',
    });

    writer.produce({
        messages: [{
            value: payload,
            headers: { '__TypeId__': TYPE_CHARGE },
        }],
    });
}

export function withdraw() {
    const userCode = randomUserCode();
    const payload = JSON.stringify({
        userCode: userCode,
        amount: 5000,
        type: 'PAYMENT_INTERNAL',
        orderCode: `k6-order-${Date.now()}`,
        productCodes: ['prod-1'],
    });

    writer.produce({
        messages: [{
            value: payload,
            headers: { '__TypeId__': TYPE_WITHDRAW },
        }],
    });
}

export function refund() {
    const userCode = randomUserCode();
    const payload = JSON.stringify({
        userCode: userCode,
        amount: 5000,
        type: 'REFUND_INTERNAL',
    });

    writer.produce({
        messages: [{
            value: payload,
            headers: { '__TypeId__': TYPE_REFUND },
        }],
    });
}

// 결제차감 = 출금과 동일한 로직, 다른 타입
export function payment() {
    const userCode = randomUserCode();
    const payload = JSON.stringify({
        userCode: userCode,
        amount: 5000,
        type: 'PAYMENT_INTERNAL',
        orderCode: `k6-payment-${Date.now()}`,
        productCodes: ['prod-1'],
    });

    writer.produce({
        messages: [{
            value: payload,
            headers: { '__TypeId__': TYPE_WITHDRAW },
        }],
    });
}

export function teardown() {
    writer.close();
}