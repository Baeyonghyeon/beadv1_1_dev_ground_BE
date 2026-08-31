// order-spike-ladder.js — 스파이크 강도를 올려가며 "타임아웃이 시작되는 경계" 를 찾는다
//
// 목적: "동기는 타임아웃이 나는데 Kafka 는 나지 않는" 부하 구간이 존재하는가?
//   기존 측정은 용량의 2~4배(200~400 TPS)에서 쟀고, 그 구간은 양쪽 다 이미 무너져
//   차이가 묻혔다(둘 다 67~79% 타임아웃). 경계는 용량 근처(1.0~2.0배)에 있다.
//
// 방식: 강도가 다른 스파이크를 한 회차 안에서 연달아 주고, 사이에 회복 구간을 둔다.
//   회복 구간이 있어야 Kafka 가 밀린 후처리를 비울 수 있다(큐의 이점이 나오는 조건).
//
// 실행: ./bench-scripts/run-k6-spike-ladder.sh <sync|kafka>

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE     = __ENV.BASE   || 'http://commerce:8081';
const USERS    = parseInt(__ENV.USERS || '200');
const BASELINE = parseInt(__ENV.BASELINE || '20');
const LEVELS   = (__ENV.LEVELS || '100,130,160,200').split(',').map(Number);
const T_SPIKE  = parseInt(__ENV.T_SPIKE || '10');    // 초
const T_REST   = parseInt(__ENV.T_REST  || '40');    // 초 (회복/드레인)
const T_BASE   = parseInt(__ENV.T_BASE  || '25');    // 초
const TIMEOUT  = __ENV.REQ_TIMEOUT || '5s';
const MAX_VUS  = parseInt(__ENV.MAX_VUS || '1500');

const rejected5xx  = new Counter('rejected_5xx');
const rejectedConn = new Counter('rejected_conn');

// stages: baseline → [1s 상승, T_SPIKE 유지, 1s 하강, T_REST 휴식] × N
const stages = [{ target: BASELINE, duration: `${T_BASE}s` }];
for (const lv of LEVELS) {
    stages.push({ target: lv,       duration: '1s' });
    stages.push({ target: lv,       duration: `${T_SPIKE}s` });
    stages.push({ target: BASELINE, duration: '1s' });
    stages.push({ target: BASELINE, duration: `${T_REST}s` });
}

// 각 스파이크의 시작·종료 시각(초)을 미리 계산해 태깅에 쓴다
const windows = [];
let t = T_BASE;
for (const lv of LEVELS) {
    const s = t + 1;            // 상승 1초 후부터
    const e = s + T_SPIKE;
    windows.push({ lv, s, e });
    t = e + 1 + T_REST;
}

const thr = { 'http_req_duration': ['p(99)<60000'] };
for (const lv of LEVELS) {
    thr[`http_req_duration{spike:${lv}}`] = ['p(99)<60000'];
    thr[`http_req_failed{spike:${lv}}`]   = ['rate<1'];
    thr[`http_reqs{spike:${lv}}`]         = ['count>=0'];
}
thr['http_req_failed{spike:rest}'] = ['rate<1'];
thr['http_reqs{spike:rest}']       = ['count>=0'];

export const options = {
    scenarios: {
        ladder: {
            executor: 'ramping-arrival-rate',
            startRate: BASELINE,
            timeUnit: '1s',
            preAllocatedVUs: MAX_VUS,
            maxVUs: MAX_VUS,
            stages,
        },
    },
    thresholds: thr,
    summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

let startedAt;
export function setup() { return { t0: Date.now() }; }

function tagOf() {
    const el = (Date.now() - startedAt) / 1000;
    for (const w of windows) if (el >= w.s && el < w.e + 1) return String(w.lv);
    return 'rest';
}

export default function (data) {
    if (!startedAt) startedAt = data.t0;
    const u = Math.floor(Math.random() * USERS);
    const res = http.post(`${BASE}/api/commerce/order/PROD-${u}`, null, {
        headers: { 'X-CODE': `BENCH-${u}` },
        timeout: TIMEOUT,
        tags: { name: 'order', spike: tagOf() },
    });
    check(res, { 'accepted (204)': r => r.status === 204 });
    if (res.status === 0)       rejectedConn.add(1);
    else if (res.status >= 500) rejected5xx.add(1);
}

export function handleSummary(data) {
    const m = data.metrics;
    const g = (k, f) => (m[k] ? (m[k].values[f] ?? 0) : 0);
    let out = '\n';
    for (const lv of LEVELS) {
        const d = (m[`http_req_duration{spike:${lv}}`] || { values: {} }).values;
        out += `K6_SPIKE ${lv}|${g(`http_reqs{spike:${lv}}`,'count')}` +
               `|${(g(`http_req_failed{spike:${lv}}`,'rate')*100).toFixed(2)}` +
               `|${Math.round(d['med']||0)}|${Math.round(d['p(95)']||0)}|${Math.round(d['max']||0)}\n`;
    }
    out += `K6_REST rest|${g('http_reqs{spike:rest}','count')}|${(g('http_req_failed{spike:rest}','rate')*100).toFixed(2)}\n`;
    const total = g('http_reqs','count');
    out += `K6_RESULT ${total}|${g('http_req_failed','passes')}|${g('rejected_5xx','count')}|${g('rejected_conn','count')}|${g('dropped_iterations','count')}\n`;
    return { stdout: out };
}
