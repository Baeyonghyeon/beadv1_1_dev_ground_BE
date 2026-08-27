// 시드 데이터 생성: Deposit 계좌 생성 + 초기 잔액 충전
// 실행 전에 Kafka 브로커가 실행 중이어야 함
//
// 실행:
//   docker run --rm -i --network docker_default \
//     -v $(pwd):/scripts grafana/k6:latest run - < seed-deposits.js
//   (⚠️ Kafka 확장 필요: k6-kafka 커스텀 이미지 사용)

import { Writer } from 'k6/x/kafka';

const BROKER = __ENV.KAFKA_BROKER || 'kafka:9090';
const TOPIC_COMMAND = 'deposits-commands';
const USER_COUNT = parseInt(__ENV.USER_COUNT || '10000');
const INITIAL_BALANCE = 1000000; // 1,000,000원

const commandWriter = new Writer({
    brokers: [BROKER],
    topic: TOPIC_COMMAND,
});

export const options = {
    iterations: USER_COUNT,
    vus: 20, // 동시 20개 유저로 빠르게 시딩
};

export default function () {
    const userIndex = __ITER; // 0 ~ USER_COUNT-1
    const userCode = `USER-${userIndex}`;

    // 1. Deposit 계좌 생성
    const createMsg = {
        value: JSON.stringify({
            userCode: userCode,
        }),
        headers: {
            'X-Kafka-Event-Type': 'io.devground.core.commands.deposit.CreateDeposit',
        },
    };
    commandWriter.produce({ messages: [createMsg] });

    // 2. 초기 잔액 충전
    const chargeMsg = {
        value: JSON.stringify({
            userCode: userCode,
            paymentKey: 'seed-initial-charge',
            amount: INITIAL_BALANCE,
            type: 'CHARGE_TRANSFER',
        }),
        headers: {
            'X-Kafka-Event-Type': 'io.devground.core.commands.deposit.ChargeDeposit',
        },
    };
    commandWriter.produce({ messages: [chargeMsg] });

    if (userIndex % 1000 === 0) {
        console.log(`[SEED] ${userIndex}/${USER_COUNT} users seeded`);
    }
}