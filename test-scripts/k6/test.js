import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';
import exec from 'k6/execution';

const testFile = JSON.parse(open('../data-generator/test-payloads.json'));
const expectedStats = testFile.stats;

const testData = new SharedArray('test-data', function () {
  return testFile.entries;
});

const totalSent = new Counter('total_sent');
const fraudCount = new Counter('fraud_count');
const legitCount = new Counter('legit_count');
const fraudScores = new Trend('fraud_scores');
const legitScores = new Trend('legit_scores');

export const options = {
    summaryTrendStats: ['min', 'med', 'max', 'p(90)', 'p(99)'],
    scenarios: {
    default: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 100,
      gracefulStop: '20s',
      stages: [
        { duration: '05s', target: 10 },
        { duration: '10s', target: 50 },
        { duration: '10s', target: 300 },
        { duration: '30s', target: 600 },
        { duration: '20s', target: 800 },
        { duration: '10s', target: 900 },
      ],
    },
  },
};

export function setup() {
  console.log(`Dataset: ${expectedStats.total} entries, ${expectedStats.fraud_count} fraud (${expectedStats.fraud_percentage}%), ${expectedStats.legit_count} legit (${expectedStats.legit_percentage}%), edge cases: ${expectedStats.edge_case_percentage}%`);
}

export default function () {
  const idx = exec.scenario.iterationInTest;
  if (idx >= testData.length) return;
  const entry = testData[idx];
  const expected = entry.info.expected_response;

  totalSent.add(1);

  const res = http.post(
    'http://localhost:9999/fraud-score',
    JSON.stringify(entry.request),
    { headers: { 'Content-Type': 'application/json' } }
  );

  if (res.status === 200) {
    const body = JSON.parse(res.body);
    if (body.approved) {
      legitCount.add(1);
      legitScores.add(body.fraud_score);
    } else {
      fraudCount.add(1);
      fraudScores.add(body.fraud_score);
    }
  }
}

export function handleSummary(data) {
  const httpDuration = data.metrics.http_req_duration.values;
  
  const sent = data.metrics.total_sent ? data.metrics.total_sent.values.count : 0;
  const fc = data.metrics.fraud_count ? data.metrics.fraud_count.values.count : 0;
  const lc = data.metrics.legit_count ? data.metrics.legit_count.values.count : 0;
  const fs = data.metrics.fraud_scores ? data.metrics.fraud_scores.values : {};
  const ls = data.metrics.legit_scores ? data.metrics.legit_scores.values : {};

  const httpReqs = data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0;
  const httpFailed = data.metrics.http_req_failed ? data.metrics.http_req_failed.values : {};

  const result = {
    expected: expectedStats,
    actual: {
      total_requests: sent,
      fraud_count: fc,
      legit_count: lc,
      fraud_percentage: sent > 0 ? +(fc / sent * 100).toFixed(2) : 0,
      legit_percentage: sent > 0 ? +(lc / sent * 100).toFixed(2) : 0,
      fraud_avg_score: fs.avg || 0,
      legit_avg_score: ls.avg || 0,
      errors: {
        http_reqs: httpReqs,
        http_req_failed_rate: httpFailed.rate || 0,
        http_req_failed_count: httpFailed.passes || 0,
      },
    },
    latency: {
      min: httpDuration.min,
      max: httpDuration.max,
      med: httpDuration['med'],
      p90: httpDuration['p(90)'],
      p99: httpDuration['p(99)'],
    },
  };

  return {
    'test-scripts/k6/results.json': JSON.stringify(result, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}
