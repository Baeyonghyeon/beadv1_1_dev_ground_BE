// order-steps.js — 저부하 → 고부하 계단식 부하 (임계점 찾기용)
//
// 도착률을 단계적으로 올리며 시스템이 어디서 무너지는지 눈으로 본다.
// 각 단계는 stage 태그가 붙어 Grafana 에서 구간별로 분리해 볼 수 있다.
//
// 실행: ./bench-scripts/run-k6-steps.sh <sync|kafka>
//
// 환경 변수
//   STEPS        도착률 계단 (쉼표 구분, 기본 "30,60,100,150,200,300")
//   STEP_DUR     각 단계 길이 (기본 60s)
//   REQ_TIMEOUT  클라이언트 타임아웃 (기본 4s) — 이걸 넘으면 "거절" 로 계산
//   MAX_VUS      VU 상한 — 반드시 (최대 도착률 × 타임아웃) 이상이어야 함
//
// ⚠️ VU 프로비저닝 규칙: maxVUs >= 최대 도착률 × 타임아웃
//    모자라면 k6 가 목표 도착률을 못 만들고 dropped_iterations 가 올라가 회차가 무효다.

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE    = __ENV.BASE  || 'http://commerce:8081';
const USERS   = parseInt(__ENV.USERS || '200');
const STEPS   = (__ENV.STEPS || '30,60,100,150,200,300').split(',').map(Number);
const STEP_DUR = __ENV.STEP_DUR || '60s';
const TIMEOUT = __ENV.REQ_TIMEOUT || '4s';
const MAX_VUS = parseInt(__ENV.MAX_VUS || '1200');

const rejected5xx  = new Counter('rejected_5xx');
const rejectedConn = new Counter('rejected_conn');

// 계단을 stages 로 변환. 각 단계는 "즉시 목표치로 올린 뒤 유지" 형태.
const stages = [];
for (const rate of STEPS) {
    stages.push({ target: rate, duration: '5s' });    // 빠르게 올림
    stages.push({ target: rate, duration: STEP_DUR }); // 유지 ← 측정 구간
}

export const options = {
    scenarios: {
        steps: {
            executor: 'ramping-arrival-rate',
            startRate: STEPS[0],
            timeUnit: '1s',
            preAllocatedVUs: Math.min(200, MAX_VUS),
            maxVUs: MAX_VUS,
            stages,
        },
    },
    // 임계값을 넘겨도 중단하지 않는다 — 무너지는 모습 자체가 관측 대상
    thresholds: { 'http_req_duration': ['p(99)<60000'] },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

// 지금 몇 번째 계단인지 태그로 남긴다 (Grafana 에서 구간 분리용)
function currentStep() {
    const t = (Date.now() - startedAt) / 1000;
    const per = 5 + parseInt(STEP_DUR);   // 단계당 총 길이(초)
    const idx = Math.min(Math.floor(t / per), STEPS.length - 1);
    return String(STEPS[idx]);
}
let startedAt;
export function setup() { return { t0: Date.now() }; }

export default function (data) {
    if (!startedAt) startedAt = data.t0;
    const u = Math.floor(Math.random() * USERS);
    const res = http.post(`${BASE}/api/commerce/order/PROD-${u}`, null, {
        headers: { 'X-CODE': `BENCH-${u}` },
        timeout: TIMEOUT,
        tags: { name: 'order', step: currentStep() },
    });

    check(res, { 'accepted (204)': (r) => r.status === 204 });
    if (res.status === 0)       rejectedConn.add(1);
    else if (res.status >= 500) rejected5xx.add(1);
}

export function handleSummary(data) {
    const m = data.metrics;
    const g = (k, f = 'value') => (m[k] ? (m[k].values[f] ?? 0) : 0);
    const d = m.http_req_duration ? m.http_req_duration.values : {};
    const total = g('http_reqs', 'count');
    const failed = g('http_req_failed', 'passes');
    const line = [
        g('http_reqs', 'rate').toFixed(1),
        Math.round(d['med'] || 0), Math.round(d['p(95)'] || 0),
        Math.round(d['p(99)'] || 0), Math.round(d['max'] || 0),
        total, failed, (total ? failed / total * 100 : 0).toFixed(2),
        g('rejected_5xx', 'count'), g('rejected_conn', 'count'),
        g('dropped_iterations', 'count'),
    ].join('|');
    return { stdout: '\nK6_RESULT ' + line + '\n' };
}
