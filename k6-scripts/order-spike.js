// order-spike.js — 순간 스파이크 흡수 테스트
//
// 지속 부하가 아니라 "짧고 강한 유입 뒤 한산한 구간" 을 만든다.
// 지속 부하에서는 밀린 일을 처리할 여유 구간이 없어 큐의 이점이 사라지지만,
// 스파이크는 이후 유휴 구간에 큐를 비울 수 있으므로 동기/비동기 차이가 드러난다.
//
// 실행: ./bench-scripts/run-k6-spike.sh <sync|kafka>
//
// 환경 변수
//   BASELINE   평상시 도착률 (기본 20)
//   SPIKE      스파이크 도착률 (기본 500)
//   T_BASE / T_SPIKE / T_RECOVER  각 구간 길이
//   REQ_TIMEOUT  클라이언트 타임아웃 (기본 2s) — 이걸 넘으면 "거절"
//
// ⚠️ VU 규칙: maxVUs >= SPIKE × REQ_TIMEOUT

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE      = __ENV.BASE      || 'http://commerce:8081';
const USERS     = parseInt(__ENV.USERS    || '200');
const BASELINE  = parseInt(__ENV.BASELINE || '20');
const SPIKE     = parseInt(__ENV.SPIKE    || '500');
const T_BASE    = __ENV.T_BASE    || '25s';
const T_SPIKE   = __ENV.T_SPIKE   || '8s';
const T_RECOVER = __ENV.T_RECOVER || '60s';
const TIMEOUT   = __ENV.REQ_TIMEOUT || '2s';
const MAX_VUS   = parseInt(__ENV.MAX_VUS || '1000');

const rejected5xx  = new Counter('rejected_5xx');
const rejectedConn = new Counter('rejected_conn');

const SEC = s => parseInt(s);
const T0_END = SEC(T_BASE);
const T1_END = T0_END + SEC(T_SPIKE);

// 구간별 임계값을 걸어야 handleSummary 에서 태그별 서브메트릭을 읽을 수 있다
const thr = { 'http_req_duration': ['p(99)<60000'] };
for (const p of ['baseline', 'spike', 'recover']) {
    thr[`http_req_duration{phase:${p}}`] = ['p(99)<60000'];
    thr[`http_req_failed{phase:${p}}`]   = ['rate<1'];
    thr[`http_reqs{phase:${p}}`]         = ['count>=0'];
}

export const options = {
    scenarios: {
        spike: {
            executor: 'ramping-arrival-rate',
            startRate: BASELINE,
            timeUnit: '1s',
            preAllocatedVUs: MAX_VUS,     // 전량 사전 할당 — 스파이크 순간 VU 증설 지연 방지
            maxVUs: MAX_VUS,
            stages: [
                { target: BASELINE, duration: T_BASE },     // 평상시
                { target: SPIKE,    duration: '1s'   },     // 급격히 상승
                { target: SPIKE,    duration: T_SPIKE },    // ← 스파이크 구간
                { target: BASELINE, duration: '1s'   },     // 급격히 하강
                { target: BASELINE, duration: T_RECOVER },  // ← 큐 드레인 관찰 구간
            ],
        },
    },
    thresholds: thr,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

let startedAt;
export function setup() { return { t0: Date.now() }; }

function phase() {
    const t = (Date.now() - startedAt) / 1000;
    if (t < T0_END) return 'baseline';
    if (t < T1_END + 2) return 'spike';
    return 'recover';
}

export default function (data) {
    if (!startedAt) startedAt = data.t0;
    const u = Math.floor(Math.random() * USERS);
    const res = http.post(`${BASE}/api/commerce/order/PROD-${u}`, null, {
        headers: { 'X-CODE': `BENCH-${u}` },
        timeout: TIMEOUT,
        tags: { name: 'order', phase: phase() },
    });
    check(res, { 'accepted (2xx)': r => r.status === 202 || r.status === 204 });
    if (res.status === 0)       rejectedConn.add(1);
    else if (res.status >= 500) rejected5xx.add(1);
}

export function handleSummary(data) {
    const m = data.metrics;
    const g = (k, f) => (m[k] ? (m[k].values[f] ?? 0) : 0);
    let out = '\n';
    for (const p of ['baseline', 'spike', 'recover']) {
        const d = (m[`http_req_duration{phase:${p}}`] || { values: {} }).values;
        const n = g(`http_reqs{phase:${p}}`, 'count');
        const fr = g(`http_req_failed{phase:${p}}`, 'rate');
        out += `K6_PHASE ${p}|${n}|${(fr * 100).toFixed(2)}|${Math.round(d['med'] || 0)}|${Math.round(d['p(95)'] || 0)}|${Math.round(d['max'] || 0)}\n`;
    }
    const total = g('http_reqs', 'count');
    const failed = g('http_req_failed', 'passes');
    out += `K6_RESULT ${g('http_reqs','rate').toFixed(1)}|${total}|${failed}|${(total?failed/total*100:0).toFixed(2)}|${g('rejected_5xx','count')}|${g('rejected_conn','count')}|${g('dropped_iterations','count')}\n`;
    return { stdout: out };
}
