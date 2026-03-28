import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const testData = new SharedArray('test-data', function () {
  return JSON.parse(open('../data-generator/preview-dataset.json'));
});

export const options = {
  stages: [
    { duration: '10s', target: 10 },
    { duration: '30s', target: 50 },
    { duration: '10s', target: 0 },
  ],
};

export default function () {
  const idx = Math.floor(Math.random() * testData.length);
  const testCase = testData[idx];

  const res = http.post(
    'http://localhost:9999/authorizations',
    JSON.stringify(testCase.request),
    { headers: { 'Content-Type': 'application/json' } }
  );

  check(res, {
    'status is 200': (r) => r.status === 200,
    'correct decision': (r) => {
      const body = JSON.parse(r.body);
      if (testCase.expected.approved) {
        return body.approved === true;
      } else {
        return (
          body.approved === false &&
          JSON.stringify(body.rules_violated.sort()) ===
            JSON.stringify(testCase.expected.rules_violated.sort())
        );
      }
    },
  });
}
