// burst-kafka-v1.js — 버전1 (Kafka) 버스트 부하 테스트
// deepseek-kafka-ab-test.md 의 단계 1~2 (예비 측정 + 본 테스트)
//
// 실행:
//   docker run --rm -i --network docker_default \
//     -v $(pwd):/scripts \
//     -e KAFKA_BROKER=kafka:9090 \
//     -e BURST_RATE=300 \
//     k6-kafka:latest run /scripts/burst-kafka-v1.js
//
// 환경 변수:
//   BURST_RATE      버스트 목표 TPS (기본 300)
//   BASELINE_RATE   안정기 TPS (기본 30)
//   BASELINE_MIN    안정기 길이 (기본 10m)
//   BURST_MIN       버스트 길이 (기본 30m)
//   USER_POOL       유저 풀 크기 (기본 10000)
//
// 예비 측정용 (짧게):
//   -e BURST_RATE=100 -e BURST_MIN=2m -e BASELINE_MIN=1m

import { Writer } from 'k6/x/kafka';

const BROKER = __ENV.KAFKA_BROKER || 'kafka:9090';
const TOPIC = 'deposits-commands';
const USER_POOL = parseInt(__ENV.USER_POOL || '10000');
const BURST_RATE = parseInt(__ENV.BURST_RATE || '300');
const BASELINE_RATE = parseInt(__ENV.BASELINE_RATE || '30');
const BASELINE_MIN = __ENV.BASELINE_MIN || '10m';
const BURST_MIN = __ENV.BURST_MIN || '30m';

// Spring JsonDeserializer 타입 헤더
const TYPE_CHARGE = 'io.devground.core.commands.deposit.ChargeDeposit';
const TYPE_WITHDRAW = 'io.devground.core.commands.deposit.WithdrawDeposit';
const TYPE_REFUND = 'io.devground.core.commands.deposit.RefundDeposit';

const writer = new Writer({
    brokers: [BROKER],
    topic: TOPIC,
});

// 연산 혼합: 충전 3 : 출금 1 : 환불 2 → 충전 50% / 출금 16.7% / 환불 33.3%
export const options = {
    scenarios: {
        burst: {
            executor: 'ramping-arrival-rate',
            startRate: BASELINE_RATE,
            timeUnit: '1s',
            preAllocatedVUs: 20,
            maxVUs: 300,
            stages: [
                { target: BASELINE_RATE, duration: BASELINE_MIN }, // 안정기
                { target: BURST_RATE, duration: '2m' },            // ramp-up
                { target: BURST_RATE, duration: BURST_MIN },       // 버스트
            ],
        },
    },
};

function randomUserCode() {
    return `USER-${Math.floor(Math.random() * USER_POOL)}`;
}

export default function () {
    const r = Math.random();
    const userCode = randomUserCode();

    if (r < 0.5) {
        // 충전 50% (3/6)
        writer.produce({
            messages: [{
                value: JSON.stringify({
                    userCode: userCode,
                    paymentKey: 'k6-burst',
                    amount: 10000,
                    type: 'CHARGE_TRANSFER',
                }),
                headers: { '__TypeId__': TYPE_CHARGE },
            }],
        });
    } else if (r < 0.667) {
        // 출금 16.7% (1/6)
        writer.produce({
            messages: [{
                value: JSON.stringify({
                    userCode: userCode,
                    amount: 5000,
                    type: 'PAYMENT_INTERNAL',
                    orderCode: `k6-burst-${Date.now()}-${userCode}`,
                    productCodes: ['prod-1'],
                }),
                headers: { '__TypeId__': TYPE_WITHDRAW },
            }],
        });
    } else {
        // 환불 33.3% (2/6)
        writer.produce({
            messages: [{
                value: JSON.stringify({
                    userCode: userCode,
                    amount: 5000,
                    type: 'REFUND_INTERNAL',
                }),
                headers: { '__TypeId__': TYPE_REFUND },
            }],
        });
    }
}

export function teardown() {
    writer.close();
}
