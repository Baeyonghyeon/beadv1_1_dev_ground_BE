// k6 mixed-workload.js — DAU 10만 예치금 성능 테스트
// 실행: docker run --rm -i -v $(pwd):/scripts grafana/k6 run /scripts/mixed-workload.js
//
// ⚠️ host.docker.internal 사용: Docker 컨테이너에서 호스트 머신 접근

import http from 'k6/http';
import { sleep, check } from 'k6';

const BASE_COMMERCE = 'http://host.docker.internal:8081';
const BASE_PAYMENTS = 'http://host.docker.internal:8085';

// 시나리오별 목표 TPS (설계기준)
// 충전:3, 출금:1, 환불:2, 결제차감:3 = 총 ~9 TPS
export const options = {
    scenarios: {
        charge: {
            executor: 'constant-arrival-rate',
            rate: 3,
            timeUnit: '1s',
            duration: '30m',
            preAllocatedVUs: 5,
            exec: 'charge',
        },
        withdraw: {
            executor: 'constant-arrival-rate',
            rate: 1,
            timeUnit: '1s',
            duration: '30m',
            preAllocatedVUs: 3,
            exec: 'withdraw',
        },
        refund: {
            executor: 'constant-arrival-rate',
            rate: 2,
            timeUnit: '1s',
            duration: '30m',
            preAllocatedVUs: 3,
            exec: 'refund',
        },
        payment: {
            executor: 'constant-arrival-rate',
            rate: 3,
            timeUnit: '1s',
            duration: '30m',
            preAllocatedVUs: 5,
            exec: 'payment',
        },
    },
    thresholds: {
        'http_req_duration{scenario:charge}': ['p(95)<100', 'p(99)<300'],
        'http_req_duration{scenario:withdraw}': ['p(95)<100', 'p(99)<300'],
        'http_req_duration{scenario:refund}': ['p(95)<100', 'p(99)<300'],
        'http_req_duration{scenario:payment}': ['p(95)<100', 'p(99)<300'],
        'http_req_failed': ['rate<0.01'],
    },
};

// 테스트 유저 풀: USER-0 ~ USER-9999 (10,000명)
function randomUserCode() {
    return `USER-${Math.floor(Math.random() * 10000)}`;
}

function commonHeaders(userCode) {
    return {
        'X-CODE': userCode,
        'Content-Type': 'application/json',
    };
}

export function charge() {
    const userCode = randomUserCode();
    const payload = JSON.stringify({ amount: 10000 });
    const res = http.post(`${BASE_PAYMENTS}/api/deposits/charge`, payload, {
        headers: commonHeaders(userCode),
    });
    check(res, { 'charge OK': (r) => r.status === 200 });
}

export function withdraw() {
    const userCode = randomUserCode();
    const payload = JSON.stringify({ amount: 5000 });
    const res = http.post(`${BASE_PAYMENTS}/api/deposits/withdraw`, payload, {
        headers: commonHeaders(userCode),
    });
    check(res, { 'withdraw OK': (r) => r.status === 200 });
}

export function refund() {
    const userCode = randomUserCode();
    const payload = JSON.stringify({ amount: 5000 });
    const res = http.post(`${BASE_PAYMENTS}/api/deposits/refund`, payload, {
        headers: commonHeaders(userCode),
    });
    check(res, { 'refund OK': (r) => r.status === 200 });
}

export function payment() {
    const userCode = randomUserCode();
    const payload = JSON.stringify({ productCode: 'prod-1' });
    const res = http.post(`${BASE_COMMERCE}/api/commerce/order/prod-1`, payload, {
        headers: commonHeaders(userCode),
    });
    check(res, { 'order OK': (r) => r.status === 200 || r.status === 201 });
}
