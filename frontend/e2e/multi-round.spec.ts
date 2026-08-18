import { expect, test } from '@playwright/test';

test('多轮面试可创建、启动首轮并读取报告', async ({ page }) => {
  let started = false;
  let createKey = '';
  let startKey = '';
  const detail = () => ({
    interview: { id: 1, status: started ? 'IN_PROGRESS' : 'ROUND_READY', currentRoundNo: started ? 1 : 0, providerId: 'provider-a' },
    rounds: [
      { id: 11, roundNo: 1, roundType: 'ALGORITHM', status: started ? 'IN_PROGRESS' : 'PENDING', questionLimit: 3 },
      { id: 12, roundNo: 2, roundType: 'TECHNICAL_1', status: 'PENDING', questionLimit: 3 },
      { id: 13, roundNo: 3, roundType: 'TECHNICAL_2', status: 'PENDING', questionLimit: 3 },
      { id: 14, roundNo: 4, roundType: 'HR', status: 'PENDING', questionLimit: 3 },
    ],
  });
  await page.route('**/api/multi-round/interviews/session-e2e', async route => {
    if (route.request().method() === 'POST') createKey = route.request().headers()['idempotency-key'] ?? '';
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: route.request().method() === 'POST' ? detail().interview : detail() }) });
  });
  await page.route('**/api/multi-round/interviews/session-e2e/report', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: { status: 'ROUND_READY', completedRounds: 0, totalRounds: 4, summary: '当前已完成 0/4 轮', source: 'DETERMINISTIC', rounds: [] } }) }));
  await page.route('**/api/multi-round/interviews/session-e2e/agent-trace', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: [] }) }));
  await page.route('**/api/learning/recommendations/from-interview/session-e2e', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: { reportSummary: '根据本次面试生成学习建议', completedRounds: 0, totalRounds: 4, recommendations: [{ sourceId: 7, skillId: 'java', categoryKey: 'jvm', displayName: 'JVM 内存模型', masteryScore: 20, reason: '优先学习' }] } }) }));
  await page.route('**/api/multi-round/rounds/11/start', async route => {
    startKey = route.request().headers()['idempotency-key'] ?? '';
    started = true;
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: detail().rounds[0] }) });
  });

  await page.goto('/multi-round?sessionId=session-e2e');
  await expect(page.getByText('当前已完成 0/4 轮')).toBeVisible();
  await page.getByRole('button', { name: '查看学习建议' }).click();
  await expect(page.getByText('JVM 内存模型（20%）')).toBeVisible();
  await page.getByRole('button', { name: '启动本轮' }).click();
  await expect(page.getByText('IN_PROGRESS · 最多 3 题').first()).toBeVisible();
  expect(createKey).toBe('multi-round-create:session-e2e');
  expect(startKey).toMatch(/^round-start:11:/);
});
