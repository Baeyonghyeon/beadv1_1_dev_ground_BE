// order-burst.js — 주문 API 도착률 고정(open-loop) 버스트 테스트
//
// 왜 k6 인가:
//   xargs -P 방식은 폐쇄 루프라 시스템이 느려지면 부하도 함께 줄어 임계점을 넘지 못한다.
//   ramping-arrival-rate 는 시스템 상태와 무관하게 초당 목표 건수를 계속 밀어넣으므로,
//   처리 못 한 요청이 쌓이다 거절(5xx/timeout)로 드러난다. "거절률" 은 이 방식으로만 측정된다.
//
// 실행: ./bench-scripts/run-k6.sh <sync|kafka>
//
// 환경 변수
//   BASE        대상 (기본 http://commerce:8081 — compose 네트워크 내부)
//   USERS       유저 풀 크기 (기본 200, seed.sql 과 일치해야 함)
//   BASELINE    안정기 도착률 (기본 30)
//   BURST       버스트 도착률 (기본 300 — 실측 최대 처리량 ~100~120 TPS 의 약 3배)
//   T_BASE/T_RAMP/T_BURST/T_RECOVER  각 구간 길이

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE      = __ENV.BASE      || 'http://commerce:8081';
const USERS     = parseInt(__ENV.USERS    || '200');
const BASELINE  = parseInt(__ENV.BASELINE || '30');
const BURST     = parseInt(__ENV.BURST    || '300');
const T_BASE    = __ENV.T_BASE    || '30s';
const T_RAMP    = __ENV.T_RAMP    || '30s';
const T_BURST   = __ENV.T_BURST   || '90s';
const T_RECOVER = __ENV.T_RECOVER || '30s';

// 거절을 유형별로 구분해서 센다 (설계 문서 §4.3 의 "거절 vs 유실 vs 지연")
const rejected5xx  = new Counter('rejected_5xx');
const rejectedConn = new Counter('rejected_conn');

export const options = {
    scenarios: {
        burst: {
            executor: 'ramping-arrival-rate',
            startRate: BASELINE,
            timeUnit: '1s',
            // 목표 도착률을 만들려면 VU 가 충분해야 한다.
            // VU 가 모자라면 k6 가 dropped_iterations 를 올리고, 그 회차는 무효다.
            preAllocatedVUs: parseInt(__ENV.PRE_VUS || '400'),
            // ⚠️ VU 가 모자라면 목표 도착률을 못 만들어 dropped_iterations 가 올라가고 회차가 무효가 된다.
            //    필요 VU ≈ 목표TPS × 응답시간(초). 단 VU 1개당 수 MB 를 쓰므로 VM 메모리가 상한이다.
            maxVUs: parseInt(__ENV.MAX_VUS || '1500'),
            stages: [
                { target: BASELINE, duration: T_BASE },     // 안정기
                { target: BURST,    duration: T_RAMP },     // 상승
                { target: BURST,    duration: T_BURST },    // 버스트 ← 임계점 돌파 구간
                { target: BASELINE, duration: T_RECOVER },  // 회복
            ],
        },
    },
    thresholds: {
        // 임계값을 넘겨도 테스트를 중단하지 않는다(abortOnFail 미사용).
        // 무너지는 모습 자체가 관측 대상이기 때문.
        'http_req_duration': ['p(99)<30000'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
    const u = Math.floor(Math.random() * USERS);
    const res = http.post(`${BASE}/api/commerce/order/PROD-${u}`, null, {
        headers: { 'X-CODE': `BENCH-${u}` },
        timeout: __ENV.REQ_TIMEOUT || '10s',
        tags: { name: 'order' },
    });

    check(res, { 'order accepted (204)': (r) => r.status === 204 });

    if (res.status === 0)      rejectedConn.add(1);   // 연결 실패 / 타임아웃
    else if (res.status >= 500) rejected5xx.add(1);   // 서버가 거절
}

export function handleSummary(data) {
    const m = data.metrics;
    const g = (k, f = 'value') => (m[k] ? (m[k].values[f] ?? 0) : 0);
    const d = m.http_req_duration ? m.http_req_duration.values : {};
    const total = g('http_reqs', 'count');
    const failed = g('http_req_failed', 'passes');   // 실패한 요청 수
    const dropped = g('dropped_iterations', 'count');

    const line = [
        (g('http_reqs', 'rate')).toFixed(1),          // 실제 처리 TPS
        Math.round(d['med'] || 0),
        Math.round(d['p(95)'] || 0),
        Math.round(d['p(99)'] || 0),
        Math.round(d['max'] || 0),
        total,
        failed,
        (total ? (failed / total * 100) : 0).toFixed(2),
        g('rejected_5xx', 'count'),
        g('rejected_conn', 'count'),
        dropped,
    ].join('|');

    return {
        stdout: '\nK6_RESULT ' + line + '\n',
        '/scripts/results/k6-summary.json': JSON.stringify(data, null, 2),
    };
}
